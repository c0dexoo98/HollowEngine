package com.hollow.assets;

import com.hollow.core.AABB;
import com.hollow.core.FileSystem;
import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.Quat;
import com.hollow.core.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * glTF 2.0 loader (docs/SPEC.md §Agent A).
 *
 * Supported:
 *  - .gltf JSON with external .bin URIs (resolved relative to the gltf file's directory,
 *    percent-escapes decoded) and embedded "data:application/octet-stream;base64,..." URIs.
 *  - .glb binary container (12-byte header, magic 0x46546C67, JSON chunk + optional BIN chunk).
 *  - Accessors: FLOAT(5126) / UNSIGNED_INT(5125) / UNSIGNED_SHORT(5123) / UNSIGNED_BYTE(5121)
 *    (plus signed BYTE/SHORT for robustness), "normalized" flag, bufferView byteStride,
 *    types SCALAR / VEC2 / VEC3 / VEC4 / MAT4.
 *  - Meshes: POSITION (required), NORMAL (smooth area-weighted normals computed when missing),
 *    TEXCOORD_0, JOINTS_0 + WEIGHTS_0 (per-vertex weights normalized to sum 1), indices (required,
 *    TRIANGLES mode only).
 *  - Materials: pbrMetallicRoughness (baseColorFactor / baseColorTexture / metallicFactor /
 *    roughnessFactor), normalTexture (+scale), emissiveTexture / emissiveFactor, alphaMode
 *    OPAQUE/MASK/BLEND (+alphaCutoff), doubleSided, KHR_materials_unlit -> unlit flag.
 *    Texture references: file-uri images become "&lt;gltfDir&gt;/&lt;uri&gt;" paths in MaterialDef;
 *    data:/bufferView images leave the texture field null (renderer falls back to factors).
 *    Pixels are NEVER decoded here (desktop + android safe).
 *  - Nodes: TRS or column-major "matrix" (decomposed), children, mesh + skin. One synthetic root
 *    is appended when the scene has multiple roots; ModelAsset.sceneRoot points at it (or at the
 *    single root, or -1 for an empty scene).
 *  - Skins: joints[] (list order = joint index), inverseBindMatrices (16 column-major floats per
 *    joint), rest TRS copied from the joint node.
 *  - Animations: samplers LINEAR / STEP / CUBICSPLINE on node translation/rotation/scale.
 *    CUBICSPLINE is baked to LINEAR by resampling at 30 Hz over [0, maxKeyTime of the clip].
 *    Node indices are mapped to joint indices via skin.joints (first skin wins on duplicates);
 *    channels on non-joint nodes are ignored. clip.duration = max key time, loop = true.
 *
 * Y-up / meters: glTF is already Y-up, no axis conversion. All parsing is index/array based —
 * no HashMap iteration order dependence. O(vertices) — fine for 100k-vertex models.
 */
public final class GltfLoader {
    private GltfLoader() {}

    private static final String TAG = "GltfLoader";

    // ---- glTF component types ----
    private static final int CT_BYTE = 5120, CT_UBYTE = 5121, CT_SHORT = 5122,
            CT_USHORT = 5123, CT_UINT = 5125, CT_FLOAT = 5126;

    // ---- GLB container ----
    private static final int GLB_MAGIC = 0x46546C67;   // 'g','l','T','F'
    private static final int CHUNK_JSON = 0x4E4F534A;  // 'J','S','O','N'
    private static final int CHUNK_BIN  = 0x004E4942;  // 'B','I','N','\0'

    /** Load a .gltf/.glb model. Throws IOException on structurally invalid files. */
    public static ModelAsset load(String path, FileSystem fs) throws IOException {
        byte[] raw = fs.read(path);
        if (raw.length < 20) throw new IOException("glTF file too small: " + path);

        byte[] jsonBytes;
        byte[] binChunk = null;
        boolean isGlb = raw[0] == 'g' && raw[1] == 'l' && raw[2] == 'T' && raw[3] == 'F';
        if (isGlb) {
            long version = u32le(raw, 4);
            if (version != 2) throw new IOException("unsupported GLB version " + version + ": " + path);
            jsonBytes = null;
            int off = 12;
            while (off + 8 <= raw.length) {
                long clen = u32le(raw, off);
                long ctype = u32le(raw, off + 4);
                int dataOff = off + 8;
                if (dataOff >= raw.length) break;
                int len = (int) Math.min(clen, raw.length - (long) dataOff);
                if (ctype == CHUNK_JSON && jsonBytes == null) {
                    jsonBytes = Arrays.copyOfRange(raw, dataOff, dataOff + len);
                } else if (ctype == CHUNK_BIN && binChunk == null) {
                    binChunk = Arrays.copyOfRange(raw, dataOff, dataOff + len);
                }
                off = dataOff + len;
            }
            if (jsonBytes == null) throw new IOException("GLB without JSON chunk: " + path);
        } else {
            int start = (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF)
                    ? 3 : 0; // strip UTF-8 BOM
            jsonBytes = Arrays.copyOfRange(raw, start, raw.length);
        }

        String text = new String(jsonBytes, StandardCharsets.UTF_8);
        Object parsed = Json.parse(text);
        if (!(parsed instanceof Map)) throw new IOException("glTF root is not a JSON object: " + path);
        @SuppressWarnings("unchecked") Map<String, Object> root = (Map<String, Object>) parsed;
        return new Ctx(path, fs, root, binChunk).read();
    }

    // =========================================================================================
    // loading context
    // =========================================================================================
    private static final class Ctx {
        final String path;
        final String dir;       // directory of the .gltf file ("" when at fs root)
        final FileSystem fs;
        final Map<String, Object> root;
        final byte[] binChunk;

        byte[][] buffers;
        BV[] views;
        AC[] accessors;
        String[] imageFileUri;  // per image: file uri (percent-decoding happens on resolve) or null
        int[] texSource;        // per texture: image index (-1 when absent)
        List<int[]> skinJointNodes = new ArrayList<>(); // per skin: node id per joint index

