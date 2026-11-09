package com.hollow.game;

import com.hollow.assets.AssetDB;
import com.hollow.core.FileSystem;
import com.hollow.core.Time;

/** Service context shared by every engine subsystem. Built once at boot by EngineBootstrap. */
public final class EngineServices {
    public final AssetDB assets;
    public final FileSystem fs;                 // project root file system
    public final Time time = new Time();
    public final GameConfig config;

    // subsystems (created by EngineBootstrap in strict order)
    public com.hollow.script.VarTable vars;                 // global game variables
    public com.hollow.script.EventBus events;               // deferred event bus
    public com.hollow.audio.AudioEngine audio;
    public com.hollow.vfx.ParticleLibrary particles;
    public com.hollow.cine.Sequencer sequencer;
    public com.hollow.physics.PhysicsWorld physics;
    public com.hollow.ai.AISystem ai;
    public com.hollow.anim.AnimationSystem anim;
    public com.hollow.ui.UIManager ui;
    public SaveSystem save;
    public Localization i18n;
    public InputActions input;
    public GameApp app;                          // back-reference

    /** determinism seed (frame-stable): scripts/ai use DerivableRng(seed + tick) */
    public long frameSeed = 0x9E3779B97F4A7C15L;

    public EngineServices(AssetDB assets, FileSystem fs, GameConfig config){
        this.assets = assets;
        this.fs = fs;
        this.config = config;
    }
}
