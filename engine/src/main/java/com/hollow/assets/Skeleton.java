package com.hollow.assets;

import java.util.ArrayList;
import java.util.List;

/** Skeleton: joints with hierarchy + inverse bind matrices (from glTF skin). */
public final class Skeleton {
    public final List<Joint> joints = new ArrayList<>();
    public final int[] jointRoots = new int[0];

    public static final class Joint {
        public String name;
        public int parent = -1;
        public float[] inverseBind = new float[16]; // column-major
        /** rest pose local transform */
        public com.hollow.core.Vec3 restPos = new com.hollow.core.Vec3();
        public com.hollow.core.Quat restRot = new com.hollow.core.Quat();
        public com.hollow.core.Vec3 restScale = new com.hollow.core.Vec3(1,1,1);
    }

    public int indexOf(String name){
        for(int i=0;i<joints.size();i++) if(joints.get(i).name.equals(name)) return i;
        return -1;
    }
    public int jointCount(){ return joints.size(); }
}
