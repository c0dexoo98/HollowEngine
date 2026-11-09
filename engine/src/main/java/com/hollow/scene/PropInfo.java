package com.hollow.scene;

import com.hollow.core.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Component property reflection (OBJ-030): lists the editable (public, non-static,
 *  primitive/String/Vec3-ish) fields of any component. Powers the CLI/inspector
 *  property grid, `inspect` output and the in-game entity editor — no per-class
 *  boilerplate required. */
public final class PropInfo {
    private PropInfo(){}

    public static final class Prop {
        public final String name;
        public final String typeName;
        public final boolean editable;
        Prop(String name, String typeName, boolean editable){
            this.name = name; this.typeName = typeName; this.editable = editable;
        }
        @Override public String toString(){ return name + " : " + typeName + (editable ? "" : " (ro)"); }
    }

    /** all exposed props of a component instance (name/type/current value) */
    public static List<Prop> describe(Component c){
        List<Prop> out = new ArrayList<>();
        Class<?> k = c.getClass();
        while(k != null && k != Component.class && k != Object.class){
            for(Field f : k.getDeclaredFields()){
                int mods = f.getModifiers();
                if(Modifier.isStatic(mods) || !Modifier.isPublic(mods)) continue;
                if(Modifier.isFinal(mods)) continue;
                if(f.isSynthetic()) continue;
                Class<?> t = f.getType();
                boolean editable = t.isPrimitive()
                        || t == String.class
                        || Number.class.isAssignableFrom(t)
                        || t == Vec3.class;
                out.add(new Prop(f.getName(), t.getSimpleName(), editable));
            }
            k = k.getSuperclass();
        }
        Collections.sort(out, (a, b) -> a.name.compareTo(b.name));
        return out;
    }

    /** read a prop value as a display string */
    public static String get(Component c, String prop){
        try {
            Field f = findField(c.getClass(), prop);
            if(f == null) return null;
            Object v = f.get(c);
            return v instanceof float[] a ? java.util.Arrays.toString(a) : String.valueOf(v);
        } catch(IllegalAccessException e){ return null; }
    }

    /** write a prop from a string (float/int/bool/String/vec3 "x,y,z") */
    public static boolean set(Component c, String prop, String value){
        try {
            Field f = findField(c.getClass(), prop);
            if(f == null) return false;
            Class<?> t = f.getType();
            if(t == float.class || t == double.class) f.set(c, Float.parseFloat(value));
            else if(t == int.class || t == long.class) f.set(c, Integer.parseInt(value.trim()));
            else if(t == boolean.class) f.set(c, Boolean.parseBoolean(value.trim()));
            else if(t == String.class) f.set(c, value);
            else if(t == Vec3.class){
                String[] parts = value.split(",");
                if(parts.length == 3){
                    Vec3 v = (Vec3) f.get(c);
                    if(v != null) v.set(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
                }
            }
            else return false;
            if(c.entity != null) c.entity.markDirtyDeep();
            return true;
        } catch(Exception e){ return false; }
    }

    private static Field findField(Class<?> k, String name){
        while(k != null && k != Object.class){
            try { Field f = k.getDeclaredField(name); if(Modifier.isPublic(f.getModifiers())) return f; }
            catch(NoSuchFieldException ignored){ }
            k = k.getSuperclass();
        }
        return null;
    }
}
