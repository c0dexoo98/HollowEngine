package com.hollow.render;

/** All GLSL ES 3.00 shader sources for the forward PBR renderer.
 *
 *  Uniform groups (documented once here):
 *   - Per-frame camera:  uProj, uView (mat4)
 *   - Per-object:        uModel, uNormalMat (mat4), uUvScale, uBaseColor, uMetallic, uRoughness,
 *                        uEmissive, uAlphaCutoff, uUnlit, uDoubleSided
 *   - Lighting:          uSunDir (surface->sun), uSunColor (rgb*intensity), uAmbient (hemisphere sky),
 *                        uLightPos/Color/Dir/Param[12] (point/spot set), uLightCount
 *   - Shadows:           uLightMat (sun clip matrix), uShadowMap (depth tex), uShadowTexel, uSunShadow
 *   - Fog (exp2 height): uFogColor, uFogDensity, uFogHeightFalloff, uFogHeight
 *   - Skinning:          uSkinned, uJoints[64] (skin-space joint matrices)
 *
 *  Mesh vertex attribs (fixed layout):
 *   loc0 aPos vec3, loc1 aNormal vec3, loc2 aUV vec2, loc3 aJoint vec4, loc4 aWeight vec4
 */
public final class Shaders {
    private Shaders() {}

    // ===================================================================
    // Shadow depth pass (no color output; FBO has draw buffers = NONE)
    // ===================================================================
    public static final String DEPTH_VS = """
    #version 300 es
    precision highp float;
    layout(location=0) in vec3 aPos;
    layout(location=3) in vec4 aJoint;
    layout(location=4) in vec4 aWeight;
    uniform mat4 uLightMat;      // sun clip matrix (ortho proj * lookAt along -sunDir)
    uniform mat4 uModel;
    uniform int uSkinned;
    uniform mat4 uJoints[64];
    void main(){
        vec3 p = aPos;
        if(uSkinned > 0){
            mat4 skin = aWeight.x * uJoints[int(aJoint.x)] + aWeight.y * uJoints[int(aJoint.y)]
                      + aWeight.z * uJoints[int(aJoint.z)] + aWeight.w * uJoints[int(aJoint.w)];
            p = (skin * vec4(p, 1.0)).xyz;
        }
        gl_Position = uLightMat * (uModel * vec4(p, 1.0));
    }
    """;

    public static final String DEPTH_FS = """
    #version 300 es
    void main(){ /* depth only */ }
    """;

    // ===================================================================
    // PBR (Cook-Torrance GGX, metallic-roughness)
    // ===================================================================
    public static final String PBR_VS = """
    #version 300 es
    precision highp float;
    layout(location=0) in vec3 aPos;
    layout(location=1) in vec3 aNormal;
    layout(location=2) in vec2 aUV;
    layout(location=3) in vec4 aJoint;
    layout(location=4) in vec4 aWeight;
    uniform mat4 uProj;
    uniform mat4 uView;
    uniform mat4 uModel;
    uniform mat4 uNormalMat;     // inverse-transpose of model (upper 3x3 used)
    uniform mat4 uLightMat;
    uniform int uSkinned;
    uniform mat4 uJoints[64];
    uniform float uUvScale;
    uniform float uNormalOffset; // world-space normal offset to fight shadow acne
    out vec3 vWorld;
    out vec3 vNormal;
    out vec2 vUV;
    out vec4 vShadowPos;
    void main(){
        vec3 p = aPos;
        vec3 n = aNormal;
        if(uSkinned > 0){
            mat4 skin = aWeight.x * uJoints[int(aJoint.x)] + aWeight.y * uJoints[int(aJoint.y)]
                      + aWeight.z * uJoints[int(aJoint.z)] + aWeight.w * uJoints[int(aJoint.w)];
            p = (skin * vec4(p, 1.0)).xyz;
            n = (skin * vec4(n, 0.0)).xyz;
        }
        vec4 wp4 = uModel * vec4(p, 1.0);
        vWorld = wp4.xyz;
        vec3 wn = mat3(uNormalMat) * n;
        vNormal = length(wn) > 1e-5 ? normalize(wn) : vec3(0.0, 1.0, 0.0);
        vUV = aUV * uUvScale;
        vShadowPos = uLightMat * vec4(vWorld + vNormal * uNormalOffset, 1.0);
        gl_Position = uProj * uView * wp4;
    }
    """;

