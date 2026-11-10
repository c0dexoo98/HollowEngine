package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.LZ4;
import com.hollow.core.Log;
import com.hollow.core.MathX;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v2.4 SAV/LOC batch — SAV-005..009, 012, 014, LOC-005..008.
 *  Deterministic, pure-Java, desktop-safe. */
public final class SaveSystems {
    private SaveSystems(){}

    // =====================================================================
    // SAV-005 + SAV-006 — compact binary save with checksum
    // =====================================================================
    public static final class BinarySave {
        public long lastChecksum = 0;

        /** encode map -> [MAGIC 'HSV1'][len][LZ4(json bytes)][crc32] */
        public byte[] encode(Map<String,Object> data){
            String json = Json.write(data);
            byte[] raw = json.getBytes(StandardCharsets.UTF_8);
            byte[] packed = LZ4.pack(raw);
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(4 + 4 + packed.length + 8);
            bb.put((byte)'H').put((byte)'S').put((byte)'V').put((byte)'1');
            bb.putInt(raw.length);
            bb.put(packed);
            bb.putLong(checksum(raw));
            lastChecksum = checksum(raw);
            return bb.array();
        }
        /** decode with integrity verification; null on corruption. */
        @SuppressWarnings("unchecked")
        public Map<String,Object> decode(byte[] file){
            try {
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(file);
                if(bb.get() != 'H' || bb.get() != 'S' || bb.get() != 'V' || bb.get() != '1') return null;
                int rawLen = bb.getInt();
                byte[] packed = new byte[file.length - 4 - 4 - 8];
                bb.get(packed);
                long stored = bb.getLong();
                byte[] raw = LZ4.unpack(packed);
                if(checksum(raw) != stored){ Log.w("Save", "checksum mismatch"); return null; }
                return Json.parseObj(new String(raw, StandardCharsets.UTF_8));
            } catch(Exception e){
                Log.w("Save", "binary decode failed: " + e.getMessage());
                return null;
            }
        }
        /** FNV-1a 64 checksum (fast, deterministic). */
        public static long checksum(byte[] data){
            long h = 0xcbf29ce484222325L;
            for(byte b : data){ h ^= (b & 0xFF); h *= 0x100000001b3L; }
            return h;
        }
    }

    // =====================================================================
    // SAV-007 — save version migrations
    // =====================================================================
    public static final class Migration {
        public interface Step { Map<String,Object> apply(Map<String,Object> save); }
        private final List<Integer> versions = new ArrayList<>();
        private final List<Step> steps = new ArrayList<>();

        /** register: version N-1 -> N migration step (ordered). */
        public void add(int fromVersion, Step s){ versions.add(fromVersion); steps.add(s); }
        /** run all steps from saveVersion up to CURRENT. */
        public Map<String,Object> migrate(Map<String,Object> save, int saveVersion, int current){
            while(saveVersion < current){
                int idx = versions.indexOf(saveVersion);
                if(idx < 0) break;                       // no registered path; stop
                save = steps.get(idx).apply(save);
                saveVersion++;
            }
            save.put("version", (double)saveVersion);
            return save;
        }
        public int stepCount(){ return steps.size(); }
    }

    // =====================================================================
    // SAV-008 — policy autosave
    // =====================================================================
    public static final class Autosave {
        public float intervalSec = 120f;               // every 2 min
        public int keepLast = 3;                       // SAV-014 rotating backups
        private float clock = 0f;
        public int autosaves = 0;
        public Runnable saveAction;

        /** call per frame; fires saveAction on interval. */
        public boolean update(float dt){
            clock += dt;
            if(clock >= intervalSec){
                clock = 0f;
                if(saveAction != null) saveAction.run();
                autosaves++;
                return true;
            }
            return false;
        }
        /** event-driven autosave (checkpoint passed, chapter end). */
        public boolean trigger(){
            if(saveAction != null){ saveAction.run(); autosaves++; return true; }
            return false;
        }
        /** rotating slot name: slot autosaves to <name>.a<k> keeping keepLast. */
        public static String rotateSlot(String base, int index, int keepLast){
            return base + ".a" + (index % Math.max(1, keepLast));
        }
    }

