package tools.tests;

import com.hollow.assets.AnimClip;
import com.hollow.assets.GltfLoader;
import com.hollow.assets.HvmGen;
import com.hollow.assets.MaterialDef;
import com.hollow.assets.MeshData;
import com.hollow.assets.ModelAsset;
import com.hollow.assets.Skeleton;
import com.hollow.core.AABB;
import com.hollow.core.NativeFS;
import com.hollow.core.Quat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Headless test for Agent A: GltfLoader (.gltf + external .bin, .glb, embedded base64)
 * and HvmGen primitive sanity (box bounds, plane normal, sphere radius, outward winding).
 * Plain main() — no JUnit. Prints PASS/FAIL lines; exits 1 on any failure.
 */
public final class TestGltf {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("/tmp", "HollowGltfTest");
        deleteRecursively(dir);
        Files.createDirectories(dir.resolve("textures"));

        byte[] bin = buildBin();
        write(dir, "model.bin", bin);
        write(dir, "model.gltf", buildGltfJson("model.bin", false).getBytes(StandardCharsets.UTF_8));
        write(dir, "multi.glb", buildGlb(buildGltfJson(null, true).getBytes(StandardCharsets.UTF_8), bin));
        write(dir, "embedded.gltf",
                buildGltfJson("data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bin), false)
                        .getBytes(StandardCharsets.UTF_8));

        NativeFS fs = new NativeFS(dir.toString());

        testGltfWithBin(GltfLoader.load("model.gltf", fs));
        testGlbMultiRoot(GltfLoader.load("multi.glb", fs));
        testEmbedded(GltfLoader.load("embedded.gltf", fs));
        testHvmGen();

        System.out.println("RESULT: " + (failed == 0
                ? "PASS (" + passed + " checks)"
                : "FAIL (" + failed + " failed / " + passed + " passed)"));
        if (failed > 0) System.exit(1);
    }

