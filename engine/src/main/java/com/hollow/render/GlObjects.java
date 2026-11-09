package com.hollow.render;

import android.opengl.GLES30;

import com.hollow.assets.ImageData;
import com.hollow.core.Log;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;

/** Thin GLES30 object wrappers: Shader (compile+link+uniform cache), Vbo/Ibo/VertexArray,
 *  Texture2D (RGBA8 + full mip chain, trilinear, clamp), Fbo (color+depth / depth-only).
 *
 *  IMPORTANT: no GL call may run before the renderer init() on the GL thread; none of these
 *  classes perform GL work in static initializers, so the classes are safe to LOAD on desktop.
 */
public final class GlObjects {
    private static final String TAG = "GL";

    private GlObjects() {}

    // ===================================================================
    // Shader program
    // ===================================================================
    public static final class Shader {
        public final int program;
        public final String name;
        private final HashMap<String, Integer> uniformCache = new HashMap<>();
        private final HashMap<String, Integer> attribCache = new HashMap<>();
        private boolean warnedMissing = false;

        public Shader(String name, String vsSource, String fsSource){
            this.name = name;
            int vs = compile(name + ".vs", GLES30.GL_VERTEX_SHADER, vsSource);
            int fs = compile(name + ".fs", GLES30.GL_FRAGMENT_SHADER, fsSource);
            int p = GLES30.glCreateProgram();
            if (vs != 0) GLES30.glAttachShader(p, vs);
            if (fs != 0) GLES30.glAttachShader(p, fs);
            GLES30.glLinkProgram(p);
            int[] ok = new int[1];
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0){
                Log.e(TAG, "program link failed [" + name + "]: " + GLES30.glGetProgramInfoLog(p));
                GLES30.glDeleteProgram(p);
                p = 0;
            }
            if (vs != 0) GLES30.glDeleteShader(vs);
            if (fs != 0) GLES30.glDeleteShader(fs);
            program = p;
        }

        public boolean ok(){ return program != 0; }

