package com.hollow.render;

import com.hollow.assets.MeshData;
import com.hollow.assets.ModelAsset;
import com.hollow.core.AABB;
import com.hollow.core.Log;
import com.hollow.render.PbrRenderer;
import com.hollow.scene.Entity;
import com.hollow.scene.World;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.opengl.GLES30;

/** GPU-instanced foliage renderer (HollowEngine 2.0).
 *  - Collects FoliageComponent instances per model path, builds a shared instance
 *    buffer per model (pos.xyz, scale, rotY(rad), phase, windAmp — 8 floats/instance).
 *  - Frustum-culls instances on CPU each frame (sphere test), reuploads only when dirty
 *    or the visible set changes (visible set is stable across frames in practice).
 *  - Draw path: dedicated instanced program (Shaders.foliageVS/foliageFS) — billboard-free,
 *    true 3D meshes with wind sway in the vertex shader.
 *  Desktop/headless: no GL context — build() still runs so tests can validate scatter.
 */
public final class FoliageSystem {
    private static final int INST_FLOATS = 8;

    private static final class Batch {
        String modelPath;
        FoliageComponent source;
        MeshData mesh;                 // first primitive of model
        int vao;                       // instanced VAO
        int instanceVBO;
        int baseVBO, baseEBO;
        int indexCount;
        FloatBuffer instanceData;      // CPU mirror
        int visibleCount;
        boolean glReady;
        boolean dirty = true;
        final List<FoliageComponent.Instance> visible = new ArrayList<>();
    }

    private final Map<String, Batch> batches = new HashMap<>();
    private final List<Batch> batchList = new ArrayList<>();
    private GlObjects.Shader shader;
    private boolean programReady;

    public void clear(){
        for(Batch b : batchList) releaseGl(b);
        batches.clear();
        batchList.clear();
    }

    /** collect all FoliageComponents in the world (build scatter deterministically) */
    public void collect(World world, com.hollow.assets.AssetDB assets){
        clear();
        if(world == null) return;
        for(Entity e : world.entities){
            FoliageComponent fc = e.get(FoliageComponent.class);
            if(fc == null) continue;
            fc.build();
            if(fc.instances.isEmpty()) continue;
            ModelAsset ma = assets.model(fc.model);
            if(ma == null || ma.meshes.isEmpty() || ma.meshes.get(0).primitives.isEmpty()) continue;
            MeshData md = ma.meshes.get(0).primitives.get(0).mesh;
            Batch b = new Batch();
            b.modelPath = fc.model;
            b.source = fc;
            b.mesh = md;
            b.visible.addAll(fc.instances);
            b.visibleCount = b.visible.size();
            batches.put(fc.model, b);
            batchList.add(b);
        }
    }

    public int batchCount(){ return batchList.size(); }
    public int totalInstances(){
        int n = 0;
        for(Batch b : batchList) n += b.visibleCount;
        return n;
    }

    /** CPU frustum cull per instance; returns drawn instance count (Android) */
    public int cull(CameraData cam){
        int total = 0;
        for(Batch b : batchList){
            float r = b.mesh != null ? b.mesh.bounds().radius() : 1f;
            b.visible.clear();
            FoliageComponent src = b.source;
            if(src != null){
                for(FoliageComponent.Instance inst : src.instances){
                    AABB box = new AABB(
                        new com.hollow.core.Vec3(inst.pos.x - r * inst.scale, inst.pos.y, inst.pos.z - r * inst.scale),
                        new com.hollow.core.Vec3(inst.pos.x + r * inst.scale, inst.pos.y + 2f * r * inst.scale, inst.pos.z + r * inst.scale));
                    if(cam.cull(box)) continue;
                    b.visible.add(inst);
                }
            }
            b.visibleCount = b.visible.size();
            total += b.visibleCount;
            if(b.visibleCount > 0) b.dirty = true;
        }
        return total;
    }

    // ------------------------------------------------------------------
    // GL (Android only — never called headless)
    // ------------------------------------------------------------------
    public void initGl(){
        if(programReady) return;
        try {
            shader = new GlObjects.Shader("foliage", Shaders.FOLIAGE_VS, Shaders.FOLIAGE_FS);
            programReady = shader.ok();
        } catch(Throwable t){
            Log.e("Foliage", "program init failed: " + t.getMessage());
            programReady = false;
        }
    }

    public boolean ready(){ return programReady && !batchList.isEmpty(); }