        Ctx(String path, FileSystem fs, Map<String, Object> root, byte[] binChunk) {
            this.path = path;
            this.fs = fs;
            this.root = root;
            this.binChunk = binChunk;
            int slash = path.lastIndexOf('/');
            this.dir = slash < 0 ? "" : path.substring(0, slash);
        }

        // ---------------- small JSON helpers ----------------
        static List<Object> arr(Map<String, Object> m, String key) {
            List<Object> l = Json.arr(m.get(key));
            return l != null ? l : new ArrayList<>(0);
        }
        static Map<String, Object> obj(Object o) { return Json.obj(o); }
        static List<Float> farr(Object o, int n, float def) {
            List<Float> out = new ArrayList<>(n);
            List<Object> l = Json.arr(o);
            for (int i = 0; i < n; i++) out.add((float) Json.num(l != null && i < l.size() ? l.get(i) : null, def));
            return out;
        }

        ModelAsset read() throws IOException {
            ModelAsset model = new ModelAsset();
            model.sourcePath = path;
            loadBuffers();
            loadBufferViews();
            loadAccessors();
            loadImagesTextures();
            loadMaterials(model);
            loadMeshes(model);
            loadNodes(model);
            buildScene(model);
            loadSkins(model);
            wireMeshSkeletons(model);
            loadAnimations(model);
            Log.i(TAG, "loaded " + path + ": " + model.meshes.size() + " meshes, "
                    + model.nodes.size() + " nodes, " + model.skins.size() + " skins, "
                    + model.clips.size() + " clips");
            return model;
        }

        // ---------------- buffers / views / accessors ----------------
        private void loadBuffers() throws IOException {
            List<Object> defs = arr(root, "buffers");
            buffers = new byte[defs.size()][];
            for (int i = 0; i < defs.size(); i++) {
                Map<String, Object> b = obj(defs.get(i));
                if (b == null) throw new IOException("buffer " + i + " is not an object: " + path);
                String uri = Json.str(b.get("uri"), null);
                if (uri == null) {
                    if (binChunk == null)
                        throw new IOException("buffer " + i + " has no uri and file has no GLB BIN chunk: " + path);
                    buffers[i] = binChunk;
                } else if (uri.startsWith("data:")) {
                    int marker = uri.indexOf("base64,");
                    if (marker < 0) throw new IOException("buffer " + i + ": unsupported data uri: " + path);
                    buffers[i] = Base64.getMimeDecoder().decode(uri.substring(marker + 7));
                } else {
                    buffers[i] = fs.read(resolvePath(uri));
                }
            }
        }

        private void loadBufferViews() {
            List<Object> defs = arr(root, "bufferViews");
            views = new BV[defs.size()];
            for (int i = 0; i < defs.size(); i++) {
                Map<String, Object> v = obj(defs.get(i));
                BV bv = new BV();
                if (v != null) {
                    bv.buffer = Json.int32(v.get("buffer"), -1);
                    bv.offset = Json.int32(v.get("byteOffset"), 0);
                    bv.length = Json.int32(v.get("byteLength"), 0);
                    bv.stride = Json.int32(v.get("byteStride"), 0);
                }
                views[i] = bv;
            }
        }

        private void loadAccessors() {
            List<Object> defs = arr(root, "accessors");
            accessors = new AC[defs.size()];
            for (int i = 0; i < defs.size(); i++) {
                Map<String, Object> a = obj(defs.get(i));
                AC ac = new AC();
                if (a != null) {
                    ac.view = Json.int32(a.get("bufferView"), -1);
                    ac.offset = Json.int32(a.get("byteOffset"), 0);
                    ac.count = Json.int32(a.get("count"), 0);
                    ac.compType = Json.int32(a.get("componentType"), CT_FLOAT);
                    ac.type = Json.str(a.get("type"), "SCALAR");
                }
                ac.comps = numComponents(ac.type);
                accessors[i] = ac;
            }
        }

        private void loadImagesTextures() {
            List<Object> imgs = arr(root, "images");
            imageFileUri = new String[imgs.size()];
            for (int i = 0; i < imgs.size(); i++) {
                Map<String, Object> im = obj(imgs.get(i));
                String uri = im == null ? null : Json.str(im.get("uri"), null);
                // only separate files are referenced; data:/bufferView images stay null
                imageFileUri[i] = (uri != null && !uri.startsWith("data:")) ? uri : null;
            }
            List<Object> texs = arr(root, "textures");
            texSource = new int[texs.size()];
            for (int i = 0; i < texs.size(); i++) {
                Map<String, Object> t = obj(texs.get(i));
                texSource[i] = t == null ? -1 : Json.int32(t.get("source"), -1);
            }
        }

        /** Resolve a texture index to a project-relative image path, or null when embedded. */
        private String texturePath(int texIndex) {
            if (texIndex < 0 || texIndex >= texSource.length) return null;
            int src = texSource[texIndex];
            if (src < 0 || src >= imageFileUri.length || imageFileUri[src] == null) return null;
            return resolvePath(imageFileUri[src]);
        }

        private String resolvePath(String uri) {
            String u = decodeUri(uri.trim());
            if (u.startsWith("file://")) u = u.substring("file://".length());
            while (u.startsWith("./")) u = u.substring(2);
            return dir.isEmpty() ? u : dir + "/" + u;
        }

