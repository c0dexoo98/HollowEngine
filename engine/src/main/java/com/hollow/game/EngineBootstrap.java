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
import com.hollow.physics.PhysicsWorld;
import com.hollow.physics.RigidBody;
import com.hollow.physics.SphereCollider;
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
    }

    /** after a world loads: physics statics, agent setup, script start events */
    public static void onWorldLoaded(EngineServices svc, World w){
        registerModuleComponents(w);
        svc.physics.onWorldLoaded(w);
        svc.particles.clear();
        // agents/scripts pick up via onAttach; fire start event
        svc.events.fire("world_loaded");
    }
}
