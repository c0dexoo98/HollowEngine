package com.hollow.ui;

import com.hollow.core.UiDrawList;

/** Desktop/headless shaper: approximate metrics, draws placeholder boxes.
 *  (Only used in CLI/headless mode — real text on Android via Paint-based shaper, Agent E.) */
public final class DesktopTextShaper extends TextShaper {
    @Override public float measure(String text, float fontSize){
        if(text == null) return 0;
        float w = 0;
        for(int i=0;i<text.length();i++) w += fontSize * (text.charAt(i) > 0x0600 && text.charAt(i) < 0x0700 ? 0.55f : 0.6f);
        return w;
    }
    @Override public void draw(UiDrawList list, String text, float x, float y, float fontSize, int rgba, int align, float maxWidth){
        if(text == null || list == null) return;
        // line-wrap approximation, one quad per char (placeholder glyph box)
        float cx = x;
        float lineHeight = fontSize * 1.4f;
        if(align == ALIGN_RIGHT || align == ALIGN_CENTER){
            float w = measure(text, fontSize);
            cx = align == ALIGN_RIGHT ? x + maxWidth - w : x + (maxWidth - w)*0.5f;
        }
        int line = 0;
        for(int i=0;i<text.length();i++){
            char c = text.charAt(i);
            if(c == '\n'){ line++; cx = x; continue; }
            if(maxWidth > 0 && cx - x > maxWidth){ line++; cx = x; }
            float cw = fontSize * (c > 0x0600 && c < 0x0700 ? 0.55f : 0.6f);
            list.addQuad("@white", cx, y + line*lineHeight, cw * 0.8f, fontSize, 0,0,1,1, rgba);
            cx += cw;
        }
    }
}
