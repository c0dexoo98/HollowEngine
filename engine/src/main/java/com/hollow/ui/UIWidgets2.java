package com.hollow.ui;

import com.hollow.core.Json;
import com.hollow.core.MathX;
import com.hollow.core.UiDrawList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** v2.0 UI widgets (HollowEngine 2.0 — same package so package-private APIs are reachable).
 *  - NineSlice : 3×3 stretched panel (UMG border image) — corners fixed, edges/center stretched.
 *  - ListView  : virtualized vertical list — only visible rows are emitted; supports
 *                programmatic items + scroll + click ("ui_select" {index}).
 */
public final class UIWidgets2 {
    private UIWidgets2() {}

    // ==================================================================
    public static class NineSlice extends UIManager.Panel {
        public float corner = 12f;          // corner size in px (of the drawn rect)

        @Override void draw(UIManager m, UiDrawList out, float a, float dx, float dy){
            if(texture == null || texture.isEmpty()){
                super.draw(m, out, a, dx, dy);
                return;
            }
            float[] uv = m.resolveUv(texture);
            if(uv == null){ super.draw(m, out, a, dx, dy); return; }
            String key = m.textureKeyFor(texture);
            int rgba = rgbaOf(color, a);
            float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
            // fraction of the source used per slice = corner / drawn size
            float cx = Math.min(corner, w * 0.5f);
            float cy = Math.min(corner, h * 0.5f);
            float fu = cx / Math.max(1f, w) * 0.5f;     // uv fraction for corner slice
            float fv = cy / Math.max(1f, h) * 0.5f;
            // texture aspect correction: use same fraction on both axes for square corners
            float um = u0 + (u1 - u0) * fu;
            float vm = v0 + (v1 - v0) * fv;
            float x2 = dx + w, y2 = dy + h;
            float mx = dx + cx, my = dy + cy;
            // corners
            out.addQuad(key, dx, dy, cx, cy, u0, v0, um, vm, rgba);                       // TL
            out.addQuad(key, x2 - cx, dy, cx, cy, um, v0, u1, vm, rgba);                  // TR
            out.addQuad(key, dx, y2 - cy, cx, cy, u0, vm, um, v1, rgba);                  // BL
            out.addQuad(key, x2 - cx, y2 - cy, cx, cy, um, vm, u1, v1, rgba);             // BR
            // edges — far UV is the MIRRORED split (um2/vm2), not the mid itself
            float um2 = u1 - (um - u0), vm2 = v1 - (vm - v0);
            out.addQuad(key, mx, dy, Math.max(0f, w - cx * 2), cy, um, v0, um2, vm, rgba); // T
            out.addQuad(key, mx, y2 - cy, Math.max(0f, w - cx * 2), cy, um, vm2, um2, v1, rgba); // B
            out.addQuad(key, dx, my, cx, Math.max(0f, h - cy * 2), u0, vm, um, vm2, rgba); // L
            out.addQuad(key, x2 - cx, my, cx, Math.max(0f, h - cy * 2), um, vm, u1, vm2, rgba); // R
            // center
            out.addQuad(key, mx, my, Math.max(0f, w - cx * 2), Math.max(0f, h - cy * 2), um, vm, um2, vm2, rgba);
        }
    }

    // ==================================================================
    public static class ListView extends UIManager.Widget {
        public final List<String> items = new ArrayList<>();
        public float rowHeight = 34f;
        public float fontSize = 15f;
        public float scroll = 0f;                 // 0..1
        public int selectedIndex = -1;
        public String evenColor = "0.14,0.14,0.2,1";
        public String oddColor = "0.11,0.11,0.16,1";
        public String selectedColor = "0.2,0.42,0.7,1";

        // drag state
        private float pressY = -1f;
        private float pressScroll = 0f;
        private boolean dragged = false;

        @Override boolean interactive(){ return true; }