        // ---------------- materials ----------------
        private void loadMaterials(ModelAsset model) {
            List<Object> defs = arr(root, "materials");
            for (int i = 0; i < defs.size(); i++) {
                Map<String, Object> m = obj(defs.get(i));
                MaterialDef d = new MaterialDef();
                d.name = m == null ? "material_" + i : Json.str(m.get("name"), "material_" + i);
                if (m != null) {
                    Map<String, Object> pbr = obj(m.get("pbrMetallicRoughness"));
                    if (pbr != null) {
                        List<Object> bcf = Json.arr(pbr.get("baseColorFactor"));
                        if (bcf != null && !bcf.isEmpty()) {
                            for (int k = 0; k < 4; k++)
                                d.baseColorFactor[k] = (float) Json.num(bcf.get(Math.min(k, bcf.size() - 1)), 1);
                        }
                        d.baseColorTexture = texturePath(texIndexOf(pbr.get("baseColorTexture")));
                        d.metallicFactor = (float) Json.num(pbr.get("metallicFactor"), 1.0);
                        d.roughnessFactor = (float) Json.num(pbr.get("roughnessFactor"), 1.0);
                    }
                    Map<String, Object> nt = obj(m.get("normalTexture"));
                    if (nt != null) {
                        d.normalTexture = texturePath(Json.int32(nt.get("index"), -1));
                        d.normalScale = (float) Json.num(nt.get("scale"), 1.0);
                    }
                    Map<String, Object> et = obj(m.get("emissiveTexture"));
                    if (et != null) d.emissiveTexture = texturePath(texIndexOf(et.get("index")));
                    List<Float> ef = farr(m.get("emissiveFactor"), 3, 0);
                    for (int k = 0; k < 3; k++) d.emissiveFactor[k] = ef.get(k);
                    String am = Json.str(m.get("alphaMode"), "OPAQUE");
                    try { d.alphaMode = MaterialDef.AlphaMode.valueOf(am.toUpperCase()); }
                    catch (IllegalArgumentException ignored) { d.alphaMode = MaterialDef.AlphaMode.OPAQUE; }
                    d.alphaCutoff = (float) Json.num(m.get("alphaCutoff"), 0.5);
                    d.doubleSided = Json.bool(m.get("doubleSided"), false);
                    Map<String, Object> ext = obj(m.get("extensions"));
                    if (ext != null && ext.containsKey("KHR_materials_unlit")) d.unlit = true;
                }
                model.materials.add(d);
            }
            if (model.materials.isEmpty()) {
                // guarantee material index 0 exists (glTF "default material": metal 1, rough 1)
                MaterialDef d = new MaterialDef();
                d.name = "default";
                d.metallicFactor = 1f;
                d.roughnessFactor = 1f;
                model.materials.add(d);
            }
        }

        static int texIndexOf(Object textureObj) {
            Map<String, Object> t = obj(textureObj);
            return t == null ? -1 : Json.int32(t.get("index"), -1);
        }

        // ---------------- meshes ----------------
        private void loadMeshes(ModelAsset model) throws IOException {
            List<Object> defs = arr(root, "meshes");
            for (int mi = 0; mi < defs.size(); mi++) {
                Map<String, Object> mm = obj(defs.get(mi));
                ModelAsset.MeshAsset ma = new ModelAsset.MeshAsset();
                ma.name = mm == null ? "mesh_" + mi : Json.str(mm.get("name"), "mesh_" + mi);
                if (mm != null) {
                    for (Object po : arr(mm, "primitives")) {
                        Map<String, Object> pm = obj(po);
                        if (pm == null) continue;
                        int mode = Json.int32(pm.get("mode"), 4);
                        if (mode != 4) {
                            Log.w(TAG, "mesh '" + ma.name + "': skipping primitive with unsupported mode " + mode
                                    + " (only TRIANGLES=4)");
                            continue;
                        }
                        ModelAsset.Primitive prim = loadPrimitive(ma.name, pm);
                        if (prim != null) ma.primitives.add(prim);
                    }
                }
                model.meshes.add(ma);
            }
        }

        private ModelAsset.Primitive loadPrimitive(String meshName, Map<String, Object> pm) throws IOException {
            Map<String, Object> attrs = obj(pm.get("attributes"));
            int posAcc = attrs == null ? -1 : Json.int32(attrs.get("POSITION"), -1);
            if (posAcc < 0 || posAcc >= accessors.length) {
                Log.w(TAG, "mesh '" + meshName + "': primitive without POSITION skipped");
                return null;
            }
            int idxAcc = Json.int32(pm.get("indices"), -1);
            if (idxAcc < 0 || idxAcc >= accessors.length) {
                Log.w(TAG, "mesh '" + meshName + "': primitive without indices skipped (indices required)");
                return null;
            }
            float[] pos = accFloats(posAcc);
            int vc = accessors[posAcc].count;

            // indices
            int[] indices = accInts(idxAcc);
            for (int i : indices) {
                if (i < 0 || i >= vc) throw new IOException("index out of range (" + i + " >= " + vc + "): " + path);
            }

            // normals: use accessor or compute smooth area-weighted normals
            float[] nrm = null;
            int nAcc = attrs.containsKey("NORMAL") ? Json.int32(attrs.get("NORMAL"), -1) : -1;
            if (nAcc >= 0 && nAcc < accessors.length) {
                try {
                    float[] raw_n = accFloats(nAcc);
                    if (raw_n.length == vc * 3) nrm = raw_n;
                } catch (IOException e) {
                    Log.w(TAG, "mesh '" + meshName + "': unreadable NORMAL accessor, computing smooth normals");
                }
            }
            if (nrm == null) nrm = smoothNormals(pos, indices);

            // uvs
            float[] uv = null;
            int tAcc = attrs.containsKey("TEXCOORD_0") ? Json.int32(attrs.get("TEXCOORD_0"), -1) : -1;
            if (tAcc >= 0 && tAcc < accessors.length) {
                try {
                    float[] raw_uv = accFloats(tAcc);
                    if (raw_uv.length == vc * 2) uv = raw_uv;
                } catch (IOException e) {
                    Log.w(TAG, "mesh '" + meshName + "': unreadable TEXCOORD_0 accessor, ignored");
                }
            }

            // skinning: JOINTS_0 + WEIGHTS_0 (per-vertex weights normalized to sum 1)
            short[] joints = null;
            float[] weights = null;
            int jAcc = attrs.containsKey("JOINTS_0") ? Json.int32(attrs.get("JOINTS_0"), -1) : -1;
            int wAcc = attrs.containsKey("WEIGHTS_0") ? Json.int32(attrs.get("WEIGHTS_0"), -1) : -1;
            if (jAcc >= 0 && jAcc < accessors.length && wAcc >= 0 && wAcc < accessors.length) {
                try {
                    int[] jv = accInts(jAcc);
                    float[] wv = accFloats(wAcc);
                    if (jv.length == vc * 4 && wv.length == vc * 4) {
                        joints = new short[vc * 4];
                        weights = new float[vc * 4];
                        for (int v = 0; v < vc; v++) {
                            float sum = 0;
                            for (int k = 0; k < 4; k++) sum += wv[v * 4 + k];
                            for (int k = 0; k < 4; k++) {
                                joints[v * 4 + k] = (short) Math.min(32767, Math.max(0, jv[v * 4 + k]));
                                if (sum > 1e-9f) weights[v * 4 + k] = wv[v * 4 + k] / sum;
                                else weights[v * 4 + k] = k == 0 ? 1f : 0f;
                            }
                        }
                    }
                } catch (IOException e) {
                    Log.w(TAG, "mesh '" + meshName + "': unreadable skin accessors, primitive loaded unskinned");
                }
            }

            MeshData md = new MeshData();
            md.positions = pos;
            md.normals = nrm;
            md.uvs = uv;
            md.indices = indices;
            md.jointIndices = joints;
            md.jointWeights = weights;

            ModelAsset.Primitive p = new ModelAsset.Primitive();
            p.mesh = md;
            p.materialIndex = Json.int32(pm.get("material"), 0);
            if (p.materialIndex < 0 || p.materialIndex >= 1_000_000) p.materialIndex = 0; // sane clamp
            p.bounds = AABB.fromPositions(pos);
            return p;
        }