    // =====================================================================================
    // .gltf + external .bin
    // =====================================================================================
    private static void testGltfWithBin(ModelAsset m) {
        check("gltf: 1 mesh", m.meshes.size() == 1);
        check("gltf: 1 primitive", m.meshes.get(0).primitives.size() == 1);
        ModelAsset.Primitive prim = m.meshes.get(0).primitives.get(0);
        MeshData md = prim.mesh;
        check("gltf: vertex count 4", md.vertexCount() == 4, "got " + md.vertexCount());
        check("gltf: indices [0,2,1,2,3,1]", Arrays.equals(md.indices, new int[]{0, 2, 1, 2, 3, 1}),
                Arrays.toString(md.indices));
        check("gltf: normals computed (NORMAL omitted)", md.hasNormals());
        boolean plusY = true;
        for (int v = 0; v < 4; v++) if (Math.abs(md.normals[v * 3 + 1] - 1f) > 1e-4f) plusY = false;
        check("gltf: smooth normals are +Y for flat quad", plusY);
        check("gltf: uvs present", md.hasUVs());
        check("gltf: skinned", md.isSkinned());
        check("gltf: weights v0 normalized 0.25/0.75 (raw 2/6)",
                Math.abs(md.jointWeights[0] - 0.25f) < 1e-4 && Math.abs(md.jointWeights[1] - 0.75f) < 1e-4,
                Arrays.toString(Arrays.copyOf(md.jointWeights, 4)));
        float sum0 = 0;
        for (int k = 0; k < 4; k++) sum0 += md.jointWeights[k];
        check("gltf: v0 weight sum == 1", Math.abs(sum0 - 1f) < 1e-4);
        float sum2 = md.jointWeights[8] + md.jointWeights[9] + md.jointWeights[10] + md.jointWeights[11];
        check("gltf: v2 weight sum == 1", Math.abs(sum2 - 1f) < 1e-4);
        check("gltf: joints v2 = [0,1,0,1]",
                md.jointIndices[8] == 0 && md.jointIndices[9] == 1 && md.jointIndices[10] == 0 && md.jointIndices[11] == 1);
        AABB b = prim.bounds;
        check("gltf: primitive bounds [0..1]x[0..0]x[0..1]",
                Math.abs(b.min.x) < 1e-5 && Math.abs(b.max.x - 1) < 1e-5
                        && Math.abs(b.min.y) < 1e-5 && Math.abs(b.max.y) < 1e-5
                        && Math.abs(b.min.z) < 1e-5 && Math.abs(b.max.z - 1) < 1e-5);

        // material (bufferView byteStride was exercised on POSITION: stride 16)
        check("gltf: 1 material", m.materials.size() == 1);
        MaterialDef mat = m.materials.get(0);
        check("gltf: baseColorFactor", near4(mat.baseColorFactor, new float[]{0.2f, 0.4f, 0.6f, 1f}, 1e-4f),
                Arrays.toString(mat.baseColorFactor));
        check("gltf: metallicFactor 0.25", Math.abs(mat.metallicFactor - 0.25f) < 1e-6);
        check("gltf: roughnessFactor 0.75", Math.abs(mat.roughnessFactor - 0.75f) < 1e-6);
        check("gltf: alphaMode MASK", mat.alphaMode == MaterialDef.AlphaMode.MASK);
        check("gltf: alphaCutoff 0.4", Math.abs(mat.alphaCutoff - 0.4f) < 1e-6);
        check("gltf: doubleSided", mat.doubleSided);
        check("gltf: normalScale 1.5", Math.abs(mat.normalScale - 1.5f) < 1e-6);
        check("gltf: emissiveFactor", near3(mat.emissiveFactor, new float[]{0.1f, 0.2f, 0.3f}, 1e-5f));
        check("gltf: baseColorTexture -> textures/tex.png", "textures/tex.png".equals(mat.baseColorTexture),
                String.valueOf(mat.baseColorTexture));
        check("gltf: normalTexture -> textures/norm.png", "textures/norm.png".equals(mat.normalTexture));
        check("gltf: emissiveTexture null (data: image stays null)", mat.emissiveTexture == null);

        // skin
        check("gltf: 1 skin", m.skins.size() == 1);
        Skeleton sk = m.skins.get(0);
        check("gltf: skin joint count 2", sk.jointCount() == 2);
        check("gltf: joint names", "j0".equals(sk.joints.get(0).name) && "j1".equals(sk.joints.get(1).name));
        float[] ib0 = sk.joints.get(0).inverseBind;
        check("gltf: IBM0 identity", ib0[0] == 1 && ib0[5] == 1 && ib0[10] == 1 && ib0[15] == 1
                && ib0[12] == 0 && ib0[13] == 0 && ib0[14] == 0);
        check("gltf: IBM1 translation(-1,0,0) col-major", sk.joints.get(1).inverseBind[12] == -1f
                && sk.joints.get(1).inverseBind[0] == 1f && sk.joints.get(1).inverseBind[15] == 1f);
        check("gltf: joint0 restPos (0,1,0)", sk.joints.get(0).restPos.y == 1f
                && sk.joints.get(0).restPos.x == 0f && sk.joints.get(0).restPos.z == 0f);
        check("gltf: joint1 restPos (0,1,0)", sk.joints.get(1).restPos.y == 1f);
        check("gltf: joint0 parent -1, joint1 parent 0", sk.joints.get(0).parent == -1 && sk.joints.get(1).parent == 0);
        check("gltf: mesh skeletonIndex 0", m.meshes.get(0).skeletonIndex == 0);

        // scene
        check("gltf: single root -> sceneRoot 0", m.sceneRoot == 0);

        // animation
        AnimClip clip = m.clips.get("wave");
        check("gltf: clip 'wave' exists", clip != null);
        if (clip != null) {
            check("gltf: duration == 1.0", Math.abs(clip.duration - 1f) < 1e-3, "got " + clip.duration);
            check("gltf: loop == true", clip.loop);
            check("gltf: 2 channels (joint0 rot, joint1 trans)", clip.channels.size() == 2
                    && clip.channels.containsKey(0) && clip.channels.containsKey(1));
            AnimClip.Channel rot = clip.channels.get(0);
            check("gltf: rotation channel LINEAR with 2 keys", rot != null && rot.rotations != null
                    && rot.times.length == 2 && rot.interpolation == AnimClip.INTERP_LINEAR);
            Quat q = new Quat();
            clip.sampleRotation(rot, 0f, q);
            check("gltf: rot(t=0) identity", q.x == 0 && q.y == 0 && q.z == 0 && Math.abs(q.w - 1) < 1e-5,
                    q.toString());
            clip.sampleRotation(rot, clip.duration, q);
            check("gltf: rot(t=duration) 90 deg yaw", Math.abs(q.x) < 1e-4 && Math.abs(q.y) < 1e-4
                            && Math.abs(q.z - 0.70710677f) < 1e-3 && Math.abs(q.w - 0.70710677f) < 1e-3,
                    "q=" + q.x + "," + q.y + "," + q.z + "," + q.w);
            float[] tv = new float[3];
            clip.sampleTranslation(clip.channels.get(1), 0.5f, tv);
            check("gltf: trans(t=0.5) = (0,0.25,0)", Math.abs(tv[1] - 0.25f) < 1e-4 && tv[0] == 0 && tv[2] == 0,
                    Arrays.toString(tv));
        }
    }

