# HollowEngine — Integration SPEC (binding contract for all modules)

Engine: general-purpose, pure Java 17, zero external dependencies, Linux CLI-first workflow.
Repo root: this file. Packages under `engine/src/main/java/com/hollow/`.
Compile check (desktop, no GL): `JAVA_HOME=/home/z/my-project/tools/jdk-21.0.13+9; $JAVA_HOME/bin/javac -d /tmp/he_classes --release 17 $(find engine/src -name '*.java')`

## RULES (violations = integration failure)
1. Java 17 syntax only. No external libraries. No reflection hacks.
2. `android.*` imports ONLY inside `com.hollow.render`, `com.hollow.audio` (output impl), or `com.hollow.platform` if created. Everything else must compile and run on desktop JVM.
3. All JSON through `com.hollow.core.Json` (parse) / `com.hollow.core.JsonW` (write) / `JsonV` (typed read).
4. All math through `com.hollow.core` (Vec2/Vec3/Quat/Mat4/AABB/Color/MathX). Never add a second math type.
5. Logging only via `com.hollow.core.Log` (d/i/w/e).
6. No `System.out.print` anywhere.
7. Component JSON `type` field == class simple name (e.g. "Animator", "RigidBody").
8. Every component implements `save(JsonW)` / `load(JsonV)` symmetrically.
9. Components register factories into `world.componentRegistry` — provided by `com.hollow.game.EngineBootstrap.registerAll(World, EngineServices)`.
10. Fixed-timestep logic: components update in `onUpdate(float fixedDt)`; render reads state.

## Already done (DO NOT REWRITE — read them first)
- core: Log, Json, JsonW, JsonV, Vec2, Vec3, Quat, Mat4, AABB, Color, MathX (Rng, Curve), FileSystem, NativeFS, Time
- scene: Entity (transform hierarchy, worldMatrix, findChild, worldAABB), Component, World (spawn/destroy/update/findByTag/byComponent, blackboard, environment JSON), Scene (load/save JSON), MeshComponent, LightComponent (flicker), CameraComponent, SpriteComponent
- assets: MeshData (+.hvm binary), ImageData (+.hvt binary, mipChain), MaterialDef, Skeleton, AnimClip, ModelAsset, AudioClipData (+WAV decode), Atlas, ImageDecoder, OggDecoder hook, AssetDB (caches mesh/model/image/audio/atlas), GltfLoader (STUB — Agent A)

## EngineServices (com.hollow.game.EngineServices) — context passed everywhere
```java
public final class EngineServices {
    public final com.hollow.assets.AssetDB assets;
    public final com.hollow.core.FileSystem fs;
    public final com.hollow.audio.AudioEngine audio;    // Agent D
    public final com.hollow.script.VarTable vars;      // Agent D — global game vars
    public final com.hollow.game.EventBus events;       // Agent D — global event bus (fire/listen)
    public final com.hollow.vfx.ParticleLibrary particles; // Agent D
    public final com.hollow.cine.Sequencer sequencer;   // Agent D
    public final com.hollow.physics.PhysicsWorld physics; // Agent C
    public final com.hollow.ai.AISystem ai;             // Agent C
    public final com.hollow.anim.AnimationSystem anim;  // Agent C
    public final com.hollow.ui.UIManager ui;           // Agent D
    public final com.hollow.game.SaveSystem save;       // Agent D? -> framework; uses fs
    public final com.hollow.game.Localization i18n;     // framework
    public final com.hollow.game.InputActions input;    // framework
    public final com.hollow.core.Time time;
    public long frameSeed;                              // deterministic RNG seed per frame
}
```
(Exact fields are defined in game/EngineServices.java — read it. Agents may read all fields, never reassign.)