        // ---------------- nodes + scene ----------------
        private void loadNodes(ModelAsset model) {
            List<Object> defs = arr(root, "nodes");
            for (int i = 0; i < defs.size(); i++) {
                Map<String, Object> nm = obj(defs.get(i));
                ModelAsset.Node nd = new ModelAsset.Node();
                nd.name = nm == null ? "node_" + i : Json.str(nm.get("name"), "node_" + i);
                if (nm != null) {
                    List<Object> mat = Json.arr(nm.get("matrix"));
                    if (mat != null && !mat.isEmpty()) {
                        decomposeMatrix(mat, nd);
                    } else {
                        List<Float> t = farr(nm.get("translation"), 3, 0);
                        nd.translation = new Vec3(t.get(0), t.get(1), t.get(2));
                        List<Object> r = Json.arr(nm.get("rotation"));
                        if (r != null && r.size() >= 4) {
                            // glTF quaternion layout: [x, y, z, w]
                            nd.rotation = new Quat(
                                    (float) Json.num(r.get(0), 0), (float) Json.num(r.get(1), 0),
                                    (float) Json.num(r.get(2), 0), (float) Json.num(r.get(3), 1)).norm();
                        }
                        List<Float> s = farr(nm.get("scale"), 3, 1);
                        nd.scale = new Vec3(s.get(0), s.get(1), s.get(2));
                    }
                    nd.meshIndex = Json.int32(nm.get("mesh"), -1);
                    nd.skinIndex = Json.int32(nm.get("skin"), -1);
                }
                model.nodes.add(nd);
            }
            // children links
            for (int i = 0; i < defs.size(); i++) {
                Map<String, Object> nm = obj(defs.get(i));
                if (nm == null) continue;
                for (Object co : arr(nm, "children")) {
                    int cid = (int) Json.num(co, -1);
                    if (cid < 0 || cid >= model.nodes.size() || cid == i) continue;
                    ModelAsset.Node nd = model.nodes.get(i);
                    if (!nd.children.contains(cid)) {
                        nd.children.add(cid);
                        model.nodes.get(cid).parent = i;
                    }
                }
            }
        }

        /** Decompose a column-major glTF node matrix into TRS (glTF: matrix excludes mirroring;
         *  negative determinant handled by flipping Z scale deterministically). */
        private static void decomposeMatrix(List<Object> m, ModelAsset.Node nd) {
            float[] f = new float[16];
            f[0] = (float) Json.num(m.size() > 0 ? m.get(0) : null, 1);
            f[5] = (float) Json.num(m.size() > 5 ? m.get(5) : null, 1);
            f[10] = (float) Json.num(m.size() > 10 ? m.get(10) : null, 1);
            f[15] = 1;
            for (int i = 0; i < Math.min(16, m.size()); i++) f[i] = (float) Json.num(m.get(i), f[i]);

            float sx = len3(f[0], f[1], f[2]);
            float sy = len3(f[4], f[5], f[6]);
            float sz = len3(f[8], f[9], f[10]);
            float det = f[0] * (f[5] * f[10] - f[9] * f[6])
                      - f[4] * (f[1] * f[10] - f[9] * f[2])
                      + f[8] * (f[1] * f[6] - f[5] * f[2]);
            boolean flip = det < 0;
            float szAbs = Math.abs(sz) < 1e-12f ? 1e-12f : Math.abs(sz);
            if (flip) sz = -szAbs;

            nd.translation = new Vec3(f[12], f[13], f[14]);
            nd.scale = new Vec3(sx, sy, sz);

            if (sx < 1e-9f || sy < 1e-9f) { nd.rotation = new Quat(); return; }
            // orthonormalized rotation basis R[row][col]
            float r00 = f[0] / sx, r10 = f[1] / sx, r20 = f[2] / sx;
            float r01 = f[4] / sy, r11 = f[5] / sy, r21 = f[6] / sy;
            float fi = flip ? -1f : 1f;
            float r02 = f[8] * fi / szAbs, r12 = f[9] * fi / szAbs, r22 = f[10] * fi / szAbs;

            float trace = r00 + r11 + r22;
            float x, y, z, w;
            if (trace > 0) {
                float s = (float) Math.sqrt(trace + 1.0) * 2f;
                w = 0.25f * s;
                x = (r21 - r12) / s; y = (r02 - r20) / s; z = (r10 - r01) / s;
            } else if (r00 > r11 && r00 > r22) {
                float s = (float) Math.sqrt(1.0 + r00 - r11 - r22) * 2f;
                w = (r21 - r12) / s; x = 0.25f * s;
                y = (r01 + r10) / s; z = (r02 + r20) / s;
            } else if (r11 > r22) {
                float s = (float) Math.sqrt(1.0 + r11 - r00 - r22) * 2f;
                w = (r02 - r20) / s; x = (r01 + r10) / s;
                y = 0.25f * s; z = (r12 + r21) / s;
            } else {
                float s = (float) Math.sqrt(1.0 + r22 - r00 - r11) * 2f;
                w = (r10 - r01) / s; x = (r02 + r20) / s;
                y = (r12 + r21) / s; z = 0.25f * s;
            }
            nd.rotation = new Quat(x, y, z, w).norm();
        }

