package com.hollow.render;

/** HollowEngine 2 — material colors: named palette + #rrggbb. */
public final class Colors {

    private Colors(){ }

    public static int parse(String name){
        if(name == null || name.isEmpty()) return 0xFFB8B8C0;
        name = name.trim().toLowerCase();
        if(name.startsWith("#") && name.length() == 7){
            try {
                return 0xFF000000 | (int)Long.parseLong(name.substring(1), 16);
            } catch(NumberFormatException e){ /* fall through */ }
        }
        switch(name){
            case "white": return 0xFFFFFFFF;
            case "black": return 0xFF202020;
            case "gray": case "grey": return 0xFF808080;
            case "silver": return 0xFFB8B8C0;
            case "gold": return 0xFFD4AF37;
            case "red": return 0xFFC0392B;
            case "green": return 0xFF27AE60;
            case "blue": return 0xFF2E74B5;
            case "sky": return 0xFF87CEEB;
            case "orange": return 0xFFE67E22;
            case "purple": return 0xFF8E44AD;
            case "teal": return 0xFF1ABC9C;
            case "sand": return 0xFFD9C7A0;
            case "wood": return 0xFF8B5A2B;
            case "brick": return 0xFF9E3B33;
            case "grass": return 0xFF4C9F4C;
            default: return 0xFFB8B8C0;
        }
    }

    public static int rgb(int argb){
        return argb & 0xFFFFFF;
    }
}
