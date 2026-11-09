package com.hollow.assets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fully loaded model: node tree -> mesh primitives with materials, skin, clips. */
public final class ModelAsset {
    public static final class Primitive {
        public MeshData mesh;
        public int materialIndex = 0;
        public com.hollow.core.AABB bounds;
        /** per-primitive optional local offset from node */
    }
    public static final class MeshAsset {
        public String name = "mesh";
        public final List<Primitive> primitives = new ArrayList<>();
        public int skeletonIndex = -1;   // if skinned
    }
    public static final class Node {
        public String name = "node";
        public int parent = -1;
        public final List<Integer> children = new ArrayList<>();
        public com.hollow.core.Vec3 translation = new com.hollow.core.Vec3();
        public com.hollow.core.Quat rotation = new com.hollow.core.Quat();
        public com.hollow.core.Vec3 scale = new com.hollow.core.Vec3(1,1,1);
        public int meshIndex = -1;
        public int skinIndex = -1;
        public com.hollow.core.Mat4 localMatrix(){
            return com.hollow.core.Mat4.trs(translation, rotation, scale);
        }
    }

    public final List<Node> nodes = new ArrayList<>();
    public final List<MeshAsset> meshes = new ArrayList<>();
    public final List<MaterialDef> materials = new ArrayList<>();
    public final List<Skeleton> skins = new ArrayList<>();
    public final Map<String, AnimClip> clips = new LinkedHashMap<>();
    public int sceneRoot = -1;
    public String sourcePath = "";

    public com.hollow.core.AABB totalBounds(){
        com.hollow.core.AABB b = new com.hollow.core.AABB().reset();
        for(MeshAsset m : meshes){
            for(Primitive p : m.primitives){
                if(p.bounds != null && p.bounds.valid()) b.expand(p.bounds);
            }
        }
        return b;
    }
}