        private void buildScene(ModelAsset model) {
            List<Object> scenes = arr(root, "scenes");
            List<Integer> roots = new ArrayList<>();
            int sceneIdx = Json.int32(root.get("scene"), scenes.isEmpty() ? -1 : 0);
            if (sceneIdx >= 0 && sceneIdx < scenes.size()) {
                Map<String, Object> sc = obj(scenes.get(sceneIdx));
                if (sc != null) {
                    for (Object no : arr(sc, "nodes")) {
                        int id = (int) Json.num(no, -1);
                        if (id >= 0 && id < model.nodes.size() && !roots.contains(id)) roots.add(id);
                    }
                }
            }
            if (roots.isEmpty()) {
                for (int i = 0; i < model.nodes.size(); i++)
                    if (model.nodes.get(i).parent == -1) roots.add(i);
            }
            if (roots.isEmpty()) { model.sceneRoot = -1; return; }
            if (roots.size() == 1) { model.sceneRoot = roots.get(0); return; }
            // multiple roots -> one synthetic root chaining them all
            ModelAsset.Node synthetic = new ModelAsset.Node();
            synthetic.name = "root";
            int rootIdx = model.nodes.size();
            for (int r : roots) {
                synthetic.children.add(r);
                model.nodes.get(r).parent = rootIdx;
            }
            model.nodes.add(synthetic);
            model.sceneRoot = rootIdx;
        }

        // ---------------- skins ----------------
        private void loadSkins(ModelAsset model) throws IOException {
            List<Object> defs = arr(root, "skins");
            for (int si = 0; si < defs.size(); si++) {
                Map<String, Object> sm = obj(defs.get(si));
                Skeleton sk = new Skeleton();
                List<Object> joints = sm == null ? new ArrayList<>(0) : arr(sm, "joints");
                float[] ibm = null;
                int ibmAcc = sm == null ? -1 : Json.int32(sm.get("inverseBindMatrices"), -1);
                if (ibmAcc >= 0 && ibmAcc < accessors.length) {
                    try { ibm = accFloats(ibmAcc); }
                    catch (IOException e) { Log.w(TAG, "skin " + si + ": unreadable inverseBindMatrices, using identity"); }
                }
                int[] jointNodes = new int[joints.size()];
                for (int j = 0; j < joints.size(); j++)
                    jointNodes[j] = (int) Json.num(joints.get(j), -1); // fill first: parent search below uses it
                for (int j = 0; j < joints.size(); j++) {
                    int nodeId = jointNodes[j];
                    Skeleton.Joint jt = new Skeleton.Joint();
                    boolean valid = nodeId >= 0 && nodeId < model.nodes.size();
                    ModelAsset.Node nd = valid ? model.nodes.get(nodeId) : null;
                    jt.name = nd != null ? nd.name : "joint_" + j;
                    if (ibm != null && ibm.length >= (j + 1) * 16) {
                        System.arraycopy(ibm, j * 16, jt.inverseBind, 0, 16);
                    } else {
                        jt.inverseBind[0] = jt.inverseBind[5] = jt.inverseBind[10] = jt.inverseBind[15] = 1;
                    }
                    if (nd != null) {
                        jt.restPos = nd.translation.cpy();
                        jt.restRot = nd.rotation.cpy();
                        jt.restScale = nd.scale.cpy();
                        if (nd.parent >= 0) {
                            for (int p = 0; p < jointNodes.length; p++)
                                if (jointNodes[p] == nd.parent) { jt.parent = p; break; }
                        }
                    }
                    sk.joints.add(jt);
                }
                skinJointNodes.add(jointNodes);
                model.skins.add(sk);
            }
        }

        private void wireMeshSkeletons(ModelAsset model) {
            for (ModelAsset.Node nd : model.nodes) {
                if (nd.meshIndex >= 0 && nd.meshIndex < model.meshes.size() && nd.skinIndex >= 0) {
                    ModelAsset.MeshAsset ma = model.meshes.get(nd.meshIndex);
                    if (ma.skeletonIndex == -1) ma.skeletonIndex = nd.skinIndex;
                    else if (ma.skeletonIndex != nd.skinIndex)
                        Log.w(TAG, "mesh '" + ma.name + "' used with multiple skins; keeping skin " + ma.skeletonIndex);
                }
            }
        }

