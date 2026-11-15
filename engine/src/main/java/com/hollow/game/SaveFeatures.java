package com.hollow.game;

import com.hollow.core.FileSystem;
import com.hollow.core.Json;
import com.hollow.core.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v2.5 save/localization batch — SAV-010/011/013/015, LOC-008/009.
 *  Deterministic, headless-testable. */
public final class SaveFeatures {
    private SaveFeatures() {}

    // =====================================================================
    // SAV-010 — safe save directory on Android (internal storage, atomic)
    // =====================================================================
    /** Resolves the save directory per platform and writes atomically (temp + rename)
     *  so a crash mid-write can never corrupt a slot. On Android the platform layer
     *  supplies context.getFilesDir(); headless/desktop uses the project dir. */
    public static final class AndroidSafeStorage {
        public final FileSystem fs;
        public String androidFilesDir = "";     // set by runtime-android (getFilesDir().getPath())
        public String projectDir = "";
        public long quotaBytes = 512L * 1024 * 1024;
        public long lastWriteBytes = 0;

        public AndroidSafeStorage(FileSystem fs){ this.fs = fs; }

        /** the one true save dir — internal storage always writable, never needs
         *  permission, removed on uninstall. */
        public String saveDir(){
            if(!androidFilesDir.isEmpty()) return androidFilesDir + "/saves";
            return projectDir + "/saves";
        }

        public boolean isAndroid(){ return !androidFilesDir.isEmpty(); }

        /** atomic write: bytes go to `<name>.tmp` first, then atomically swapped into place. */
        public boolean atomicWrite(String name, byte[] data){
            if(data.length > quotaBytes){
                Log.w("SafeSave", "quota exceeded (" + data.length + " > " + quotaBytes + ")");
                return false;
            }
            String dir = saveDir();
            try {
                fs.write(dir + "/" + name + ".tmp", data);
                // N8: the old double-write was NOT atomic (crash mid-final-write corrupted the
                // slot) and the .tmp was never cleaned up; a real atomic swap closes both holes
                if(!fs.move(dir + "/" + name + ".tmp", dir + "/" + name)){
                    try { fs.delete(dir + "/" + name + ".tmp"); } catch(Exception ignored){ }
                    Log.e("SafeSave", "atomic swap failed: " + name);
                    return false;
                }
                lastWriteBytes = data.length;
                return true;
            } catch(Exception e){
                try { fs.delete(dir + "/" + name + ".tmp"); } catch(Exception ignored){ }
                Log.e("SafeSave", "atomic write failed: " + name, e);
                return false;
            }
        }
        public byte[] atomicRead(String name){
            try { return fs.read(saveDir() + "/" + name); }
            catch(Exception e){ return null; }
        }
        public boolean exists(String name){
            try { fs.read(saveDir() + "/" + name); return true; }
            catch(Exception e){ return false; }
        }
        public String status(){ return "safestorage[dir=" + saveDir() + " android=" + isAndroid() + " quota=" + (quotaBytes >> 20) + "MB]"; }
    }

    // =====================================================================
    // SAV-011 — cloud sync hook (save folder -> service)
    // =====================================================================
    /** Scans local slots, compares hash+timestamp with the cloud adapter and syncs
     *  newest-wins. Offline: actions queue and flush later. The adapter interface is
     *  pluggable (REST / Play Games / custom) — tests use an in-memory adapter. */
    public static final class CloudSyncHook {
        public interface CloudAdapter {
            /** upload slot: returns cloud timestamp after write */
            long upload(String slotName, byte[] data);
            /** download slot: null when not present */
            byte[] download(String slotName);
            long cloudStamp(String slotName);
            List<String> listSlots();
        }

        public static final class MemoryAdapter implements CloudAdapter {
            public final Map<String, byte[]> store = new LinkedHashMap<>();
            public final Map<String, Long> stamps = new LinkedHashMap<>();
            public long clock = 1000;
            @Override public long upload(String s, byte[] d){ store.put(s, d); stamps.put(s, ++clock); return stamps.get(s); }
            @Override public byte[] download(String s){ return store.get(s); }
            @Override public long cloudStamp(String s){ return stamps.getOrDefault(s, 0L); }
            @Override public List<String> listSlots(){ return new ArrayList<>(store.keySet()); }
        }

        public CloudAdapter adapter = new MemoryAdapter();
        public final AndroidSafeStorage storage;
        public int uploads = 0, downloads = 0, conflicts = 0;
        public final List<String> offlineQueue = new ArrayList<>();
        public boolean online = true;