    public static final String PBR_FS = """
    #version 300 es
    precision highp float;
    precision highp int;
    in vec3 vWorld;
    in vec3 vNormal;
    in vec2 vUV;
    in vec4 vShadowPos;
    layout(location=0) out vec4 fragColor;

    uniform sampler2D uBaseTex;
    uniform sampler2D uShadowMap;
    uniform vec4 uBaseColor;
    uniform vec3 uEmissive;
    uniform float uMetallic;
    uniform float uRoughness;
    uniform float uAlphaCutoff;   // >0 = alpha-tested (discard)
    uniform int uUnlit;
    uniform int uDoubleSided;

    uniform vec3 uCamPos;
    uniform vec3 uAmbient;        // hemisphere sky ambient; ground = 0.5*uAmbient

    uniform vec3 uSunDir;         // surface -> sun (normalized)
    uniform vec3 uSunColor;       // rgb * intensity
    uniform int uSunShadow;
    uniform float uShadowTexel;

    uniform int uLightCount;
    uniform vec4 uLightPos[12];   // xyz = world pos
    uniform vec4 uLightColor[12]; // rgb = color * intensity
    uniform vec4 uLightDir[12];   // xyz = spot forward
    uniform vec4 uLightParam[12]; // x=range, y=innerCos, z=outerCos, w=isSpot

    uniform vec3 uFogColor;
    uniform float uFogDensity;
    uniform float uFogHeightFalloff;
    uniform float uFogHeight;

    const float PI = 3.14159265358979;

    float dGGX(float ndh, float a){
        float a2 = a * a;
        float d = ndh * ndh * (a2 - 1.0) + 1.0;
        return a2 / (PI * d * d + 1e-7);
    }
    float gSmith(float ndv, float ndl, float rough){
        float k = (rough + 1.0);
        k = k * k * 0.125;
        float gv = ndv / (ndv * (1.0 - k) + k);
        float gl = ndl / (ndl * (1.0 - k) + k);
        return gv * gl;
    }
    vec3 fSchlick(float u, vec3 f0){
        float f = 1.0 - u;
        float f5 = f * f * f * f * f;
        return f0 + (1.0 - f0) * f5;
    }
    vec3 pbrLight(vec3 N, vec3 V, vec3 L, vec3 radiance, vec3 albedo, vec3 f0, float rough, float metallic){
        vec3 H = normalize(V + L);
        float ndl = max(dot(N, L), 0.0);
        float ndv = max(dot(N, V), 1e-4);
        float ndh = max(dot(N, H), 0.0);
        if(ndl <= 0.0) return vec3(0.0);
        float D = dGGX(ndh, rough * rough);
        float G = gSmith(ndv, ndl, rough);
        vec3 F = fSchlick(max(dot(H, V), 0.0), f0);
        vec3 spec = (D * G * F) / (4.0 * ndv * ndl + 1e-4);
        vec3 kd = (1.0 - F) * (1.0 - metallic);
        return (kd * albedo / PI + spec) * radiance * ndl;
    }
    float sunShadow(vec3 N){
        if(uSunShadow == 0) return 1.0;
        vec3 sp = vShadowPos.xyz / max(vShadowPos.w, 1e-5);
        if(sp.x < 0.0 || sp.x > 1.0 || sp.y < 0.0 || sp.y > 1.0 || sp.z > 1.0) return 1.0;
        float bias = 0.0018 + 0.0035 * (1.0 - max(dot(N, uSunDir), 0.0));
        float s = 0.0;
        for(int dx = -1; dx <= 1; dx++){
            for(int dy = -1; dy <= 1; dy++){
                float d = texture(uShadowMap, sp.xy + vec2(float(dx), float(dy)) * uShadowTexel).r;
                s += (sp.z - bias > d) ? 0.0 : 1.0;
            }
        }
        return s / 9.0;
    }

    void main(){
        vec4 texel = texture(uBaseTex, vUV);
        vec3 albedo = texel.rgb * uBaseColor.rgb;
        float alpha = texel.a * uBaseColor.a;
        if(uAlphaCutoff > 0.0 && alpha < uAlphaCutoff) discard;

        vec3 N = normalize(vNormal);
        if(uDoubleSided == 1 && !gl_FrontFacing) N = -N;
        vec3 V = normalize(uCamPos - vWorld);

        if(uUnlit == 1){
            fragColor = vec4(albedo + uEmissive, alpha);
            return;
        }

        float rough = clamp(uRoughness, 0.045, 1.0);
        float metallic = clamp(uMetallic, 0.0, 1.0);
        vec3 f0 = mix(vec3(0.04), albedo, metallic);

        vec3 color = uEmissive;

        // --- directional sun (with PCF shadow) ---
        float shadow = sunShadow(N);
        if(max(dot(N, uSunDir), 0.0) > 0.0){
            color += pbrLight(N, V, uSunDir, uSunColor * shadow, albedo, f0, rough, metallic);
        }

        // --- point / spot lights (nearest set chosen per object on the CPU) ---
        for(int i = 0; i < 12; i++){
            if(i >= uLightCount) break;
            vec3 L = uLightPos[i].xyz - vWorld;
            float dist = length(L);
            if(dist >= uLightParam[i].x || dist < 1e-4) continue;
            L /= dist;
            float rng = max(uLightParam[i].x, 0.01);
            float att = clamp(1.0 - pow(dist / rng, 4.0), 0.0, 1.0);
            att *= att;
            att /= (dist * dist + 1.0);
            float spot = 1.0;
            if(uLightParam[i].w > 0.5){
                float cone = dot(-L, uLightDir[i].xyz);
                // innerCos < outerCos; 1 - smoothstep(inner..outer)
                spot = 1.0 - smoothstep(uLightParam[i].y, uLightParam[i].z, cone);
            }
            if(att * spot <= 0.0005) continue;
            color += pbrLight(N, V, L, uLightColor[i].rgb * att * spot, albedo, f0, rough, metallic);
        }

        // --- ambient hemisphere (IBL-lite) ---
        vec3 ambSky = uAmbient;
        vec3 ambGround = uAmbient * 0.5;
        vec3 amb = mix(ambGround, ambSky, 0.5 + 0.5 * N.y);
        color += amb * albedo;
        color += f0 * amb * (1.0 - rough) * 0.35;

        // --- exp2 height fog ---
        float dist = length(vWorld - uCamPos);
        float hf = exp(-max(vWorld.y - uFogHeight, 0.0) * uFogHeightFalloff);
        float f = uFogDensity * dist * hf;
        float fogF = 1.0 - exp(-f * f);
        color = mix(color, uFogColor, clamp(fogF, 0.0, 1.0));

        fragColor = vec4(color, alpha);
    }
    """;

