package com.hollow.render;

import android.opengl.GLES30;

import com.hollow.assets.Atlas;
import com.hollow.assets.AssetDB;
import com.hollow.assets.ImageData;
import com.hollow.assets.MeshData;
import com.hollow.assets.MaterialDef;
import com.hollow.assets.ModelAsset;
import com.hollow.core.AABB;
import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.Mat4;
import com.hollow.core.UiDrawList;
import com.hollow.core.Vec3;
import com.hollow.game.EngineServices;
import com.hollow.game.GameApp;
import com.hollow.scene.Entity;
import com.hollow.scene.LightComponent;
import com.hollow.scene.MeshComponent;
import com.hollow.scene.SpriteComponent;
import com.hollow.scene.World;
import com.hollow.anim.Animator;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** GLES 3.0 forward PBR renderer (SPEC §Agent B).
 *
 *  Frame pipeline:
 *    1. shadow pass   — first directional light with castShadow -> depth FBO (PCF 3x3 on receive)
 *    2. main pass     — scene FBO: opaque+mask (front-to-back, frustum-culled), procedural sky,
 *                       water, transparent (back-to-front, no depth write), sprites, particles,
 *                       debug lines
 *    3. post chain    — brightpass -> 1/4-res separable gaussian blur (x2) -> composite
 *                       (FXAA-lite + chromatic aberration + bloom + ACES + exposure + grade +
 *                       vignette + grain) into the default framebuffer
 *    4. overlays      — UI quads, sequencer letterbox, fade-to-black
 *
 *  Desktop-safe: no GL call executes before init() and there are none in static initializers.
 */
public final class PbrRenderer {
    public static final String TAG = "Renderer";

    public static final int MAX_LIGHTS = RendererConfig.MAX_LIGHTS_SUPPORTED; // 12 (shader arrays)
    public static final int MAX_JOINTS = 64;
    private static final int MAX_PARTICLES = 4096;
    /** brightpass threshold is clamped to this because the scene target is RGBA8 (max 1.0) */
    private static final float BLOOM_THRESHOLD = 0.8f;

    // ---------------- public API ----------------

    public static final class Stats {
        public int drawCalls;
        public int triangles;
        public int uploadedTextures;
        public float fpsEstimate;
    }

    // ---------------- config / sizes ----------------
    private boolean initialized = false;
    private int surfaceW = 1280, surfaceH = 720;
    private int renderW = 1280, renderH = 720;
    private RendererConfig cfg = new RendererConfig();

    // ---------------- GL objects ----------------
    private GlObjects.Shader depthShader, pbrShader, waterShader, skyShader, spriteShader,
            particleShader, flatShader, uiShader, brightShader, blurShader, compositeShader;
    private GlObjects.Fbo sceneFbo, shadowFbo, brightFbo, blurFbo;
    // v2.0
    private GlObjects.Shader ssaoShader, decalShader;
    private GlObjects.Fbo ssaoFbo, decalFboA, decalFboB;
    private final FoliageSystem foliage = new FoliageSystem();
    private World foliageWorld;
    private IBLData ibl;
    private String iblKey = "";
    private int irrTexId = 0, prefTexId = 0, dfgTexId = 0;
    private boolean iblUploaded = false;
    private final ArrayList<Entity> decalList = new ArrayList<>();
    private GlObjects.VertexArray particleVao, uiVao, lineVao;
    private GlObjects.Vbo particleVbo, uiVbo, lineVbo;

    // ---------------- caches ----------------
    private final IdentityHashMap<MeshData, GpuMesh> meshCache = new IdentityHashMap<>();
    private final HashMap<String, GlObjects.Texture2D> texCache = new HashMap<>();
    private int texBaseline = 0;
    private AssetDB assets = null;

    // ---------------- stats ----------------
    private final Stats stats = new Stats();
    private float fpsEma = 60f;
    private long lastFrameNanos = 0L;

    // ---------------- per-frame scratch ----------------
    private final Mat4 scratchInvVP = new Mat4();
    private final Mat4 scratchLightView = new Mat4();
    private final Mat4 scratchLightProj = new Mat4();
    private final float[] jointBuf = new float[MAX_JOINTS * 16];
    private final float[] particleBuf = new float[MAX_PARTICLES * 8];
    private final float[] uiQuadScratch = new float[6 * 8];
    private float[] uiBuf = new float[6 * 8 * 128];
    private float[] lineBuf = new float[14 * 512];
    private final float[] uiOrtho = new float[16];
    private final AABB sceneAabb = new AABB();
    private boolean sceneAabbValid = false;

    // ---------------- draw items ----------------
    private static final class DrawItem {
        GpuMesh mesh;
        Entity entity;
        final Material mat = new Material();
        final AABB worldAabb = new AABB();
        float dist2;
        boolean castShadow;
        boolean water;
    }

    /** resolved material (pooled on DrawItem; glTF sources are copied in, never referenced) */
    private static final class Material {
        final float[] baseColor = {0.82f, 0.82f, 0.82f, 1f};
        final float[] emissive = {0f, 0f, 0f};
        float metallic = 0f, roughness = 0.85f;
        float alphaCutoff = 0f;          // >0 -> alpha-tested
        float uvScale = 1f;
        boolean blend;                   // alpha BLEND bucket
        boolean doubleSided;
        boolean unlit;
        String texPath = null;           // baseColor texture asset path or null
    }

    private static final class GpuMesh {
        GlObjects.VertexArray vao;
        GlObjects.Vbo vbo;
        GlObjects.Ibo ibo;
        int triCount;
        boolean skinned;
        final AABB localBounds = new AABB();
        boolean boundsValid;
    }

    private final ArrayList<DrawItem> itemPool = new ArrayList<>();
    private int poolUsed = 0;
    private final ArrayList<DrawItem> opaqueList = new ArrayList<>();
    private final ArrayList<DrawItem> maskList = new ArrayList<>();
    private final ArrayList<DrawItem> blendList = new ArrayList<>();
    private final ArrayList<DrawItem> waterList = new ArrayList<>();

    private static final Comparator<DrawItem> FRONT_TO_BACK = (a, b) -> Float.compare(a.dist2, b.dist2);
    private static final Comparator<DrawItem> BACK_TO_FRONT = (a, b) -> Float.compare(b.dist2, a.dist2);

    // ---------------- lights ----------------
    private static final class LightRec {
        int kind;                                  // 0=point 1=spot
        final Vec3 pos = new Vec3();
        final Vec3 dir = new Vec3(0, 0, -1);       // spot forward
        final float[] color = new float[3];        // rgb * intensity
        float range = 10f, innerCos = 0.9f, outerCos = 0.76f;
    }

    private final ArrayList<LightRec> lightPool = new ArrayList<>();
    private int lightCount = 0;
    private final float[] sunColor = {0.9f, 0.9f, 0.9f};
    private final Vec3 sunDirScene = new Vec3(0, -1, 0);  // direction sun -> scene
    private boolean sunPresent = false;
    private boolean sunShadows = false;

    private final float[] selPos = new float[MAX_LIGHTS * 4];
    private final float[] selColor = new float[MAX_LIGHTS * 4];
    private final float[] selDir = new float[MAX_LIGHTS * 4];
    private final float[] selParam = new float[MAX_LIGHTS * 4];
    private final int[] selIdx = new int[MAX_LIGHTS];
    private final float[] selDist = new float[MAX_LIGHTS];
    private long lastLightSignature = -1L;
    private int lastLightSelCount = -1;

    private final float[] moonDir = new float[3];

    // ---------------- environment ----------------
    private static final class EnvData {
        final float[] ambient = {0.12f, 0.13f, 0.18f};
        final float[] skyTop = {0.25f, 0.45f, 0.85f};
        final float[] skyHorizon = {0.70f, 0.75f, 0.85f};
        final float[] moonColor = {0.9f, 0.93f, 1.0f};
        float moonAz = 0f, moonEl = 40f, starDensity = 0.4f;
        final float[] fogColor = {0.70f, 0.75f, 0.85f};
        float fogDensity = 0f, fogHeightFalloff = 0f, fogHeight = 0f;
        final float[] lift = {0f, 0f, 0f};
        final float[] gain = {1f, 1f, 1f};
        float gamma = 1f;
    }

    private final EnvData env = new EnvData();
    private final UiDrawList uiList = new UiDrawList();

    // ===================================================================
    // Lifecycle
    // ===================================================================