        public CloudSyncHook(AndroidSafeStorage storage){ this.storage = storage; }

        public static long fnv1a(byte[] data){
            long h = 0xCBF29CE484222325L;
            for(byte b : data){ h ^= (b & 0xFF); h *= 0x100000001B3L; }
            return h;
        }

        /** sync one slot: newest timestamp wins; equal hashes are no-ops. */
        public String syncSlot(String slotName, long localStamp){
            byte[] local = storage.atomicRead(slotName);
            long cloudStamp = adapter.cloudStamp(slotName);
            if(!online){ offlineQueue.add(slotName); return "queued"; }
            if(local == null && cloudStamp == 0) return "empty";
            if(local != null && cloudStamp == 0){ adapter.upload(slotName, local); uploads++; return "uploaded"; }
            if(local == null){
                byte[] cloud = adapter.download(slotName);
                if(cloud != null){ storage.atomicWrite(slotName, cloud); downloads++; return "downloaded"; }
                return "empty";
            }
            boolean same = fnv1a(local) == fnv1a(adapter.download(slotName));
            if(same) return "identical";
            conflicts++;
            if(localStamp >= cloudStamp){ adapter.upload(slotName, local); uploads++; return "uploaded (local newer)"; }
            storage.atomicWrite(slotName, adapter.download(slotName)); downloads++;
            return "downloaded (cloud newer)";
        }

        public String flushOfflineQueue(long localStamp){
            StringBuilder b = new StringBuilder();
            for(String slot : offlineQueue){
                online = true;
                b.append(slot).append(':').append(syncSlot(slot, localStamp)).append(' ');
            }
            offlineQueue.clear();
            return b.toString().trim();
        }
        public String status(){ return "cloudsync[up=" + uploads + " down=" + downloads + " conflicts=" + conflicts + " queued=" + offlineQueue.size() + "]"; }
    }

    // =====================================================================
    // SAV-013 — screenshot cover saved with the slot
    // =====================================================================
    /** Stores a small RGB cover image (render capture) next to each save slot so
     *  load menus show what the player will resume. Encoder writes a tiny
     *  uncompressed .hcover format (magic + w + h + RGB bytes) — decoder returns
     *  the pixels; the platform layer can then draw it. */
    public static final class SlotCover {
        public static final int HCOVER_MAGIC = 0x48434F56;   // 'HCOV'
        public final AndroidSafeStorage storage;
        public int maxWidth = 320, maxHeight = 180;

        public SlotCover(AndroidSafeStorage storage){ this.storage = storage; }

        /** downscale an ARGB capture to cover size and write `slot_N.hcover`. */
        public boolean save(int slot, int[] argb, int w, int h){
            int[] scaled = downscale(argb, w, h, maxWidth, maxHeight);
            int nw = scaled[0], nh = scaled[1];
            int[] px = new int[nw * nh];
            System.arraycopy(scaled, 2, px, 0, px.length);
            return storage.atomicWrite("slot_" + slot + ".hcover", encode(px, nw, nh));
        }

        /** returns [w, h, pixel0..pixelN] or null — decoder for the load menu. */
        public int[] load(int slot){
            byte[] bin = storage.atomicRead("slot_" + slot + ".hcover");
            if(bin == null || bin.length < 12) return null;
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bin);
            if(bb.getInt() != HCOVER_MAGIC) return null;
            int w = bb.getInt(), h = bb.getInt();
            if(w <= 0 || h <= 0 || w * h * 3 + 12 != bin.length) return null;
            int[] px = new int[w * h];
            for(int i = 0; i < px.length; i++){
                int r = bb.get() & 0xFF, g = bb.get() & 0xFF, b = bb.get() & 0xFF;
                px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            int[] out = new int[px.length + 2];
            out[0] = w; out[1] = h;
            System.arraycopy(px, 0, out, 2, px.length);
            return out;
        }

        static byte[] encode(int[] argb, int w, int h){
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try {
                java.io.DataOutputStream d = new java.io.DataOutputStream(bos);
                d.writeInt(HCOVER_MAGIC); d.writeInt(w); d.writeInt(h);
                for(int i = 0; i < w * h; i++){
                    int c = argb[i];
                    d.writeByte((c >> 16) & 0xFF); d.writeByte((c >> 8) & 0xFF); d.writeByte(c & 0xFF);
                }
                d.flush();
            } catch(Exception ignored){}
            return bos.toByteArray();
        }