    // ===================================================================
    // Water (path ends with ".water"): animated translucent plane
    // ===================================================================
    public static final String WATER_VS = """
    #version 300 es
    precision highp float;
    layout(location=0) in vec3 aPos;
    layout(location=1) in vec3 aNormal;   // replaced by analytic wave normal
    layout(location=2) in vec2 aUV;
    uniform mat4 uProj;
    uniform mat4 uView;
    uniform mat4 uModel;
    uniform mat4 uNormalMat;
    uniform float uTime;
    uniform float uWaveHeight;
    uniform float uUvScale;
    out vec3 vWorld;
    out vec3 vNormal;
    out vec2 vUV;
    void main(){
        vec3 p = aPos;
        float ph1 = p.x * 1.6 + p.z * 1.0;
        float ph2 = p.x * 0.7 - p.z * 1.4;
        float a1 = cos(ph1 + uTime * 2.2);
        float a2 = cos(ph2 + uTime * 1.4);
        p.y += (sin(ph1 + uTime * 2.2) * 0.6 + sin(ph2 + uTime * 1.4) * 0.4) * uWaveHeight;
        float dhdx = (a1 * 0.6 * 1.6 + a2 * 0.4 * 0.7) * uWaveHeight;
        float dhdz = (a1 * 0.6 * 1.0 - a2 * 0.4 * 1.4) * uWaveHeight;
        vec3 n = normalize(vec3(-dhdx, 1.0, -dhdz));
        vec4 wp4 = uModel * vec4(p, 1.0);
        vWorld = wp4.xyz;
        vNormal = normalize(mat3(uNormalMat) * n);
        vUV = aUV * uUvScale;
        gl_Position = uProj * uView * wp4;
    }
    """;