        private static int compile(String label, int type, String src){
            int s = GLES30.glCreateShader(type);
            GLES30.glShaderSource(s, src);
            GLES30.glCompileShader(s);
            int[] ok = new int[1];
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0){
                Log.e(TAG, "shader compile failed [" + label + "]: " + GLES30.glGetShaderInfoLog(s));
                GLES30.glDeleteShader(s);
                return 0;
            }
            return s;
        }

        public int use(){ GLES30.glUseProgram(program); return program; }

        /** cached uniform location; -1 if not present (warned once per program) */
        public int uniform(String u){
            Integer c = uniformCache.get(u);
            if (c != null) return c;
            int loc = GLES30.glGetUniformLocation(program, u);
            if (loc < 0 && !warnedMissing){
                Log.w(TAG, "shader [" + name + "] missing uniform (first seen): " + u);
                warnedMissing = true;
            }
            uniformCache.put(u, loc);
            return loc;
        }

        public int attrib(String a){
            Integer c = attribCache.get(a);
            if (c != null) return c;
            int loc = GLES30.glGetAttribLocation(program, a);
            attribCache.put(a, loc);
            return loc;
        }

        // -------- typed setters (all no-op if the uniform doesn't exist) --------
        public void setMat4(String u, float[] m){
            int loc = uniform(u);
            if (loc >= 0) GLES30.glUniformMatrix4fv(loc, 1, false, m, 0);
        }
        public void setMat4Array(String u, float[] data, int count){
            int loc = uniform(u);
            if (loc >= 0 && count > 0) GLES30.glUniformMatrix4fv(loc, count, false, data, 0);
        }
        public void setVec2Direct(String u, float x, float y){
            GLES30.glUniform2f(uniform(u), x, y);
        }
        public void setVec3(String u, float x, float y, float z){
            int loc = uniform(u);
            if (loc >= 0) GLES30.glUniform3f(loc, x, y, z);
        }
        public void setVec3(String u, float[] v){ setVec3(u, v[0], v[1], v[2]); }
        public void setVec4(String u, float x, float y, float z, float w){
            int loc = uniform(u);
            if (loc >= 0) GLES30.glUniform4f(loc, x, y, z, w);
        }
        public void setVec4(String u, float[] v){ setVec4(u, v[0], v[1], v[2], v[3]); }
        public void setVec4Array(String u, float[] data, int count){
            int loc = uniform(u);
            if (loc >= 0 && count > 0) GLES30.glUniform4fv(loc, count, data, 0);
        }
        public void setFloat(String u, float v){
            int loc = uniform(u);
            if (loc >= 0) GLES30.glUniform1f(loc, v);
        }
        public void setInt(String u, int v){
            int loc = uniform(u);
            if (loc >= 0) GLES30.glUniform1i(loc, v);
        }
        public void setFloatArray(String u, float[] data, int count){
            int loc = uniform(u);
            if (loc >= 0 && count > 0) GLES30.glUniform1fv(loc, count, data, 0);
        }

        public void dispose(){
            if (program != 0) GLES30.glDeleteProgram(program);
        }
    }

    // ===================================================================
    // Buffers
    // ===================================================================
    public static final class Vbo {
        public final int id;
        public Vbo(){
            int[] b = new int[1];
            GLES30.glGenBuffers(1, b, 0);
            id = b[0];
        }
        public void bind(){ GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id); }
        public void upload(float[] data){
            bind();
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.length * 4,
                    FloatBuffer.wrap(data), GLES30.GL_STATIC_DRAW);
        }
        /** orphan + upload first `floats` values (per-frame dynamic data) */
        public void uploadDynamic(float[] data, int floats){
            bind();
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, floats * 4,
                    floats > 0 ? FloatBuffer.wrap(data, 0, floats) : null, GLES30.GL_DYNAMIC_DRAW);
        }
        public void dispose(){ GLES30.glDeleteBuffers(1, new int[]{id}, 0); }
    }

    public static final class Ibo {
        public final int id;
        public final int count;
        public Ibo(int[] indices){
            count = indices.length;
            int[] b = new int[1];
            GLES30.glGenBuffers(1, b, 0);
            id = b[0];
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, id);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.length * 4,
                    IntBuffer.wrap(indices), GLES30.GL_STATIC_DRAW);
        }
        public void bind(){ GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, id); }
        public void dispose(){ GLES30.glDeleteBuffers(1, new int[]{id}, 0); }
    }

    public static final class VertexArray {
        public final int id;
        public VertexArray(){
            int[] b = new int[1];
            GLES30.glGenVertexArrays(1, b, 0);
            id = b[0];
        }
        public void bind(){ GLES30.glBindVertexArray(id); }
        public static void unbind(){ GLES30.glBindVertexArray(0); }
        /** call with the target VBO bound; stride/offset in floats */
        public void attrib(int loc, int size, int strideFloats, int offsetFloats){
            GLES30.glEnableVertexAttribArray(loc);
            GLES30.glVertexAttribPointer(loc, size, GLES30.GL_FLOAT, false,
                    strideFloats * 4, offsetFloats * 4);
        }
        public void dispose(){ GLES30.glDeleteVertexArrays(1, new int[]{id}, 0); }
    }

    // ===================================================================
    // Texture2D — RGBA8 from ImageData with full mip chain (trilinear, clamp)
    // ===================================================================
    public static final class Texture2D {
        public final int id;
        public final int width, height;
        /** total textures uploaded since process start (renderer snapshots at init) */
        public static int uploadedCount = 0;
        private static Texture2D white;

        public Texture2D(ImageData img, boolean withMips){
            int[] b = new int[1];
            GLES30.glGenTextures(1, b, 0);
            id = b[0];
            width = img.width;
            height = img.height;
            upload(img, withMips);
            uploadedCount++;
        }

        /** creates an empty allocatable RGBA8 texture — used for FBO color attachments */
        public Texture2D(int w, int h){
            int[] b = new int[1];
            GLES30.glGenTextures(1, b, 0);
            id = b[0];
            width = w;
            height = h;
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            uploadedCount++;
        }

        /** wraps an ALREADY-CREATED GL texture id (no upload, no count) — used for FBO depth textures */
        Texture2D(int existingGlId, int w, int h){
            id = existingGlId;
            width = w;
            height = h;
        }

        private void upload(ImageData img, boolean withMips){
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
                    withMips ? GLES30.GL_LINEAR_MIPMAP_LINEAR : GLES30.GL_LINEAR);
            ImageData[] chain = withMips ? img.mipChain() : new ImageData[]{img};
            for (int i = 0; i < chain.length; i++){
                ImageData m = chain[i];
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, i, GLES30.GL_RGBA8, m.width, m.height, 0,
                        GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgbaBytes(m));
            }
        }

        /** ImageData stores (a<<24)|(r<<16)|(g<<8)|b ints — GL wants byte rows R,G,B,A. */
        private static ByteBuffer rgbaBytes(ImageData im){
            ByteBuffer b = ByteBuffer.allocateDirect(im.width * im.height * 4);
            int[] px = im.pixels;
            for (int i = 0; i < px.length; i++){
                int p = px[i];
                b.put((byte) ((p >> 16) & 255)); // R
                b.put((byte) ((p >> 8) & 255));  // G
                b.put((byte) (p & 255));         // B
                b.put((byte) ((p >>> 24) & 255));// A
            }
            b.position(0);
            return b;
        }

        public void bind(int unit){
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id);
        }

        public void dispose(){ GLES30.glDeleteTextures(1, new int[]{id}, 0); }

        /** 1x1 white fallback (created lazily on the GL thread during init) */
        public static Texture2D white(){
            if (white == null) white = new Texture2D(new ImageData(1, 1, new int[]{0xFFFFFFFF}), false);
            return white;
        }
        public static void disposeWhite(){
            if (white != null){ white.dispose(); white = null; }
        }
    }

    // ===================================================================
    // Fbo — color texture + depth renderbuffer (or depth-texture only, for shadows)
    // ===================================================================
    public static final class Fbo {
        public final int id;
        public int width, height;
        public Texture2D color;       // null for depth-only
        public Texture2D depthTex;    // set when depthTexture=true
        public int depthRb;           // set when depthTexture=false
        public final boolean depthTexture;

        private Fbo(int w, int h, boolean depthTexture){
            this.depthTexture = depthTexture;
            width = Math.max(1, w);
            height = Math.max(1, h);
            int[] fb = new int[1];
            GLES30.glGenFramebuffers(1, fb, 0);
            id = fb[0];
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, id);
            if (depthTexture){
                int[] t = new int[1];
                GLES30.glGenTextures(1, t, 0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24,
                        width, height, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null);
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
                        GLES30.GL_TEXTURE_2D, t[0], 0);
                depthTex = new Texture2D(t[0], width, height); // wraps existing GL id (see ctor below)
                GLES30.glDrawBuffers(1, new int[]{GLES30.GL_NONE}, 0);
                GLES30.glReadBuffer(GLES30.GL_NONE);
            } else {
                color = new Texture2D(width, height);
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                        GLES30.GL_TEXTURE_2D, color.id, 0);
                int[] rb = new int[1];
                GLES30.glGenRenderbuffers(1, rb, 0);
                depthRb = rb[0];
                GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRb);
                GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24,
                        width, height);
                GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
                        GLES30.GL_RENDERBUFFER, depthRb);
            }
            int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE)
                Log.e(TAG, "incomplete framebuffer (" + width + "x" + height + " depthTex=" + depthTexture + "): 0x"
                        + Integer.toHexString(status));
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        }

        public static Fbo create(int w, int h){ return new Fbo(w, h, false); }
        public static Fbo createDepthOnly(int w, int h){ return new Fbo(w, h, true); }

        public void bind(){
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, id);
            GLES30.glViewport(0, 0, width, height);
        }

        public void resize(int w, int h){
            w = Math.max(1, w); h = Math.max(1, h);
            if (w == width && h == height) return;
            width = w; height = h;
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, id);
            if (depthTexture){
                depthTex.dispose();
                int[] t = new int[1];
                GLES30.glGenTextures(1, t, 0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24,
                        width, height, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null);
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
                        GLES30.GL_TEXTURE_2D, t[0], 0);
                depthTex = new Texture2D(t[0], width, height);
            } else {
                if (color != null) color.dispose();
                color = new Texture2D(width, height);
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                        GLES30.GL_TEXTURE_2D, color.id, 0);
                if (depthRb != 0) GLES30.glDeleteRenderbuffers(1, new int[]{depthRb}, 0);
                int[] rb = new int[1];
                GLES30.glGenRenderbuffers(1, rb, 0);
                depthRb = rb[0];
                GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRb);
                GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24,
                        width, height);
                GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
                        GLES30.GL_RENDERBUFFER, depthRb);
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        }

        public void dispose(){
            if (color != null) color.dispose();
            if (depthTex != null) depthTex.dispose();
            if (depthRb != 0) GLES30.glDeleteRenderbuffers(1, new int[]{depthRb}, 0);
            GLES30.glDeleteFramebuffers(1, new int[]{id}, 0);
        }

        public static void unbindDefault(int w, int h){
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, Math.max(1, w), Math.max(1, h));
        }
    }

    /** internal: wrap an ALREADY-CREATED GL texture id without re-uploading (depth textures) */
    public static Texture2D wrapExistingTexture(int glId, int w, int h){
        return new Texture2D(glId, w, h);
    }

    /** count GL errors queued so far (call sparsely — init/shutdown only) */
    public static void checkError(String where){
        int err;
        int n = 0;
        while ((err = GLES30.glGetError()) != GLES30.GL_NO_ERROR && n++ < 8){
            Log.e(TAG, "GL error 0x" + Integer.toHexString(err) + " at " + where);
        }
    }
}