    /** create all GL resources. Must be called on the GL/EGL thread with a current context. */
    public void init(int width, int height, RendererConfig config){
        shutdown();
        cfg = config != null ? config : new RendererConfig();
        surfaceW = Math.max(1, width);
        surfaceH = Math.max(1, height);
        computeRenderSize();

        depthShader     = new GlObjects.Shader("depth", Shaders.DEPTH_VS, Shaders.DEPTH_FS);
        pbrShader       = new GlObjects.Shader("pbr", Shaders.PBR_VS, Shaders.PBR_FS);
        waterShader     = new GlObjects.Shader("water", Shaders.WATER_VS, Shaders.WATER_FS);
        skyShader       = new GlObjects.Shader("sky", Shaders.SKY_VS, Shaders.SKY_FS);
        spriteShader    = new GlObjects.Shader("sprite", Shaders.SPRITE_VS, Shaders.SPRITE_FS);
        particleShader  = new GlObjects.Shader("particle", Shaders.PARTICLE_VS, Shaders.PARTICLE_FS);
        flatShader      = new GlObjects.Shader("flat", Shaders.FLAT_VS, Shaders.FLAT_FS);
        uiShader        = new GlObjects.Shader("ui", Shaders.UI_VS, Shaders.UI_FS);
        brightShader    = new GlObjects.Shader("bright", Shaders.POST_VS, Shaders.BRIGHT_FS);
        blurShader      = new GlObjects.Shader("blur", Shaders.POST_VS, Shaders.BLUR_FS);
        compositeShader = new GlObjects.Shader("composite", Shaders.POST_VS, Shaders.COMPOSITE_FS);
        // v2.0 passes (non-fatal if they fail — fallback path used)
        try {
            ssaoShader  = new GlObjects.Shader("ssao", Shaders.SSAO_VS, Shaders.SSAO_FS);
            decalShader = new GlObjects.Shader("decal", Shaders.DECAL_VS, Shaders.DECAL_FS);
        } catch(Throwable t){ Log.w(TAG, "v2.0 shader init failed: " + t.getMessage()); }

        boolean depthTexNeeded = cfg.ssao || cfg.decals;
        sceneFbo  = depthTexNeeded ? GlObjects.Fbo.createColorDepthTex(renderW, renderH)
                                   : GlObjects.Fbo.create(renderW, renderH);
        shadowFbo = GlObjects.Fbo.createDepthOnly(cfg.shadowMapSize, cfg.shadowMapSize);
        int bw = Math.max(1, renderW / 4), bh = Math.max(1, renderH / 4);
        brightFbo = GlObjects.Fbo.create(bw, bh);
        blurFbo   = GlObjects.Fbo.create(bw, bh);
        if(cfg.ssao && ssaoShader != null && ssaoShader.ok())
            ssaoFbo = GlObjects.Fbo.create(Math.max(1, renderW / 2), Math.max(1, renderH / 2));
        if(cfg.decals && decalShader != null && decalShader.ok()){
            decalFboA = GlObjects.Fbo.createColorDepthTex(renderW, renderH);
            decalFboB = GlObjects.Fbo.createColorDepthTex(renderW, renderH);
        }
        if(cfg.instancing) foliage.initGl();

        particleVao = new GlObjects.VertexArray();
        particleVbo = new GlObjects.Vbo();
        particleVao.bind();
        particleVbo.bind();
        particleVao.attrib(0, 3, 8, 0);  // pos
        particleVao.attrib(1, 1, 8, 3);  // size
        particleVao.attrib(2, 4, 8, 4);  // rgba
        GlObjects.VertexArray.unbind();

        uiVao = new GlObjects.VertexArray();
        uiVbo = new GlObjects.Vbo();
        uiVao.bind();
        uiVbo.bind();
        uiVao.attrib(0, 2, 8, 0);  // pos px
        uiVao.attrib(1, 2, 8, 2);  // uv
        uiVao.attrib(2, 4, 8, 4);  // rgba
        GlObjects.VertexArray.unbind();

        lineVao = new GlObjects.VertexArray();
        lineVbo = new GlObjects.Vbo();
        lineVao.bind();
        lineVbo.bind();
        lineVao.attrib(0, 3, 7, 0);  // pos
        lineVao.attrib(1, 4, 7, 3);  // rgba
        GlObjects.VertexArray.unbind();

        GlObjects.Texture2D.white();
        texBaseline = GlObjects.Texture2D.uploadedCount;

        initialized = depthShader.ok() && pbrShader.ok() && waterShader.ok() && skyShader.ok()
                && spriteShader.ok() && particleShader.ok() && flatShader.ok() && uiShader.ok()
                && brightShader.ok() && blurShader.ok() && compositeShader.ok()
                && sceneFbo != null && shadowFbo != null;

        computeUiOrtho();
        GlObjects.checkError("init");
        Log.i(TAG, "PbrRenderer init " + surfaceW + "x" + surfaceH
                + " render=" + renderW + "x" + renderH
                + " quality=" + cfg.quality + " shadows=" + cfg.shadowMapSize
                + (initialized ? "" : "  [SHADER ERRORS — see log]"));
    }

    public void resize(int w, int h){
        if (!initialized) return;
        surfaceW = Math.max(1, w);
        surfaceH = Math.max(1, h);
        computeRenderSize();
        sceneFbo.resize(renderW, renderH);
        brightFbo.resize(Math.max(1, renderW / 4), Math.max(1, renderH / 4));
        blurFbo.resize(Math.max(1, renderW / 4), Math.max(1, renderH / 4));
        if(ssaoFbo != null) ssaoFbo.resize(Math.max(1, renderW / 2), Math.max(1, renderH / 2));
        if(decalFboA != null) decalFboA.resize(renderW, renderH);
        if(decalFboB != null) decalFboB.resize(renderW, renderH);
        computeUiOrtho();
    }

    public void shutdown(){
        if (!initialized && sceneFbo == null) return;
        initialized = false;
        if (depthShader != null) depthShader.dispose();
        if (pbrShader != null) pbrShader.dispose();
        if (waterShader != null) waterShader.dispose();
        if (skyShader != null) skyShader.dispose();
        if (spriteShader != null) spriteShader.dispose();
        if (particleShader != null) particleShader.dispose();
        if (flatShader != null) flatShader.dispose();
        if (uiShader != null) uiShader.dispose();
        if (brightShader != null) brightShader.dispose();
        if (blurShader != null) blurShader.dispose();
        if (compositeShader != null) compositeShader.dispose();
        if (ssaoShader != null) ssaoShader.dispose();
        if (decalShader != null) decalShader.dispose();
        depthShader = pbrShader = waterShader = skyShader = spriteShader = null;
        particleShader = flatShader = uiShader = brightShader = blurShader = compositeShader = null;
        ssaoShader = decalShader = null;

        if (sceneFbo != null) sceneFbo.dispose();
        if (shadowFbo != null) shadowFbo.dispose();
        if (brightFbo != null) brightFbo.dispose();
        if (blurFbo != null) blurFbo.dispose();
        if (ssaoFbo != null) ssaoFbo.dispose();
        if (decalFboA != null) decalFboA.dispose();
        if (decalFboB != null) decalFboB.dispose();
        sceneFbo = shadowFbo = brightFbo = blurFbo = null;
        ssaoFbo = decalFboA = decalFboB = null;
        disposeIbl();
        foliage.clear();
        foliageWorld = null;

        if (particleVao != null) particleVao.dispose();
        if (uiVao != null) uiVao.dispose();
        if (lineVao != null) lineVao.dispose();
        if (particleVbo != null) particleVbo.dispose();
        if (uiVbo != null) uiVbo.dispose();
        if (lineVbo != null) lineVbo.dispose();
        particleVao = uiVao = lineVao = null;
        particleVbo = uiVbo = lineVbo = null;

        clearGpuCaches();
        GlObjects.Texture2D.disposeWhite();
        GlObjects.checkError("shutdown");
    }

    public Stats getStats(){
        stats.uploadedTextures = GlObjects.Texture2D.uploadedCount - texBaseline;
        return stats;
    }

    private void computeRenderSize(){
        renderW = Math.max(1, Math.round(surfaceW * cfg.resolutionScale));
        renderH = Math.max(1, Math.round(surfaceH * cfg.resolutionScale));
    }

    private void computeUiOrtho(){
        Mat4 o = Mat4.ortho(0, surfaceW, surfaceH, 0, -1, 1);
        System.arraycopy(o.m, 0, uiOrtho, 0, 16);
    }

    private void clearGpuCaches(){
        for (GpuMesh g : meshCache.values()){
            if (g.vao != null) g.vao.dispose();
            if (g.vbo != null) g.vbo.dispose();
            if (g.ibo != null) g.ibo.dispose();
        }
        meshCache.clear();
        for (GlObjects.Texture2D t : texCache.values()) t.dispose();
        texCache.clear();
    }

    // ===================================================================
    // Frame
    // ===================================================================