    // =====================================================================================
    // .glb multi-root -> synthetic root
    // =====================================================================================
    private static void testGlbMultiRoot(ModelAsset g) {
        check("glb: loads, 1 mesh / 4 verts", g.meshes.size() == 1
                && g.meshes.get(0).primitives.size() == 1 && g.meshes.get(0).primitives.get(0).mesh.vertexCount() == 4);
        check("glb: 2 scene roots -> synthetic root appended", g.nodes.size() == 5 && g.sceneRoot == 4,
                "nodes=" + g.nodes.size() + " sceneRoot=" + g.sceneRoot);
        check("glb: synthetic root has 2 children", g.nodes.get(4).children.size() == 2);
        check("glb: old roots reparented to synthetic", g.nodes.get(0).parent == 4 && g.nodes.get(1).parent == 4);
        check("glb: synthetic root identity transform", g.nodes.get(4).translation.x == 0
                && g.nodes.get(4).scale.y == 1 && g.nodes.get(4).rotation.w == 1);
        check("glb: skin joints 2", g.skins.size() == 1 && g.skins.get(0).jointCount() == 2);
        check("glb: clip 'wave' duration 1", g.clips.containsKey("wave")
                && Math.abs(g.clips.get("wave").duration - 1f) < 1e-3);
        check("glb: material factors carried", Math.abs(g.materials.get(0).metallicFactor - 0.25f) < 1e-6);
    }

    // =====================================================================================
    // .gltf with embedded base64 buffer
    // =====================================================================================
    private static void testEmbedded(ModelAsset e) {
        check("embedded: loads, 4 verts", e.meshes.size() == 1
                && e.meshes.get(0).primitives.get(0).mesh.vertexCount() == 4);
        check("embedded: indices ok", Arrays.equals(e.meshes.get(0).primitives.get(0).mesh.indices,
                new int[]{0, 2, 1, 2, 3, 1}));
        check("embedded: file-uri texture still resolved", "textures/tex.png".equals(e.materials.get(0).baseColorTexture));
        check("embedded: clip ok", e.clips.containsKey("wave") && Math.abs(e.clips.get("wave").duration - 1f) < 1e-3);
    }

    // =====================================================================================
    // HvmGen sanity: box bounds, plane normal, sphere radius, outward winding
    // =====================================================================================
    private static void testHvmGen() {
        MeshData box = HvmGen.box(2, 4, 6);
        AABB bb = box.bounds();
        check("gen: box bounds == [±w/2,±h/2,±d/2]",
                Math.abs(bb.min.x + 1) < 1e-5 && Math.abs(bb.max.x - 1) < 1e-5
                        && Math.abs(bb.min.y + 2) < 1e-5 && Math.abs(bb.max.y - 2) < 1e-5
                        && Math.abs(bb.min.z + 3) < 1e-5 && Math.abs(bb.max.z - 3) < 1e-5);
        check("gen: box outward winding", outwardOriented(box));

        MeshData plane = HvmGen.plane(4, 2);
        boolean planeY = true;
        for (int i = 1; i < plane.normals.length; i += 3)
            if (Math.abs(plane.normals[i] - 1f) > 1e-6f || Math.abs(plane.normals[i - 1]) > 1e-6f
                    || Math.abs(plane.normals[i + 1]) > 1e-6f) planeY = false;
        check("gen: plane normals == +Y", planeY);
        AABB pb = plane.bounds();
        check("gen: plane centered", Math.abs(pb.min.x + 2) < 1e-5 && Math.abs(pb.max.x - 2) < 1e-5);

        MeshData sph = HvmGen.sphere(2, 12, 24);
        boolean radiusOk = true;
        for (int i = 0; i < sph.positions.length; i += 3) {
            float l = (float) Math.sqrt(sph.positions[i] * sph.positions[i]
                    + sph.positions[i + 1] * sph.positions[i + 1] + sph.positions[i + 2] * sph.positions[i + 2]);
            if (Math.abs(l - 2f) > 1e-3f) { radiusOk = false; break; }
        }
        check("gen: sphere radius preserved", radiusOk);
        check("gen: sphere outward winding", outwardOriented(sph));

        MeshData cap = HvmGen.capsule(0.5f, 2f, 8);
        check("gen: capsule bounds y == ±1", Math.abs(cap.bounds().min.y + 1) < 1e-4
                && Math.abs(cap.bounds().max.y - 1) < 1e-4);
        check("gen: capsule outward winding", outwardOriented(cap));

        MeshData cyl = HvmGen.cylinder(1f, 3f, 12);
        check("gen: cylinder bounds y == ±1.5", Math.abs(cyl.bounds().min.y + 1.5f) < 1e-4
                && Math.abs(cyl.bounds().max.y - 1.5f) < 1e-4);
        check("gen: cylinder outward winding", outwardOriented(cyl));
        check("gen: cylinder indices in range", indicesInRange(cyl));
    }

