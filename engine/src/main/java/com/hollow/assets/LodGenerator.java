package com.hollow.assets;

import com.hollow.core.Log;
import com.hollow.core.MathX;
import com.hollow.scene.MeshComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** WLD-013 — automatic multi-level mesh LOD generation (Unreal "LOD reduce" equivalent).
 *
 *  Vertex-clustering decimation: vertices are bucketed into a uniform grid whose cell
 *  size grows per level; each bucket keeps its highest-degree vertex, triangles whose
 *  three corners collapse to one bucket are dropped, duplicate triangles are removed
 *  and normals are recomputed. Deterministic (fixed bucket hashing, no RNG).
 *
 *  The generated chain is saved next to the source mesh as `name_lod1.hvm`,
 *  `name_lod2.hvm` ... and the LOD thresholds are written onto the MeshComponent
 *  (screen metric bands, see LODSelector). */
public final class LodGenerator {
    private LodGenerator() {}

    public static final class Level {
        public final int lodIndex;
        public final float reduction;      // target vertex-count fraction 0..1
        public MeshData mesh;
        public int vertsIn, vertsOut, trisIn, trisOut;
        Level(int i, float r){ lodIndex = i; reduction = r; }
        public String report(){
            return "lod" + lodIndex + ": " + vertsIn + "->" + vertsOut + " verts, "
                + trisIn + "->" + trisOut + " tris (" + Math.round(reduction * 100) + "%)";
        }
    }

    /** default UE-style chain: keep 60% / 35% / 15% of vertices */
    public static final float[] DEFAULT_CHAIN = { 0.60f, 0.35f, 0.15f };

    // ------------------------------------------------------------------
    // decimation core
    // ------------------------------------------------------------------

    /** decimate `src` so vertex count approaches `targetFraction` of the original. */
    public static MeshData decimate(MeshData src, float targetFraction){
        int srcVerts = src.vertexCount();
        int targetVerts = Math.max(4, Math.round(srcVerts * MathX.clamp(targetFraction, 0.01f, 1f)));
        if(src.triangleCount() == 0 || targetVerts >= srcVerts) return copy(src);

        // cell size so expected buckets ≈ targetVerts (squared grid over bounds)
        com.hollow.core.AABB b = src.bounds();
        float span = Math.max(b.max.x - b.min.x, Math.max(b.max.y - b.min.y, b.max.z - b.min.z));
        float cell = Math.max(1e-5f, span / Math.max(1f, (float)Math.cbrt(targetVerts) * 4f));

        Map<Long, Integer> bucketOfVertex = new HashMap<>();
        List<Float> bx = new ArrayList<>(), by = new ArrayList<>(), bz = new ArrayList<>();
        int[] remap = new int[srcVerts];
        for(int v = 0; v < srcVerts; v++){
            long key = bucketKey(src.positions[v*3], src.positions[v*3+1], src.positions[v*3+2], cell);
            Integer idx = bucketOfVertex.get(key);
            if(idx == null){
                idx = bx.size();
                bucketOfVertex.put(key, idx);
                bx.add(src.positions[v*3]); by.add(src.positions[v*3+1]); bz.add(src.positions[v*3+2]);
            }
            remap[v] = idx;
        }
        // rebuild indices, dropping degenerates and duplicates
        LinkedHashMap<Long, int[]> uniqueTris = new LinkedHashMap<>();
        for(int t = 0; t < src.indices.length; t += 3){
            int a = remap[src.indices[t]], c = remap[src.indices[t+1]], d = remap[src.indices[t+2]];
            if(a == c || a == d || c == d) continue;
            int min = Math.min(a, Math.min(c, d)), max = Math.max(a, Math.max(c, d));
            int mid = a + c + d - min - max;
            long key = ((long)min << 40) | ((long)mid << 20) | max;
            uniqueTris.putIfAbsent(key, new int[]{ a, mid, max });
        }
        MeshData out = new MeshData();
        int vc = bx.size();
        out.positions = new float[vc*3];
        for(int i = 0; i < vc; i++){
            out.positions[i*3] = bx.get(i); out.positions[i*3+1] = by.get(i); out.positions[i*3+2] = bz.get(i);
        }
        out.indices = new int[uniqueTris.size()*3];
        int o = 0;
        for(int[] t : uniqueTris.values()){ out.indices[o++] = t[0]; out.indices[o++] = t[1]; out.indices[o++] = t[2]; }
        if(src.hasUVs()){
            // bucket-average UVs
            float[] uAcc = new float[vc*2]; int[] uCnt = new int[vc];
            for(int v = 0; v < srcVerts; v++){
                int r = remap[v];
                uAcc[r*2] += src.uvs[v*2]; uAcc[r*2+1] += src.uvs[v*2+1]; uCnt[r]++;
            }
            out.uvs = new float[vc*2];
            for(int i = 0; i < vc; i++){
                out.uvs[i*2] = uAcc[i*2] / Math.max(1, uCnt[i]);
                out.uvs[i*2+1] = uAcc[i*2+1] / Math.max(1, uCnt[i]);
            }
        }
        recomputeNormals(out);
        return out;
    }