    public static final String WATER_FS = """
    #version 300 es
    precision highp float;
    precision highp int;
    in vec3 vWorld;
    in vec3 vNormal;
    in vec2 vUV;
    layout(location=0) out vec4 fragColor;
    uniform vec3 uDeepColor;    // material baseColorFactor.rgb
    uniform vec3 uSkyColor;     // environment sky horizon/top average
    uniform vec3 uSunDir;
    uniform vec3 uSunColor;
    uniform vec3 uCamPos;
    uniform vec3 uFogColor;
    uniform float uFogDensity;
    uniform float uFogHeightFalloff;
    uniform float uFogHeight;
    void main(){
        vec3 N = normalize(vNormal);
        if(!gl_FrontFacing) N = -N;
        vec3 V = normalize(uCamPos - vWorld);
        float fres = pow(1.0 - max(dot(N, V), 0.0), 3.0);
        vec3 col = mix(uDeepColor, uSkyColor, clamp(fres * 0.85 + 0.08, 0.0, 1.0));
        vec3 H = normalize(V + uSunDir);
        col += uSunColor * pow(max(dot(N, H), 0.0), 160.0) * 1.2;
        float dist = length(vWorld - uCamPos);
        float hf = exp(-max(vWorld.y - uFogHeight, 0.0) * uFogHeightFalloff);
        float f = uFogDensity * dist * hf;
        float fogF = 1.0 - exp(-f * f);
        col = mix(col, uFogColor, clamp(fogF, 0.0, 1.0));
        fragColor = vec4(col, 0.8);
    }
    """;

    // ===================================================================
    // Procedural sky (fullscreen triangle, depth == far)
    // ===================================================================
    public static final String SKY_VS = """
    #version 300 es
    precision highp float;
    out vec2 vUV;
    void main(){
        vec2 p = vec2(float((gl_VertexID & 1) << 2) - 1.0, float((gl_VertexID & 2) << 1) - 1.0);
        vUV = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.99999, 1.0);   // depth ~= far; drawn with LEQUAL
    }
    """;

