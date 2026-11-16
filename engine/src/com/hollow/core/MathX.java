package com.hollow.core;

/** HollowEngine 2 — deterministic math: Vec3, column-major Mat4,
 *  perspective/lookAt, euler rotations, seeded RNG (xorshift64*). */
public final class MathX {

    private MathX(){ }

    public static float clamp(float v, float lo, float hi){
        return v < lo ? lo : (v > hi ? hi : v);
    }
    public static int clamp(int v, int lo, int hi){
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------- Vec3 -------------------------

    public static final class Vec3 {
        public float x, y, z;
        public Vec3(){ }
        public Vec3(float x, float y, float z){ this.x = x; this.y = y; this.z = z; }
        public Vec3 set(float x, float y, float z){ this.x = x; this.y = y; this.z = z; return this; }
        public Vec3 set(Vec3 o){ x = o.x; y = o.y; z = o.z; return this; }
        public Vec3 add(Vec3 o){ x += o.x; y += o.y; z += o.z; return this; }
        public Vec3 sub(Vec3 o){ x -= o.x; y -= o.y; z -= o.z; return this; }
        public Vec3 scale(float s){ x *= s; y *= s; z *= s; return this; }
        public float dot(Vec3 o){ return x * o.x + y * o.y + z * o.z; }
        public Vec3 cross(Vec3 o){
            return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
        }
        public float len(){ return (float)Math.sqrt(dot(this)); }
        public Vec3 norm(){
            float l = len();
            if(l < 1e-8f) return this;
            return scale(1f / l);
        }
        public Vec3 copy(){ return new Vec3(x, y, z); }
        @Override public String toString(){ return "(" + x + ", " + y + ", " + z + ")"; }
    }

    // ------------------------- Mat4 (column-major) -------------------------

    public static final class Mat4 {
        public final float[] m = new float[16];

        public Mat4 identity(){
            java.util.Arrays.fill(m, 0f);
            m[0] = m[5] = m[10] = m[15] = 1f;
            return this;
        }
        public Mat4 copy(){
            Mat4 r = new Mat4();
            System.arraycopy(m, 0, r.m, 0, 16);
            return r;
        }
        public Mat4 mul(Mat4 b){
            Mat4 r = new Mat4();
            for(int c = 0; c < 4; c++)
                for(int rw = 0; rw < 4; rw++){
                    float s = 0;
                    for(int k = 0; k < 4; k++) s += m[k * 4 + rw] * b.m[c * 4 + k];
                    r.m[c * 4 + rw] = s;
                }
            System.arraycopy(r.m, 0, m, 0, 16);
            return this;
        }
        public Mat4 translate(float x, float y, float z){
            Mat4 t = new Mat4().identity();
            t.m[12] = x; t.m[13] = y; t.m[14] = z;
            return mul(t);
        }
        public Mat4 rotateX(float deg){
            float r = (float)Math.toRadians(deg), c = (float)Math.cos(r), s = (float)Math.sin(r);
            Mat4 t = new Mat4().identity();
            t.m[5] = c; t.m[6] = s; t.m[9] = -s; t.m[10] = c;
            return mul(t);
        }
        public Mat4 rotateY(float deg){
            float r = (float)Math.toRadians(deg), c = (float)Math.cos(r), s = (float)Math.sin(r);
            Mat4 t = new Mat4().identity();
            t.m[0] = c; t.m[2] = -s; t.m[8] = s; t.m[10] = c;
            return mul(t);
        }
        public Mat4 rotateZ(float deg){
            float r = (float)Math.toRadians(deg), c = (float)Math.cos(r), s = (float)Math.sin(r);
            Mat4 t = new Mat4().identity();
            t.m[0] = c; t.m[1] = s; t.m[4] = -s; t.m[5] = c;
            return mul(t);
        }
        public Mat4 rotateEuler(float rx, float ry, float rz){
            return rotateZ(rz).rotateY(ry).rotateX(rx);
        }
        public Mat4 scale(float sx, float sy, float sz){
            Mat4 t = new Mat4().identity();
            t.m[0] = sx; t.m[5] = sy; t.m[10] = sz;
            return mul(t);
        }
        public Vec3 transformPoint(Vec3 p){
            float x = m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12];
            float y = m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13];
            float z = m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14];
            float w = m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15];
            if(w != 0f && w != 1f){ x /= w; y /= w; z /= w; }
            return new Vec3(x, y, z);
        }
        public Vec3 transformDir(Vec3 d){
            float x = m[0] * d.x + m[4] * d.y + m[8] * d.z;
            float y = m[1] * d.x + m[5] * d.y + m[9] * d.z;
            float z = m[2] * d.x + m[6] * d.y + m[10] * d.z;
            return new Vec3(x, y, z);
        }
        public Mat4 perspective(float fovYDeg, float aspect, float near, float far){
            java.util.Arrays.fill(m, 0f);
            float f = 1f / (float)Math.tan(Math.toRadians(fovYDeg) * 0.5f);
            m[0] = f / aspect;
            m[5] = f;
            m[10] = (far + near) / (near - far);
            m[11] = -1f;
            m[14] = 2f * far * near / (near - far);
            return this;
        }
        public Mat4 lookAt(Vec3 eye, Vec3 center, Vec3 up){
            Vec3 f = center.copy().sub(eye).norm();          // forward
            Vec3 r = f.cross(up).norm();                      // right
            Vec3 u = r.cross(f).norm();                       // true up
            Mat4 v = new Mat4().identity();
            v.m[0] = r.x;  v.m[4] = r.y;  v.m[8]  = r.z;
            v.m[1] = u.x;  v.m[5] = u.y;  v.m[9]  = u.z;
            v.m[2] = -f.x; v.m[6] = -f.y; v.m[10] = -f.z;
            v.m[12] = -r.dot(eye);
            v.m[13] = -u.dot(eye);
            v.m[14] = f.dot(eye);
            System.arraycopy(v.m, 0, m, 0, 16);
            return this;
        }
    }

    // ------------------------- deterministic RNG -------------------------

    public static final class Rng {
        private long s;
        public Rng(long seed){ this.s = seed == 0 ? 0x9E3779B97F4A7C15L : seed; }
        public long nextLong(){
            s ^= s >> 12;
            s ^= s << 25;
            s ^= s >> 27;
            return s * 0x2545F4914F6CDD1DL;
        }
        public double nextDouble(){
            return (nextLong() >>> 11) * (1.0 / (1L << 53));
        }
        public double range(double lo, double hi){
            return lo + (hi - lo) * nextDouble();
        }
        public int nextInt(int bound){
            if(bound <= 0) throw new IllegalArgumentException("bound");
            return (int)(Long.remainderUnsigned(nextLong(), bound));
        }
    }
}