    public void render(World world, Entity cameraEntity, EngineServices svc){
        if (!initialized || world == null || svc == null) return;

        if (cameraEntity == null && GameApp.instance != null) cameraEntity = GameApp.instance.activeCamera;
        float aspect = (float) surfaceW / (float) surfaceH;
        CameraData cam = CameraData.from(cameraEntity, aspect);
        if (cam == null) return;

        assets = svc.assets;
        stats.drawCalls = 0;
        stats.triangles = 0;
        float now = svc.time.time;
        if (svc.time.deltaTime > 0.0001f){
            float fps = 1f / svc.time.deltaTime;
            fpsEma = fpsEma * 0.9f + fps * 0.1f;
        }
        stats.fpsEstimate = fpsEma;

        parseEnvironment(world.environment);
        gatherLights(world, cam, svc);
        collectItems(world, cam);
        // v2.0: IBL bake, foliage collect, decals gather
        ensureIBL();
        if(cfg.instancing && foliageWorld != world){
            foliage.collect(world, assets);
            foliageWorld = world;
            foliage.initGl();
        }
        decalList.clear();
        if(cfg.decals && decalFboA != null){
            for (Entity e : world.entities){
                if(e.destroyed || !e.active) continue;
                if(e.has("Decal")) decalList.add(e);
            }
        }

        // ---- 1. shadow pass ----
        if (sunShadows && cfg.shadowMapSize > 0) renderShadowPass();

        // ---- 2. main pass into scene FBO ----
        sceneFbo.bind();
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LEQUAL);
        GLES30.glDepthMask(true);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glCullFace(GLES30.GL_BACK);
        float[] clear = env.fogDensity > 0.0001f ? env.fogColor : env.skyHorizon;
        GLES30.glClearColor(clear[0], clear[1], clear[2], 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);

        renderOpaque(cam, false);   // opaque
        renderSky(cam, now);
        renderOpaque(cam, true);    // alpha-masked (after sky so cutout edges stay crisp)
        // v2.0: instanced foliage (after sky/alpha so tall grass reads over geometry)
        if(cfg.instancing && foliage.ready()){
            int vis = foliage.cull(cam);
            if(vis > 0) stats.drawCalls += foliage.draw(cam, now, assets);
        }
        renderWater(cam, now);
        renderTransparent(cam);
        renderSprites(world, cam, svc, now);
        renderParticles(svc, cam);
        renderDebugLines(cam);
        GLES30.glDepthMask(true);

        // ---- 3. post chain -> default framebuffer ----
        renderPost(now, cam);

        // ---- 4. overlays ----
        renderUi(svc);
        renderLetterbox(svc);
        renderFade();