        // ---------------- animations ----------------
        private void loadAnimations(ModelAsset model) {
            List<Object> defs = arr(root, "animations");
            if (defs.isEmpty()) return;

            // node index -> joint index (union over skins in order; first skin wins)
            int[] nodeToJoint = new int[model.nodes.size()];
            Arrays.fill(nodeToJoint, -1);
            for (int[] jointNodes : skinJointNodes) {
                for (int j = 0; j < jointNodes.length; j++) {
                    int n = jointNodes[j];
                    if (n >= 0 && n < nodeToJoint.length && nodeToJoint[n] == -1) nodeToJoint[n] = j;
                }
            }

            for (int ai = 0; ai < defs.size(); ai++) {
                Map<String, Object> am = obj(defs.get(ai));
                if (am == null) continue;
                List<Object> samplers = arr(am, "samplers");
                List<Object> channels = arr(am, "channels");
                if (channels.isEmpty()) continue;

                try {
                    AnimClip clip = new AnimClip();
                    clip.name = Json.str(am.get("name"), "clip_" + ai);
                    clip.loop = true;

                    // clip time range = max key time over all channels (per SPEC)
                    float maxTime = 0;
                    for (Object co : channels) {
                        Map<String, Object> cm = obj(co);
                        Map<String, Object> sm = cm == null ? null : obj(samplerAt(samplers, Json.int32(cm.get("sampler"), -1)));
                        if (sm == null) continue;
                        int inAcc = Json.int32(sm.get("input"), -1);
                        if (inAcc < 0 || inAcc >= accessors.length) continue;
                        float[] times = accFloats(inAcc);
                        for (float t : times) if (t > maxTime) maxTime = t;
                    }
                    clip.duration = maxTime;
                    float[] grid = cubicGrid(maxTime); // shared 30 Hz bake grid for CUBICSPLINE channels

                    boolean any = false;
                    for (Object co : channels) {
                        Map<String, Object> cm = obj(co);
                        if (cm == null) continue;
                        Map<String, Object> target = obj(cm.get("target"));
                        if (target == null) continue;
                        String p = Json.str(target.get("path"), "");
                        if (!p.equals("translation") && !p.equals("rotation") && !p.equals("scale")) continue;
                        int node = Json.int32(target.get("node"), -1);
                        if (node < 0 || node >= nodeToJoint.length) continue;
                        int joint = nodeToJoint[node];
                        if (joint < 0) continue; // non-joint nodes are ignored

                        Map<String, Object> sm = obj(samplerAt(samplers, Json.int32(cm.get("sampler"), -1)));
                        if (sm == null) continue;
                        int inAcc = Json.int32(sm.get("input"), -1);
                        int outAcc = Json.int32(sm.get("output"), -1);
                        if (inAcc < 0 || inAcc >= accessors.length || outAcc < 0 || outAcc >= accessors.length) continue;

                        String interp = Json.str(sm.get("interpolation"), "LINEAR");
                        float[] times = accFloats(inAcc);
                        int comps = p.equals("rotation") ? 4 : 3;
                        AnimClip.Channel ch = clip.channels.get(joint);
                        if (ch == null) { ch = new AnimClip.Channel(); clip.channels.put(joint, ch); }

                        float[] data;
                        boolean step = false;
                        if (interp.equals("CUBICSPLINE")) {
                            float[] raw = accFloats(outAcc);
                            if (raw.length < times.length * comps * 3) continue;
                            data = bakeCubic(times, raw, comps, grid);
                            times = grid;
                        } else {
                            step = interp.equals("STEP");
                            data = accFloats(outAcc);
                            if (data.length < times.length * comps) continue;
                        }

                        // merge into the joint channel: first path wins the time array,
                        // later paths with different key times are resampled onto it
                        if (ch.times == null) {
                            ch.times = times;
                        } else if (!Arrays.equals(ch.times, times)) {
                            data = resample(times, data, comps, step, ch.times);
                            times = ch.times;
                        }

                        switch (p) {
                            case "translation" -> { if (ch.translations == null) ch.translations = data; }
                            case "scale" -> { if (ch.scales == null) ch.scales = data; }
                            case "rotation" -> {
                                if (ch.rotations == null) {
                                    ch.rotations = data;
                                    ch.interpolation = step ? AnimClip.INTERP_STEP : AnimClip.INTERP_LINEAR;
                                }
                            }
                        }
                        any = true;
                    }

                    if (!any) continue; // animation targets no known joints
                    String name = clip.name;
                    if (model.clips.containsKey(name)) name = name + "_" + ai; // deterministic de-dup
                    clip.name = name;
                    model.clips.put(name, clip);
                } catch (IOException e) {
                    Log.w(TAG, "animation " + ai + " skipped: " + e.getMessage());
                }
            }
        }

        private Object samplerAt(List<Object> samplers, int idx) {
            return idx >= 0 && idx < samplers.size() ? samplers.get(idx) : null;
        }

        /** 30 Hz bake grid covering [0, maxTime] (last key exactly maxTime). */
        private static float[] cubicGrid(float maxTime) {
            if (maxTime <= 1e-6f) return new float[]{0f};
            int steps = (int) Math.ceil(maxTime * 30f - 1e-4f);
            if (steps < 1) steps = 1;
            float dt = 1f / 30f;
            float[] g = new float[steps + 1];
            for (int k = 0; k <= steps; k++) {
                float t = k * dt;
                g[k] = t > maxTime ? maxTime : t;
            }
            g[steps] = maxTime;
            return g;
        }

