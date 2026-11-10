package com.hollow.game;

import com.hollow.anim.AnimationSystem;
import com.hollow.anim.Animator;
import com.hollow.anim.StateMachine;
import com.hollow.ai.AISystem;
import com.hollow.ai.Agent;
import com.hollow.ai.Perception;
import com.hollow.assets.AssetDB;
import com.hollow.audio.AudioEngine;
import com.hollow.audio.AudioOut;
import com.hollow.audio.AudioSource;
import com.hollow.cine.Sequencer;
import com.hollow.core.FileSystem;
import com.hollow.core.Log;
import com.hollow.core.NativeFS;
import com.hollow.physics.BoxCollider;
import com.hollow.physics.CapsuleCollider;
import com.hollow.physics.CharacterController;
import com.hollow.physics.Joints;
import com.hollow.physics.PhysicsWorld;
import com.hollow.physics.RigidBody;
import com.hollow.physics.SphereCollider;
import com.hollow.scene.Entity;
import com.hollow.scene.Scene;
import com.hollow.scene.World;
import com.hollow.script.EventBus;
import com.hollow.script.ScriptComponent;
import com.hollow.script.VarTable;
import com.hollow.ui.UIManager;
import com.hollow.vfx.ParticleEmitter;
import com.hollow.vfx.ParticleLibrary;

/** Boot order + component registry. Single entry point for all engine wiring. */
public final class EngineBootstrap {
    static {
        World.registryHooks.add(EngineBootstrap::registerModuleComponents);
    }
    private EngineBootstrap() {}

    public static EngineServices boot(FileSystem fs, String projectDir, AudioOut audioOut){
        GameConfig cfg = GameConfig.load(fs, projectDir);
        AssetDB assets = new AssetDB(fs, projectDir);
        EngineServices svc = new EngineServices(assets, fs, cfg);

        // --- subsystems in strict dependency order ---
        svc.vars = new VarTable();
        svc.events = new EventBus();
        svc.audio = new AudioEngine();
        svc.particles = new ParticleLibrary();
        svc.sequencer = new Sequencer();
        svc.physics = new PhysicsWorld();
        svc.ai = new AISystem();
        svc.anim = new AnimationSystem();
        svc.ui = new UIManager();
        svc.save = new SaveSystem(fs);
        svc.save.projectDir = projectDir;
        svc.i18n = new Localization();
        svc.input = new InputActions();
        svc.input.loadMapping(fs, projectDir);
        svc.i18n.load(fs, projectDir, "fa");

        if(cfg.headless) svc.audio.init(assets, null, 44100);
        else svc.audio.init(assets, audioOut, 44100);

        Log.i("Bootstrap", "engine services ready (project=" + projectDir + ", quality=" + cfg.quality + ")");
        return svc;
    }

    /** register every built-in component factory on a world */
    public static void registerAll(EngineServices svc){
        World w = svc.app != null && svc.app.world != null ? svc.app.world : null;
        // registry is per-world; standard components first
        // (called via World.empty -> Scene.registerStandardComponents, plus module components here)
        // This method exists for symmetry; actual registration happens in onWorldLoaded.
    }

