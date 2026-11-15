package com.hollow.world;

import com.hollow.core.MathX;
import com.hollow.core.Vec3;

import java.util.ArrayList;
import java.util.List;

/** AAA-02 — ocean / large water surface (HollowEngine 4.0).
 *  Sum of Gerstner wave trains: each wave displaces points horizontally as well as
 *  vertically, producing sharp crests instead of pure sine rolls.
 *
 *  Gameplay coupling: heightAt()/normalAt() for visuals and wake effects,
 *  submersionAt()/buoyancyAccel() drive floating objects (crates, boats, ragdolls).
 */
public final class Ocean {
    public static final float G = 9.81f;

    public static final class Wave {
        public float dirX = 1f, dirZ = 0f;    // normalized travel direction
        public float wavelength = 12f;        // meters
        public float steepness = 0.25f;       // 0 = flat sine .. 1 = pinch (before loop)
        public float amplitude = 0.5f;        // meters
        public Wave(float dirDeg, float wavelength, float steepness, float amplitude){
            float rad = (float)Math.toRadians(dirDeg);
            this.dirX = (float)Math.cos(rad);
            this.dirZ = (float)Math.sin(rad);
            this.wavelength = Math.max(0.5f, wavelength);
            this.steepness = MathX.clamp(steepness, 0f, 0.95f);
            this.amplitude = Math.max(0f, amplitude);
        }
        float k(){ return (float)(2 * Math.PI / wavelength); }
        float omega(){ return (float)Math.sqrt(G * k()); }    // deep-water dispersion
    }

    public final List<Wave> waves = new ArrayList<>();
    public float level = 0f;                  // sea level (world y)
    public float flowSpeed = 1f;              // global time multiplier (currents)

    public Ocean(){                           // plausible default sea (4 trains)
        addWave(0f,   28f, 0.18f, 0.9f);
        addWave(38f,  14f, 0.22f, 0.45f);
        addWave(120f,  7f, 0.25f, 0.22f);
        addWave(210f,  3.5f, 0.28f, 0.10f);
    }

    public void addWave(float dirDeg, float wavelength, float steepness, float amplitude){
        waves.add(new Wave(dirDeg, wavelength, steepness, amplitude));
    }
    public void clear(){ waves.clear(); }

    /** Gerstner displacement at world (x,z), time t. Returns height (y) only —
     *  horizontal displacement is folded into the height sum iteratively (2 passes),
     *  which is the standard cheap approximation for gameplay-scale seas. */
    public float heightAt(float x, float z, float t){
        float y = 0f;
        for(int pass = 0; pass < 2; pass++){
            float sx = x, sz = z;
            if(pass == 1){
                // disperse the sample point backwards by the summed horizontal offsets
                for(Wave w : waves){
                    float k = w.k(), om = w.omega() * flowSpeed;
                    float qa = w.steepness / (k * w.amplitude * waves.size() + 1e-5f);
                    float a = w.amplitude;
                    float phase = k * (w.dirX * sx + w.dirZ * sz) - om * t;
                    sx -= qa * w.dirX * a * (float)Math.cos(phase);
                    sz -= qa * w.dirZ * a * (float)Math.cos(phase);
                }
            }
            y = 0f;
            for(Wave w : waves){
                float k = w.k(), om = w.omega() * flowSpeed;
                float phase = k * (w.dirX * sx + w.dirZ * sz) - om * t;
                y += w.amplitude * (float)Math.sin(phase);
            }
        }
        return level + y;
    }

    public Vec3 normalAt(float x, float z, float t, Vec3 out){
        float e = 0.35f;
        float hl = heightAt(x - e, z, t), hr = heightAt(x + e, z, t);
        float hd = heightAt(x, z - e, t), hu = heightAt(x, z + e, t);
        out.set(hl - hr, 2f * e, hd - hu).norm();
        return out;
    }

    /** crest whiteness 0..1 — highest waves approach the pinch limit */
    public float foamAt(float x, float z, float t){
        float h = heightAt(x, z, t) - level;
        float ampSum = 0f;
        for(Wave w : waves) ampSum += w.amplitude;
        return MathX.clamp((h / Math.max(1e-4f, ampSum) - 0.55f) / 0.45f, 0f, 1f);
    }

    // ---------------- buoyancy (gameplay physics hook) ----------------
    /** 0 = fully out of water .. 1 = fully submerged at draft meters under the surface */
    public float submersionAt(float y, float x, float z, float t, float draft){
        float surface = heightAt(x, z, t);
        return MathX.clamp((surface - y) / Math.max(0.05f, draft), 0f, 1f);
    }
    /** upward acceleration (m/s²) for a floating body — exceeds g at full submersion so
     *  objects bob to the surface; the caller adds its own drag (linear damping). */
    public float buoyancyAccel(float submersion, float buoyancyFactor){
        return submersion * G * Math.max(0.1f, buoyancyFactor);
    }
    /** one-step float integration helper for simple bodies (no rotation). */
    public void floatBody(Body b, float t, float dt, float buoyancyFactor, float waterDrag){
        float sub = submersionAt(b.y, b.x, b.z, t, b.draft);
        b.vy += (buoyancyAccel(sub, buoyancyFactor) - G) * dt;   // gravity applied inside
        if(sub > 0f){
            float damp = Math.max(0f, 1f - waterDrag * dt);
            b.vx *= damp; b.vy *= damp; b.vz *= damp;
        }
        b.x += b.vx * dt; b.y += b.vy * dt; b.z += b.vz * dt;
    }
    public static final class Body {
        public float x, y, z, vx, vy, vz, draft = 0.6f;
    }
}