    public static final String SKY_FS = """
    #version 300 es
    precision highp float;
    precision highp int;
    in vec2 vUV;
    layout(location=0) out vec4 fragColor;
    uniform mat4 uInvViewProj;
    uniform vec3 uCamPos;
    uniform vec3 uTop;
    uniform vec3 uHorizon;
    uniform vec3 uMoonDir;      // normalized direction toward the moon
    uniform vec3 uMoonColor;
    uniform float uStarDensity; // 0..1
    uniform float uTime;

    float hash13(vec3 p){
        p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
        p *= 17.0;
        return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
    }

    void main(){
        vec2 ndc = vUV * 2.0 - 1.0;
        vec4 wp = uInvViewProj * vec4(ndc, 1.0, 1.0);
        vec3 d = normalize(wp.xyz / max(wp.w, 1e-5) - uCamPos);
        float h = d.y;

        vec3 sky = mix(uHorizon, uTop, pow(clamp(h, 0.0, 1.0), 0.55));
        if(h < 0.0) sky = mix(uHorizon, uHorizon * 0.25, clamp(-h * 2.5, 0.0, 1.0));
        sky += uHorizon * pow(1.0 - abs(h), 8.0) * 0.3;   // horizon glow

        // star field: hash grid over the view direction
        if(uStarDensity > 0.001 && h > -0.05){
            vec3 sp = d * 55.0;
            vec3 cell = floor(sp);
            float hs = hash13(cell);
            float thr = 1.0 - uStarDensity * 0.05;
            if(hs > thr){
                vec3 f = fract(sp) - 0.5;
                float sd = length(f);
                float tw = 0.55 + 0.45 * sin(uTime * 2.5 + hs * 97.0);
                float star = (1.0 - smoothstep(0.0, 0.18, sd)) * tw * (hs - thr) / max(1.0 - thr, 1e-4);
                sky += vec3(star * 0.85);
            }
        }

        // moon: disc + soft halo
        float md = dot(d, uMoonDir);
        float disc = smoothstep(0.99930, 0.99965, md);
        float halo = pow(max(md, 0.0), 900.0) * 0.9 + pow(max(md, 0.0), 120.0) * 0.15;
        sky += uMoonColor * (disc * 1.2 + halo);

        fragColor = vec4(sky, 1.0);
    }
    """;

    // ===================================================================
    // Camera-billboarded / axis-aligned sprite quad (4 verts, gl_VertexID)
    // ===================================================================
    public static final String SPRITE_VS = """
    #version 300 es
    precision highp float;
    uniform mat4 uProj;
    uniform mat4 uView;
    uniform vec3 uCenter;
    uniform vec3 uRight;
    uniform vec3 uUp;
    uniform vec2 uSize;
    uniform vec4 uUV;       // u0, v0, u1, v1
    out vec2 vUV;
    void main(){
        vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));  // (0,0)(1,0)(0,1)(1,1)
        vec3 wp = uCenter + (c.x - 0.5) * uSize.x * uRight + (0.5 - c.y) * uSize.y * uUp;
        vUV = mix(uUV.xy, uUV.zw, c);
        gl_Position = uProj * uView * vec4(wp, 1.0);
    }
    """;

    public static final String SPRITE_FS = """
    #version 300 es
    precision highp float;
    precision highp int;
    in vec2 vUV;
    layout(location=0) out vec4 fragColor;
    uniform sampler2D uTex;
    uniform vec4 uColor;
    void main(){
        fragColor = texture(uTex, vUV) * uColor;
    }
    """;

    // ===================================================================
    // Particles: GL_POINTS, interleaved [x,y,z,size,r,g,b,a]
    // ===================================================================
    public static final String PARTICLE_VS = """
    #version 300 es
    precision highp float;
    layout(location=0) in vec3 aPos;
    layout(location=1) in float aSize;
    layout(location=2) in vec4 aColor;
    uniform mat4 uProj;
    uniform mat4 uView;
    uniform float uPointScale;   // 0.5 * renderHeight * proj.m[5]
    out vec4 vColor;
    void main(){
        gl_Position = uProj * (uView * vec4(aPos, 1.0));
        gl_PointSize = clamp(uPointScale * aSize / max(gl_Position.w, 0.0001), 1.0, 512.0);
        vColor = aColor;
    }
    """;

    public static final String PARTICLE_FS = """
    #version 300 es
    precision highp float;
    precision highp int;
    in vec4 vColor;
    layout(location=0) out vec4 fragColor;
    void main(){
        vec2 c = gl_PointCoord * 2.0 - 1.0;
        float r2 = dot(c, c);
        if(r2 > 1.0) discard;
        float fall = 1.0 - smoothstep(0.4, 1.0, r2);   // round soft falloff
        fragColor = vec4(vColor.rgb, vColor.a * fall);
    }
    """;