## Agent A — assets: GLTF + import pipeline + atlas + primitives
Implement in `com.hollow.assets`:
1. `GltfLoader.load(String path, FileSystem fs)` (replace stub): full glTF 2.0:
   - .gltf JSON (+ external .bin URIs, embedded `data:base64`), .glb binary container (magic 'glTF', JSON chunk + BIN chunk)
   - accessors: FLOAT/UNSIGNED_INT/UNSIGNED_SHORT/UNSIGNED_BYTE, normalized flags; bufferViews stride
   - meshes: attributes POSITION (required), NORMAL (compute if missing), TEXCOORD_0, JOINTS_0, WEIGHTS_0 (normalize weights), indices
   - materials: pbrMetallicRoughness (baseColorFactor/Texture, metallicFactor, roughnessFactor), normalTexture(+scale), emissiveTexture/Factor, alphaMode (OPAQUE/MASK/BLEND + alphaCutoff), doubleSided
   - nodes: TRS (matrix if present -> decompose), hierarchy, mesh index; scene root list (use scene[0].nodes, all roots chained under one synthetic root node if multiple)
   - skins: joints[] (order defines joint index), inverseBindMatrices (accessor float16 mat4 column-major)
   - animations: samplers (LINEAR, STEP, CUBICSPLINE), channels (translation/rotation/scale, cubic needs in/out tangents -> bake to LINEAR by resampling every 1/30s if cubic), clip.duration, loop=true
   - textures/images: DO NOT decode pixels. Store texture reference path in MaterialDef only when the image is a separate file (`uri` without data:). For embedded/buffer images, set texture path to null and material keeps factors (renderer falls back to factors). That keeps the loader desktop-AND-android-safe without image decoders.
   - Return `ModelAsset`. Deterministic: no HashMap iteration order dependency on geometry.
2. `HvmGen.java`: static generators returning MeshData: `box(w,h,d, centered)`, `plane(size, axisUp)`, `sphere(radius, segLat, segLon)`, `capsule(radius, height, seg)`, `cylinder(radius, height, seg)`, `gridTerrain(heights int[][], worldSize, heightScale)` (positions+y+normals computed from heightfield central differences, uv = x/z / size).
3. `AtlasPacker.java`: `static AtlasPackResult pack(List<String> pngPaths, ImageDecoder decoder, int maxSize)` — shelf/skyline packer, power-of-two canvas, 2px gutter, dedupe; result: ImageData canvas + map name->Region(px).
4. `Importer.java` (used by CLI): walk `raw/` -> `assets/`: `.png/.jpg` -> `.hvt` (+mips), `.wav` -> passthrough copy to assets, `.gltf/.glb` -> validate only (loaded at runtime), `.hvm/.hvt` -> passthrough. Generate `manifest.json` {imported:[{src,dst,type,md5? no—size}], atlas:{...}}. Skip unchanged (size+name match).
5. `DesktopImageDecoder.java`: ImageDecoder impl using `javax.imageio.ImageIO` (desktop only; runtime-android registers its own). `static void install()`.

