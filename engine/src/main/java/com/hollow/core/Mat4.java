package com.hollow.core;

/** 4x4 column-major matrix (OpenGL layout). */
public final class Mat4 {
    public final float[] m = new float[16];

    public Mat4 identity(){
        java.util.Arrays.fill(m, 0f);
        m[0]=m[5]=m[10]=m[15]=1f;
        return this;
    }
    public Mat4(){ identity(); }
    public Mat4 cpy(){ Mat4 r = new Mat4(); System.arraycopy(m,0,r.m,0,16); return r; }
    public Mat4 set(Mat4 o){ System.arraycopy(o.m,0,m,0,16); return this; }

    public static Mat4 perspective(float fovYDeg, float aspect, float near, float far){
        Mat4 r = new Mat4();
        java.util.Arrays.fill(r.m, 0f);
        float f = 1f/(float)Math.tan(Math.toRadians(fovYDeg)*0.5f);
        r.m[0] = f/aspect; r.m[5] = f;
        r.m[10] = (far+near)/(near-far); r.m[11] = -1f;
        r.m[14] = 2f*far*near/(near-far);
        return r;
    }
    public static Mat4 ortho(float left, float right, float bottom, float top, float near, float far){
        Mat4 r = new Mat4();
        java.util.Arrays.fill(r.m, 0f);
        r.m[0] = 2f/(right-left); r.m[5] = 2f/(top-bottom);
        r.m[10] = -2f/(far-near); r.m[15] = 1f;
        r.m[12] = -(right+left)/(right-left);
        r.m[13] = -(top+bottom)/(top-bottom);
        r.m[14] = -(far+near)/(far-near);
        return r;
    }
    public static Mat4 lookAt(Vec3 eye, Vec3 target, Vec3 up){
        Vec3 f = target.cpy().sub(eye).norm();          // forward
        Vec3 s = f.cpy().cross(up).norm();              // right
        Vec3 u = s.cpy().cross(f);                      // true up
        Mat4 r = new Mat4();
        r.m[0]=s.x;  r.m[4]=s.y;  r.m[8]=s.z;
        r.m[1]=u.x;  r.m[5]=u.y;  r.m[9]=u.z;
        r.m[2]=-f.x; r.m[6]=-f.y; r.m[10]=-f.z;
        r.m[3]=0; r.m[7]=0; r.m[11]=0;
        r.m[12] = -s.dot(eye); r.m[13] = -u.dot(eye); r.m[14] = f.dot(eye);
        r.m[15] = 1;
        return r;
    }
    public static Mat4 trs(Vec3 pos, Quat rot, Vec3 scale){
        Mat4 r = new Mat4();
        float x=rot.x, y=rot.y, z=rot.z, w=rot.w;
        float x2=x+x, y2=y+y, z2=z+z;
        float xx=x*x2, xy=x*y2, xz=x*z2, yy=y*y2, yz=y*z2, zz=z*z2;
        float wx=w*x2, wy=w*y2, wz=w*z2;
        float sx=scale.x, sy=scale.y, sz=scale.z;
        r.m[0] = (1-(yy+zz))*sx; r.m[1] = (xy+wz)*sx;       r.m[2]  = (xz-wy)*sx;       r.m[3]=0;
        r.m[4] = (xy-wz)*sy;     r.m[5] = (1-(xx+zz))*sy;   r.m[6]  = (yz+wx)*sy;       r.m[7]=0;
        r.m[8] = (xz+wy)*sz;     r.m[9] = (yz-wx)*sz;       r.m[10] = (1-(xx+yy))*sz;   r.m[11]=0;
        r.m[12] = pos.x;         r.m[13] = pos.y;           r.m[14] = pos.z;            r.m[15]=1;
        return r;
    }
    public Mat4 mul(Mat4 o){
        float[] a = this.m, b = o.m;
        float r00=a[0]*b[0]+a[1]*b[4]+a[2]*b[8]+a[3]*b[12];
        float r01=a[0]*b[1]+a[1]*b[5]+a[2]*b[9]+a[3]*b[13];
        float r02=a[0]*b[2]+a[1]*b[6]+a[2]*b[10]+a[3]*b[14];
        float r03=a[0]*b[3]+a[1]*b[7]+a[2]*b[11]+a[3]*b[15];
        float r10=a[4]*b[0]+a[5]*b[4]+a[6]*b[8]+a[7]*b[12];
        float r11=a[4]*b[1]+a[5]*b[5]+a[6]*b[9]+a[7]*b[13];
        float r12=a[4]*b[2]+a[5]*b[6]+a[6]*b[10]+a[7]*b[14];
        float r13=a[4]*b[3]+a[5]*b[7]+a[6]*b[11]+a[7]*b[15];
        float r20=a[8]*b[0]+a[9]*b[4]+a[10]*b[8]+a[11]*b[12];
        float r21=a[8]*b[1]+a[9]*b[5]+a[10]*b[9]+a[11]*b[13];
        float r22=a[8]*b[2]+a[9]*b[6]+a[10]*b[10]+a[11]*b[14];
        float r23=a[8]*b[3]+a[9]*b[7]+a[10]*b[11]+a[11]*b[15];
        float r30=a[12]*b[0]+a[13]*b[4]+a[14]*b[8]+a[15]*b[12];
        float r31=a[12]*b[1]+a[13]*b[5]+a[14]*b[9]+a[15]*b[13];
        float r32=a[12]*b[2]+a[13]*b[6]+a[14]*b[10]+a[15]*b[14];
        float r33=a[12]*b[3]+a[13]*b[7]+a[14]*b[11]+a[15]*b[15];
        m[0]=r00; m[1]=r01; m[2]=r02; m[3]=r03;
        m[4]=r10; m[5]=r11; m[6]=r12; m[7]=r13;
        m[8]=r20; m[9]=r21; m[10]=r22; m[11]=r23;
        m[12]=r30; m[13]=r31; m[14]=r32; m[15]=r33;
        return this;
    }
    public Vec3 transformPoint(Vec3 p){
        float x=p.x, y=p.y, z=p.z;
        float w = m[3]*x + m[7]*y + m[11]*z + m[15];
        return new Vec3(
            m[0]*x + m[4]*y + m[8]*z + m[12],
            m[1]*x + m[5]*y + m[9]*z + m[13],
            m[2]*x + m[6]*y + m[10]*z + m[14]).scale(w!=0 && w!=1 ? 1f/w : 1f);
    }
    public Vec3 transformDir(Vec3 v){
        float x=v.x, y=v.y, z=v.z;
        return new Vec3(m[0]*x + m[4]*y + m[8]*z,
                        m[1]*x + m[5]*y + m[9]*z,
                        m[2]*x + m[6]*y + m[10]*z);
    }
    /** for normals: inverse-transpose of upper 3x3, assumes uniform-ish scaling */
    public Mat4 normalMatrix(){
        Mat4 inv = invert();
        Mat4 r = new Mat4();
        r.m[0]=inv.m[0]; r.m[1]=inv.m[4]; r.m[2]=inv.m[8];
        r.m[4]=inv.m[1]; r.m[5]=inv.m[5]; r.m[6]=inv.m[9];
        r.m[8]=inv.m[2]; r.m[9]=inv.m[6]; r.m[10]=inv.m[10];
        return r;
    }
    public Mat4 invert(){
        float a00=m[0],a01=m[1],a02=m[2],a03=m[3];
        float a10=m[4],a11=m[5],a12=m[6],a13=m[7];
        float a20=m[8],a21=m[9],a22=m[10],a23=m[11];
        float a30=m[12],a31=m[13],a32=m[14],a33=m[15];
        float b00=a00*a11-a01*a10, b01=a00*a12-a02*a10, b02=a00*a13-a03*a10,
              b03=a01*a12-a02*a11, b04=a01*a13-a03*a11, b05=a02*a13-a03*a12,
              b06=a20*a31-a21*a30, b07=a20*a32-a22*a30, b08=a20*a33-a23*a30,
              b09=a21*a32-a22*a31, b10=a21*a33-a23*a31, b11=a22*a33-a23*a32;
        float det = b00*b11 - b01*b10 + b02*b09 + b03*b08 - b04*b07 + b05*b06;
        if(Math.abs(det) < 1e-12f){ return identity(); }
        float inv = 1f/det;
        Mat4 r = new Mat4();
        r.m[0]=(a11*b11-a12*b10+a13*b09)*inv;
        r.m[1]=(a02*b10-a01*b11-a03*b09)*inv;
        r.m[2]=(a31*b05-a32*b04+a33*b03)*inv;
        r.m[3]=(a22*b04-a21*b05-a23*b03)*inv;
        r.m[4]=(a12*b08-a10*b11-a13*b07)*inv;
        r.m[5]=(a00*b11-a02*b08+a03*b07)*inv;
        r.m[6]=(a32*b02-a30*b05-a33*b01)*inv;
        r.m[7]=(a20*b05-a22*b02+a23*b01)*inv;
        r.m[8]=(a10*b10-a11*b08+a13*b06)*inv;
        r.m[9]=(a01*b08-a00*b10-a03*b06)*inv;
        r.m[10]=(a30*b04-a31*b02+a33*b00)*inv;
        r.m[11]=(a21*b02-a20*b04-a23*b00)*inv;
        r.m[12]=(a11*b07-a10*b09-a12*b06)*inv;
        r.m[13]=(a00*b09-a01*b07+a02*b06)*inv;
        r.m[14]=(a31*b01-a30*b03-a32*b00)*inv;
        r.m[15]=(a20*b03-a21*b01+a22*b00)*inv;
        return r;
    }
    public Vec3 translation(){ return new Vec3(m[12], m[13], m[14]); }
    public static Mat4 translation(Vec3 p){
        Mat4 r = new Mat4(); r.m[12]=p.x; r.m[13]=p.y; r.m[14]=p.z; return r;
    }
}