        /** Hermite-evaluate a CUBICSPLINE sampler at every grid time; returns grid.length*comps.
         *  out layout per key: [inTangent(comps), value(comps), outTangent(comps)]. */
        private static float[] bakeCubic(float[] times, float[] out, int comps, float[] grid) {
            int n = times.length;
            float[] res = new float[grid.length * comps];
            for (int gi = 0; gi < grid.length; gi++) {
                float t = grid[gi];
                if (n == 1 || t <= times[0]) {
                    for (int c = 0; c < comps; c++) res[gi * comps + c] = out[comps + c]; // key 0 value
                    continue;
                }
                if (t >= times[n - 1]) {
                    for (int c = 0; c < comps; c++) res[gi * comps + c] = out[(n - 1) * comps * 3 + comps + c];
                    continue;
                }
                int k = 1;
                while (k < n - 1 && times[k] <= t) k++;
                k--; // segment [k, k+1]
                float dt = Math.max(1e-9f, times[k + 1] - times[k]);
                float s = (t - times[k]) / dt;
                float s2 = s * s, s3 = s2 * s;
                float h00 = 2 * s3 - 3 * s2 + 1, h10 = s3 - 2 * s2 + s;
                float h01 = -2 * s3 + 3 * s2, h11 = s3 - s2;
                for (int c = 0; c < comps; c++) {
                    float p0 = out[k * comps * 3 + comps + c];           // value at k
                    float p1 = out[(k + 1) * comps * 3 + comps + c];     // value at k+1
                    float a = out[k * comps * 3 + 2 * comps + c];        // out-tangent at k
                    float b = out[(k + 1) * comps * 3 + c];              // in-tangent at k+1
                    res[gi * comps + c] = h00 * p0 + h10 * (a * dt) + h01 * p1 + h11 * (b * dt);
                }
                if (comps == 4) { // normalize baked quaternion
                    float x = res[gi * 4], y = res[gi * 4 + 1], z = res[gi * 4 + 2], w = res[gi * 4 + 3];
                    float l2 = x * x + y * y + z * z + w * w;
                    if (l2 > 1e-12f) {
                        float inv = 1f / (float) Math.sqrt(l2);
                        res[gi * 4] = x * inv; res[gi * 4 + 1] = y * inv;
                        res[gi * 4 + 2] = z * inv; res[gi * 4 + 3] = w * inv;
                    } else {
                        res[gi * 4 + 3] = 1;
                    }
                }
            }
            return res;
        }

        /** Resample data (LINEAR or STEP) from 'times' onto 'target' key times. */
        private static float[] resample(float[] times, float[] data, int comps, boolean step, float[] target) {
            float[] out = new float[target.length * comps];
            int n = times.length;
            for (int i = 0; i < target.length; i++) {
                float t = target[i];
                if (n == 1 || t <= times[0]) {
                    for (int c = 0; c < comps; c++) out[i * comps + c] = data[c];
                    continue;
                }
                if (t >= times[n - 1]) {
                    for (int c = 0; c < comps; c++) out[i * comps + c] = data[(n - 1) * comps + c];
                    continue;
                }
                int k = 1;
                while (k < n - 1 && times[k] <= t) k++;
                k--;
                float f = (t - times[k]) / Math.max(1e-6f, times[k + 1] - times[k]);
                for (int c = 0; c < comps; c++) {
                    float a = data[k * comps + c], b = data[(k + 1) * comps + c];
                    if (step) {
                        out[i * comps + c] = a;
                    } else if (comps == 4) {
                        // slerp via Quat (matches AnimClip.sampleRotation behaviour)
                        Quat qa = new Quat(data[k * 4], data[k * 4 + 1], data[k * 4 + 2], data[k * 4 + 3]);
                        Quat qb = new Quat(data[(k + 1) * 4], data[(k + 1) * 4 + 1], data[(k + 1) * 4 + 2], data[(k + 1) * 4 + 3]);
                        Quat q = qa.cpy().slerp(qb, f);
                        out[i * 4] = q.x; out[i * 4 + 1] = q.y; out[i * 4 + 2] = q.z; out[i * 4 + 3] = q.w;
                    } else {
                        out[i * comps + c] = com.hollow.core.MathX.lerp(a, b, f);
                    }
                }
            }
            return out;
        }

        // ---------------- accessor reading ----------------
        /** Read every component of an accessor as floats (honors normalized + stride). */
        private float[] accFloats(int ai) throws IOException {
            AC a = accessor(ai);
            float[] out = new float[a.count * a.comps];
            fillAccessor(ai, a, (buf, p, ct) -> readComponent(buf, p, ct, false), out);
            return out;
        }

        /** Read every component of an accessor as ints (indices / joint indices). */
        private int[] accInts(int ai) throws IOException {
            AC a = accessor(ai);
            int[] out = new int[a.count * a.comps];
            float[] tmp = new float[a.count * a.comps];
            fillAccessor(ai, a, (buf, p, ct) -> readComponent(buf, p, ct, false), tmp);
            for (int i = 0; i < tmp.length; i++) out[i] = (int) tmp[i];
            return out;
        }

        private AC accessor(int ai) throws IOException {
            if (ai < 0 || ai >= accessors.length) throw new IOException("accessor " + ai + " out of range: " + path);
            AC a = accessors[ai];
            if (a.count <= 0 || a.comps <= 0) throw new IOException("accessor " + ai + " is empty: " + path);
            if (a.view < 0 || a.view >= views.length) throw new IOException("accessor " + ai + " has no bufferView: " + path);
            return a;
        }

        private interface CompReader { float read(byte[] buf, long pos, int compType) throws IOException; }

        private void fillAccessor(int ai, AC a, CompReader rd, float[] out) throws IOException {
            BV v = views[a.view];
            if (v.buffer < 0 || v.buffer >= buffers.length || buffers[v.buffer] == null)
                throw new IOException("accessor " + ai + ": bufferView " + a.view + " has no buffer: " + path);
            byte[] buf = buffers[v.buffer];
            int compSz = compSize(a.compType);
            if (compSz <= 0) throw new IOException("accessor " + ai + ": unsupported componentType " + a.compType);
            int stride = v.stride > 0 ? v.stride : a.comps * compSz;
            long start = (long) v.offset + a.offset;
            long need = (long) (a.count - 1) * stride + (long) a.comps * compSz;
            if (a.offset < 0 || v.offset < 0 || a.offset + need > v.length
                    || start < 0 || start + need > buf.length)
                throw new IOException("accessor " + ai + " exceeds bufferView/buffer bounds: " + path);
            for (int i = 0; i < a.count; i++) {
                long e = start + (long) i * stride;
                for (int c = 0; c < a.comps; c++)
                    out[i * a.comps + c] = rd.read(buf, e + (long) c * compSz, a.compType);
            }
        }

