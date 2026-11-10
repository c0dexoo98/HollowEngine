package com.hollow.ui;

import com.hollow.core.UiDrawList;

/** Text shaping + drawing (RTL-capable). Desktop stub: metrics only. API FINAL. */
public abstract class TextShaper {
    public static final int ALIGN_LEFT = 0, ALIGN_CENTER = 1, ALIGN_RIGHT = 2;

    /** measured text width at fontSize */
    public abstract float measure(String text, float fontSize);
    /** append text quads to draw list; alignment within maxWidth; '\n' supported */
    public abstract void draw(UiDrawList list, String text, float x, float y, float fontSize, int rgba, int align, float maxWidth);
}