        GlObjects.VertexArray.unbind();
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthMask(true);
    }

    // ===================================================================
    // Environment parsing
    // ===================================================================

    private void parseEnvironment(Map<String,Object> e){
        env.ambient[0]=0.12f; env.ambient[1]=0.13f; env.ambient[2]=0.18f;
        env.skyTop[0]=0.25f;  env.skyTop[1]=0.45f;  env.skyTop[2]=0.85f;
        env.skyHorizon[0]=0.7f; env.skyHorizon[1]=0.75f; env.skyHorizon[2]=0.85f;
        env.moonColor[0]=0.9f; env.moonColor[1]=0.93f; env.moonColor[2]=1f;
        env.moonAz=0; env.moonEl=40; env.starDensity=0.4f;
        env.fogColor[0]=0.7f; env.fogColor[1]=0.75f; env.fogColor[2]=0.85f;
        env.fogDensity=0; env.fogHeightFalloff=0; env.fogHeight=0;
        env.lift[0]=0; env.lift[1]=0; env.lift[2]=0;
        env.gain[0]=1; env.gain[1]=1; env.gain[2]=1; env.gamma=1;
        if (e == null) return;

        f3(e.get("ambient"), env.ambient);
        Map<String,Object> sky = Json.obj(e.get("sky"));
        if (sky != null){
            f3(sky.get("topColor"), env.skyTop);
            f3(sky.get("horizonColor"), env.skyHorizon);
            f3(sky.get("moonColor"), env.moonColor);
            List<Object> mp = Json.arr(sky.get("moonPos"));
            if (mp != null && mp.size() >= 2){
                env.moonAz = (float) Json.num(mp.get(0), env.moonAz);
                env.moonEl = (float) Json.num(mp.get(1), env.moonEl);
            }
            env.starDensity = (float) Json.num(sky.get("starDensity"), env.starDensity);
        }
        Map<String,Object> fog = Json.obj(e.get("fog"));
        if (fog != null){
            f3(fog.get("color"), env.fogColor);
            env.fogDensity = (float) Json.num(fog.get("density"), env.fogDensity);
            env.fogHeightFalloff = (float) Json.num(fog.get("heightFalloff"), env.fogHeightFalloff);
            env.fogHeight = (float) Json.num(fog.get("height"), env.fogHeight);
        }
        Map<String,Object> post = Json.obj(e.get("post"));
        if (post != null){
            Map<String,Object> grade = Json.obj(post.get("grade"));
            if (grade != null){
                f3(grade.get("lift"), env.lift);
                f3(grade.get("gain"), env.gain);
                env.gamma = (float) Json.num(grade.get("gamma"), env.gamma);
            }
        }
    }

    private static void f3(Object o, float[] out){
        List<Object> a = Json.arr(o);
        if (a == null) return;
        for (int i = 0; i < out.length && i < a.size(); i++) out[i] = (float) Json.num(a.get(i), out[i]);
    }

    // ===================================================================
    // Light gathering
    // ===================================================================

    private void gatherLights(World world, CameraData cam, EngineServices svc){
        lightCount = 0;
        sunPresent = false;
        sunShadows = false;
        sunColor[0] = sunColor[1] = sunColor[2] = 0f;
        sunDirScene.set(0, -1, 0);

        LightComponent shadowSun = null;
        LightComponent firstSun = null;
        for (Entity e : world.entities){
            if (!e.active || e.destroyed) continue;
            LightComponent lc = e.get(LightComponent.class);
            if (lc == null) continue;
            if (lc.kind == LightComponent.Kind.DIRECTIONAL){
                if (firstSun == null) firstSun = lc;
                if (shadowSun == null && lc.castShadow) shadowSun = lc;
            } else {
                addLight(e, lc);
            }
        }
        LightComponent sun = shadowSun != null ? shadowSun : firstSun;
        if (sun != null && sun.entity != null){
            sun.entity.updateWorld();
            sunPresent = true;
            Vec3 f = sun.entity.rotation.forward();
            sunDirScene.set(f).norm();                    // sun -> scene
            float k = sun.effectiveIntensity();
            sunColor[0] = sun.color.r * k;
            sunColor[1] = sun.color.g * k;
            sunColor[2] = sun.color.b * k;
            sunShadows = sun.castShadow && cfg.shadowMapSize > 0;
        }

        // headlamp: game-driven spot at the camera
        if (world.blackboard.get("__headlamp") == Boolean.TRUE){
            LightRec r = obtainLight();
            r.kind = 1;
            r.pos.set(cam.eye);
            r.dir.set(cam.forward).norm();
            r.color[0] = 1f * 2.5f; r.color[1] = 0.95f * 2.5f; r.color[2] = 0.85f * 2.5f;
            r.range = 18f;
            r.innerCos = (float) Math.cos(Math.toRadians(30));
            r.outerCos = (float) Math.cos(Math.toRadians(45));
        }
    }

    private void addLight(Entity e, LightComponent lc){
        LightRec r = obtainLight();
        e.updateWorld();
        r.kind = lc.kind == LightComponent.Kind.SPOT ? 1 : 0;
        r.pos.set(e.worldMatrix.translation());
        r.dir.set(e.rotation.forward()).norm();
        float k = lc.effectiveIntensity();
        r.color[0] = lc.color.r * k;
        r.color[1] = lc.color.g * k;
        r.color[2] = lc.color.b * k;
        r.range = Math.max(0.05f, lc.range);
        float inner = Math.max(0.02f, lc.innerAngle), outer = Math.max(inner, lc.outerAngle);
        r.innerCos = (float) Math.cos(Math.toRadians(inner));
        r.outerCos = (float) Math.cos(Math.toRadians(outer));
    }

    private LightRec obtainLight(){
        if (lightCount < lightPool.size()) return lightPool.get(lightCount);
        LightRec r = new LightRec();
        lightPool.add(r);
        lightCount++;
        return r;
    }

    /** pick nearest `cfg.maxLights` lights for one object; uploads uniforms when the set changed */
    private void uploadLights(Entity e, int cx, int dummy){
        // (kept separate for clarity; see uploadLightsFor)
    }

    private boolean uploadLightsFor(Entity e){
        int max = Math.min(cfg.maxLights, MAX_LIGHTS);
        if (max <= 0 || lightCount == 0){
            if (lastLightSelCount != 0){
                pbrShader.setInt("uLightCount", 0);
                lastLightSelCount = 0;
                lastLightSignature = 0L;
            }
            return false;
        }
        Vec3 p = e.worldPosition();
        int n = Math.min(max, lightCount);
        // insertion-select the n nearest lights (deterministic: pool order is world order)
        for (int i = 0; i < n; i++){ selIdx[i] = -1; selDist[i] = Float.MAX_VALUE; }
        for (int l = 0; l < lightCount; l++){
            LightRec r = lightPool.get(l);
            float d2 = r.pos.dist2(p);
            if (d2 > r.range * r.range) continue;          // out of range — never selected
            int slot = n - 1;
            if (d2 >= selDist[slot]) continue;
            while (slot > 0 && selDist[slot - 1] > d2){
                selIdx[slot] = selIdx[slot - 1]; selDist[slot] = selDist[slot - 1]; slot--;
            }
            selIdx[slot] = l; selDist[slot] = d2;
        }
        int count = 0;
        long sig = 0L;
        for (int i = 0; i < n; i++){
            if (selIdx[i] < 0) break;
            sig |= 1L << selIdx[i];
            LightRec r = lightPool.get(selIdx[i]);
            int o = i * 4;
            selPos[o] = r.pos.x; selPos[o+1] = r.pos.y; selPos[o+2] = r.pos.z; selPos[o+3] = 0f;
            selColor[o] = r.color[0]; selColor[o+1] = r.color[1]; selColor[o+2] = r.color[2]; selColor[o+3] = 0f;
            selDir[o] = r.dir.x; selDir[o+1] = r.dir.y; selDir[o+2] = r.dir.z; selDir[o+3] = 0f;
            selParam[o] = r.range; selParam[o+1] = r.innerCos; selParam[o+2] = r.outerCos;
            selParam[o+3] = r.kind == 1 ? 1f : 0f;
            count++;
        }
        if (sig != lastLightSignature || count != lastLightSelCount){
            pbrShader.setVec4Array("uLightPos", selPos, count);
            pbrShader.setVec4Array("uLightColor", selColor, count);
            pbrShader.setVec4Array("uLightDir", selDir, count);
            pbrShader.setVec4Array("uLightParam", selParam, count);
            pbrShader.setInt("uLightCount", count);
            lastLightSignature = sig;
            lastLightSelCount = count;
        }
        return count > 0;
    }

    // ===================================================================
    // Item collection + materials
    // ===================================================================

    private DrawItem obtainItem(){
        if (poolUsed < itemPool.size()) return itemPool.get(poolUsed++);
        DrawItem d = new DrawItem();
        itemPool.add(d);
        poolUsed++;
        return d;
    }

    private void collectItems(World world, CameraData cam){
        poolUsed = 0;
        opaqueList.clear(); maskList.clear(); blendList.clear(); waterList.clear();
        sceneAabbValid = false;
        for (Entity e : world.entities){
            if (!e.active || e.destroyed) continue;
            MeshComponent mc = e.get(MeshComponent.class);
            if (mc == null || !mc.visible || mc.path == null || mc.path.isEmpty()) continue;
            collectMesh(e, mc, cam);
        }
        Collections.sort(opaqueList, FRONT_TO_BACK);
        Collections.sort(maskList, FRONT_TO_BACK);
        Collections.sort(blendList, BACK_TO_FRONT);
    }

    private void collectMesh(Entity e, MeshComponent mc, CameraData cam){
        boolean isWater = mc.path.endsWith(".water");
        if (mc.path.endsWith(".gltf") || mc.path.endsWith(".glb")){
            ModelAsset model = assets.model(mc.path);
            if (model == null) return;
            for (int mi = 0; mi < model.meshes.size(); mi++){
                ModelAsset.MeshAsset ma = model.meshes.get(mi);
                for (ModelAsset.Primitive pr : ma.primitives){
                    GpuMesh g = getMesh(pr.mesh);
                    if (g == null) continue;
                    MaterialDef src = model.materials.isEmpty() ? null
                            : model.materials.get(Math.min(pr.materialIndex, model.materials.size() - 1));
                    addItem(e, mc, g, src, src != null ? src.name : "default", isWater, cam);
                }
            }
        } else {
            GpuMesh g = getMesh(assets.mesh(mc.path));
            if (g == null) return;
            addItem(e, mc, g, null, "default", isWater, cam);
        }
    }

    private void addItem(Entity e, MeshComponent mc, GpuMesh g, MaterialDef src, String matName,
                         boolean isWater, CameraData cam){
        e.updateWorld();
        // frustum cull
        if (g.boundsValid){
            AABB wb = e.worldAABB(g.localBounds);
            if (cam.cull(wb)) return;
            DrawItem d = obtainItem();
            d.worldAabb.min.set(wb.min); d.worldAabb.max.set(wb.max);
        } else {
            DrawItem d = obtainItem();
            d.worldAabb.reset();
            d.worldAabb.expand(e.worldMatrix.translation());
        }
        DrawItem item = itemPool.get(itemPool.size() - 1);

        item.mesh = g;
        item.entity = e;
        item.castShadow = mc.castShadow && !isWater;
        item.water = isWater;
        Material m = item.mat;
        m.baseColor[0]=0.82f; m.baseColor[1]=0.82f; m.baseColor[2]=0.82f; m.baseColor[3]=1f;
        m.emissive[0]=0; m.emissive[1]=0; m.emissive[2]=0;
        m.metallic = 0f; m.roughness = 0.85f;
        m.alphaCutoff = 0f; m.uvScale = 1f;
        m.blend = false; m.doubleSided = false; m.unlit = false;
        m.texPath = null;

        if (src != null){
            System.arraycopy(src.baseColorFactor, 0, m.baseColor, 0, 4);
            System.arraycopy(src.emissiveFactor, 0, m.emissive, 0, 3);
            m.metallic = src.metallicFactor;
            m.roughness = src.roughnessFactor;
            m.uvScale = Math.max(0.0001f, src.uvScale);
            m.doubleSided = src.doubleSided;
            m.unlit = src.unlit;
            m.texPath = src.baseColorTexture;
            if (src.alphaMode == MaterialDef.AlphaMode.MASK) m.alphaCutoff = src.alphaCutoff > 0 ? src.alphaCutoff : 0.5f;
            else m.blend = src.alphaMode == MaterialDef.AlphaMode.BLEND;
        }

        // MeshComponent material overrides: materialName -> {color, metallic, roughness, emissive, texturePath, ...}
        if (mc.materialOverrides != null && !mc.materialOverrides.isEmpty()){
            Object ov = mc.materialOverrides.get(matName);
            if (ov == null) ov = mc.materialOverrides.get("default");
            if (ov == null && mc.materialOverrides.size() == 1)
                ov = mc.materialOverrides.values().iterator().next();
            applyOverride(m, Json.obj(ov));
        }

        item.dist2 = e.worldPosition().dist2(_camEye);
        _camEyeHolder = item; // placeholder to keep a single write path (see below)
    }

    /** camera eye is needed in addItem — passed via field to avoid changing many signatures */
    private final Vec3 _camEye = new Vec3();
    private DrawItem _camEyeHolder;

    private static void applyOverride(Material m, Map<String,Object> ov){
        if (ov == null) return;
        float[] c = f3Copy(ov.get("color"), null);
        if (c != null){
            m.baseColor[0] = c[0]; m.baseColor[1] = c[1]; m.baseColor[2] = c[2];
            if (c.length > 3) m.baseColor[3] = c[3];
        }
        m.metallic = (float) Json.num(ov.get("metallic"), m.metallic);
        m.roughness = (float) Json.num(ov.get("roughness"), m.roughness);
        float[] em = f3Copy(ov.get("emissive"), null);
        if (em != null){ m.emissive[0]=em[0]; m.emissive[1]=em[1]; m.emissive[2]=em[2]; }
        String tex = Json.str(ov.get("texturePath"), null);
        if (tex != null && !tex.isEmpty()) m.texPath = tex;
        m.uvScale = (float) Json.num(ov.get("uvScale"), m.uvScale);
        m.doubleSided = Json.bool(ov.get("doubleSided"), m.doubleSided);
        m.unlit = Json.bool(ov.get("unlit"), m.unlit);
        String am = Json.str(ov.get("alphaMode"), null);
        if (am != null){
            String a = am.toUpperCase();
            if (a.equals("MASK")) m.alphaCutoff = (float) Json.num(ov.get("alphaCutoff"), 0.5);
            else if (a.equals("BLEND")){ m.blend = true; m.alphaCutoff = 0; }
            else { m.blend = false; m.alphaCutoff = 0; }
        }
    }

    private static float[] f3Copy(Object o, float[] def){
        List<Object> a = Json.arr(o);
        if (a == null || a.isEmpty()) return def;
        int n = Math.min(4, a.size());
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = (float) Json.num(a.get(i), 0);
        return out;
    }

    // ===================================================================
    // GPU mesh upload
    // ===================================================================

    private GpuMesh getMesh(MeshData md){
        if (md == null || md.positions == null || md.positions.length < 9 || md.indices == null
                || md.indices.length < 3) return null;
        GpuMesh g = meshCache.get(md);
        if (g != null) return g;
        g = uploadMesh(md);
        if (g == null) return null;
        meshCache.put(md, g);
        return g;
    }

    private GpuMesh uploadMesh(MeshData md){
        int vc = md.vertexCount();
        boolean skinned = md.isSkinned();
        boolean hasN = md.hasNormals();
        boolean hasUV = md.hasUVs();
        int stride = 8 + (skinned ? 8 : 0); // pos3 normal3 uv2 [joints4 weights4]

        float[] data = new float[vc * stride];
        float[] normals = hasN ? md.normals : computeNormals(md);
        for (int v = 0; v < vc; v++){
            int s = v * stride, p = v * 3;
            data[s]   = md.positions[p];
            data[s+1] = md.positions[p+1];
            data[s+2] = md.positions[p+2];
            data[s+3] = normals != null ? normals[p]   : 0f;
            data[s+4] = normals != null ? normals[p+1] : 1f;
            data[s+5] = normals != null ? normals[p+2] : 0f;
            data[s+6] = hasUV ? md.uvs[v*2]   : 0f;
            data[s+7] = hasUV ? md.uvs[v*2+1] : 0f;
            if (skinned){
                for (int k = 0; k < 4; k++){
                    data[s+8+k]   = md.jointIndices != null ? md.jointIndices[v*4+k] : 0f;
                    data[s+12+k]  = md.jointWeights != null ? md.jointWeights[v*4+k] : (k == 0 ? 1f : 0f);
                }
            }
        }

        GpuMesh g = new GpuMesh();
        g.skinned = skinned;
        g.triCount = md.indices.length / 3;
        { com.hollow.core.AABB lb = md.bounds(); g.localBounds.min.set(lb.min); g.localBounds.max.set(lb.max); }
        g.boundsValid = g.localBounds.valid();
        g.vao = new GlObjects.VertexArray();
        g.vbo = new GlObjects.Vbo();
        g.ibo = new GlObjects.Ibo(md.indices);
        g.vao.bind();
        g.vbo.bind();
        g.ibo.bind();
        g.vao.attrib(0, 3, stride, 0);
        g.vao.attrib(1, 3, stride, 3);
        g.vao.attrib(2, 2, stride, 6);
        if (skinned){
            g.vao.attrib(3, 4, stride, 8);
            g.vao.attrib(4, 4, stride, 12);
        }
        g.vbo.upload(data);
        GlObjects.VertexArray.unbind();
        return g;
    }

    /** smooth per-vertex normals when the source mesh has none */
    private static float[] computeNormals(MeshData md){
        int vc = md.vertexCount();
        float[] n = new float[vc * 3];
        int[] idx = md.indices;
        float[] p = md.positions;
        for (int t = 0; t + 2 < idx.length; t += 3){
            int a = idx[t] * 3, b = idx[t+1] * 3, c = idx[t+2] * 3;
            float e1x = p[b]-p[a], e1y = p[b+1]-p[a+1], e1z = p[b+2]-p[a+2];
            float e2x = p[c]-p[a], e2y = p[c+1]-p[a+1], e2z = p[c+2]-p[a+2];
            float nx = e1y*e2z - e1z*e2y, ny = e1z*e2x - e1x*e2z, nz = e1x*e2y - e1y*e2x;
            n[a]+=nx; n[a+1]+=ny; n[a+2]+=nz;
            n[b]+=nx; n[b+1]+=ny; n[b+2]+=nz;
            n[c]+=nx; n[c+1]+=ny; n[c+2]+=nz;
        }
        for (int v = 0; v < vc; v++){
            int s = v * 3;
            float l = (float) Math.sqrt(n[s]*n[s] + n[s+1]*n[s+1] + n[s+2]*n[s+2]);
            if (l > 1e-9f){ n[s]/=l; n[s+1]/=l; n[s+2]/=l; }
        }
        return n;
    }

    private GlObjects.Texture2D getTexture(String path){
        if (path == null || path.isEmpty() || path.equals("@white")) return GlObjects.Texture2D.white();
        GlObjects.Texture2D t = texCache.get(path);
        if (t == null){
            ImageData im = assets != null ? assets.image(path) : null;
            if (im == null) return GlObjects.Texture2D.white();
            t = new GlObjects.Texture2D(im, true);
            texCache.put(path, t);
        }
        return t;
    }

    // ===================================================================
    // Passes
    // ===================================================================

    private void renderShadowPass(){
        if (!sceneAabbValid || sunPresent == false) return;
        AABB b = sceneAabb;
        Vec3 center = b.center();
        Vec3 size = b.size();
        float ext = Math.max(size.x, Math.max(size.y, size.z)) * 0.6f + 1.5f;
        float dist = ext * 2.5f + 5f;
        Vec3 eye = center.cpy().addScaled(sunDirScene, -dist);
        Vec3 up = Math.abs(sunDirScene.y) > 0.99f ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
        scratchLightView.set(Mat4.lookAt(eye, center, up));
        scratchLightProj.set(Mat4.ortho(-ext, ext, -ext, ext, 0.5f, dist * 2f));
        float[] lightMat = scratchLightProj.mul(scratchLightView).m;

        shadowFbo.bind();
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LESS);
        GLES30.glDepthMask(true);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glCullFace(GLES30.GL_BACK);
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL);
        GLES30.glPolygonOffset(2f, 4f);
        GLES30.glClearColor(1f, 1f, 1f, 1f);
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);

        depthShader.use();
        depthShader.setMat4("uLightMat", lightMat);
        for (DrawItem d : opaqueList) drawShadowItem(d);
        for (DrawItem d : maskList) drawShadowItem(d);

        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL);
        GlObjects.VertexArray.unbind();
    }

    private void drawShadowItem(DrawItem d){
        if (!d.castShadow || d.mesh == null) return;
        depthShader.setMat4("uModel", d.entity.worldMatrix.m);
        setJoints(d.entity, d.mesh, depthShader);
        d.mesh.vao.bind();
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, d.mesh.ibo.count, GLES30.GL_UNSIGNED_INT, 0);
        stats.drawCalls++;
        stats.triangles += d.mesh.triCount;
    }

    private void setJoints(Entity e, GpuMesh mesh, GlObjects.Shader shader){
        if (!mesh.skinned){
            shader.setInt("uSkinned", 0);
            return;
        }
        Animator an = e.get(Animator.class);
        float[] jm = an != null ? an.jointMatrices : null;
        if (jm == null || jm.length < 16){
            shader.setInt("uSkinned", 0);
            return;
        }
        int n = Math.min(MAX_JOINTS, jm.length / 16);
        System.arraycopy(jm, 0, jointBuf, 0, n * 16);
        shader.setInt("uSkinned", 1);
        shader.setMat4Array("uJoints", jointBuf, n);
    }

    private void bindPbrFrameUniforms(CameraData cam){
        pbrShader.use();
        pbrShader.setMat4("uProj", cam.proj.m);
        pbrShader.setMat4("uView", cam.view.m);
        pbrShader.setVec3("uCamPos", cam.eye.x, cam.eye.y, cam.eye.z);
        pbrShader.setVec3("uAmbient", env.ambient[0], env.ambient[1], env.ambient[2]);
        pbrShader.setVec3("uSunDir", -sunDirScene.x, -sunDirScene.y, -sunDirScene.z);
        pbrShader.setVec3("uSunColor", sunColor[0], sunColor[1], sunColor[2]);
        pbrShader.setInt("uSunShadow", sunShadows ? 1 : 0);
        pbrShader.setFloat("uShadowTexel", 1f / Math.max(1, cfg.shadowMapSize));
        pbrShader.setVec3("uFogColor", env.fogColor[0], env.fogColor[1], env.fogColor[2]);
        pbrShader.setFloat("uFogDensity", env.fogDensity);
        pbrShader.setFloat("uFogHeightFalloff", env.fogHeightFalloff);
        pbrShader.setFloat("uFogHeight", env.fogHeight);
        pbrShader.setInt("uBaseTex", 0);
        pbrShader.setInt("uShadowMap", 1);
        if (sunShadows && shadowFbo != null && shadowFbo.depthTex != null)
            shadowFbo.depthTex.bind(1);
        else
            GlObjects.Texture2D.white().bind(1);
        // v2.0: full IBL (SkyLight equivalent)
        if(cfg.ibl && iblUploaded && irrTexId != 0){
            pbrShader.setInt("uIBL", 1);
            pbrShader.setInt("uIrradiance", 2);
            pbrShader.setInt("uPrefilter", 3);
            pbrShader.setInt("uDFG", 4);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, irrTexId);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, prefTexId);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE4);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexId);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        } else {
            pbrShader.setInt("uIBL", 0);
        }
        lastLightSignature = -1L;
        lastLightSelCount = -1;
    }

    private void renderOpaque(CameraData cam, boolean maskPass){
        List<DrawItem> list = maskPass ? maskList : opaqueList;
        if (list.isEmpty()) return;
        bindPbrFrameUniforms(cam);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDepthMask(true);
        for (DrawItem d : list){
            Material m = d.mat;
            pbrShader.setMat4("uModel", d.entity.worldMatrix.m);
            pbrShader.setMat4("uNormalMat", d.entity.normalMatrix.m);
            pbrShader.setVec4("uBaseColor", m.baseColor[0], m.baseColor[1], m.baseColor[2], m.baseColor[3]);
            pbrShader.setVec3("uEmissive", m.emissive[0], m.emissive[1], m.emissive[2]);
            pbrShader.setFloat("uMetallic", m.metallic);
            pbrShader.setFloat("uRoughness", m.roughness);
            pbrShader.setFloat("uAlphaCutoff", maskPass ? (m.alphaCutoff > 0 ? m.alphaCutoff : 0.5f) : 0f);
            pbrShader.setFloat("uUvScale", m.uvScale);
            pbrShader.setFloat("uNormalOffset", 0.04f);
            pbrShader.setInt("uUnlit", m.unlit ? 1 : 0);
            pbrShader.setInt("uDoubleSided", m.doubleSided ? 1 : 0);
            getTexture(m.texPath).bind(0);
            uploadLightsFor(d.entity);
            setJoints(d.entity, d.mesh, pbrShader);
            if (m.doubleSided) GLES30.glDisable(GLES30.GL_CULL_FACE);
            else { GLES30.glEnable(GLES30.GL_CULL_FACE); GLES30.glCullFace(GLES30.GL_BACK); }
            d.mesh.vao.bind();
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, d.mesh.ibo.count, GLES30.GL_UNSIGNED_INT, 0);
            stats.drawCalls++;
            stats.triangles += d.mesh.triCount;
        }
    }

    private void renderSky(CameraData cam, float time){
        scratchInvVP.set(cam.viewProj).invert();
        float az = (float) Math.toRadians(env.moonAz);
        float el = (float) Math.toRadians(env.moonEl);
        moonDir[0] = (float) (Math.cos(el) * Math.sin(az));
        moonDir[1] = (float) Math.sin(el);
        moonDir[2] = (float) (-Math.cos(el) * Math.cos(az));

        skyShader.use();
        skyShader.setMat4("uInvViewProj", scratchInvVP.m);
        skyShader.setVec3("uCamPos", cam.eye.x, cam.eye.y, cam.eye.z);
        skyShader.setVec3("uTop", env.skyTop[0], env.skyTop[1], env.skyTop[2]);
        skyShader.setVec3("uHorizon", env.skyHorizon[0], env.skyHorizon[1], env.skyHorizon[2]);
        skyShader.setVec3("uMoonDir", moonDir[0], moonDir[1], moonDir[2]);
        skyShader.setVec3("uMoonColor", env.moonColor[0], env.moonColor[1], env.moonColor[2]);
        skyShader.setFloat("uStarDensity", env.starDensity);
        skyShader.setFloat("uTime", time);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glDepthMask(false);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        GLES30.glDepthMask(true);
        stats.drawCalls++;
    }

    private void renderWater(CameraData cam, float time){
        if (waterList.isEmpty()) return;
        waterShader.use();
        waterShader.setMat4("uProj", cam.proj.m);
        waterShader.setMat4("uView", cam.view.m);
        waterShader.setFloat("uTime", time);
        waterShader.setFloat("uWaveHeight", 0.14f);
        waterShader.setVec3("uSkyColor",
                (env.skyTop[0] + env.skyHorizon[0]) * 0.5f,
                (env.skyTop[1] + env.skyHorizon[1]) * 0.5f,
                (env.skyTop[2] + env.skyHorizon[2]) * 0.5f);
        waterShader.setVec3("uSunDir", -sunDirScene.x, -sunDirScene.y, -sunDirScene.z);
        waterShader.setVec3("uSunColor", sunColor[0], sunColor[1], sunColor[2]);
        waterShader.setVec3("uCamPos", cam.eye.x, cam.eye.y, cam.eye.z);
        waterShader.setVec3("uFogColor", env.fogColor[0], env.fogColor[1], env.fogColor[2]);
        waterShader.setFloat("uFogDensity", env.fogDensity);
        waterShader.setFloat("uFogHeightFalloff", env.fogHeightFalloff);
        waterShader.setFloat("uFogHeight", env.fogHeight);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glDepthMask(false);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        for (DrawItem d : waterList){
            waterShader.setMat4("uModel", d.entity.worldMatrix.m);
            waterShader.setMat4("uNormalMat", d.entity.normalMatrix.m);
            waterShader.setFloat("uUvScale", d.mat.uvScale);
            waterShader.setVec3("uDeepColor", d.mat.baseColor[0], d.mat.baseColor[1], d.mat.baseColor[2]);
            d.mesh.vao.bind();
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, d.mesh.ibo.count, GLES30.GL_UNSIGNED_INT, 0);
            stats.drawCalls++;
            stats.triangles += d.mesh.triCount;
        }
        GLES30.glDepthMask(true);
    }

    private void renderTransparent(CameraData cam){
        if (blendList.isEmpty()) return;
        bindPbrFrameUniforms(cam);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glDepthMask(false);
        for (DrawItem d : blendList){
            Material m = d.mat;
            pbrShader.setMat4("uModel", d.entity.worldMatrix.m);
            pbrShader.setMat4("uNormalMat", d.entity.normalMatrix.m);
            pbrShader.setVec4("uBaseColor", m.baseColor[0], m.baseColor[1], m.baseColor[2], m.baseColor[3]);
            pbrShader.setVec3("uEmissive", m.emissive[0], m.emissive[1], m.emissive[2]);
            pbrShader.setFloat("uMetallic", m.metallic);
            pbrShader.setFloat("uRoughness", m.roughness);
            pbrShader.setFloat("uAlphaCutoff", 0f);
            pbrShader.setFloat("uUvScale", m.uvScale);
            pbrShader.setFloat("uNormalOffset", 0.04f);
            pbrShader.setInt("uUnlit", m.unlit ? 1 : 0);
            pbrShader.setInt("uDoubleSided", m.doubleSided ? 1 : 0);
            getTexture(m.texPath).bind(0);
            uploadLightsFor(d.entity);
            setJoints(d.entity, d.mesh, pbrShader);
            if (m.doubleSided) GLES30.glDisable(GLES30.GL_CULL_FACE);
            else { GLES30.glEnable(GLES30.GL_CULL_FACE); GLES30.glCullFace(GLES30.GL_BACK); }
            d.mesh.vao.bind();
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, d.mesh.ibo.count, GLES30.GL_UNSIGNED_INT, 0);
            stats.drawCalls++;
            stats.triangles += d.mesh.triCount;
        }
        GLES30.glDepthMask(true);
    }

    private void renderSprites(World world, CameraData cam, EngineServices svc, float time){
        boolean any = false;
        for (Entity e : world.entities){
            if (e.active && !e.destroyed && e.has("Sprite")){ any = true; break; }
        }
        if (!any) return;

        spriteShader.use();
        spriteShader.setMat4("uProj", cam.proj.m);
        spriteShader.setMat4("uView", cam.view.m);
        spriteShader.setInt("uTex", 0);
        // billboard basis from the camera (right = forward x worldUp, up = right x forward)
        Vec3 worldUp = new Vec3(0, 1, 0);
        Vec3 right = cam.forward.cpy().cross(worldUp).norm();
        Vec3 up = right.cpy().cross(cam.forward).norm();
        if (right.len2() < 1e-8f){ right.set(1, 0, 0); up.set(0, 1, 0); }

        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glDepthMask(false);
        GLES30.glDisable(GLES30.GL_CULL_FACE);

        for (Entity e : world.entities){
            if (!e.active || e.destroyed) continue;
            SpriteComponent sc = e.get(SpriteComponent.class);
            if (sc == null || sc.sprite == null || sc.sprite.isEmpty()) continue;
            var atlas = assets.atlas(sc.atlas);
            Atlas.Region reg = null;
            if (sc.fps > 0){
                int frame = (int) (time * sc.fps);
                reg = atlas.get(sc.sprite + "_" + frame);
            }
            if (reg == null) reg = atlas.get(sc.sprite);
            if (reg == null) continue;

            GlObjects.Texture2D tex = getTexture(atlas.texturePath);
            e.updateWorld();
            Vec3 center = e.worldMatrix.translation();
            float w = sc.width * e.scale.x;
            float h = sc.height * e.scale.y;
            spriteShader.setVec3("uCenter", center.x, center.y, center.z);
            if (sc.billboard){
                spriteShader.setVec3("uRight", right.x, right.y, right.z);
                spriteShader.setVec3("uUp", up.x, up.y, up.z);
            } else {
                // axis-aligned poster in the world X/Y plane
                spriteShader.setVec3("uRight", 1, 0, 0);
                spriteShader.setVec3("uUp", 0, 1, 0);
            }
            spriteShader.setVec4("uSize", w, h, 0, 0);
            spriteShader.setVec4("uUV", reg.u0, reg.v0, reg.u1, reg.v1);
            spriteShader.setVec4("uColor", sc.color.r, sc.color.g, sc.color.b, sc.color.a);
            tex.bind(0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            stats.drawCalls++;
            stats.triangles += 2;
        }
        GLES30.glDepthMask(true);
    }

    private void renderParticles(EngineServices svc, CameraData cam){
        if (svc.particles == null) return;
        int n = svc.particles.getRenderData(particleBuf);   // interleaved [x,y,z,size,r,g,b,a]
        if (n <= 0) return;
        n = Math.min(n, MAX_PARTICLES);
        particleVao.bind();
        particleVbo.uploadDynamic(particleBuf, n * 8);
        particleShader.use();
        particleShader.setMat4("uProj", cam.proj.m);
        particleShader.setMat4("uView", cam.view.m);
        particleShader.setFloat("uPointScale", 0.5f * renderH * cam.proj.m[5]);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glDepthMask(false);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        // single additive batch (emitter default); alpha-batched particles arrive with the
        // 2-arg getRenderData(out, additive) contract — see report
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, n);
        stats.drawCalls++;
        GLES30.glDepthMask(true);
    }

    private void renderDebugLines(CameraData cam){
        int lines = DebugDraw.lineCount();
        if (lines <= 0) return;
        int need = lines * 14;
        if (lineBuf.length < need) lineBuf = new float[Math.max(need, lineBuf.length * 2)];
        float[] p = DebugDraw.linePositions();
        float[] c = DebugDraw.lineColors();
        for (int i = 0; i < lines; i++){
            int po = i * 6, co = i * 8, o = i * 14;
            lineBuf[o]    = p[po];   lineBuf[o+1]  = p[po+1]; lineBuf[o+2]  = p[po+2];
            lineBuf[o+3]  = c[co];   lineBuf[o+4]  = c[co+1]; lineBuf[o+5]  = c[co+2]; lineBuf[o+6]  = c[co+3];
            lineBuf[o+7]  = p[po+3]; lineBuf[o+8]  = p[po+4]; lineBuf[o+9]  = p[po+5];
            lineBuf[o+10] = c[co+4]; lineBuf[o+11] = c[co+5]; lineBuf[o+12] = c[co+6]; lineBuf[o+13] = c[co+7];
        }
        lineVao.bind();
        lineVbo.uploadDynamic(lineBuf, need);
        flatShader.use();
        flatShader.setMat4("uProj", cam.proj.m);
        flatShader.setMat4("uView", cam.view.m);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glDepthMask(false);
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, lines * 2);
        stats.drawCalls++;
        GLES30.glDepthMask(true);
    }

    // ===================================================================
    // Post chain
    // ===================================================================

    private void renderPost(float time, CameraData cam){
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDisable(GLES30.GL_CULL_FACE);

        // ---- v2.0: SSAO (half res, applies to scene color) ----
        boolean ssaoOn = cfg.ssao && ssaoFbo != null && ssaoShader != null && ssaoShader.ok()
                && sceneFbo.depthTex != null;
        if(ssaoOn){
            ssaoFbo.bind();
            ssaoShader.use();
            ssaoShader.setInt("uDepth", 0);
            ssaoShader.setInt("uSceneColor", 1);
            ssaoShader.setMat4("uInvProj", cam.proj.cpy().invert().m);
            ssaoShader.setMat4("uProj", cam.proj.m);
            ssaoShader.setInt("uSamples", cfg.ssaoSamples);
            ssaoShader.setFloat("uRadius", cfg.ssaoRadius);
            ssaoShader.setFloat("uStrength", cfg.ssaoStrength);
            ssaoShader.setFloat("uSeed", time % 17.0f);
            sceneFbo.depthTex.bind(0);
            sceneFbo.color.bind(1);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            stats.drawCalls++;
        }

        // ---- v2.0: screen-space decals (ping-pong, up to 8 per frame) ----
        boolean decalOn = cfg.decals && decalFboA != null && decalShader != null && decalShader.ok()
                && !decalList.isEmpty() && sceneFbo.depthTex != null;
        GlObjects.Texture2D sceneSource = ssaoOn ? ssaoFbo.color : sceneFbo.color;
        if(decalOn){
            Mat4 invVP = cam.viewProj.cpy().invert();
            GlObjects.Fbo srcFbo = decalFboA, dstFbo = decalFboB;
            int count = Math.min(8, decalList.size());
            for(int i = 0; i < count; i++){
                Entity d = decalList.get(i);
                DecalComponent dc = d.get(DecalComponent.class);
                if(dc == null) continue;
                d.updateWorld();
                dstFbo.bind();
                decalShader.use();
                decalShader.setInt("uSceneColor", 0);
                decalShader.setInt("uDepth", 1);
                decalShader.setInt("uDecalTex", 2);
                decalShader.setVec3("uDecalPos", d.worldMatrix.m[12], d.worldMatrix.m[13], d.worldMatrix.m[14]);
                decalShader.setVec3("uDecalHalf", dc.size.x * 0.5f, dc.size.y * 0.5f, dc.size.z * 0.5f);
                decalShader.setMat4("uDecalRot", d.worldMatrix.m);
                decalShader.setMat4("uInvViewProj", invVP.m);
                decalShader.setFloat("uIntensity", dc.intensity);
                decalShader.setFloat("uTiling", dc.tiling);
                sceneSource.bind(0);
                sceneFbo.depthTex.bind(1);
                GlObjects.Texture2D dtex = getTexture(dc.texture.isEmpty() ? null : dc.texture);
                if(dtex != null) dtex.bind(2); else GlObjects.Texture2D.white().bind(2);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
                stats.drawCalls++;
                sceneSource = dstFbo.color;
                GlObjects.Fbo tmp = srcFbo; srcFbo = dstFbo; dstFbo = tmp;
            }
            // ensure we leave the ping-pong with the newest result in a known buffer
            if((count & 1) == 1){
                // srcFbo now holds the latest — move it back to A for a stable reference below
                // (sceneSource already points at it)
            }
            GlObjects.Fbo.unbindDefault(surfaceW, surfaceH);
        }

        boolean bloomOn = cfg.bloom && cfg.quality != RendererConfig.Quality.LOW;
        if (bloomOn){
            // brightpass scene -> 1/4 res (v2.0: uses SSAO/decal-processed scene)
            brightFbo.bind();
            brightShader.use();
            brightShader.setInt("uTex", 0);
            brightShader.setFloat("uThreshold", BLOOM_THRESHOLD);
            sceneSource.bind(0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            stats.drawCalls++;
            // separable gaussian, 2 iterations
            blurShader.use();
            blurShader.setInt("uTex", 0);
            float tw = 1f / Math.max(1, brightFbo.width);
            float th = 1f / Math.max(1, brightFbo.height);
            for (int i = 0; i < 2; i++){
                blurFbo.bind();
                blurShader.setVec2Direct("uDir", tw, 0f);
                brightFbo.color.bind(0);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
                brightFbo.bind();
                blurShader.setVec2Direct("uDir", 0f, th);
                blurFbo.color.bind(0);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            }
            stats.drawCalls += 5;
        }

        // composite into the default framebuffer
        GlObjects.Fbo.unbindDefault(surfaceW, surfaceH);
        compositeShader.use();
        compositeShader.setInt("uScene", 0);
        compositeShader.setInt("uBloom", 1);
        compositeShader.setVec2Direct("uTexel", 1f / renderW, 1f / renderH);
        compositeShader.setFloat("uBloomStrength", bloomOn ? 0.9f : 0f);
        compositeShader.setFloat("uExposure", cfg.exposure);
        compositeShader.setFloat("uTime", time);
        compositeShader.setFloat("uGrain", cfg.grain ? 0.045f : 0f);
        compositeShader.setFloat("uVignette", cfg.vignette ? 0.35f : 0f);
        compositeShader.setFloat("uCA", cfg.chromaticAberration);
        compositeShader.setInt("uFxaa", (cfg.fxaa && cfg.quality != RendererConfig.Quality.LOW) ? 1 : 0);
        compositeShader.setVec3("uLift", env.lift[0], env.lift[1], env.lift[2]);
        compositeShader.setVec3("uGain", env.gain[0], env.gain[1], env.gain[2]);
        compositeShader.setFloat("uGamma", env.gamma);
        sceneSource.bind(0);
        if (bloomOn) brightFbo.color.bind(1);
        else GlObjects.Texture2D.white().bind(1);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        stats.drawCalls++;
    }

    // ===================================================================
    // v2.0: IBL bake + upload (SkyLight equivalent)
    // ===================================================================

    private void ensureIBL(){
        if(!cfg.ibl) return;
        String key = env.skyTop[0] + "," + env.skyTop[1] + "," + env.skyTop[2] + "|"
                + env.skyHorizon[0] + "," + env.skyHorizon[1] + "," + env.skyHorizon[2] + "|"
                + env.moonColor[0] + "," + env.moonColor[1] + "," + env.moonColor[2] + "|"
                + env.moonAz + "," + env.moonEl;
        if(key.equals(iblKey) && iblUploaded) return;
        iblKey = key;
        iblUploaded = false;
        try {
            Vec3 moon = new Vec3();
            double az = Math.toRadians(env.moonAz), el = Math.toRadians(env.moonEl);
            moon.set((float)(Math.cos(el) * Math.sin(az)), (float)Math.sin(el), (float)(-Math.cos(el) * Math.cos(az)));
            ibl = new IBLData(new Vec3(env.skyTop[0], env.skyTop[1], env.skyTop[2]),
                              new Vec3(env.skyHorizon[0], env.skyHorizon[1], env.skyHorizon[2]),
                              moon,
                              new Vec3(env.moonColor[0], env.moonColor[1], env.moonColor[2]));
            uploadIbl();
            iblUploaded = true;
        } catch(Throwable t){
            Log.w(TAG, "IBL bake failed: " + t.getMessage());
            iblUploaded = false;
        }
    }

    private void uploadIbl(){
        if(irrTexId == 0){
            int[] ids = new int[3];
            GLES30.glGenTextures(3, ids, 0);
            irrTexId = ids[0]; prefTexId = ids[1]; dfgTexId = ids[2];
        }
        // irradiance cubemap (6 faces RGBA16F)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, irrTexId);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        int n = ibl.irrSize;
        for(int face = 0; face < 6; face++){
            float[] rgba = new float[n * n * 4];
            for(int px = 0; px < n * n; px++){
                rgba[px * 4]     = ibl.irradiance[(face * n * n + px) * 3];
                rgba[px * 4 + 1] = ibl.irradiance[(face * n * n + px) * 3 + 1];
                rgba[px * 4 + 2] = ibl.irradiance[(face * n * n + px) * 3 + 2];
                rgba[px * 4 + 3] = 1f;
            }
            java.nio.FloatBuffer buf = com.hollow.core.NioUtil.floatBuffer(rgba.length);
            buf.put(rgba); buf.position(0);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, GLES30.GL_RGBA16F,
                    n, n, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf);
        }
        // prefiltered specular chain (5 mips)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, prefTexId);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        for(int mip = 0; mip < ibl.preMips; mip++){
            int s = ibl.mipSize(mip);
            for(int face = 0; face < 6; face++){
                float[] rgba = new float[s * s * 4];
                for(int px = 0; px < s * s; px++){
                    int o = ((mip * 6 + face) * ibl.preSize * ibl.preSize + px) * 3;
                    rgba[px * 4]     = ibl.prefiltered[o];
                    rgba[px * 4 + 1] = ibl.prefiltered[o + 1];
                    rgba[px * 4 + 2] = ibl.prefiltered[o + 2];
                    rgba[px * 4 + 3] = 1f;
                }
                java.nio.FloatBuffer buf = com.hollow.core.NioUtil.floatBuffer(rgba.length);
                buf.put(rgba); buf.position(0);
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, mip, GLES30.GL_RGBA16F,
                        s, s, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf);
            }
        }
        // DFG LUT (RGBA16F; xy used)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexId);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        float[] drgba = new float[ibl.dfgSize * ibl.dfgSize * 4];
        for(int px = 0; px < ibl.dfgSize * ibl.dfgSize; px++){
            drgba[px * 4]     = ibl.dfg[px * 2];
            drgba[px * 4 + 1] = ibl.dfg[px * 2 + 1];
            drgba[px * 4 + 2] = 0f;
            drgba[px * 4 + 3] = 1f;
        }
        java.nio.FloatBuffer dbuf = com.hollow.core.NioUtil.floatBuffer(drgba.length);
        dbuf.put(drgba); dbuf.position(0);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, ibl.dfgSize, ibl.dfgSize, 0,
                GLES30.GL_RGBA, GLES30.GL_FLOAT, dbuf);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
    }

    private void disposeIbl(){
        if(irrTexId != 0){
            GLES30.glDeleteTextures(3, new int[]{irrTexId, prefTexId, dfgTexId}, 0);
            irrTexId = prefTexId = dfgTexId = 0;
        }
        iblUploaded = false;
        iblKey = "";
        ibl = null;
    }

    // ===================================================================
    // Overlays (UI / letterbox / fade)
    // ===================================================================

    private void renderUi(EngineServices svc){
        if (svc.ui == null) return;
        UiDrawList dl = uiList;
        dl.reset();
        try {
            svc.ui.buildDrawList(dl, surfaceW, surfaceH);
        } catch (Exception ex){
            Log.e(TAG, "ui.buildDrawList failed", ex);
            return;
        }
        if (dl.quadCount <= 0) return;

        int need = dl.quadCount * 6 * 8;
        if (uiBuf.length < need) uiBuf = new float[Math.max(need, uiBuf.length * 2)];

        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        uiShader.use();
        uiShader.setMat4("uOrtho", uiOrtho);
        uiShader.setInt("uTex", 0);

        // batch consecutive quads sharing a texture key (typical UI runs are long)
        int i = 0;
        while (i < dl.quadCount){
            String key = dl.textures[i];
            int j = i;
            while (j < dl.quadCount && sameKey(dl.textures[j], key)) j++;
            int o = 0;
            for (int q = i; q < j; q++){
                float x = dl.quads[q*10], y = dl.quads[q*10+1];
                float w = dl.quads[q*10+2], h = dl.quads[q*10+3];
                float u0 = dl.quads[q*10+4], v0 = dl.quads[q*10+5];
                float u1 = dl.quads[q*10+6], v1 = dl.quads[q*10+7];
                float r = dl.quads[q*10+8], g = dl.quads[q*10+9];
                float b = dl.quads[q*10+10], a = dl.quads[q*10+11];
                float[] B = uiBuf;
                // tri1: (x,y)(x+w,y)(x,y+h)   tri2: (x+w,y)(x+w,y+h)(x,y+h)
                putUiVertex(B, o,     x,     y,     u0, v0, r, g, b, a);
                putUiVertex(B, o+8,   x+w,   y,     u1, v0, r, g, b, a);
                putUiVertex(B, o+16,  x,     y+h,   u0, v1, r, g, b, a);
                putUiVertex(B, o+24,  x+w,   y,     u1, v0, r, g, b, a);
                putUiVertex(B, o+32,  x+w,   y+h,   u1, v1, r, g, b, a);
                putUiVertex(B, o+40,  x,     y+h,   u0, v1, r, g, b, a);
                o += 48;
            }
            GlObjects.Texture2D tex = getTexture(key);
            tex.bind(0);
            uiVao.bind();
            uiVbo.uploadDynamic(uiBuf, o);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, o / 8);
            stats.drawCalls++;
            i = j;
        }
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    private static boolean sameKey(String a, String b){
        return a == null ? b == null : a.equals(b);
    }

    private static void putUiVertex(float[] b, int o, float x, float y, float u, float v,
                                    float r, float g, float bl, float a){
        b[o]=x; b[o+1]=y; b[o+2]=u; b[o+3]=v; b[o+4]=r; b[o+5]=g; b[o+6]=bl; b[o+7]=a;
    }

    private void renderLetterbox(EngineServices svc){
        float lb = svc.sequencer != null ? svc.sequencer.letterbox : 0f;
        if (lb <= 0.001f) return;
        float barH = Math.max(0f, Math.min(1f, lb)) * 0.12f * surfaceH;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glDisable(GLES30.GL_BLEND);
        drawScreenQuad(0, 0, surfaceW, barH, 0, 0, 0, 1);
        drawScreenQuad(0, surfaceH - barH, surfaceW, barH, 0, 0, 0, 1);
    }

    private void renderFade(){
        float a = GameApp.instance != null ? GameApp.instance.fadeAlpha() : 0f;
        if (a <= 0.001f) return;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        drawScreenQuad(0, 0, surfaceW, surfaceH, 0, 0, 0, Math.min(1f, a));
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    /** one colored 2D quad through the UI shader ("@white" texture path) */
    private void drawScreenQuad(float x, float y, float w, float h, float r, float g, float b, float a){
        float[] B = uiQuadScratch;
        putUiVertex(B, 0,  x, y,     0, 0, r, g, b, a);
        putUiVertex(B, 8,  x+w, y,   1, 0, r, g, b, a);
        putUiVertex(B, 16, x, y+h,   0, 1, r, g, b, a);
        putUiVertex(B, 24, x+w, y,   1, 0, r, g, b, a);
        putUiVertex(B, 32, x+w, y+h, 1, 1, r, g, b, a);
        putUiVertex(B, 40, x, y+h,   0, 1, r, g, b, a);
        uiShader.use();
        uiShader.setMat4("uOrtho", uiOrtho);
        uiShader.setInt("uTex", 0);
        GlObjects.Texture2D.white().bind(0);
        uiVao.bind();
        uiVbo.uploadDynamic(uiQuadScratch, 48);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6);
        stats.drawCalls++;
    }
}
