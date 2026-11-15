package com.hollow.world;

import com.hollow.core.MathX;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Noise;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** v2.5 environment batch — ENV-013/017/018/019/020.
 *  CPU-state components + math, deterministic, headless-testable; renderer reads
 *  the public state each frame. */
public final class EnvFeatures {
    private EnvFeatures() {}

    // =====================================================================
    // ENV-013 — warm rooms (fake temperature drives steam/condensation)
    // =====================================================================
    /** Temperature volume: a room with a target temperature. The world temperature
     *  at any point blends all overlapping volumes; steam shimmer / condensation
     *  FX sample `condensation(pos)` — warm humid rooms steam up cold mirrors. */
    public static final class TemperatureVolume extends Component {
        public float[] boundsMin = { -4, 0, -4 };
        public float[] boundsMax = {  4, 3,  4 };
        public float targetTemp = 38f;          // °C inside the volume
        public float humidity = 0.85f;          // 0..1
        public float outsideTemp = 12f;
        public float blendSpeed = 0.8f;         // temp approaches target over time
        public float currentTemp = 12f;
        public int visitors = 0;
        private static final Noise NZ = new Noise(0x7EA);

        public boolean contains(Vec3 p){
            return p.x >= boundsMin[0] && p.x <= boundsMax[0]
                && p.y >= boundsMin[1] && p.y <= boundsMax[1]
                && p.z >= boundsMin[2] && p.z <= boundsMax[2];
        }

        @Override public void onUpdate(float dt){
            currentTemp = MathX.approach(currentTemp, targetTemp, blendSpeed * dt);
            Entity host = entity;
            if(host != null && contains(host.worldPosition())) visitors++;
        }

        /** steam intensity 0..1 at a world point (hot + humid + noise shimmer). */
        public float steamAt(Vec3 p, float time){
            if(!contains(p)) return 0f;
            float heat = MathX.clamp((currentTemp - outsideTemp) / 30f, 0, 1);
            float shimmer = 0.5f + 0.5f * NZ.perlin2(p.x * 0.9f + time * 0.7f, p.z * 0.9f - time * 0.5f);
            return MathX.clamp(heat * humidity * (0.6f + 0.4f * shimmer), 0, 1);
        }

        /** condensation on cold glass in a warm humid room. */
        public float condensation(float surfaceTemp){
            if(surfaceTemp >= currentTemp) return 0f;
            float dT = currentTemp - surfaceTemp;
            return MathX.clamp((dT / 20f) * humidity, 0, 1);
        }

        @Override public void save(JsonW w){
            w.obj(); w.kv("type","TemperatureVolume")
              .kvv("boundsMin",boundsMin[0],boundsMin[1],boundsMin[2])
              .kvv("boundsMax",boundsMax[0],boundsMax[1],boundsMax[2])
              .kv("targetTemp",targetTemp).kv("humidity",humidity)
              .kv("outsideTemp",outsideTemp).kv("blendSpeed",blendSpeed); w.endObj();
        }
        @Override public void load(JsonV n){
            com.hollow.core.JsonV min = n.node("boundsMin");
            com.hollow.core.JsonV max = n.node("boundsMax");
            if(min.map != null) boundsMin = new float[]{ min.flt("x",-4), min.flt("y",0), min.flt("z",-4) };
            if(max.map != null) boundsMax = new float[]{ max.flt("x",4), max.flt("y",3), max.flt("z",4) };
            targetTemp = n.flt("targetTemp", 38f); humidity = n.flt("humidity", 0.85f);
            outsideTemp = n.flt("outsideTemp", 12f); blendSpeed = n.flt("blendSpeed", 0.8f);
        }
    }

    // =====================================================================
    // ENV-017 — in-game video projector (render-to-texture)
    // =====================================================================
    /** Projects a texture (render target / video frame) onto the world inside a
     *  cone. CPU side owns the cone math + projector screen surface mapping;
     *  the renderer supplies `FrameSource` frames and draws. */
    public static final class VideoProjector extends Component {
        public interface FrameSource { int[] frameArgb(); int frameWidth(); int frameHeight(); boolean playing(); }

