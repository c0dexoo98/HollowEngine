package com.hollow.ui;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v5.0 — world map markers (compass / minimap data source, HollowEngine 5.0).
 *  Named markers with categories and colors, distance + bearing queries relative to
 *  the player, category filtering, "nearest of category" lookups and JSON persistence.
 *  Pure data — the UI renders it.
 */
public final class MapMarkerSystem {

    public enum Category { QUEST, VENDOR, ENEMY, RESOURCE, WAYPOINT, CUSTOM }

    public static final class Marker {
        public final String id;
        public String label;
        public final Vec3 pos = new Vec3();
        public Category category = Category.CUSTOM;
        public int argb = 0xFFFFFFFF;
        public boolean visible = true;
        public float radius = 0f;             // > 0: auto-clears when the player enters
        public Marker(String id, String label){ this.id = id; this.label = label; }
        public Marker at(float x, float y, float z){ pos.set(x, y, z); return this; }
        public Marker cat(Category c){ category = c; return this; }
        public Marker color(int argb){ this.argb = argb; return this; }
        public Marker clears(float r){ radius = r; return this; }
    }

    public interface Listener { void onMarkerEvent(String type, String markerId); }
    public Listener listener;

    public final Map<String, Marker> markers = new LinkedHashMap<>();

    public Marker add(Marker m){ markers.put(m.id, m); return m; }
    public Marker marker(String id){ return markers.get(id); }
    public void remove(String id){
        if(markers.remove(id) != null && listener != null) listener.onMarkerEvent("removed", id);
    }

    /** visible markers of the given categories (null = all) */
    public List<Marker> visible(List<Category> cats){
        List<Marker> out = new ArrayList<>();
        for(Marker m : markers.values()){
            if(!m.visible) continue;
            if(cats != null && !cats.contains(m.category)) continue;
            out.add(m);
        }
        return out;
    }

    public static final class Probe {
        public final Marker marker;
        public final float distance;          // horizontal distance (m)
        public final float bearingDeg;        // 0 = +Z north, clockwise (compass convention)
        public Probe(Marker m, float d, float b){ marker = m; distance = d; bearingDeg = b; }
    }

    /** distance + compass bearing of every visible marker relative to `playerPos`/`playerYawDeg` */
    public List<Probe> probes(Vec3 playerPos, float playerYawDeg, List<Category> cats){
        List<Probe> out = new ArrayList<>();
        for(Marker m : visible(cats)){
            float dx = m.pos.x - playerPos.x, dz = m.pos.z - playerPos.z;
            float dist = (float)Math.sqrt(dx * dx + dz * dz);
            float bearing = (float)Math.toDegrees(Math.atan2(dx, dz));       // 0 = north(+Z)
            float rel = bearing - playerYawDeg;
            rel = ((rel % 360f) + 360f) % 360f;
            out.add(new Probe(m, dist, rel));
        }
        out.sort((a, b) -> Float.compare(a.distance, b.distance));
        return out;
    }

    /** nearest visible marker of one category (or null) */
    public Probe nearest(Vec3 playerPos, Category cat){
        Probe best = null;
        for(Marker m : markers.values()){
            if(!m.visible || m.category != cat) continue;
            float dx = m.pos.x - playerPos.x, dz = m.pos.z - playerPos.z;
            float dist = (float)Math.sqrt(dx * dx + dz * dz);
            float bearing = (float)Math.toDegrees(Math.atan2(dx, dz));
            float rel = ((bearing - 0f) % 360f + 360f) % 360f;
            if(best == null || dist < best.distance) best = new Probe(m, dist, rel);
        }
        return best;
    }

    /** per-frame tick: auto-clear radius markers the player walked into */
    public int update(Vec3 playerPos){
        int cleared = 0;
        for(Marker m : markers.values()){
            if(!m.visible || m.radius <= 0f) continue;
            float dx = m.pos.x - playerPos.x, dz = m.pos.z - playerPos.z;
            if(dx * dx + dz * dz <= m.radius * m.radius){
                m.visible = false;
                cleared++;
                if(listener != null) listener.onMarkerEvent("reached", m.id);
            }
        }
        return cleared;
    }

    /** build a compass strip sample: the marker closest to each of the 8 compass sectors */
    public String compassSummary(Vec3 playerPos, float playerYawDeg){
        StringBuilder sb = new StringBuilder();
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        List<Probe> ps = probes(playerPos, playerYawDeg, null);
        for(int i = 0; i < 8; i++){
            float sector = i * 45f;
            Probe best = null;
            for(Probe p : ps){
                float d = Math.min(Math.abs(p.bearingDeg - sector), 360f - Math.abs(p.bearingDeg - sector));
                if(d <= 22.5f && (best == null || p.distance < best.distance)) best = p;
            }
            if(sb.length() > 0) sb.append(' ');
            sb.append(dirs[i]).append('=').append(best != null ? best.marker.label : "-");
        }
        return sb.toString();
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj();
        w.arr("markers");
        for(Marker m : markers.values()){
            w.obj().kv("id", m.id).kv("label", m.label)
              .kvv("pos", m.pos.x, m.pos.y, m.pos.z)
              .kv("cat", m.category.name()).kv("argb", m.argb)
              .kv("vis", m.visible).kv("radius", m.radius).endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            List<Object> ms = n.list("markers");
            if(ms == null) return;
            for(Object o : ms){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String id = Json.str(m.get("id"), null);
                if(id == null) continue;
                Marker mk = markers.get(id);
                if(mk == null){ mk = new Marker(id, Json.str(m.get("label"), id)); add(mk); }
                mk.label = Json.str(m.get("label"), mk.label);
                List<Object> pos = Json.arr(m.get("pos"));
                if(pos != null && pos.size() >= 3)
                    mk.pos.set((float)Json.num(pos.get(0), 0), (float)Json.num(pos.get(1), 0), (float)Json.num(pos.get(2), 0));
                try { mk.category = Category.valueOf(Json.str(m.get("cat"), mk.category.name())); } catch(Exception ignored){ }
                mk.argb = (int)Json.num(m.get("argb"), mk.argb);
                mk.visible = Json.bool(m.get("vis"), mk.visible);
                mk.radius = (float)Json.num(m.get("radius"), mk.radius);
            }
        } catch(Exception ignored){ }
    }
}
