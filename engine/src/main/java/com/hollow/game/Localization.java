package com.hollow.game;

import com.hollow.core.FileSystem;

import java.util.LinkedHashMap;
import java.util.Map;

/** String tables with RTL support. strings/fa.json, strings/en.json ... */
public final class Localization {
    private Map<String,Object> current = new LinkedHashMap<>();
    public String language = "en";
    public boolean rtl = false;

    public void load(FileSystem fs, String projectDir, String lang){
        this.language = lang;
        try {
            current = com.hollow.core.Json.parseObj(fs.readText(projectDir + "/strings/" + lang + ".json"));
        } catch(Exception e){
            com.hollow.core.Log.w("Localization", "string table missing for '" + lang + "'");
            current = new LinkedHashMap<>();
        }
    }
    public static boolean isRtl(String text){
        if(text == null) return false;
        for(int i=0;i<text.length();i++){
            char c = text.charAt(i);
            if(Character.isWhitespace(c)) continue;
            // Arabic block, Persian letters, Hebrew
            if((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F) || (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF) || c == 0x200F) return true;
            return false;
        }
        return false;
    }
    public String get(String key, String def){
        Object v = current.get(key);
        return v instanceof String s ? s : def;
    }
    /** L() shorthand */
    public String t(String key){ return get(key, key); }
}