        public FrameSource source = null;
        public float fovDeg = 42f;
        public float range = 12f;
        public float brightness = 1.6f;
        public String screenEntity = "";        // entity that displays the frame
        public boolean on = true;
        public float flicker = 0.06f;           // projector flicker amount
        public float currentFov = 42f;
        public long framesProjected = 0;
        private static final Noise NZ = new Noise(0x60);

        @Override public void onUpdate(float dt){
            if(!on) return;
            currentFov = fovDeg * (1f + flicker * NZ.perlin2(framesProjected * 0.1f, 5f));
            framesProjected++;
        }

        /** is a world point inside the projection cone (from entity position along
         *  entity forward)? */
        public boolean hits(Vec3 point){
            if(!on || entity == null || source == null || !source.playing()) return false;
            Vec3 origin = entity.worldPosition();
            Vec3 fwd = forward();
            Vec3 to = new Vec3(point.x - origin.x, point.y - origin.y, point.z - origin.z);
            float dist = to.len();
            if(dist > range || dist < 1e-4f) return false;
            float cosAngle = to.dot(fwd) / dist;
            return cosAngle >= (float)Math.cos(Math.toRadians(currentFov * 0.5f));
        }

        /** attenuation at a hit point (1 at cone axis → 0 at rim / max range). */
        public float brightnessAt(Vec3 point){
            if(!hits(point)) return 0f;
            Vec3 origin = entity != null ? entity.worldPosition() : new Vec3();
            Vec3 fwd = forward();
            Vec3 to = new Vec3(point.x - origin.x, point.y - origin.y, point.z - origin.z);
            float dist = Math.max(0.01f, to.len());
            float cosAngle = to.dot(fwd) / dist;
            float radial = (cosAngle - (float)Math.cos(Math.toRadians(currentFov * 0.5f)))
                         / (1f - (float)Math.cos(Math.toRadians(currentFov * 0.5f)));
            float falloff = 1f - dist / range;
            return MathX.clamp(brightness * Math.max(0, radial) * falloff * falloff, 0, 4);
        }

        Vec3 forward(){
            return entity != null ? entity.forward().scale(1f) : new Vec3(0, 0, -1);
        }

        @Override public void save(JsonW w){
            w.obj(); w.kv("type","VideoProjector").kv("fovDeg",fovDeg).kv("range",range)
              .kv("brightness",brightness).kv("screenEntity",screenEntity).kv("on",on)
              .kv("flicker",flicker); w.endObj();
        }
        @Override public void load(JsonV n){
            fovDeg = n.flt("fovDeg", 42f); range = n.flt("range", 12f);
            brightness = n.flt("brightness", 1.6f); screenEntity = n.str("screenEntity", "");
            on = n.bool("on", true); flicker = n.flt("flicker", 0.06f);
        }
    }

    // =====================================================================
    // ENV-018 — simple planar mirror
    // =====================================================================
    /** Reflects the scene camera across a plane: CPU side computes the mirrored
     *  camera transform + oblique clip hint; renderer draws the reflection pass. */
    public static final class PlanarMirror extends Component {
        public Vec3 normal = new Vec3(0, 1, 0);   // plane normal (normalized on attach)
        public float width = 4f, height = 3f;
        public float reflectionQuality = 0.7f;    // 0..1 (resolution scale)
        public boolean reflectLights = true;
        public boolean enabled = true;
        public long reflectionPasses = 0;

        public Vec3 planePoint(){ return entity != null ? entity.worldPosition() : new Vec3(); }

        /** mirror a camera position across the plane through P with normal N. */
        public Vec3 reflectPoint(Vec3 camPos){
            Vec3 p = planePoint();
            Vec3 n = norm(normal);
            Vec3 d = new Vec3(camPos.x - p.x, camPos.y - p.y, camPos.z - p.z);
            float dist = d.dot(n);
            return new Vec3(camPos.x - 2f * dist * n.x, camPos.y - 2f * dist * n.y, camPos.z - 2f * dist * n.z);
        }

