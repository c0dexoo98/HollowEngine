package com.hollow.assets;

import com.hollow.core.FileSystem;

/** Central asset database — resolves, caches, and serves engine assets.
 *  Desktop + Android identical: everything is read through FileSystem. */
public final class AssetDB {
    public final FileSystem fs;
    public final String projectRoot;

    private final java.util.Map<String, MeshData> meshCache = new java.util.HashMap<>();
    private final java.util.Map<String, ModelAsset> modelCache = new java.util.HashMap<>();
    private final java.util.Map<String, ImageData> imageCache = new java.util.HashMap<>();
    private final java.util.Map<String, AudioClipData> audioCache = new java.util.HashMap<>();
    private final java.util.Map<String, Atlas> atlasCache = new java.util.HashMap<>();

    public AssetDB(FileSystem fs, String projectRoot){
        this.fs = fs;
        this.projectRoot = projectRoot;
    }

    // ------------- path helpers -------------
    public boolean exists(String path){ return fs.exists(path); }
    public FileSystem files(){ return fs; }

    /** register a runtime-generated image (text atlases, video frames) under a synthetic key */
    public synchronized void registerImage(String key, ImageData img){
        imageCache.put(key, img);
    }
    public synchronized boolean isImageRegistered(String key){ return imageCache.containsKey(key); }

    // ------------- meshes -------------
    public synchronized MeshData mesh(String path){
        MeshData m = meshCache.get(path);
        if(m != null) return m;
        try {
            if(path.endsWith(".hvm")) m = MeshData.load(path, fs);
            else {
                ModelAsset ma = model(path);
                m = ma != null && !ma.meshes.isEmpty() && !ma.meshes.get(0).primitives.isEmpty()
                        ? ma.meshes.get(0).primitives.get(0).mesh : null;
            }
        } catch(Exception e){
            com.hollow.core.Log.e("AssetDB", "mesh load failed: " + path, e);
        }
        if(m == null) m = new MeshData(); // empty fallback
        meshCache.put(path, m);
        return m;
    }

    // ------------- models (glTF) -------------
    public synchronized ModelAsset model(String path){
        ModelAsset m = modelCache.get(path);
        if(m != null) return m;
        try {
            m = GltfLoader.load(path, fs);
        } catch(Exception e){
            com.hollow.core.Log.e("AssetDB", "model load failed: " + path, e);
            m = new ModelAsset();
            m.sourcePath = path;
        }
        modelCache.put(path, m);
        return m;
    }
    public synchronized AnimClip clip(String modelPath, String clipName){
        ModelAsset m = model(modelPath);
        AnimClip c = m.clips.get(clipName);
        if(c == null && !m.clips.isEmpty()) c = m.clips.values().iterator().next();
        return c;
    }

    /** v2.0: blend spaces (anim/*.blendspace.json) with cache */
    private final java.util.Map<String, com.hollow.anim.BlendSpace> blendCache = new java.util.HashMap<>();
    public synchronized com.hollow.anim.BlendSpace blendSpace(String path){
        com.hollow.anim.BlendSpace bs = blendCache.get(path);
        if(bs != null) return bs;
        bs = com.hollow.anim.BlendSpace.loadFromProject(fs, path);
        if(bs != null) blendCache.put(path, bs);
        return bs;
    }

    // ------------- images -------------
    public synchronized ImageData image(String path){
        ImageData im = imageCache.get(path);
        if(im != null) return im;
        if(path.endsWith(".hvt")){
            try { im = ImageData.load(path, fs); }
            catch(Exception e){ com.hollow.core.Log.e("AssetDB", "texture load failed: " + path, e); im = null; }
        }
        if(im == null){
            ImageDecoder dec = ImageDecoder.get();
            if(dec != null) im = dec.decodeFile(path, fs);
            else im = new ImageData(1, 1, new int[]{0xFFFFFFFF});
        }
        imageCache.put(path, im);
        return im;
    }

    // ------------- audio -------------
    public synchronized AudioClipData audio(String path){
        AudioClipData a = audioCache.get(path);
        if(a != null) return a;
        try {
            byte[] raw = fs.read(path);
            if(path.endsWith(".wav")) a = AudioClipData.fromWav(raw, path);
            else if(path.endsWith(".ogg") && OggDecoder.available()) a = OggDecoder.decode(raw, path);
            else if(path.endsWith(".ogg")) com.hollow.core.Log.w("AssetDB", "OGG decode unavailable in this runtime: " + path);
        } catch(Exception e){
            com.hollow.core.Log.e("AssetDB", "audio load failed: " + path, e);
        }
        if(a == null) a = new AudioClipData();
        audioCache.put(path, a);
        return a;
    }

    // ------------- atlas -------------
    public synchronized Atlas atlas(String path){
        Atlas a = atlasCache.get(path);
        if(a != null) return a;
        try {
            a = Atlas.fromJson(com.hollow.core.Json.parseObj(fs.readText(path)));
        } catch(Exception e){
            com.hollow.core.Log.e("AssetDB", "atlas load failed: " + path, e);
            a = new Atlas();
        }
        atlasCache.put(path, a);
        return a;
    }

    public void clearCaches(){
        meshCache.clear(); modelCache.clear(); imageCache.clear(); audioCache.clear(); atlasCache.clear();
        refCounts.clear();
    }

    // ------------- v2.2 refcounting (MEM-015) -------------
    private final java.util.Map<String, Integer> refCounts = new java.util.HashMap<>();

    /** claim a reference on a cached asset (call when a system starts using it) */
    public synchronized void retain(String path){
        refCounts.merge(path, 1, Integer::sum);
    }
    /** drop a reference; asset stays cached but becomes evictable at refcount 0 */
    public synchronized void release(String path){
        Integer c = refCounts.get(path);
        if(c == null) return;
        if(c <= 1) refCounts.remove(path); else refCounts.put(path, c - 1);
    }
    public synchronized int refCount(String path){
        return refCounts.getOrDefault(path, 0);
    }

    /** evict cached assets with zero references (memory pressure hook).
     *  Registered runtime images are never evicted. Returns evicted count. */
    public synchronized int evictUnreferenced(){
        int evicted = 0;
        evicted += evictIfIdle(meshCache, refCounts);
        evicted += evictIfIdle(modelCache, refCounts);
        evicted += evictIfIdle(imageCache, refCounts);
        evicted += evictIfIdle(audioCache, refCounts);
        evicted += evictIfIdle(atlasCache, refCounts);
        return evicted;
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int evictIfIdle(java.util.Map cache, java.util.Map<String, Integer> refs){
        int n = 0;
        java.util.Iterator<java.util.Map.Entry<String, ?>> it = cache.entrySet().iterator();
        while(it.hasNext()){
            java.util.Map.Entry<String, ?> e = it.next();
            if(!refs.containsKey(e.getKey())){ it.remove(); n++; }
        }
        return n;
    }
    public synchronized int cachedCount(){
        return meshCache.size() + modelCache.size() + imageCache.size() + audioCache.size() + atlasCache.size();
    }
}
