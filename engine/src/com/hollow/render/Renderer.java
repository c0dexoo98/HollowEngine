package com.hollow.render;

import com.hollow.core.MathX.Mat4;
import com.hollow.core.MathX.Vec3;
import com.hollow.scene.CameraNode;
import com.hollow.scene.LightNode;
import com.hollow.scene.MeshNode;
import com.hollow.scene.Node;
import com.hollow.scene.Scene;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** HollowEngine 2 — renders a Scene to a Frame through the software
 *  rasterizer: active camera, directional lights, ambient, mesh
 *  nodes (obj on disk or the built-in cube). Deterministic output. */
public final class Renderer {

    private final Map<String, Mesh> meshCache = new HashMap<>();
    private final Path projectDir;

    public Renderer(Path projectDir){
        this.projectDir = projectDir;
    }

    public Frame render(Scene scene, int w, int h){
        Frame f = new Frame(w, h);
        int clear = Colors.parse(scene.clearColor);
        f.clear(clear);

        CameraNode cam = scene.activeCamera();
        if(cam == null) return f;                       // nothing to see — blank frame

        Vec3 eye = cam.worldPosition();
        Mat4 view = Rasterizer.cameraView(eye, cam.rotation);
        Mat4 proj = new Mat4().identity().perspective(cam.fov, (float)w / h, cam.near, cam.far);

        List<Rasterizer.Light> lights = new ArrayList<>();
        List<Node> meshNodes = new ArrayList<>();
        scene.root.walk(n -> {
            if(n instanceof LightNode && n.visible){
                LightNode ln = (LightNode)n;
                Mat4 wm = ln.worldMatrix();
                Vec3 dir = wm.transformDir(ln.lightDir()).norm();
                lights.add(new Rasterizer.Light(dir, Colors.parse(ln.color), ln.intensity));
            }
            if(n instanceof MeshNode && n.visible) meshNodes.add(n);
        });
        Rasterizer.Light light = lights.isEmpty()
            ? new Rasterizer.Light(new Vec3(-0.3f, -1f, -0.2f).norm(), 0xFFFFFFFF, 1f)
            : lights.get(0);

        for(Node n : meshNodes){
            MeshNode mn = (MeshNode)n;
            Mesh mesh = meshFor(mn.meshPath);
            Rasterizer.drawMesh(f, mesh, n.worldMatrix(), view, proj, eye, light,
                                Colors.parse(mn.color), scene.ambient, scene.fog, clear);
        }

        // planar ground shadows (skip meshes resting on the plane itself)
        if(scene.shadowPlaneY >= 0 && light.dir.y < -1e-4f){
            for(Node n : meshNodes){
                float bottom = n.worldPosition().y - Math.abs(n.scale.y);
                if(bottom > scene.shadowPlaneY + 0.02f){
                    Rasterizer.drawShadow(f, meshFor(((MeshNode)n).meshPath), n.worldMatrix(),
                                          view, proj, light, scene.shadowPlaneY);
                }
            }
        }
        return f;
    }

    /** nearest mesh under a viewport pixel — editor picking */
    public Node pick(Scene scene, int w, int h, float px, float py){
        CameraNode cam = scene.activeCamera();
        if(cam == null) return null;
        Vec3 eye = cam.worldPosition();
        Rasterizer.Ray ray = Rasterizer.cameraRay(w, h, eye, cam.rotation, cam.fov, px, py);
        Node best = null;
        float bestT = Float.MAX_VALUE;
        float pad = 0.15f;                               // small grab tolerance
        com.hollow.physics.Physics phys = new com.hollow.physics.Physics();
        java.util.List<Node> nodes = new ArrayList<>();
        scene.root.walk(nodes::add);
        for(Node n : nodes){
            if(!(n instanceof MeshNode) || !n.visible) continue;
            Vec3 center = n.worldPosition();
            Vec3 half = new Vec3(n.scale.x + pad, n.scale.y + pad, n.scale.z + pad);
            float t = phys.rayBox(ray.origin, ray.dir, center, half);
            if(t >= 0 && t < bestT){
                bestT = t;
                best = n;
            }
        }
        return best;
    }

    public Mesh meshFor(String meshPath){
        if(meshPath == null || meshPath.isEmpty()) return Mesh.cube();
        Mesh cached = meshCache.get(meshPath);
        if(cached != null) return cached;
        try {
            Mesh m = Mesh.fromObj(Files.readString(projectDir.resolve(meshPath)));
            meshCache.put(meshPath, m);
            return m;
        } catch(Exception e){
            Mesh m = Mesh.cube();
            meshCache.put(meshPath, m);
            return m;
        }
    }
}