    /** every non-degenerate triangle's geometric normal points away from origin (centered shapes) */
    private static boolean outwardOriented(MeshData m) {
        int[] idx = m.indices;
        for (int t = 0; t + 2 < idx.length; t += 3) {
            int a = idx[t], b = idx[t + 1], c = idx[t + 2];
            float ax = m.positions[a * 3], ay = m.positions[a * 3 + 1], az = m.positions[a * 3 + 2];
            float e1x = m.positions[b * 3] - ax, e1y = m.positions[b * 3 + 1] - ay, e1z = m.positions[b * 3 + 2] - az;
            float e2x = m.positions[c * 3] - ax, e2y = m.positions[c * 3 + 1] - ay, e2z = m.positions[c * 3 + 2] - az;
            float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
            float l2 = nx * nx + ny * ny + nz * nz;
            if (l2 < 1e-14f) continue; // degenerate
            float cx = (ax + m.positions[b * 3] + m.positions[c * 3]) / 3f;
            float cy = (ay + m.positions[b * 3 + 1] + m.positions[c * 3 + 1]) / 3f;
            float cz = (az + m.positions[b * 3 + 2] + m.positions[c * 3 + 2]) / 3f;
            if (nx * cx + ny * cy + nz * cz <= 0) return false;
        }
        return true;
    }

    private static boolean indicesInRange(MeshData m) {
        for (int i : m.indices) if (i < 0 || i >= m.vertexCount()) return false;
        return true;
    }

    // =====================================================================================
    // synthetic glTF asset builders
    // =====================================================================================
    private static final int BIN_LEN = 392;

