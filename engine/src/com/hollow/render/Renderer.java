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
        f.clear(Colors.parse(scene.clearColor));

        CameraNode cam = scene.activeCamera();
        if(cam == null) return f;                       // nothing to see — blank frame

        Vec3 eye = cam.worldPosition();
        Mat4 view = Rasterizer.cameraView(eye, cam.rotation);
        Mat4 proj = new Mat4().identity().perspective(cam.fov, (float)w / h, cam.near, cam.far);

        List<Rasterizer.Light> lights = new ArrayList<>();
        scene.root.walk(n -> {
            if(n instanceof LightNode && n.visible){
                LightNode ln = (LightNode)n;
                Mat4 wm = ln.worldMatrix();
                Vec3 dir = wm.transformDir(ln.lightDir()).norm();
                lights.add(new Rasterizer.Light(dir, Colors.parse(ln.color), ln.intensity));
            }
        });
        Rasterizer.Light light = lights.isEmpty()
            ? new Rasterizer.Light(new Vec3(-0.3f, -1f, -0.2f).norm(), 0xFFFFFFFF, 1f)
            : lights.get(0);

        scene.root.walk(n -> {
            if(n instanceof MeshNode && n.visible){
                MeshNode mn = (MeshNode)n;
                Mesh mesh = meshFor(mn.meshPath);
                Rasterizer.drawMesh(f, mesh, n.worldMatrix(), view, proj, eye, light,
                                    Colors.parse(mn.color), scene.ambient);
            }
        });
        return f;
    }

    private Mesh meshFor(String meshPath){
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