    // ===================================================================
    // Debug lines (flat, depth-tested)
    // ===================================================================
    public static final String FLAT_VS = """
    #version 300 es
    precision highp float;
    layout(location=0) in vec3 aPos;
    layout(location=1) in vec4 aColor;
    uniform mat4 uProj;
    uniform mat4 uView;
    out vec4 vColor;
    void main(){
        vColor = aColor;
        gl_Position = uProj * (uView * vec4(aPos, 1.0));
    }
    """;

    public static final String FLAT_FS = """
    #version 300 es
    precision highp float;
    in vec4 vColor;
    layout(location=0) out vec4 fragColor;
    void main(){ fragColor = vColor; }
    """;

    // ===================================================================
    // UI quads (ortho pixels, origin top-left, standard alpha blend)
    // ===================================================================
    public static final String UI_VS = """
    #version 300 es
    precision highp float;
    layout(location=0) in vec2 aPos;
    layout(location=1) in vec2 aUV;
    layout(location=2) in vec4 aColor;
    uniform mat4 uOrtho;    // ortho(0,w,h,0) — y down
    out vec2 vUV;
    out vec4 vColor;
    void main(){
        vUV = aUV;
        vColor = aColor;
        gl_Position = uOrtho * vec4(aPos, 0.0, 1.0);
    }
    """;

    public static final String UI_FS = """
    #version 300 es
    precision highp float;
    precision highp int;
    in vec2 vUV;
    in vec4 vColor;
    layout(location=0) out vec4 fragColor;
    uniform sampler2D uTex;
    void main(){
        fragColor = texture(uTex, vUV) * vColor;
    }
    """;

    // ===================================================================
    // Post chain: fullscreen triangle VS shared by bright/blur/composite
    // ===================================================================
    public static final String POST_VS = """
    #version 300 es
    precision highp float;
    out vec2 vUV;
    void main(){
        vec2 p = vec2(float((gl_VertexID & 1) << 2) - 1.0, float((gl_VertexID & 2) << 1) - 1.0);
        vUV = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }
    """;

    public static final String BRIGHT_FS = """
    #version 300 es
    precision highp float;
    precision highp int;
    in vec2 vUV;
    layout(location=0) out vec4 fragColor;
    uniform sampler2D uTex;
    uniform float uThreshold;
    void main(){
        vec3 c = texture(uTex, vUV).rgb;
        float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
        float k = max(l - uThreshold, 0.0) / max(l, 1e-4);
        fragColor = vec4(c * k, 1.0);
    }
    """;

    public static final String BLUR_FS = """
    #version 300 es
    precision highp float;
    precision highp int;
    in vec2 vUV;
    layout(location=0) out vec4 fragColor;
    uniform sampler2D uTex;
    uniform vec2 uDir;    // texelSize * blur direction
    void main(){
        vec3 c = texture(uTex, vUV).rgb * 0.227027;
        c += (texture(uTex, vUV + uDir        ).rgb + texture(uTex, vUV - uDir        ).rgb) * 0.1945946;
        c += (texture(uTex, vUV + uDir * 2.0  ).rgb + texture(uTex, vUV - uDir * 2.0  ).rgb) * 0.1216216;
        c += (texture(uTex, vUV + uDir * 3.0  ).rgb + texture(uTex, vUV - uDir * 3.0  ).rgb) * 0.0540540;
        c += (texture(uTex, vUV + uDir * 4.0  ).rgb + texture(uTex, vUV - uDir * 4.0  ).rgb) * 0.0162160;
        fragColor = vec4(c, 1.0);
    }
    """;