        /** mirror a direction (no translation). */
        public Vec3 reflectDirection(Vec3 dir){
            Vec3 n = norm(normal);
            float dist = dir.dot(n);
            return new Vec3(dir.x - 2f * dist * n.x, dir.y - 2f * dist * n.y, dir.z - 2f * dist * n.z);
        }

        /** signed distance of a point to the mirror plane (negative = behind → skip). */
        public float sideOf(Vec3 p){
            Vec3 p0 = planePoint();
            return (p.x - p0.x) * normal.x + (p.y - p0.y) * normal.y + (p.z - p0.z) * normal.z;
        }

        /** renderer hook: call once per reflected frame. */
        public void passDrawn(){ reflectionPasses++; }

        Vec3 norm(Vec3 v){
            float l = v.len();
            return l > 1e-6f ? new Vec3(v.x / l, v.y / l, v.z / l) : new Vec3(0, 1, 0);
        }

        @Override public void save(JsonW w){
            w.obj(); w.kv("type","PlanarMirror")
              .kvv("normal",normal.x,normal.y,normal.z)
              .kv("width",width).kv("height",height)
              .kv("reflectionQuality",reflectionQuality).kv("reflectLights",reflectLights); w.endObj();
        }
        @Override public void load(JsonV n){
            com.hollow.core.JsonV nn = n.node("normal");
            if(nn.map != null) normal = new Vec3(nn.flt("x",0), nn.flt("y",1), nn.flt("z",0));
            width = n.flt("width", 4f); height = n.flt("height", 3f);
            reflectionQuality = n.flt("reflectionQuality", 0.7f);
            reflectLights = n.bool("reflectLights", true);
        }
    }

    // =====================================================================
    // ENV-019 — rain-covered windows (light refraction)
    // =====================================================================
    /** Glass pane state: rain streaks crawl down the window; the renderer refracts
     *  the scene behind by sampling per-pixel offsets from the streak field. */
    public static final class RainWindow extends Component {
        public float width = 2f, height = 2f;
        public float wetness = 0f;              // 0 dry .. 1 soaked (driven by Weather)
        public int streakCount = 40;
        public float refractionStrength = 0.045f;  // UV offset amplitude
        public float fogging = 0f;              // breath condensation 0..1

        public static final class Streak {
            public float x, y;                  // pane UV 0..1
            public float len, speed;
            public boolean active;
        }
        public final List<Streak> streaks = new ArrayList<>();
        private final Random rng = new Random(0x2718);

        void ensureStreaks(){
            while(streaks.size() < streakCount){
                Streak s = new Streak();
                s.active = false;
                streaks.add(s);
            }
        }

        /** weather feeds wetness each frame; streak spawn rate scales with it. */
        public void setWetness(float w, float dt){
            wetness = MathX.clamp(w, 0, 1);
            ensureStreaks();
            int wantActive = Math.round(streakCount * wetness);
            int active = 0;
            for(Streak s : streaks) if(s.active) active++;
            if(active < wantActive && rng.nextFloat() < 0.35f){
                for(Streak s : streaks){
                    if(!s.active){
                        s.active = true;
                        s.x = rng.nextFloat();
                        s.y = rng.nextFloat() * 0.4f;
                        s.len = 0.08f + rng.nextFloat() * 0.22f;
                        s.speed = 0.25f + rng.nextFloat() * 0.5f;
                        break;
                    }
                }
            }
        }

        @Override public void onUpdate(float dt){
            for(Streak s : streaks){
                if(!s.active) continue;
                s.y += s.speed * dt * wetness;
                if(s.y - s.len > 1f) s.active = false;
            }
        }

        /** refraction offset (dx, dy) at a pane UV for the shader. */
        public float[] refractAt(float u, float v){
            float off = refractionStrength * wetness;
            float dx = 0, dy = 0;
            for(Streak s : streaks){
                if(!s.active) continue;
                if(Math.abs(u - s.x) < 0.015f && v >= s.y - s.len && v <= s.y){
                    dx = off * (1f - Math.abs(u - s.x) / 0.015f);
                    dy = off * 0.6f;
                    break;
                }
            }
            return new float[]{ dx, dy };
        }

