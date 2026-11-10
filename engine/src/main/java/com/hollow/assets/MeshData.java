package com.hollow.assets;

/** GPU-ready mesh data (interleaved on upload). Pure data — no GL here. */
public final class MeshData {
    public float[] positions;     // 3 per vertex
    public float[] normals;       // 3 per vertex (optional, may be null)
    public float[] uvs;           // 2 per vertex (optional)
    public int[] indices;         // triangle list
    /** skeletal skinning (optional): 4 joint indices + 4 weights per vertex */
    public short[] jointIndices;  // 4 per vertex (optional)
    public float[] jointWeights;  // 4 per vertex (optional)

    public int vertexCount(){ return positions.length/3; }
    public int triangleCount(){ return indices.length/3; }
    public boolean hasNormals(){ return normals != null && normals.length == positions.length; }
    public boolean hasUVs(){ return uvs != null && uvs.length == (positions.length/3)*2; }
    public boolean isSkinned(){ return jointIndices != null && jointWeights != null; }

    public com.hollow.core.AABB bounds(){
        return com.hollow.core.AABB.fromPositions(positions);
    }

    // ---- binary .hvm format ----
    public static final int HVM_MAGIC = 0x48564D31; // 'HVM1'
    public void save(String path, com.hollow.core.FileSystem fs) throws java.io.IOException {
        java.io.DataOutputStream d = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new FSOut(fs, path), 1 << 16));
        d.writeInt(HVM_MAGIC);
        int flags = 0;
        if(hasNormals()) flags |= 1;
        if(hasUVs()) flags |= 2;
        if(isSkinned()) flags |= 4;
        d.writeInt(flags);
        d.writeInt(vertexCount());
        for(int i=0;i<positions.length;i++) d.writeFloat(positions[i]);
        if((flags&1)!=0) for(int i=0;i<normals.length;i++) d.writeFloat(normals[i]);
        if((flags&2)!=0) for(int i=0;i<uvs.length;i++) d.writeFloat(uvs[i]);
        if((flags&4)!=0){
            for(int i=0;i<jointIndices.length;i++) d.writeShort(jointIndices[i]);
            for(int i=0;i<jointWeights.length;i++) d.writeFloat(jointWeights[i]);
        }
        d.writeInt(indices.length);
        for(int v : indices) d.writeInt(v);
        d.close();
    }
    public static MeshData load(String path, com.hollow.core.FileSystem fs) throws java.io.IOException {
        byte[] raw = fs.read(path);
        java.io.DataInputStream d = new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw));
        if(d.readInt() != HVM_MAGIC) throw new java.io.IOException("bad HVM magic: " + path);
        int flags = d.readInt();
        int vc = d.readInt();
        MeshData m = new MeshData();
        m.positions = new float[vc*3];
        for(int i=0;i<vc*3;i++) m.positions[i] = d.readFloat();
        if((flags&1)!=0){ m.normals = new float[vc*3]; for(int i=0;i<vc*3;i++) m.normals[i]=d.readFloat(); }
        if((flags&2)!=0){ m.uvs = new float[vc*2]; for(int i=0;i<vc*2;i++) m.uvs[i]=d.readFloat(); }
        if((flags&4)!=0){
            m.jointIndices = new short[vc*4]; for(int i=0;i<vc*4;i++) m.jointIndices[i]=d.readShort();
            m.jointWeights = new float[vc*4]; for(int i=0;i<vc*4;i++) m.jointWeights[i]=d.readFloat();
        }
        int ic = d.readInt();
        m.indices = new int[ic];
        for(int i=0;i<ic;i++) m.indices[i]=d.readInt();
        d.close();
        return m;
    }

    static final class FSOut extends java.io.OutputStream {
        private final com.hollow.core.FileSystem fs; private final String path;
        private java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        FSOut(com.hollow.core.FileSystem fs, String path){ this.fs=fs; this.path=path; }
        @Override public void write(int b){ buf.write(b); }
        @Override public void close() throws java.io.IOException {
            try { fs.write(path, buf.toByteArray()); } finally { super.close(); }
        }
    }
}
