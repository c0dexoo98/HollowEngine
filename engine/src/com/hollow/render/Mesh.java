package com.hollow.render;

/** HollowEngine 2 — triangle mesh + .obj text loader (v/vn/f, fan
 *  triangulation, 1-based and negative indices). If the file has no
 *  vertex normals the renderer shades with computed face normals. */
public final class Mesh {

    public final float[] pos;          // xyz per vertex
    public final float[] nrm;          // xyz per vertex, or null
    public final int[] idx;            // 3 per triangle

    public Mesh(float[] pos, float[] nrm, int[] idx){
        this.pos = pos;
        this.nrm = nrm;
        this.idx = idx;
    }

    public int vertexCount(){ return pos.length / 3; }
    public int triangleCount(){ return idx.length / 3; }

    /** minimal cube (2x2x2, centered): 24 verts, 12 triangles, CCW quads */
    public static Mesh cube(){
        float[] p = {
            -1,-1,-1,  1,-1,-1,  1, 1,-1, -1, 1,-1,   // back (z-)
             1,-1, 1, -1,-1, 1, -1, 1, 1,  1, 1, 1,   // front (z+)
            -1,-1, 1,  1,-1, 1,  1,-1,-1, -1,-1,-1,   // bottom
            -1, 1,-1,  1, 1,-1,  1, 1, 1, -1, 1, 1,   // top
             1,-1, 1,  1,-1,-1,  1, 1,-1,  1, 1, 1,   // right
            -1,-1,-1, -1,-1, 1, -1, 1, 1, -1, 1,-1    // left
        };
        int[] t = new int[36];
        for(int f = 0; f < 6; f++){
            int v = f * 4, b = f * 6;
            t[b] = v;     t[b + 1] = v + 1; t[b + 2] = v + 2;
            t[b + 3] = v; t[b + 4] = v + 2; t[b + 5] = v + 3;
        }
        return new Mesh(p, null, t);
    }

    public static Mesh fromObj(String text){
        java.util.List<float[]> verts = new java.util.ArrayList<>();
        java.util.List<float[]> norms = new java.util.ArrayList<>();
        java.util.List<Integer> tris = new java.util.ArrayList<>();
        java.util.List<Integer> triN = new java.util.ArrayList<>();
        boolean hasN = false;
        for(String raw : text.split("\n")){
            String line = raw.trim();
            if(line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            if(p[0].equals("v") && p.length >= 4){
                verts.add(new float[]{ Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]) });
            } else if(p[0].equals("vn") && p.length >= 4){
                norms.add(new float[]{ Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]) });
            } else if(p[0].equals("f") && p.length >= 4){
                int[] vi = new int[p.length - 1];
                int[] ni = new int[p.length - 1];
                for(int i = 1; i < p.length; i++){
                    String[] part = p[i].split("/");
                    int v = Integer.parseInt(part[0]);
                    if(v < 0) v = verts.size() + v; else v = v - 1;
                    vi[i - 1] = v;
                    ni[i - 1] = -1;
                    if(part.length == 3 && !part[2].isEmpty()){
                        int nn = Integer.parseInt(part[2]);
                        if(nn < 0) nn = norms.size() + nn; else nn = nn - 1;
                        ni[i - 1] = nn;
                        hasN = true;
                    }
                }
                for(int i = 1; i + 1 < vi.length; i++){       // fan triangulation
                    tris.add(vi[0]); tris.add(vi[i]); tris.add(vi[i + 1]);
                    triN.add(ni[0]); triN.add(ni[i]); triN.add(ni[i + 1]);
                }
            }
        }
        float[] pos = new float[verts.size() * 3];
        for(int i = 0; i < verts.size(); i++){
            float[] v = verts.get(i);
            pos[i * 3] = v[0]; pos[i * 3 + 1] = v[1]; pos[i * 3 + 2] = v[2];
        }
        int[] idx = new int[tris.size()];
        for(int i = 0; i < tris.size(); i++) idx[i] = tris.get(i);
        float[] nrm = null;
        if(hasN && norms.size() > 0){
            nrm = new float[verts.size() * 3];
            for(int i = 0; i + 2 < triN.size(); i += 3){
                for(int k = 0; k < 3; k++){
                    int n = triN.get(i + k);
                    if(n >= 0 && n < norms.size()){
                        float[] nv = norms.get(n);
                        int v = idx[i + k];
                        nrm[v * 3] = nv[0]; nrm[v * 3 + 1] = nv[1]; nrm[v * 3 + 2] = nv[2];
                    }
                }
            }
        }
        return new Mesh(pos, nrm, idx);
    }
}
