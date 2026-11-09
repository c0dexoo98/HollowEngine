package com.hollow.core;

/** Unit quaternion (x,y,z,w). */
public final class Quat {
    public float x, y, z, w;

    public Quat(){ this(0,0,0,1); }
    public Quat(float x, float y, float z, float w){ this.x=x; this.y=y; this.z=z; this.w=w; }
    public Quat(Quat o){ this(o.x,o.y,o.z,o.w); }

    public Quat set(float x, float y, float z, float w){ this.x=x; this.y=y; this.z=z; this.w=w; return this; }
    public Quat set(Quat o){ return set(o.x,o.y,o.z,o.w); }
    public Quat identity(){ return set(0,0,0,1); }
    public Quat cpy(){ return new Quat(this); }

    public static Quat fromAxisAngle(float ax, float ay, float az, float deg){
        float rad = (float)Math.toRadians(deg);
        float s = (float)Math.sin(rad*0.5f);
        float l = (float)Math.sqrt(ax*ax+ay*ay+az*az);
        if(l < 1e-9f) return new Quat();
        return new Quat(ax/l*s, ay/l*s, az/l*s, (float)Math.cos(rad*0.5f));
    }
    public static Quat fromEuler(float pitch, float yaw, float roll){
        // degrees, order Y(yaw) X(pitch) Z(roll) — common game camera convention
        float hr=(float)Math.toRadians(roll)*0.5f, hp=(float)Math.toRadians(pitch)*0.5f, hy=(float)Math.toRadians(yaw)*0.5f;
        float cr=(float)Math.cos(hr), sr=(float)Math.sin(hr);
        float cp=(float)Math.cos(hp), sp=(float)Math.sin(hp);
        float cy=(float)Math.cos(hy), sy=(float)Math.sin(hy);
        return new Quat(
            sr*cp*cy - cr*sp*sy,
            cr*sp*cy + sr*cp*sy,
            cr*cp*sy - sr*sp*cy,
            cr*cp*cy + sr*sp*sy);
    }
    /** yaw (deg) around Y only */
    public static Quat fromYaw(float yawDeg){ return fromAxisAngle(0,1,0,yawDeg); }

    public Quat mul(Quat q){
        return set(
            w*q.x + x*q.w + y*q.z - z*q.y,
            w*q.y - x*q.z + y*q.w + z*q.x,
            w*q.z + x*q.y - y*q.x + z*q.w,
            w*q.w - x*q.x - y*q.y - z*q.z);
    }
    public Quat slerp(Quat target, float t){
        float c = x*target.x + y*target.y + z*target.z + w*target.w;
        float tx=target.x, ty=target.y, tz=target.z, tw=target.w;
        if(c < 0){ tx=-tx; ty=-ty; tz=-tz; tw=-tw; c=-c; }
        float s0, s1;
        if(c > 0.9995f){
            s0 = 1-t; s1 = t;
        } else {
            float th = (float)Math.acos(Math.min(1f,c));
            float st = (float)Math.sin(th);
            s0 = (float)Math.sin((1-t)*th)/st;
            s1 = (float)Math.sin(t*th)/st;
        }
        return set(x*s0+tx*s1, y*s0+ty*s1, z*s0+tz*s1, w*s0+tw*s1).norm();
    }
    public Quat norm(){
        float l2 = x*x+y*y+z*z+w*w;
        if(l2 > 1e-12f){
            float inv = 1f/(float)Math.sqrt(l2);
            x*=inv; y*=inv; z*=inv; w*=inv;
        }
        return this;
    }
    public Vec3 rotate(Vec3 v){
        // v' = q * v * q^-1 (optimized)
        float qx=x, qy=y, qz=z, qw=w;
        float ix = qw*v.x + qy*v.z - qz*v.y;
        float iy = qw*v.y + qz*v.x - qx*v.z;
        float iz = qw*v.z + qx*v.y - qy*v.x;
        float iw = -qx*v.x - qy*v.y - qz*v.z;
        return new Vec3(ix*qw + iw*-qx + iy*-qz - iz*-qy,
                        iy*qw + iw*-qy + iz*-qx - ix*-qz,
                        iz*qw + iw*-qz + ix*-qy - iy*-qx);
    }
    /** forward (-Z) direction of this rotation */
    public Vec3 forward(){ return rotate(new Vec3(0,0,-1)); }
    public Vec3 up(){ return rotate(new Vec3(0,1,0)); }
    public Vec3 right(){ return rotate(new Vec3(1,0,0)); }

    public float yawDeg(){
        // extract yaw from rotation matrix
        float m11 = 1 - 2*(y*y+z*z), m13 = 2*(x*z + w*y);
        return (float)Math.toDegrees(Math.atan2(-m13, m11));
    }
    public float pitchDeg(){
        float m23 = 2*(y*z + w*x);
        return (float)Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, -m23))));
    }
}