        public int activeStreaks(){ int n = 0; for(Streak s : streaks) if(s.active) n++; return n; }

        @Override public void save(JsonW w){
            w.obj(); w.kv("type","RainWindow").kv("width",width).kv("height",height)
              .kv("streakCount",streakCount).kv("refractionStrength",refractionStrength); w.endObj();
        }
        @Override public void load(JsonV n){
            width = n.flt("width", 2f); height = n.flt("height", 2f);
            streakCount = n.int32("streakCount", 40);
            refractionStrength = n.flt("refractionStrength", 0.045f);
        }
    }

    // =====================================================================
    // ENV-020 — water surface with sky reflection
    // =====================================================================
    /** Water plane state: two moving wave normals perturb the reflect vector; the
     *  sky function supplies the reflected color; fresnel mixes water tint in. */
    public static final class WaterSurface extends Component {
        public interface SkyColor { /** returns [r,g,b] 0..1 for a normalized direction */
            float[] sample(Vec3 dir); }

        public SkyColor sky = dir -> new float[]{ 0.45f, 0.62f, 0.85f };   // default clear sky
        public float waveHeight = 0.12f;
        public float waveLenA = 2.1f, waveSpeedA = 0.8f;
        public float waveLenB = 3.7f, waveSpeedB = -0.6f;
        public float waterTintR = 0.06f, waterTintG = 0.18f, waterTintB = 0.22f;
        public float fresnelPower = 4f;
        public float time = 0f;
        public boolean dirty = true;

        @Override public void onUpdate(float dt){
            time += dt;
        }

        /** wave height at a point (sum of two sines). */
        public float heightAt(float x, float z){
            return waveHeight * ((float)Math.sin(x * waveLenA + time * waveSpeedA)
                               + (float)Math.sin(z * waveLenB + time * waveSpeedB)) * 0.5f;
        }

        /** perturbed normal at a point (finite differences of heightAt). */
        public Vec3 normalAt(float x, float z, float e){
            float hL = heightAt(x - e, z), hR = heightAt(x + e, z);
            float hD = heightAt(x, z - e), hU = heightAt(x, z + e);
            return new Vec3(-(hR - hL) / (2 * e), 1f, -(hU - hD) / (2 * e)).norm();
        }

        /** final water color at a point for view direction `view` (surface → eye). */
        public float[] colorAt(float x, float z, Vec3 view){
            Vec3 n = normalAt(x, z, 0.05f);
            Vec3 r = reflect(view, n);
            r.y = Math.abs(r.y);                                    // never reflect below horizon
            float[] skyCol = sky.sample(r);
            float cosT = Math.max(0f, view.dot(new Vec3(0, 1, 0)));
            float fres = 0.02f + 0.98f * (float)Math.pow(1f - cosT, fresnelPower);
            return new float[]{
                skyCol[0] * fres + waterTintR * (1 - fres),
                skyCol[1] * fres + waterTintG * (1 - fres),
                skyCol[2] * fres + waterTintB * (1 - fres)
            };
        }

        static Vec3 reflect(Vec3 v, Vec3 n){
            float d = v.dot(n);
            return new Vec3(v.x - 2f * d * n.x, v.y - 2f * d * n.y, v.z - 2f * d * n.z).norm();
        }

        @Override public void save(JsonW w){
            w.obj(); w.kv("type","WaterSurface").kv("waveHeight",waveHeight)
              .kv("waveLenA",waveLenA).kv("waveSpeedA",waveSpeedA)
              .kv("waveLenB",waveLenB).kv("waveSpeedB",waveSpeedB)
              .kv("fresnelPower",fresnelPower); w.endObj();
        }
        @Override public void load(JsonV n){
            waveHeight = n.flt("waveHeight", 0.12f);
            waveLenA = n.flt("waveLenA", 2.1f); waveSpeedA = n.flt("waveSpeedA", 0.8f);
            waveLenB = n.flt("waveLenB", 3.7f); waveSpeedB = n.flt("waveSpeedB", -0.6f);
            fresnelPower = n.flt("fresnelPower", 4f);
        }
    }
}