    /** Composite: CA + FXAA-lite on HDR scene, + bloom, ACES tonemap, exposure, lift/gamma/gain,
     *  vignette, animated film grain. */
    public static final String COMPOSITE_FS = """
    #version 300 es
    precision highp float;
    precision highp int;
    in vec2 vUV;
    layout(location=0) out vec4 fragColor;
    uniform sampler2D uScene;
    uniform sampler2D uBloom;
    uniform vec2 uTexel;          // 1 / scene render size
    uniform float uBloomStrength;
    uniform float uExposure;
    uniform float uTime;
    uniform float uGrain;         // amplitude, 0 = off
    uniform float uVignette;      // strength, 0 = off
    uniform float uCA;            // chromatic aberration 0..1, 0 = off
    uniform int uFxaa;            // 0/1
    uniform vec3 uLift;
    uniform vec3 uGain;
    uniform float uGamma;

    vec3 fetch(vec2 uv){
        vec3 c;
        if(uCA > 0.001){
            vec2 dir = uv - 0.5;
            vec2 off = dir * dot(dir, dir) * uCA * 0.15;
            c.r = texture(uScene, uv + off).r;
            c.g = texture(uScene, uv).g;
            c.b = texture(uScene, uv - off).b;
        } else {
            c = texture(uScene, uv).rgb;
        }
        c += texture(uBloom, uv).rgb * uBloomStrength;
        return c;
    }

    float luma(vec3 c){ return dot(c, vec3(0.299, 0.587, 0.114)); }

    vec3 aces(vec3 x){
        return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
    }

    float hash12(vec2 p){
        vec3 p3 = fract(vec3(p.xyx) * 0.1031);
        p3 += dot(p3, p3.yzx + 33.33);
        return fract((p3.x + p3.y) * p3.z);
    }

    void main(){
        vec3 c;
        if(uFxaa == 1){
            // FXAA 3.11 console-lite
            vec2 px = uTexel;
            vec3 rgbM  = fetch(vUV);
            vec3 rgbNW = fetch(vUV + px * vec2(-1.0, -1.0));
            vec3 rgbNE = fetch(vUV + px * vec2( 1.0, -1.0));
            vec3 rgbSW = fetch(vUV + px * vec2(-1.0,  1.0));
            vec3 rgbSE = fetch(vUV + px * vec2( 1.0,  1.0));
            float lNW = luma(rgbNW), lNE = luma(rgbNE), lSW = luma(rgbSW), lSE = luma(rgbSE), lM = luma(rgbM);
            float lMin = min(lM, min(min(lNW, lNE), min(lSW, lSE)));
            float lMax = max(lM, max(max(lNW, lNE), max(lSW, lSE)));
            vec2 dir = vec2(-((lNW + lNE) - (lSW + lSE)), ((lNW + lSW) - (lNE + lSE)));
            float dirReduce = max((lNW + lNE + lSW + lSE) * 0.03125, 0.0078125);
            float rcpMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
            dir = clamp(dir * rcpMin, vec2(-8.0), vec2(8.0)) * px;
            vec3 rgbA = 0.5 * (fetch(vUV + dir * (1.0 / 3.0 - 0.5)) + fetch(vUV + dir * (2.0 / 3.0 - 0.5)));
            vec3 rgbB = rgbA * 0.5 + 0.25 * (fetch(vUV + dir * -0.5) + fetch(vUV + dir * 0.5));
            float lB = luma(rgbB);
            c = (lB < lMin || lB > lMax) ? rgbA : rgbB;
        } else {
            c = fetch(vUV);
        }

        c = aces(c * uExposure);

        // color grade (lift/gamma/gain; neutral = 0/1/1)
        c = clamp(c * uGain + uLift, 0.0, 1.0);
        c = pow(c, vec3(1.0 / max(uGamma, 0.01)));

        if(uVignette > 0.001){
            float d = length(vUV - 0.5) * 1.414;
            c *= 1.0 - uVignette * smoothstep(0.55, 1.25, d);
        }

        if(uGrain > 0.001){
            float n = hash12(gl_FragCoord.xy + vec2(fract(uTime * 13.73) * 913.0, fract(uTime * 7.31) * 541.0));
            c += (n - 0.5) * uGrain;
        }

        fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
    }
    """;
}
