package com.hollow.runtime;

import com.hollow.core.Vec3;
import com.hollow.game.GameApp;
import com.hollow.physics.CharacterController;
import com.hollow.scene.CameraComponent;
import com.hollow.scene.Entity;
import com.hollow.scene.LightComponent;

/**
 * Generic runtime game (data-driven): player controller + camera + interact + flashlight.
 * All game-specific behavior is authored via FlowScript/BehaviorTree/Sequencer in the
 * project files — no recompilation needed for new games.
 */
public final class RuntimeGame extends GameApp {
    private float yaw = 0, pitch = 0;
    private boolean headlamp = false;
    private float bobTimer = 0;

    @Override public void onStart(){
        player = world != null ? world.byTag("player").stream().findFirst().orElse(null) : null;
        if(player != null){
            yaw = player.rotation.yawDeg();
            pitch = player.rotation.pitchDeg();
        }
        pickCamera();
    }

    private void pickCamera(){
        activeCamera = null;
        if(world == null) return;
        // camera child of player first
        if(player != null){
            for(Entity c : player.children()){
                if(c.has("Camera")){ activeCamera = c; return; }
            }
        }
        for(Entity e : world.entities){
            CameraComponent cc = e.get(CameraComponent.class);
            if(cc != null && cc.active){ activeCamera = e; return; }
        }
    }

    @Override public void onFixedUpdate(float dt){
        if(svc.sequencer.isPlaying()) return;   // cinematics take over
        var input = svc.input;
        var inputActions = input;

        // player movement
        if(player != null && player.active){
            CharacterController cc = player.get(CharacterController.class);
            float f = inputActions.axis("move_forward") - inputActions.axis("move_back");
            float s = inputActions.axis("move_right") - inputActions.axis("move_left");
            // touch stick merges
            f += -inputActions.touchMove.y;
            s += inputActions.touchMove.x;
            f = com.hollow.core.MathX.clamp(f, -1, 1);
            s = com.hollow.core.MathX.clamp(s, -1, 1);

            if(cc != null){
                Vec3 f3 = player.rotation.forward(); Vec3 fwd = new Vec3(f3.x, 0, f3.z).norm();
                Vec3 right = new Vec3(fwd.z, 0, -fwd.x);
                float speed = inputActions.held("run") ? 6.2f : 3.6f;
                Vec3 move = fwd.scale(f * speed / 60f).addScaled(right, s * speed / 60f);
                if(move.len2() > 1e-8f) cc.move(move);
                if(inputActions.pressed("jump") && cc.isGrounded()) cc.jump(5.2f);
            } else {
                Vec3 fwd = player.rotation.forward();
                player.position.addScaled(fwd, f * 3f / 60f);
                player.markDirtyDeep();
            }
            if(f != 0) bobTimer += dt * 9f;

            // interact: raycast forward, fire event
            if(inputActions.pressed("interact")){
                interact();
            }
            // flashlight
            if(inputActions.pressed("flashlight")){
                headlamp = !headlamp;
                world.blackboard.put("__headlamp", headlamp);
            }
        }

        // look
        float lx = inputActions.touchLook.x * 240f;
        float ly = inputActions.touchLook.y * 200f;
        if(Math.abs(lx) > 0.01f || Math.abs(ly) > 0.01f){
            yaw += lx;
            pitch = com.hollow.core.MathX.clamp(pitch + ly, -85, 85);
            if(player != null){
                player.rotation.set(com.hollow.core.Quat.fromEuler(0, yaw, 0));
                player.markDirtyDeep();
            }
        }
        // camera follows player head
        if(activeCamera != null && player != null){
            boolean isChild = activeCamera.parent() == player;
            if(!isChild){
                Vec3 head = player.position.cpy().add(0, 1.62f, 0);
                activeCamera.position.set(head);
                activeCamera.rotation.set(com.hollow.core.Quat.fromEuler(pitch, yaw, 0));
                activeCamera.markDirtyDeep();
            } else {
                activeCamera.rotation.set(com.hollow.core.Quat.fromEuler(pitch, 0, 0));
                activeCamera.markDirtyDeep();
            }
        }
    }

    private void interact(){
        if(world == null || player == null) return;
        Vec3 eye = player.position.cpy().add(0, 1.5f, 0);
        Vec3 dir = new com.hollow.core.Quat().set(player.rotation).forward();
        com.hollow.physics.PhysicsWorld.Hit hit = svc.physics.raycast(eye, dir, 3f);
        if(hit.hit && hit.entity != null){
            var data = new java.util.LinkedHashMap<String,Object>();
            data.put("target", hit.entity.name);
            data.put("by", player.name);
            svc.events.fire("interact", data);
        }
    }

    @Override public void onFrameUpdate(float dt){
        // headlamp light follows camera (world space temp light handled by renderer via blackboard)
        if(world != null){
            Entity lamp = world.find("__headlampLight");
            if(lamp != null){
                LightComponent lc = lamp.get(LightComponent.class);
                if(lc != null) lc.intensity = headlamp ? 2.2f : 0f;
            }
        }
    }
}