        /** returns [nw, nh, pixels...] — dims ride in front for exact encode. */
        static int[] downscale(int[] argb, int w, int h, int maxW, int maxH){
            if(w <= maxW && h <= maxH){
                int[] out = new int[argb.length + 2];
                out[0] = w; out[1] = h;
                System.arraycopy(argb, 0, out, 2, argb.length);
                return out;
            }
            float scale = Math.min(maxW / (float)w, maxH / (float)h);
            int nw = Math.max(1, Math.round(w * scale)), nh = Math.max(1, Math.round(h * scale));
            int[] out = new int[nw * nh + 2];
            out[0] = nw; out[1] = nh;
            for(int y = 0; y < nh; y++){
                int sy = Math.min(h - 1, (int)(y / scale));
                for(int x = 0; x < nw; x++){
                    int sx = Math.min(w - 1, (int)(x / scale));
                    out[2 + y * nw + x] = argb[sy * w + sx];
                }
            }
            return out;
        }
    }

    // =====================================================================
    // SAV-015 — multi-part save merge (chapter select)
    // =====================================================================
    /** Long games split saves into chapters. A manifest lists chapter slots; loading
     *  a chapter merges its vars/inventory with all earlier chapters (earlier values
     *  lose to later ones), so state carries across a chapter jump. */
    public static final class ChapterSelect {
        public static final class Manifest {
            public final List<String> chapters = new ArrayList<>();   // slot file names in story order
            public int currentIndex = 0;
        }
        public final AndroidSafeStorage storage;
        public final Manifest manifest = new Manifest();
        public int merges = 0;

        public ChapterSelect(AndroidSafeStorage storage){ this.storage = storage; }

        public void addChapter(String slotFile){ manifest.chapters.add(slotFile); }

        /** write the manifest file (for the title screen) */
        public boolean saveManifest(){
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("chapters", manifest.chapters);
            m.put("current", manifest.currentIndex);
            return storage.atomicWrite("chapters.json", Json.write(m).getBytes(StandardCharsets.UTF_8));
        }
        public boolean loadManifest(){
            byte[] raw = storage.atomicRead("chapters.json");
            if(raw == null) return false;
            try {
                Map<String,Object> m = Json.parseObj(new String(raw, StandardCharsets.UTF_8));
                manifest.chapters.clear();
                List<Object> ch = Json.arr(m.get("chapters"));
                if(ch != null) for(Object o : ch) manifest.chapters.add(String.valueOf(o));
                manifest.currentIndex = (int)Json.num(m.get("current"), 0);
                return true;
            } catch(Exception e){ return false; }
        }

        /** merged view of vars from chapter 0..index (later chapters win). */
        public Map<String,Object> mergedVarsUpTo(int index){
            Map<String,Object> merged = new LinkedHashMap<>();
            for(int i = 0; i <= Math.min(index, manifest.chapters.size() - 1); i++){
                byte[] raw = storage.atomicRead(manifest.chapters.get(i));
                if(raw == null) continue;
                try {
                    Map<String,Object> root = Json.parseObj(new String(raw, StandardCharsets.UTF_8));
                    Map<String,Object> vars = Json.obj(root.get("vars"));
                    if(vars != null){ merged.putAll(vars); merges++; }
                } catch(Exception ignored){}
            }
            return merged;
        }

        /** merged inventory (deduped, story order). */
        public List<String> mergedInventoryUpTo(int index){
            List<String> out = new ArrayList<>();
            for(int i = 0; i <= Math.min(index, manifest.chapters.size() - 1); i++){
                byte[] raw = storage.atomicRead(manifest.chapters.get(i));
                if(raw == null) continue;
                try {
                    Map<String,Object> root = Json.parseObj(new String(raw, StandardCharsets.UTF_8));
                    List<Object> inv = Json.arr(root.get("inventory"));
                    if(inv != null) for(Object o : inv) if(!out.contains(String.valueOf(o))) out.add(String.valueOf(o));
                } catch(Exception ignored){}
            }
            return out;
        }

        public boolean jumpTo(int chapterIndex){
            if(chapterIndex < 0 || chapterIndex >= manifest.chapters.size()) return false;
            manifest.currentIndex = chapterIndex;
            return saveManifest();
        }
        public String status(){ return "chapters[n=" + manifest.chapters.size() + " current=" + manifest.currentIndex + " merges=" + merges + "]"; }
    }