    /** deterministic 3D bucket key (quantized coordinates, order-independent). */
    static long bucketKey(float x, float y, float z, float cell){
        long ix = (long)Math.floor(x / cell), iy = (long)Math.floor(y / cell), iz = (long)Math.floor(z / cell);
        return (ix & 0x1FFFFF) | ((iy & 0x1FFFFF) << 21) | ((iz & 0xFFFFF) << 42);
    }

    public static void recomputeNormals(MeshData m){
        int vc = m.vertexCount();
        float[] n = new float[vc*3];
        for(int t = 0; t < m.indices.length; t += 3){
            int a = m.indices[t]*3, b2 = m.indices[t+1]*3, c = m.indices[t+2]*3;
            float ax = m.positions[b2]-m.positions[a], ay = m.positions[b2+1]-m.positions[a+1], az = m.positions[b2+2]-m.positions[a+2];
            float bx = m.positions[c]-m.positions[a], by = m.positions[c+1]-m.positions[a+1], bz = m.positions[c+2]-m.positions[a+2];
            float nx = ay*bz - az*by, ny = az*bx - ax*bz, nz = ax*by - ay*bx;
            for(int k = 0; k < 3; k++){
                int vi = m.indices[t+k]*3;
                n[vi] += nx; n[vi+1] += ny; n[vi+2] += nz;
            }
        }
        for(int v = 0; v < vc; v++){
            float l = (float)Math.sqrt(n[v*3]*(float)n[v*3] + n[v*3+1]*n[v*3+1] + n[v*3+2]*n[v*3+2]);
            n[v*3] = l > 1e-8f ? n[v*3]/l : 0; n[v*3+1] = l > 1e-8f ? n[v*3+1]/l : 0; n[v*3+2] = l > 1e-8f ? n[v*3+2]/l : 0;
        }
        m.normals = n;
    }

    // ------------------------------------------------------------------
    // chain generation + persistence
    // ------------------------------------------------------------------

    /** generate the full LOD chain (does not save — see {@link #saveChain}). */
    public static List<Level> generateChain(MeshData src, float[] reductions){
        List<Level> out = new ArrayList<>();
        for(int i = 0; i < reductions.length; i++){
            Level l = new Level(i + 1, reductions[i]);
            l.mesh = decimate(src, reductions[i]);
            l.vertsIn = src.vertexCount(); l.trisIn = src.triangleCount();
            l.vertsOut = l.mesh.vertexCount(); l.trisOut = l.mesh.triangleCount();
            out.add(l);
        }
        return out;
    }

    /** save chain meshes as `<base>_lodN.hvm` (same directory as the source mesh). */
    public static List<String> saveChain(MeshData src, String sourcePath, List<Level> chain, com.hollow.core.FileSystem fs){
        List<String> paths = new ArrayList<>();
        String base = sourcePath.endsWith(".hvm") ? sourcePath.substring(0, sourcePath.length()-4) : sourcePath;
        for(Level l : chain){
            String p = base + "_lod" + l.lodIndex + ".hvm";
            try {
                l.mesh.save(p, fs);
                paths.add(p);
            } catch(Exception e){
                Log.w("LodGen", "save failed " + p + ": " + e.getMessage());
            }
        }
        return paths;
    }

    /** attach generated LODs to a MeshComponent: entries ASCENDING by screen-metric
     *  threshold (see LODSelector) — lod0 (heaviest reduction) shown when far away. */
    public static void attachToComponent(com.hollow.scene.MeshComponent mc, List<Level> chain, float[] thresholds){
        mc.lods.clear();
        for(int i = 0; i < chain.size(); i++){
            MeshComponent.LodEntry e = new MeshComponent.LodEntry(
                "_lod" + chain.get(i).lodIndex,                    // suffix convention; exporter resolves relative to base mesh
                i < thresholds.length ? thresholds[i] : 0.02f * (chain.size() - i));
            mc.lods.add(e);
        }
    }

    static MeshData copy(MeshData src){
        MeshData m = new MeshData();
        m.positions = src.positions.clone();
        m.indices = src.indices.clone();
        if(src.hasNormals()) m.normals = src.normals.clone();
        if(src.hasUVs()) m.uvs = src.uvs.clone();
        return m;
    }
}