## Agent B — render: GLES 3.0 PBR forward renderer
Classes in `com.hollow.render`. All GL via `android.opengl.GLES30`. Compile with android.jar on classpath (provided).
1. `GlObjects.java`: thin wrappers Shader(program compile+uniform/attrib lookup+error dump via Log), Vbo/Ibo/VertexArray, Texture2D (RGBA8 upload from ImageData with mips, TRILINEAR, CLAMP), FBO (color+depth), Cubemap.
2. `CameraData.java`: from Entity+CameraComponent: view (Mat4.lookAt inverse-free: use entity worldMatrix invert), proj (perspective fov/near/far), frustum planes + `cull(AABB)`.
3. `PbrRenderer.java` — the heart:
   - `init(int width, int height, RendererConfig cfg)`; `resize(w,h)`; `shutdown()`
   - `render(World world, Entity cameraEntity, EngineServices svc)`:
     a) shadow pass: directional light with castShadow -> FBO depth 2048² ortho fitting scene AABB (~lookAt along -lightDir), polygon offset, PCF 3x3 in shader
     b) main pass: render to HDR FBO (RGBA16F if EXT available else RGBA8): opaque (front-to-back, frustum culled), then MASK, then BLEND (back-to-front) with depth-write off
     c) lighting: gather all LightComponents; nearest ≤7 point/spot per object by distance + directional + ambient hemispheric from world.environment; headlamp: if world.blackboard.get("__headlamp")==Boolean.TRUE, add a headlamp spot at camera (used by games)
     d) sky pass: full-screen triangle, procedural: gradient + stars (hash noise) + moon disc + horizon glow, parameters from environment.sky {topColor, horizonColor, moonPos:[az,el], moonColor, starDensity, clouds? optional}
     e) particles: point sprites (Agent D provides ParticleSystem.getRenderData()); renderer only draws given arrays (see ParticleRender contract)
     f) debug lines: DebugDraw static queue (lines, spheres, text quads) drawn depth-tested
     g) post chain into default framebuffer: brightpass -> blur H/V (2 iterations) -> composite: ACES tonemap + exposure + color grade (lift/gamma/gain from environment) + vignette + film grain (hash, time) + chromatic aberration (strength cfg) + FXAA. All toggles in RendererConfig (quality LOW/MEDIUM/HIGH/ULTRA scales resolution factor 0.5/0.75/1.0/1.0 + toggles).
   - GLSL: ES 3.00 (`#version 300 es`), PBR = Cook-Torrance GGX (metallic-roughness), IBL-lite ambient (hemisphere gradient + optional irradiance from uniform), fog = exp2 height fog {color, density, heightFalloff}, alpha modes, doubleSided via disabling culling, uvScale.
   - Skinning: uniform mat4 uJoints[64]; vertex shader: if uSkinned>0, position = skin(pos) etc.
   - SpriteRenderer: textured quads (billboard: camera right/up axes; or axis-aligned for 2D with ortho flag).
   - WaterRenderer (simple): horizontal plane, sine vertex waves (2 gerstner-lite), fresnel mix of sky color + reflection color, alpha blend.
   - Terrain: reuse PBR path with grid mesh.
4. `RendererConfig.java`: {quality enum, width,height (logical), resolutionScale, bloom, fxaa, grain, vignette, chromaticAberration (0..1), shadowMapSize, maxLights, exposure, static fromJson}.
5. `DebugDraw.java`: `static line(Vec3 a, Vec3 b, Color c)`, `sphere(Vec3 c, float r, Color c)`, `box(AABB, Color)`, `text3D? skip`; `static void clear()` called by renderer after use. Queue consumed by renderer.
6. `GLTile2D? no`. Keep scope above.
Public surface exactly: `PbrRenderer.init/resize/shutdown/render/getStats()`, stats: {drawCalls, triangles, fpsEstimate}.
Do NOT instantiate GL calls on desktop: guard everything behind `init` which is only called on Android (CLI never calls render; but class must LOAD — no GL call in static init).