        private static float readComponent(byte[] buf, long pos, int ct, boolean normalized) throws IOException {
            int i = (int) pos;
            if (i < 0) throw new IOException("buffer read out of bounds");
            switch (ct) {
                case CT_FLOAT: {
                    if (i + 4 > buf.length) throw new IOException("buffer read out of bounds");
                    return Float.intBitsToFloat(le32(buf, i));
                }
                case CT_UINT: {
                    if (i + 4 > buf.length) throw new IOException("buffer read out of bounds");
                    long v = u32le(buf, i);
                    return normalized ? (float) (v / 4294967295.0) : (float) v;
                }
                case CT_USHORT: {
                    if (i + 2 > buf.length) throw new IOException("buffer read out of bounds");
                    int v = le16u(buf, i);
                    return normalized ? v / 65535f : (float) v;
                }
                case CT_SHORT: {
                    if (i + 2 > buf.length) throw new IOException("buffer read out of bounds");
                    int v = le16s(buf, i);
                    return normalized ? Math.max(v / 32767f, -1f) : (float) v;
                }
                case CT_UBYTE: {
                    if (i + 1 > buf.length) throw new IOException("buffer read out of bounds");
                    int v = buf[i] & 0xFF;
                    return normalized ? v / 255f : (float) v;
                }
                case CT_BYTE: {
                    if (i + 1 > buf.length) throw new IOException("buffer read out of bounds");
                    int v = buf[i];
                    return normalized ? Math.max(v / 127f, -1f) : (float) v;
                }
                default: throw new IOException("unsupported componentType " + ct);
            }
        }

        // ---------------- helpers ----------------
        private static int numComponents(String type) {
            switch (type) {
                case "SCALAR": return 1;
                case "VEC2": return 2;
                case "VEC3": return 3;
                case "VEC4": case "MAT2": return 4;
                case "MAT3": return 9;
                case "MAT4": return 16;
                default: return 0;
            }
        }

        private static int compSize(int ct) {
            switch (ct) {
                case CT_FLOAT: case CT_UINT: return 4;
                case CT_SHORT: case CT_USHORT: return 2;
                case CT_BYTE: case CT_UBYTE: return 1;
                default: return -1;
            }
        }

        private static float[] smoothNormals(float[] pos, int[] idx) {
            int vc = pos.length / 3;
            float[] n = new float[vc * 3];
            for (int t = 0; t + 2 < idx.length; t += 3) {
                int a = idx[t], b = idx[t + 1], c = idx[t + 2];
                if (a < 0 || b < 0 || c < 0 || a >= vc || b >= vc || c >= vc) continue;
                float e1x = pos[b * 3] - pos[a * 3], e1y = pos[b * 3 + 1] - pos[a * 3 + 1], e1z = pos[b * 3 + 2] - pos[a * 3 + 2];
                float e2x = pos[c * 3] - pos[a * 3], e2y = pos[c * 3 + 1] - pos[a * 3 + 1], e2z = pos[c * 3 + 2] - pos[a * 3 + 2];
                float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
                n[a * 3] += nx; n[a * 3 + 1] += ny; n[a * 3 + 2] += nz;
                n[b * 3] += nx; n[b * 3 + 1] += ny; n[b * 3 + 2] += nz;
                n[c * 3] += nx; n[c * 3 + 1] += ny; n[c * 3 + 2] += nz;
            }
            for (int v = 0; v < vc; v++) {
                float l = (float) Math.sqrt((double) n[v * 3] * n[v * 3] + n[v * 3 + 1] * n[v * 3 + 1] + n[v * 3 + 2] * n[v * 3 + 2]);
                if (l > 1e-12f) {
                    n[v * 3] /= l; n[v * 3 + 1] /= l; n[v * 3 + 2] /= l;
                } else { // unreferenced / degenerate vertex
                    n[v * 3] = 0; n[v * 3 + 1] = 1; n[v * 3 + 2] = 0;
                }
            }
            return n;
        }

        private static float len3(float x, float y, float z) {
            return (float) Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        }

        /** Percent-decode a URI (keeps '+' literally, unlike java.net.URLDecoder). */
        private static String decodeUri(String s) {
            if (s.indexOf('%') < 0) return s;
            StringBuilder b = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '%' && i + 2 < s.length()) {
                    int hi = hexVal(s.charAt(i + 1)), lo = hexVal(s.charAt(i + 2));
                    if (hi >= 0 && lo >= 0) { b.append((char) (hi * 16 + lo)); i += 2; continue; }
                }
                b.append(c);
            }
            return b.toString();
        }

        private static int hexVal(char c) {
            if (c >= '0' && c <= '9') return c - '0';
            if (c >= 'a' && c <= 'f') return c - 'a' + 10;
            if (c >= 'A' && c <= 'F') return c - 'A' + 10;
            return -1;
        }

        private static int le16u(byte[] b, int off) { return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8; }
        private static int le16s(byte[] b, int off) { return (short) le16u(b, off); }
    }

    // ---- bufferView / accessor descriptors ----
    private static final class BV { int buffer = -1, offset, length, stride; }
    private static final class AC { int view = -1, offset, count, compType = CT_FLOAT, comps = 1; String type = "SCALAR"; }

    // ---- little-endian byte helpers (shared by GLB header parse and accessor reads) ----
    private static long u32le(byte[] b, int off) {
        return (b[off] & 0xFFL) | (b[off + 1] & 0xFFL) << 8 | (b[off + 2] & 0xFFL) << 16 | (b[off + 3] & 0xFFL) << 24;
    }
    private static int le32(byte[] b, int off) { return (int) u32le(b, off); }
    private static int le16u(byte[] b, int off) { return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8; }
    private static int le16s(byte[] b, int off) { return (short) le16u(b, off); }
}