        @Override void press(float px2, float py2){ pressY = py2; pressScroll = scroll; dragged = false; }
        @Override void drag(float px2, float py2){
            if(pressY < 0f) return;
            float d = py2 - pressY;
            if(Math.abs(d) > 6f) dragged = true;
            float content = contentHeight();
            if(content > h) scroll = MathX.clamp(pressScroll - d / (content - h), 0f, 1f);
        }
        @Override void release(float px2, float py2, boolean inside){
            if(inside && !dragged){
                int idx = rowAt(py2);
                if(idx >= 0 && idx < items.size()){
                    selectedIndex = idx;
                    com.hollow.script.EventBus ev = UIManager.events();
                    if(ev != null){
                        Map<String,Object> d = new java.util.LinkedHashMap<>();
                        d.put("id", id);
                        d.put("index", selectedIndex);
                        d.put("item", items.get(selectedIndex));
                        ev.fire("ui_select", d);
                    }
                }
            }
            pressY = -1f;
        }

        float contentHeight(){ return Math.max(h, items.size() * rowHeight); }
        int rowAt(float py2){
            float content = contentHeight();
            float offset = (content > h) ? scroll * (content - h) : 0f;
            int idx = (int)((py2 - y + offset) / rowHeight);
            return idx;
        }

        @Override void draw(UIManager m, UiDrawList out, float a, float dx, float dy){
            out.addQuad("@white", dx, dy, w, h, 0, 0, 1, 1, rgbaOf(new com.hollow.core.Color(0.08f, 0.08f, 0.12f, 1), a));
            float content = contentHeight();
            float offset = (content > h) ? scroll * (content - h) : 0f;
            int first = (int)(offset / rowHeight);
            int visible = (int)(h / rowHeight) + 1;
            int last = Math.min(items.size(), first + visible + 1);
            for(int i = first; i < last; i++){
                float ry = dy + i * rowHeight - offset;
                if(ry + rowHeight < dy || ry > dy + h) continue;
                com.hollow.core.Color bg = (i == selectedIndex)
                        ? com.hollow.core.Color.parse(selectedColor, new com.hollow.core.Color(0.2f, 0.42f, 0.7f, 1f))
                        : com.hollow.core.Color.parse(i % 2 == 0 ? evenColor : oddColor,
                              new com.hollow.core.Color(0.12f, 0.12f, 0.18f, 1f));
                out.addQuad("@white", dx, ry, w, rowHeight - 1, 0, 0, 1, 1, rgbaOf(bg, a));
                String s = m.resolveText(items.get(i));
                if(!s.isEmpty()){
                    m.text.draw(out, s, dx + 8, ry + (rowHeight - fontSize) * 0.5f, fontSize,
                            rgbaOf(color, a), TextShaper.ALIGN_LEFT, Math.max(0, w - 16));
                }
            }
            // scrollbar
            if(content > h){
                float barH = Math.max(20f, h * (h / content));
                float barY = dy + scroll * (h - barH);
                out.addQuad("@white", dx + w - 4, barY, 3, barH, 0, 0, 1, 1,
                        rgbaOf(new com.hollow.core.Color(0.5f, 0.5f, 0.6f, 0.6f), a));
            }
        }
    }

    static int rgbaOf(com.hollow.core.Color c, float alphaMul){
        // UIManager.rgbaOf is package-private static — direct call within com.hollow.ui
        return UIManager.rgbaOf(c, alphaMul);
    }

    // helper: JSON constructor for the build factory
    public static UIManager.Widget buildNineSlice(Map<String,Object> node){
        NineSlice ns = new NineSlice();
        ns.texture = Json.str(node.get("texture"), Json.str(node.get("sprite"), null));
        ns.borderWidth = 0f;
        ns.corner = (float) Json.num(node.get("corner"), 12);
        return ns;
    }

    public static UIManager.Widget buildListView(Map<String,Object> node){
        ListView lv = new ListView();
        lv.rowHeight = (float) Json.num(node.get("rowHeight"), 34);
        lv.fontSize = (float) Json.num(node.get("fontSize"), 15);
        lv.evenColor = Json.str(node.get("evenColor"), lv.evenColor);
        lv.oddColor = Json.str(node.get("oddColor"), lv.oddColor);
        lv.selectedColor = Json.str(node.get("selectedColor"), lv.selectedColor);
        for(Object o : Json.arr(node.get("items"))){
            if(o != null) lv.items.add(String.valueOf(o));
        }
        return lv;
    }
}