## Agent C — anim + physics + ai
`com.hollow.anim`:
1. `AnimationSystem`: registered Animator components; update order before AI/physics.
2. `Animator extends Component`: fields: modelPath, autoPlay, currentClip, crossfadeTime, speed, playing, loop; `play(String clip, float fade)`, `stop()`, `onUpdate(dt)`: sample clip channels -> local TRS per joint -> compute joint world matrices (Skeleton hierarchy) -> multiply inverseBind -> `float[] jointMatrices` (16*64) exposed for renderer; blends two clips during crossfade (lerp translations/scales, slerp rotations). If entity has no MeshComponent skin — still fine.
3. `StateMachine extends Component` (on top of Animator): JSON states {name, clip, speed, loop, transitions:[{to, conditions:[{var,op,value}|{event,name}]}]}, `fire(String event)`, default state. Condition evaluated against world.blackboard + entity-local vars (via VarTable API).
`com.hollow.physics`:
1. `PhysicsWorld`: static colliders registered from world (BoxCollider/SphereCollider/CapsuleCollider components); broadphase = simple grid or O(n) sweep (fine for <2k colliders); `update(dt)` integrates dynamic bodies (gravity, velocity damping), resolves vs statics; exposes `raycast(origin, dir, maxDist)` -> {hit, point, normal, entity, distance} (slab tests, capsule as segment-sphere); triggers: overlap events -> EventBus fire "trigger_enter"/"trigger_exit" with {zone, entity}.
2. Collider components: BoxCollider {size:[x,y,z], offset, isTrigger}, SphereCollider {radius, offset, isTrigger}, CapsuleCollider {radius, height, offset, isTrigger}. All save/load JSON.
3. `RigidBody extends Component`: {mass, velocity Vec3, gravityScale, drag, kinematic, bounciness} — sphere/point dynamics only (character uses controller).
4. `CharacterController extends Component`: {radius, height, stepHeight, slopeLimit, skinWidth}; API: `move(Vec3 displacement)` — collide-and-slide against statics, step-up, `isGrounded()`, `groundNormal()`, applies gravity when `useGravity`; capsule vs AABB/box/sphere resolution. This is critical quality — make it solid (no tunneling at 60Hz for speeds < 20 m/s).
`com.hollow.ai`:
1. `AISystem`: updates Agent components (after anim, before render); builds `NavGrid` from world static colliders on demand: grid cell 1m over world AABB, blocked cells from box/capsule footprints, `findPath(Vec3 from, Vec3 to)` A* 8-dir with corner-cut prevention, path smoothing (line-of-sight string pulling).
2. `Perception extends Component`: {sightRange, fovDeg, hearingRange, targetTag}; `canSee(Entity)`: range+fov+raycast occlusion; exposes `lastKnownTargetPos`, `visibleTarget`, `targetVisibleTime`; hearing: EventBus "noise" events add suspicion.
3. `Agent extends Component` (the AI brain driver): {treePath or inline tree, navmesh:NavGrid ref, speed, chaseSpeed, waypointLoop}; state exposed on blackboard of entity vars: "ai_state" (patrol/chase/search/attack), "target_dist". `onUpdate` ticks the behavior tree.
4. `BehaviorTree.java`: JSON format:
```json
{ "root": "seq1", "nodes": { "seq1": {"type":"sequence", "children":["sel1","act_attack"]},
  "sel1": {"type":"selector", "children":["cond_seen","act_patrol"]},
  "cond_seen": {"type":"condition","check":"seen"},
  "act_patrol": {"type":"action","do":"patrol"}, "act_attack":{"type":"action","do":"attack"},
  "w": {"type":"wait","seconds":2} } }
```
   Node types: selector, sequence, parallel(successAll|any), inverter, repeat(n|forever), wait(seconds or [min,max]), condition(check names — see below), action(do names), set_bb {key,value}, fire_event {name}.
   Standard conditions: "seen" (perception.visibleTarget != null), "target_in_range" (params.range), "has_path", custom via Agent.registerCheck(String, Predicate<Agent>).
   Standard actions: "patrol" (waypoints from entity children named wp1..N or tag), "chase" (path to lastKnownTarget), "search" (wander near lastKnown), "attack" (fire event "attack" + params), "move_to" (params.point/params.tag), "flee" (from target), "play_anim" (params.clip), custom via Agent.registerAction(String, Consumer<Agent>).
5. `CrowdSystem` (lite): optional boids-style separation for tagged agents (skip if time).

