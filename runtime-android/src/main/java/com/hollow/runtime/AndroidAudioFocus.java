package com.hollow.runtime;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;

import com.hollow.audio.AudioStreams;

/** v2.3 AUD-026 — Android media-audio focus bridge.
 *  Wraps AudioFocusRequest (API 26+) with ducking-friendly usage; the engine's
 *  FocusPolicy (music duck + bus pause) reacts to the lost-focus callback, and
 *  EngineActivity calls {@link #refocus()} on foreground resume.
 */
public final class AndroidAudioFocus implements AudioStreams.AudioFocusBridge {
    private final AudioManager am;
    private AudioFocusRequest request;
    private Runnable lost;
    private int state = 0;

    public AndroidAudioFocus(Context context){
        this.am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override public boolean requestFocus(){
        if(am == null) return false;
        if(request == null){
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
            request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(this::onChange)
                .build();
        }
        int result = am.requestAudioFocus(request);
        state = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? 1 : 0;
        return state == 1;
    }

    @Override public void abandonFocus(){
        if(am != null && request != null) am.abandonAudioFocusRequest(request);
        state = 0;
    }

    @Override public void onFocusLost(Runnable r){ lost = r; }

    @Override public int state(){ return state; }

    private void onChange(int focusChange){
        switch(focusChange){
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                state = 2;
                if(lost != null) lost.run();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                state = 2;
                if(lost != null) lost.run();    // policy decides duck vs stop
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                state = 1;
                break;
        }
    }
}