    /** little-endian buffer: positions(stride16) | uv | joints(ubyte) | weights | idx(uint) | IBM | times | rots | trans */
    private static byte[] buildBin() {
        ByteBuffer b = ByteBuffer.allocate(BIN_LEN).order(ByteOrder.LITTLE_ENDIAN);
        float[][] pos = {{0, 0, 0}, {1, 0, 0}, {0, 0, 1}, {1, 0, 1}};
        for (float[] p : pos) { b.putFloat(p[0]); b.putFloat(p[1]); b.putFloat(p[2]); b.putFloat(0f); } // 64B, stride 16
        float[][] uv = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
        for (float[] t : uv) { b.putFloat(t[0]); b.putFloat(t[1]); }                                    // @64 32B
        byte[][] joints = {{0, 1, 0, 0}, {0, 0, 0, 0}, {0, 1, 0, 1}, {0, 1, 0, 0}};
        for (byte[] j : joints) for (byte x : j) b.put(x);                                             // @96 16B
        float[][] weights = {{2, 6, 0, 0}, {1, 0, 0, 0}, {1, 1, 1, 1}, {0, 0, 1, 1}};
        for (float[] w : weights) for (float x : w) b.putFloat(x);                                     // @112 64B
        for (int i : new int[]{0, 2, 1, 2, 3, 1}) b.putInt(i);                                         // @176 24B
        float[] ibm0 = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};                               // column-major
        for (float x : ibm0) b.putFloat(x);                                                            // @200 64B
        float[] ibm1 = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -1, 0, 0, 1};
        for (float x : ibm1) b.putFloat(x);                                                            // @264 64B
        b.putFloat(0f); b.putFloat(1f);                                                                // @328 times
        b.putFloat(0f); b.putFloat(0f); b.putFloat(0f); b.putFloat(1f);                                // @336 rot k0
        b.putFloat(0f); b.putFloat(0f); b.putFloat(0.70710677f); b.putFloat(0.70710677f);              // @352 rot k1
        b.putFloat(0f); b.putFloat(0f); b.putFloat(0f);                                                // @368 trans k0
        b.putFloat(0f); b.putFloat(0.5f); b.putFloat(0f);                                              // @380 trans k1
        if (b.position() != BIN_LEN) throw new IllegalStateException("bin size " + b.position());
        return b.array();
    }

    private static Map<String, Object> acc(int bv, int ct, int count, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bufferView", bv);
        m.put("componentType", ct);
        m.put("count", count);
        m.put("type", type);
        return m;
    }

    /** @param bufferUri null for GLB (buffer 0 = BIN chunk) */
    private static String buildGltfJson(String bufferUri, boolean multiRoot) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("version", "2.0");
        asset.put("generator", "TestGltf");
        root.put("asset", asset);

        Map<String, Object> buffer = new LinkedHashMap<>();
        buffer.put("byteLength", BIN_LEN);
        if (bufferUri != null) buffer.put("uri", bufferUri);
        root.put("buffers", List.of(buffer));

        List<Object> views = new ArrayList<>();
        views.add(Map.of("buffer", 0, "byteOffset", 0, "byteLength", 64, "byteStride", 16)); // POSITION strided
        views.add(Map.of("buffer", 0, "byteOffset", 64, "byteLength", 32));
        views.add(Map.of("buffer", 0, "byteOffset", 96, "byteLength", 16));
        views.add(Map.of("buffer", 0, "byteOffset", 112, "byteLength", 64));
        views.add(Map.of("buffer", 0, "byteOffset", 176, "byteLength", 24));
        views.add(Map.of("buffer", 0, "byteOffset", 200, "byteLength", 128));
        views.add(Map.of("buffer", 0, "byteOffset", 328, "byteLength", 8));
        views.add(Map.of("buffer", 0, "byteOffset", 336, "byteLength", 32));
        views.add(Map.of("buffer", 0, "byteOffset", 368, "byteLength", 24));
        root.put("bufferViews", views);

        List<Object> accessors = new ArrayList<>();
        accessors.add(acc(0, 5126, 4, "VEC3"));   // 0 POSITION
        accessors.add(acc(1, 5126, 4, "VEC2"));   // 1 TEXCOORD_0
        accessors.add(acc(2, 5121, 4, "VEC4"));   // 2 JOINTS_0 (ubyte)
        accessors.add(acc(3, 5126, 4, "VEC4"));   // 3 WEIGHTS_0
        accessors.add(acc(4, 5125, 6, "SCALAR")); // 4 indices (uint)
        accessors.add(acc(5, 5126, 2, "MAT4"));   // 5 inverseBindMatrices
        accessors.add(acc(6, 5126, 2, "SCALAR")); // 6 anim times
        accessors.add(acc(7, 5126, 2, "VEC4"));   // 7 anim rotations
        accessors.add(acc(8, 5126, 2, "VEC3"));   // 8 anim translations
        root.put("accessors", accessors);

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("POSITION", 0);
        attrs.put("TEXCOORD_0", 1);
        attrs.put("JOINTS_0", 2);
        attrs.put("WEIGHTS_0", 3);
        Map<String, Object> primitive = new LinkedHashMap<>();
        primitive.put("attributes", attrs);
        primitive.put("indices", 4);
        primitive.put("material", 0);
        Map<String, Object> mesh = new LinkedHashMap<>();
        mesh.put("name", "quad");
        mesh.put("primitives", List.of(primitive));
        root.put("meshes", List.of(mesh));

        Map<String, Object> pbr = new LinkedHashMap<>();
        pbr.put("baseColorFactor", List.of(0.2, 0.4, 0.6, 1.0));
        pbr.put("baseColorTexture", Map.of("index", 0));
        pbr.put("metallicFactor", 0.25);
        pbr.put("roughnessFactor", 0.75);
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("name", "mat0");
        material.put("pbrMetallicRoughness", pbr);
        material.put("normalTexture", Map.of("index", 1, "scale", 1.5));
        material.put("emissiveTexture", Map.of("index", 2));
        material.put("emissiveFactor", List.of(0.1, 0.2, 0.3));
        material.put("alphaMode", "MASK");
        material.put("alphaCutoff", 0.4);
        material.put("doubleSided", true);
        root.put("materials", List.of(material));

        root.put("textures", List.of(Map.of("source", 0), Map.of("source", 1), Map.of("source", 2)));
        root.put("images", List.of(
                Map.of("uri", "textures/tex.png"),
                Map.of("uri", "textures/norm.png"),
                Map.of("uri", "data:image/png;base64,iVBORw0KGgo=")));

        // nodes
        List<Object> nodes = new ArrayList<>();
        if (!multiRoot) {
            nodes.add(Map.of("name", "body", "mesh", 0, "skin", 0, "children", List.of(1)));
            nodes.add(Map.of("name", "j0", "translation", List.of(0, 1, 0), "children", List.of(2)));
            nodes.add(Map.of("name", "j1", "translation", List.of(0, 1, 0)));
        } else {
            nodes.add(Map.of("name", "body", "mesh", 0, "skin", 0, "children", List.of(2)));
            nodes.add(Map.of("name", "extra", "mesh", 0));
            nodes.add(Map.of("name", "j0", "translation", List.of(0, 1, 0), "children", List.of(3)));
            nodes.add(Map.of("name", "j1", "translation", List.of(0, 1, 0)));
        }
        root.put("nodes", nodes);

        root.put("skins", List.of(Map.of("name", "arm", "joints",
                multiRoot ? List.of(2, 3) : List.of(1, 2), "inverseBindMatrices", 5)));

        int rotNode = multiRoot ? 2 : 1, transNode = multiRoot ? 3 : 2;
        Map<String, Object> anim = new LinkedHashMap<>();
        anim.put("name", "wave");
        anim.put("samplers", List.of(
                Map.of("input", 6, "output", 7, "interpolation", "LINEAR"),
                Map.of("input", 6, "output", 8, "interpolation", "LINEAR")));
        anim.put("channels", List.of(
                Map.of("sampler", 0, "target", Map.of("node", rotNode, "path", "rotation")),
                Map.of("sampler", 1, "target", Map.of("node", transNode, "path", "translation"))));
        root.put("animations", List.of(anim));

        if (multiRoot) {
            root.put("scenes", List.of(Map.of("nodes", List.of(0, 1))));
        } else {
            root.put("scenes", List.of(Map.of("nodes", List.of(0))));
        }
        root.put("scene", 0);
        return com.hollow.core.Json.write(root);
    }

    private static byte[] buildGlb(byte[] json, byte[] bin) {
        byte[] jsonPadded = pad(json, 4, (byte) 0x20); // JSON chunk padded with spaces
        byte[] binPadded = pad(bin, 4, (byte) 0x00);
        int total = 12 + 8 + jsonPadded.length + 8 + binPadded.length;
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546C67);      // magic 'glTF'
        b.putInt(2);               // version
        b.putInt(total);           // total length
        b.putInt(jsonPadded.length);
        b.putInt(0x4E4F534A);      // 'JSON'
        b.put(jsonPadded);
        b.putInt(binPadded.length);
        b.putInt(0x004E4942);      // 'BIN\0'
        b.put(binPadded);
        return b.array();
    }

    private static byte[] pad(byte[] data, int alignment, byte filler) {
        int rem = data.length % alignment;
        if (rem == 0) return data;
        byte[] out = Arrays.copyOf(data, data.length + alignment - rem);
        Arrays.fill(out, data.length, out.length, filler);
        return out;
    }

    // =====================================================================================
    // helpers
    // =====================================================================================
    private static void write(Path dir, String name, byte[] data) throws Exception {
        Files.write(dir.resolve(name), data);
    }

    private static void deleteRecursively(Path p) throws Exception {
        if (!Files.exists(p)) return;
        Files.walk(p).sorted(Comparator.reverseOrder()).forEach(path -> {
            try { Files.delete(path); } catch (Exception ignored) {}
        });
    }

    private static boolean near3(float[] a, float[] b, float eps) {
        return Math.abs(a[0] - b[0]) < eps && Math.abs(a[1] - b[1]) < eps && Math.abs(a[2] - b[2]) < eps;
    }

    private static boolean near4(float[] a, float[] b, float eps) {
        return near3(a, b, eps) && Math.abs(a[3] - b[3]) < eps;
    }

    private static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS: " + name);
        } else {
            failed++;
            System.out.println("FAIL: " + name + (detail.isEmpty() ? "" : "  -- " + detail));
        }
    }

    private TestGltf() {}
}