## Agent D — audio + ui + vfx + script + cine + net + save + events
`com.hollow.audio` (desktop-safe core + android impl behind interface):
1. `AudioOut` interface: `open(int sampleRate)`, `write(short[] interleavedStereo, int frames)`, `close()`. Desktop impl: null sink. (Agent E adds AndroidAudioOut.)
2. `AudioEngine`: buses {master, music, sfx, ambient, voice} each with volume; `play(String path, PlayParams)` -> AudioHandle {stop(), setVolume(), setPitch? no—skip pitch, isPlaying()}; PlayParams {bus, volume, loop, position(world, if 3d), minDist, maxDist, rolloff}; 3D: equal-power pan from listener (set each frame: `setListener(pos, forward, up)`), gain = clamp(minDist/dist)^rolloff; virtualization: skip voices beyond 24; WAV via AssetDB; OGG via OggDecoder hook; mixer thread pulls from active voices, 16-bit stereo 44100; clean thread lifecycle (start/stop with engine).
`com.hollow.ui`:
1. `UIManager`: root UiPanel tree; `load(String uiJsonPath, AssetDB)` builds; `handlePointer(Vec2 ndc, boolean down, boolean up)`; `update(dt)` (animations: slide/fade via UiTween); `buildDrawList(UiDrawList)` for renderer (textured quads + colored quads).
2. Widgets (all extend `Widget` with anchors {left,top,right,bottom, pivot}, visible, alpha, children, id): Panel (bg color/texture, border, cornerRadius? -> keep flat), Label, Button (normal/hover/pressed colors, onClick -> EventBus fire "ui_click" {id}), Image (sprite or texture path, fit modes), Slider (value, min, max, on change -> EventBus "ui_value"), Checkbox, ProgressBar, TextField (single line, focus, caret blink — chars via KeyboardEvents API from framework), Toast (queue, fade in/out, duration). 
3. `TextShaper` interface: `void layout(String text, float fontSize, out Metrics)` + `void draw(UiDrawList, String text, float x, float y, float fontSize, Color color, int align, float maxWidth)`. Desktop: DesktopTextShaper stub (measures 0.6*fontSize per char, draws colored rects — enough for headless tests to verify layout doesn't crash). Android runtime registers Paint-based shaper (Agent E) with FULL Arabic/Persian shaping + RTL (Android Canvas does it natively via HarfBuzz). Alignment: LEFT/CENTER/RIGHT; RTL flag per text auto-detected (first strong char).
`com.hollow.vfx`:
1. `ParticleSystem` (singleton-ish per world via ParticleLibrary): emitters registered from `ParticleEmitter` components; update(dt): spawn per rate (accumulator) + bursts, integrate (pos+=vel*dt, vel+=gravity*dt, vel*=1-drag*dt), age; kill at life; per-particle: pos, vel, size, color, life, maxLife, rotation.
2. `ParticleEmitter extends Component` JSON: {sprite, texture, rate, burst, lifetime[min,max], speed[min,max], direction:[x,y,z], spread(deg), gravity:[x,y,z], drag, size[min,max] + sizeCurve:[[t,v]..], colorCurve:[[[t,r,g,b,a]..]], maxCount, additive, billboard:"camera"|"velocity", localSpace}.
3. `ParticleRenderData`: renderer pulls `float[] pos(3)+size(1)+color(4)+rotation(1)` interleaved; max 4096 particles/emitter.
`com.hollow.script`:
1. `VarTable`: nested vars (global + per-entity `Entity.vars` map exposed via same class), get/set typed (string/number/bool), `eval(String expr)` mini-language: `var == 5`, `var != x`, `var > 3`, `var && other`, `!var`, `has_item(key)`, `dist_to(tag) < 10`, `chance(0.3)`, `count(tag) >= 2`, literals, parentheses. Deterministic — Rng from svc.frameSeed.
2. `ScriptComponent extends Component`: {graphs:[{on:{event, params...}, steps:[...]}]}; listens EventBus + own triggers (on_start, on_update_every:{t}, on_trigger_enter/exit {zone}, on_interact, on_message {name}, on_var {key}, on_anim_end, on_damage, on_death, on_timer {t, repeat}); steps (see docs/ACTIONS.md in your implementation notes; MUST include): set_var, add_var, if/else, wait {t}, wait_random, sequence, play_anim, stop_anim, teleport {to:entity|point}, move_by {v}, look_at {target}, spawn {prefab, at}, destroy {target}, show_toast {text}, play_sound {asset,...}, play_music {asset}, stop_music, fade {color, in_out, t}, camera_shake {amp, t}, set_light {target, on|color|intensity}, enable/disable {target}, set_active_camera {entity}, give_item {key}, remove_item, has_item (condition), quest {id, state}, save, load, load_scene {path, fade}, cinematic {path}, message {name, data}, log {text}, debug_draw {..}. Steps run on a per-script coroutine-like StepRunner (no threads: time-based scheduling via engine update). Any step errors -> Log.w + skip step (never crash game).
3. `EventBus`: fire(name, Map data) / listen(name, listener) / defer mode: events queued, flushed each frame end (deterministic order).
`com.hollow.cine`:
1. `Sequencer`: `load(path)`, `play(world, onComplete)`, `stop()`, `update(dt)`; JSON: {duration, letterbox:true, tracks:[{type:"camera", entity:"CineCam", keys:[{t, position:[..], rotationEuler:[..], fov}]}, {type:"anim", target, clip, loop}, {type:"transform", target, keys[{t, position, rotationEuler, scale}] (lerped)}, {type:"event", t, name, data}, {type:"sound", t, asset, volume}, {type:"music"}, {type:"fade", t, color, fadeIn, duration}, {type:"caption", t, text, duration}]}; evaluation deterministic; while playing, player input disabled (framework reads sequencer.activeCamera).
`com.hollow.net` (scaffold only): `NetMessage` (type byte[] payload), `TcpChannel` (connect/accept/send/receive with length-prefix framing, poll), `ReplicationStub` — document "Phase 2" in code comments. ~150 lines max.
`com.hollow.game` framework additions (I already define EngineServices/GameApp/InputActions/Localization — read them; Agent D implements):
- `SaveSystem`: slots via fs `saves/slot_N.json`; serialize: vars, player transform, entity flags (enabled/destroyed lists), inventory; `save(slot)`, `load(slot)` -> applied by GameApp.
- `EventBus` is in script package (above).
- `ParticleLibrary` is registry entry point in vfx.
DO NOT modify files in core/, scene/, assets/ (except GltfLoader per Agent A), render/ (Agent B), anim/physics/ai (Agent C). If you need a core addition — add NEW class in your own package.

## Agent E — android runtime + sample project (runs after A-D integrate)
- `runtime-android/src/main/java/com/hollow/runtime/`: EngineActivity (GLSurfaceView ES3, immersive, loads `project/` from assets via AssetFS implements FileSystem, registers AndroidImageDecoder(BitmapFactory), OggDecoder via MediaCodec, AudioOut via AudioTrack, TextShaper via Paint/Canvas — Persian/Arabic correct by Android text stack; touch -> InputActions via mapping json; video: MediaPlayer->SurfaceTexture->GLES_TEXTURE_EXTERNAL_OES fullscreen quad + audio, aspect-fit letterboxed — for splash/menu videos), boot: read project.json, build EngineServices, load entry scene, run GameApp loop on GL thread.
- `projects/demo-fps/` sample project exercising EVERYTHING (scene JSON, BT guard, FlowScript door/pickup, particles, sky, night lighting, sequencer intro, UI HUD, save) + `docs/PROJECT_FORMAT.md`.

## Definition of done for every agent
`javac` compiles clean. No TODO left in code you own (design decisions resolved). Report: files created + public APIs + JSON schemas + deviations from SPEC (with reasons).

## Annex — v2.6 to v8.0 systems (current contract additions)

Since v2.6 the engine grows through self-contained "systems" placed in existing packages
(`com.hollow.world` / `com.hollow.game` / `com.hollow.gameplay` / `com.hollow.ai` / `com.hollow.audio` / `com.hollow.ui`).
Every system in the annex follows the same contract:

1. **Deterministic** — no wall-clock, no `Math.random()`; randomness only via `com.hollow.core.MathX.Rng` seeded from a public `seed` field.
2. **Headless-testable** — a plain constructor + explicit `tickDay()`/`update(dt)` drives all simulation; no GL/audio/thread required.
3. **Symmetric persistence** — `save()` -> JSON string via `JsonW`, `load(String)` via `JsonV`; roundtrip restores all public state.
4. **Events** — state changes publish through a small `Listener` interface on a public field (game layer wires it; the system never calls `System.out`).
5. **Wiring** — cross-system effects are explicit interfaces or direct references (e.g. `CookingSystem.serve(dish, SurvivalSystem)`, `MigrationSystem(SettlementSystem)`, `CaravanSystem.Market`).
6. **Regression cover** — each version batch owns a suite: `verify/VerifyV5.java` (78), `VerifyV6.java` (86), `VerifyV7.java` (87), `VerifyV8.java` (67), `VerifyV9.java` (67), `VerifyV10.java` (64), `VerifyV11.java` (61), `VerifyV12.java` (58), `VerifyV13.java` (54), `VerifyV14.java` (54), `VerifyV15.java` (65), `VerifyV16.java` (66), `VerifyV17.java` (61), `VerifyV18.java` (75) plus `VerifyAaa` (54) for v4; `verify.sh` runs build + selftest (249) + all suites and must stay ALL GREEN before any push.

Package map of the annex (systems per package, latest version):

| Package | Systems (v) |
|---|---|
| `com.hollow.world` | Terrain/Ocean/Weather (4), Seasons/MapMarker (5), Farming/Ecosystem (6), Settlement/Caravan/Territory/NpcScheduler (7), Quarry/Migration/Festival (8), Road/Guild/Plague/Granary (9), MarketDay/Lighthouse (10), Apiary/Well/Orchard/Vital (11), Fishery/Mill/Bridge/Pasture (12), Astrologer/Ratcatcher/Scribe/Tannery (13), Watchtower/Falconry/Icehouse/Dredge (14), Tollgate/ChimneySweep/Buoy (15), LampLighter/NightWatch/Harbourmaster/Saltworks (16), CharcoalBurner/Weir/PeatCutting/Hayward (17), Pinfold/TitheBarn/Hatchery/Almshouse (18) |
| `com.hollow.game` | AbilitySystem/WaveDirector/Replay (4), Achievement/DifficultyDirector/Journal/BaseBuilding (6), Diplomacy/Bounty (7), Research/Treasury/Justice/Auction/Chronicle (8), Rumor/Tournament/Succession/Expedition (9), Wonder/Herald/Courier/Wager/Debt/SpyNetwork (10), Inn/Matchmaker/School/Graveyard (11), Blacksmith/Apprentice/Bakery/Carpenter (12), Apothecary/Mint/Brewery/Healer (13), Puppeteer/Glassblower/Cartographer/Candlemaker (14), Tailor/Bellfoundry/Perfumer/Keymaker/Toyman (15), Wheelwright/Ropemaker/Lapidary/Cooper (16), Stable/Saddler/Thatcher/Clockmaker (17), Tinker/Papermaker/Bookbinder/BasketWeaver (18) |
| `com.hollow.gameplay` | Inventory/Combatant/Weapon/Dialogue/Quest/Tutorial (3), Progression/Faction/Loot/Economy (5), Survival/SkillTree/Cooking (6-8) |
| `com.hollow.ai` | StealthSystem (5), SquadCommander (6) |
| `com.hollow.audio` | MusicDirector (5) |
| `com.hollow.net` | InputBuffer/Transport+Relay/RollbackSim (2.6) |
| `com.hollow.nav` | NavMesh (2.6) |
| `com.hollow.platform` | PluginHost/MobileGovernor (3) |