    public static void registerModuleComponents(World w){
        Scene.registerStandardComponents(w);
        w.componentRegistry.put("Animator", Animator::new);
        w.componentRegistry.put("StateMachine", StateMachine::new);
        w.componentRegistry.put("RigidBody", RigidBody::new);
        w.componentRegistry.put("BoxCollider", BoxCollider::new);
        w.componentRegistry.put("SphereCollider", SphereCollider::new);
        w.componentRegistry.put("CapsuleCollider", CapsuleCollider::new);
        w.componentRegistry.put("CharacterController", CharacterController::new);
        w.componentRegistry.put("Perception", Perception::new);
        w.componentRegistry.put("Agent", Agent::new);
        w.componentRegistry.put("AudioSource", AudioSource::new);
        w.componentRegistry.put("ParticleEmitter", ParticleEmitter::new);
        w.componentRegistry.put("Script", ScriptComponent::new);
        // v2.0 components
        w.componentRegistry.put("DistanceJoint", Joints.Distance::new);
        w.componentRegistry.put("SpringJoint", Joints.Spring::new);
        w.componentRegistry.put("HingeJoint", Joints.Hinge::new);
        w.componentRegistry.put("Attachment", com.hollow.anim.Attachment::new);
        w.componentRegistry.put("FootIK", com.hollow.anim.FootIK::new);
        w.componentRegistry.put("Foliage", com.hollow.render.FoliageComponent::new);
        w.componentRegistry.put("Decal", com.hollow.render.DecalComponent::new);
        w.componentRegistry.put("StreamingVolume", com.hollow.game.StreamingVolume::new);
        // v2.2 components (Batch 2)
        w.componentRegistry.put("LightProbe", com.hollow.render.LightProbes.LightProbeComponent::new);
        w.componentRegistry.put("LightProbeComponent", com.hollow.render.LightProbes.LightProbeComponent::new);
        // v2.3 components (Batch 3: PHY/COL/AUD/ANI)
        w.componentRegistry.put("PointJoint", Joints.Point::new);
        w.componentRegistry.put("Ragdoll", com.hollow.physics.Ragdoll::new);
        w.componentRegistry.put("Cloth", com.hollow.physics.Cloth::new);
        w.componentRegistry.put("Vehicle", com.hollow.physics.Vehicle::new);
        w.componentRegistry.put("WaterVolume", com.hollow.physics.EnvironmentPhysics.WaterVolume::new);
        w.componentRegistry.put("WindZone", com.hollow.physics.EnvironmentPhysics.WindZone::new);
        w.componentRegistry.put("Destruction", com.hollow.physics.Destruction::new);
        w.componentRegistry.put("TrapPhysics", com.hollow.physics.TrapPhysics::new);
        w.componentRegistry.put("FootPlant", com.hollow.physics.FootPlant::new);
        w.componentRegistry.put("FacialRig", com.hollow.anim.AnimationV23.FacialRig::new);
        w.componentRegistry.put("BoneSpringRig", com.hollow.anim.AnimationV23.BoneSpringRig::new);
        // v2.4 components (world/AI/UI/VFX)
        w.componentRegistry.put("DayNightCycle", com.hollow.world.DayNightCycle::new);
        w.componentRegistry.put("WaterDomain", com.hollow.world.WorldSystems.WaterDomain::new);
        w.componentRegistry.put("Portal", com.hollow.world.WorldSystems.Portal::new);
        w.componentRegistry.put("GlobalWind", com.hollow.world.WorldSystems.GlobalWind::new);
        w.componentRegistry.put("WorldBounds", com.hollow.world.WorldSystems.WorldBounds::new);
        w.componentRegistry.put("TriggerZone", com.hollow.world.WorldSystems.TriggerZone::new);
        w.componentRegistry.put("AudioZone", com.hollow.world.WorldSystems.AudioZone::new);
        w.componentRegistry.put("EnemyBrain", com.hollow.ai.AISystems.EnemyBrain::new);
        w.componentRegistry.put("Weather", com.hollow.vfx.VfxSystems.Weather::new);
        w.componentRegistry.put("AmbientEvents", com.hollow.vfx.VfxSystems.AmbientEvents::new);
        w.componentRegistry.put("SmokePlume", com.hollow.vfx.VfxSystems.SmokePlume::new);
        w.componentRegistry.put("Dissolve", com.hollow.vfx.VfxSystems.Dissolve::new);
        w.componentRegistry.put("AmbientLife", com.hollow.vfx.VfxSystems.AmbientLife::new);
        w.componentRegistry.put("Seasons", com.hollow.vfx.VfxSystems.Seasons::new);
        // v2.5 components (WLD/AI/VFX/ENV batch)
        w.componentRegistry.put("InteriorBlend", com.hollow.world.WorldFeatures.InteriorBlend::new);
        w.componentRegistry.put("MedicalBehavior", com.hollow.ai.AiFeatures.MedicalBehavior::new);
        w.componentRegistry.put("AuraRing", com.hollow.vfx.VfxFeatures.AuraRing::new);
        w.componentRegistry.put("AmbientFlight", com.hollow.vfx.VfxFeatures.AmbientFlight::new);
        w.componentRegistry.put("TemperatureVolume", com.hollow.world.EnvFeatures.TemperatureVolume::new);
        w.componentRegistry.put("VideoProjector", com.hollow.world.EnvFeatures.VideoProjector::new);
        w.componentRegistry.put("PlanarMirror", com.hollow.world.EnvFeatures.PlanarMirror::new);
        w.componentRegistry.put("RainWindow", com.hollow.world.EnvFeatures.RainWindow::new);
        w.componentRegistry.put("WaterSurface", com.hollow.world.EnvFeatures.WaterSurface::new);
    }

    /** after a world loads: physics statics, agent setup, script start events */
    public static void onWorldLoaded(EngineServices svc, World w){
        registerModuleComponents(w);
        svc.physics.onWorldLoaded(w);
        svc.streaming.collect(w);
        svc.particles.clear();
        // v2.0: auto-assign the active camera (tag "camera" or any Camera component)
        if(svc.app != null && svc.app.activeCamera == null && w != null){
            for(Entity e : w.entities){
                if(e.destroyed) continue;
                if("camera".equals(e.tag) || e.has("Camera")){
                    svc.app.activeCamera = e;
                    break;
                }
            }
        }
        // agents/scripts pick up via onAttach; fire start event
        svc.events.fire("world_loaded");
    }
}