    // =====================================================================
    // LOC-008 — hot-reload translation files at runtime
    // =====================================================================
    /** Watches strings/<lang>.json stamps (like DebugSystems.HotReload) and reloads
     *  the Localization table live — no restart. Fires event "strings_reloaded". */
    public static final class LocHotReload {
        public final FileSystem fs;
        public final String projectDir;
        public final Localization i18n;
        public long lastStamp = 0, lastSize = 0;
        public int reloads = 0;
        public boolean enabled = true;

        public LocHotReload(FileSystem fs, String projectDir, Localization i18n){
            this.fs = fs; this.projectDir = projectDir; this.i18n = i18n;
            remember();
        }

        long[] stamp(String lang){
            try {
                byte[] raw = fs.read(projectDir + "/strings/" + lang + ".json");
                return new long[]{ raw != null ? raw.length : -1, System.identityHashCode(raw) };
            } catch(Exception e){ return new long[]{ -1, -1 }; }
        }

        void remember(){
            long[] s = stamp(i18n.language);
            lastSize = s[0];
        }

        /** poll once per frame (cheap stat reads); reloads when the file changed. */
        public boolean poll(){
            if(!enabled) return false;
            byte[] raw = null;
            try { raw = fs.read(projectDir + "/strings/" + i18n.language + ".json"); }
            catch(Exception e){ return false; }
            if(raw == null) return false;
            long size = raw.length;
            long hash = fnv(raw);
            if(lastSize == size && lastStamp == hash) return false;
            lastSize = size; lastStamp = hash;
            i18n.load(fs, projectDir, i18n.language);
            reloads++;
            if(GameApp.instance != null && GameApp.instance.services() != null)
                GameApp.instance.services().events.fire("strings_reloaded",
                    Map.of("lang", i18n.language));
            Log.i("Loc", "hot-reloaded strings/" + i18n.language + ".json");
            return true;
        }

        static long fnv(byte[] d){
            long h = 0xCBF29CE484222325L;
            for(byte b : d){ h ^= (b & 0xFF); h *= 0x100000001B3L; }
            return h;
        }
    }

    // =====================================================================
    // LOC-009 — automatic font fallback for missing glyphs
    // =====================================================================
    /** Font coverage registry: each font declares the Unicode ranges it covers;
     *  `resolveFor(text)` returns the font chain that renders every glyph (primary
     *  font + fallbacks per missing range). The text shaper asks once per string. */
    public static final class FontFallback {
        public static final class FontDef {
            public final String id;
            public final int[][] ranges;      // inclusive [start,end] pairs
            public FontDef(String id, int[][] ranges){ this.id = id; this.ranges = ranges; }
            public boolean covers(int cp){
                for(int[] r : ranges) if(cp >= r[0] && cp <= r[1]) return true;
                return false;
            }
        }
        public final List<FontDef> fonts = new ArrayList<>();
        public final Map<Integer, String> cache = new LinkedHashMap<>();
        public int misses = 0;

        public void add(FontDef f){ fonts.add(f); cache.clear(); }

        /** font id for one codepoint: first covering font in registration order. */
        public String fontFor(int cp){
            String cached = cache.get(cp);
            if(cached != null) return cached;
            String hit = "<none>";
            for(FontDef f : fonts) if(f.covers(cp)){ hit = f.id; break; }
            cache.put(cp, hit);
            if("<none>".equals(hit)) misses++;
            return hit;
        }

        /** chain of distinct fonts needed to draw `text` (primary first). */
        public List<String> chainFor(String text){
            List<String> chain = new ArrayList<>();
            if(text == null || text.isEmpty()) return chain;
            for(int i = 0; i < text.length(); ){
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                String f = fontFor(cp);
                if(!chain.contains(f)) chain.add(f);
            }
            return chain;
        }

        /** true when every glyph of `text` is available in the primary font. */
        public boolean fullyCovered(String text){
            List<String> chain = chainFor(text);
            return !chain.isEmpty() && "<none>".equals(chain.get(0)) == false
                && chain.size() == 1 && chain.get(0).equals(fonts.isEmpty() ? "" : fonts.get(0).id);
        }

        public static FontDef latin(String id){ return new FontDef(id, new int[][]{ {0x20, 0x7E}, {0xA0, 0x24F} }); }
        public static FontDef arabic(String id){ return new FontDef(id, new int[][]{ {0x0600, 0x06FF}, {0xFB50, 0xFDFF}, {0xFE70, 0xFEFF} }); }
        public static FontDef cjk(String id){ return new FontDef(id, new int[][]{ {0x2E80, 0x9FFF}, {0xF900, 0xFAFF}, {0x20000, 0x2FA1F} }); }
    }
}