    // =====================================================================
    // SAV-009 — incremental snapshot (delta vs previous)
    // =====================================================================
    public static final class Incremental {
        private Map<String,Object> last = new LinkedHashMap<>();
        /** diff two flat maps: returns only changed keys with new values ("__del__" marks removals). */
        public Map<String,Object> diff(Map<String,Object> current){
            Map<String,Object> d = new LinkedHashMap<>();
            for(Map.Entry<String,Object> e : current.entrySet()){
                Object prev = last.get(e.getKey());
                if(prev == null || !prev.equals(e.getValue())) d.put(e.getKey(), e.getValue());
            }
            for(String k : last.keySet()) if(!current.containsKey(k)) d.put(k, "__del__");
            last = new LinkedHashMap<>(current);
            return d;
        }
        /** apply delta over a base. */
        public static Map<String,Object> apply(Map<String,Object> base, Map<String,Object> delta){
            Map<String,Object> out = new LinkedHashMap<>(base);
            for(Map.Entry<String,Object> e : delta.entrySet()){
                if("__del__".equals(e.getValue())) out.remove(e.getKey());
                else out.put(e.getKey(), e.getValue());
            }
            return out;
        }
        public void reset(){ last.clear(); }
    }

    // =====================================================================
    // SAV-012 — lightweight save obfuscation (anti quick-edit)
    // =====================================================================
    public static final class Obfuscate {
        private final byte[] key;
        public Obfuscate(String passphrase){
            key = (passphrase + "|hollow").getBytes(StandardCharsets.UTF_8);
        }
        public byte[] wrap(byte[] data){
            byte[] out = new byte[data.length + 4];
            long sum = BinarySave.checksum(data);
            int low32 = (int)(sum & 0xFFFFFFFFL);
            out[0] = (byte)(low32 & 0xFF); out[1] = (byte)((low32 >> 8) & 0xFF);
            out[2] = (byte)((low32 >> 16) & 0xFF); out[3] = (byte)((low32 >> 24) & 0xFF);
            for(int i = 0; i < data.length; i++) out[4 + i] = (byte)(data[i] ^ key[i % key.length]);
            return out;
        }
        public byte[] unwrap(byte[] wrapped){
            byte[] data = new byte[wrapped.length - 4];
            for(int i = 0; i < data.length; i++) data[i] = (byte)(wrapped[4 + i] ^ key[i % key.length]);
            long sum = BinarySave.checksum(data);
            long stored = (wrapped[0] & 0xFFL) | ((wrapped[1] & 0xFFL) << 8) | ((wrapped[2] & 0xFFL) << 16) | ((wrapped[3] & 0xFFL) << 24);
            if((sum & 0xFFFFFFFFL) != stored) return null;
            return data;
        }
    }

    // =====================================================================
    // LOC-005..008 — RTL numerals, plurals, pseudo-loc, hot reload marker
    // =====================================================================
    public static final class LocTools {
        /** LOC-006: plural categories — fa/ar use zero/one/two/few/many/other; en one/other. */
        public static String pluralKey(String baseKey, double n, String lang){
            if("fa".equals(lang) || "ar".equals(lang)){
                if(n == 0) return baseKey + "_zero";
                if(n == 1) return baseKey + "_one";
                if(n == 2) return baseKey + "_two";
                if(n % 100 >= 3 && n % 100 <= 10) return baseKey + "_few";
                if(n % 100 >= 11) return baseKey + "_many";
                return baseKey + "_other";
            }
            return n == 1 ? baseKey + "_one" : baseKey + "_other";
        }
        /** LOC-007: pseudo-localization — wraps with brackets + doubles vowels to expose layout bugs. */
        public static String pseudo(String s){
            StringBuilder b = new StringBuilder("[");
            for(char c : s.toCharArray()){
                b.append(c);
                if("aeiouAEIOU".indexOf(c) >= 0) b.append(Character.toLowerCase(c));
            }
            return b.append("]").toString();
        }
        /** LOC-008: returns true if file stamp changed (caller reloads strings). */
        public static boolean stampChanged(long lastStamp, long currentStamp){ return lastStamp != currentStamp; }
        /** format with args, RTL digits if requested. */
        public static String format(String template, Map<String,Object> args, boolean persianDigits){
            String out = template;
            for(Map.Entry<String,Object> e : args.entrySet())
                out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
            if(persianDigits){
                char[] d = {'۰','۱','۲','۳','۴','۵','۶','۷','۸','۹'};
                StringBuilder b = new StringBuilder(out.length());
                for(char c : out.toCharArray()) b.append(c >= '0' && c <= '9' ? d[c - '0'] : c);
                out = b.toString();
            }
            return out;
        }
    }
}