    private void uploadBase(Batch b){
        if(b.glReady) return;
        int[] vao = new int[1];
        GLES30.glGenVertexArrays(1, vao, 0);
        b.vao = vao[0];
        GLES30.glBindVertexArray(b.vao);
        int[] vbo = new int[1];
        GLES30.glGenBuffers(1, vbo, 0);
        b.baseVBO = vbo[0];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.baseVBO);
        FloatBuffer vb = com.hollow.core.NioUtil.floatBuffer(b.mesh.positions.length + (b.mesh.normals != null ? b.mesh.normals.length : 0));
        vb.put(b.mesh.positions);
        if(b.mesh.normals != null) vb.put(b.mesh.normals);
        vb.position(0);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vb.capacity() * 4, vb, GLES30.GL_STATIC_DRAW);
        int[] ebo = new int[1];
        GLES30.glGenBuffers(1, ebo, 0);
        b.baseEBO = ebo[0];
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, b.baseEBO);
        ShortBuffer eb = com.hollow.core.NioUtil.shortBuffer(b.mesh.indices.length);
        for(int idx : b.mesh.indices) eb.put((short)idx);
        eb.position(0);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, eb.capacity() * 2, eb, GLES30.GL_STATIC_DRAW);
        b.indexCount = b.mesh.indices.length;
        int[] ivbo = new int[1];
        GLES30.glGenBuffers(1, ivbo, 0);
        b.instanceVBO = ivbo[0];
        b.glReady = true;
    }

    private void releaseGl(Batch b){
        if(!b.glReady) return;
        GLES30.glDeleteBuffers(3, new int[]{ b.baseVBO, b.baseEBO, b.instanceVBO }, 0);
        GLES30.glDeleteVertexArrays(1, new int[]{ b.vao }, 0);
        b.glReady = false;
    }

    /** draws all foliage batches; returns draw call count */
    public int draw(CameraData cam, float timeSec, com.hollow.assets.AssetDB assets){
        if(!programReady || shader == null) return 0;
        int draws = 0;
        shader.use();
        GLES30.glUniformMatrix4fv(shader.uniform("uViewProj"), 1, false, cam.viewProj.m, 0);
        GLES30.glUniform1f(shader.uniform("uTime"), timeSec);
        GLES30.glUniform3f(shader.uniform("uLightDir"), -0.4f, -1f, -0.3f);
        GLES30.glUniform3f(shader.uniform("uLightCol"), 1f, 0.97f, 0.9f);
        GLES30.glUniform3f(shader.uniform("uAmbient"), 0.25f, 0.27f, 0.33f);
        GLES30.glUniform3f(shader.uniform("uFogColor"), 0.06f, 0.07f, 0.12f);
        GLES30.glUniform1f(shader.uniform("uFogDensity"), 0.012f);
        GLES30.glUniform3f(shader.uniform("uCamPos"), cam.eye.x, cam.eye.y, cam.eye.z);

        for(Batch b : batchList){
            if(b.visibleCount == 0) continue;
            uploadBase(b);
            // instance data (visible set)
            int need = b.visibleCount * INST_FLOATS;
            if(b.instanceData == null || b.instanceData.capacity() < need){
                b.instanceData = com.hollow.core.NioUtil.floatBuffer(Math.max(64, need));
            }
            b.instanceData.position(0);
            for(FoliageComponent.Instance inst : b.visible){
                b.instanceData.put(inst.pos.x).put(inst.pos.y).put(inst.pos.z);
                b.instanceData.put(inst.scale);
                b.instanceData.put((float)Math.toRadians(inst.rotY));
                b.instanceData.put(inst.phase);
                b.instanceData.put(b.source != null ? b.source.wind : 0.4f);
                b.instanceData.put(0f);   // pad
            }
            b.instanceData.position(0);
            GLES30.glBindVertexArray(b.vao);
            // attribute layout for base mesh: pos(0), normal(1) — interleaved upload above
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.baseVBO);
            int stride = 6 * 4;
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0);
            GLES30.glEnableVertexAttribArray(1);
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12);
            // instance attribs: location 2 = iPosScale(4), location 3 = iRotPhase(4)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.instanceVBO);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, need * 4, b.instanceData, GLES30.GL_DYNAMIC_DRAW);
            GLES30.glEnableVertexAttribArray(2);
            GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, INST_FLOATS * 4, 0);
            GLES30.glVertexAttribDivisor(2, 1);
            GLES30.glEnableVertexAttribArray(3);
            GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, INST_FLOATS * 4, 16);
            GLES30.glVertexAttribDivisor(3, 1);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, b.baseEBO);
            GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, b.indexCount, GLES30.GL_UNSIGNED_SHORT, 0, b.visibleCount);
            GLES30.glVertexAttribDivisor(2, 0);
            GLES30.glVertexAttribDivisor(3, 0);
            draws++;
        }
        GLES30.glBindVertexArray(0);
        return draws;
    }
}
