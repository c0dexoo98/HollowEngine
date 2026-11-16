package com.hollow.editor;

import com.hollow.core.Json;
import com.hollow.core.Json.JsonW;
import com.hollow.render.Frame;
import com.hollow.render.Rasterizer;
import com.hollow.render.Renderer;
import com.hollow.scene.CameraNode;
import com.hollow.scene.LightNode;
import com.hollow.scene.MeshNode;
import com.hollow.scene.Node;
import com.hollow.scene.Scene;
import com.hollow.scene.ScriptHost;
import com.hollow.scene.ScriptNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** HollowEngine 2 — editor state machine, fully headless-testable.
 *  The Swing shell (EditorApp) is a thin view over this model; every
 *  menu action routes through exactly these methods. */
public final class EditorModel {

    public Scene scene = new Scene();
    public Path projectDir = Path.of(".");
    public String selected = "";
    public final List<String> console = new ArrayList<>();
    public final StringBuilder runLog = new StringBuilder();
    // undo/redo (scene JSON snapshots, capped)
    final java.util.Deque<String> undoStack = new java.util.ArrayDeque<>();
    final java.util.Deque<String> redoStack = new java.util.ArrayDeque<>();
    // editor orbit camera (used when useOrbitCamera is on)
    public boolean useOrbitCamera = false;
    public float viewYaw = 25f, viewPitch = 22f, viewDist = 12f;
    // play mode (Godot-style F5)
    ScriptHost playHost;
    public boolean playing = false;

    public EditorModel(){ }
    public EditorModel(Path projectDir){
        this.projectDir = projectDir;
    }

    // ------------------------- undo / redo -------------------------

    void snapshot(){
        undoStack.push(scene.save());
        if(undoStack.size() > 100) undoStack.removeLast();
        redoStack.clear();
    }

    public boolean canUndo(){ return !undoStack.isEmpty(); }
    public boolean canRedo(){ return !redoStack.isEmpty(); }

    public boolean undo(){
        if(undoStack.isEmpty()) return false;
        redoStack.push(scene.save());
        try {
            scene = new Scene();
            scene.load(undoStack.pop());
            if(scene.find(selected) == null) selected = "";
            log("undo");
            return true;
        } catch(Exception e){
            log("undo failed: " + e.getMessage());
            return false;
        }
    }

    public boolean redo(){
        if(redoStack.isEmpty()) return false;
        undoStack.push(scene.save());
        try {
            scene = new Scene();
            scene.load(redoStack.pop());
            if(scene.find(selected) == null) selected = "";
            log("redo");
            return true;
        } catch(Exception e){
            log("redo failed: " + e.getMessage());
            return false;
        }
    }

    // ------------------------- console -------------------------

    private void log(String line){
        console.add(line);
        runLog.append(line).append('\n');
    }

    // ------------------------- scene files -------------------------

    public String scenePath(){ return projectDir.resolve("scene.hscene").toString(); }

    public boolean openScene(Path file){
        try {
            Scene s = new Scene();
            s.load(Files.readString(file));
            scene = s;
            selected = "";
            log("opened " + file.getFileName());
            return true;
        } catch(Exception e){
            log("open failed: " + e.getMessage());
            return false;
        }
    }

    public boolean saveScene(Path file){
        try {
            Path p = file == null ? Path.of(scenePath()) : file;
            Files.createDirectories(p.getParent());
            Files.writeString(p, scene.save());
            log("saved " + p.getFileName());
            return true;
        } catch(Exception e){
            log("save failed: " + e.getMessage());
            return false;
        }
    }

    // ------------------------- tree ops -------------------------

    public Node addNode(String type, String name){
        if(scene.find(name) != null){
            log("node '" + name + "' already exists");
            return null;
        }
        snapshot();
        Node n = scene.createNode(type, name);
        scene.add(n);
        selected = name;
        log("added " + type + " '" + name + "'");
        return n;
    }

    public boolean deleteNode(String name){
        Node n = scene.find(name);
        if(n == null || n == scene.root) return false;
        snapshot();
        scene.root.remove(n);
        if(selected.equals(name)) selected = "";
        log("deleted '" + name + "'");
        return true;
    }

    public boolean renameNode(String oldName, String newName){
        Node n = scene.find(oldName);
        if(n == null || newName == null || newName.isEmpty() || scene.find(newName) != null) return false;
        snapshot();
        n.name = newName;
        if(selected.equals(oldName)) selected = newName;
        log("renamed '" + oldName + "' -> '" + newName + "'");
        return true;
    }

    public void select(String name){ selected = name == null ? "" : name; }

    public Node findNode(String name){ return scene.find(name); }

    // ------------------------- inspector -------------------------

    /** ordered property schema for the selected (or named) node */
    public LinkedHashMap<String, Object> properties(String name){
        Node n = scene.find(name);
        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        if(n == null) return props;
        props.put("type", n.type());
        props.put("name", n.name);
        props.put("visible", n.visible);
        props.put("position_x", (double)n.position.x);
        props.put("position_y", (double)n.position.y);
        props.put("position_z", (double)n.position.z);
        props.put("rotation_x", (double)n.rotation.x);
        props.put("rotation_y", (double)n.rotation.y);
        props.put("rotation_z", (double)n.rotation.z);
        props.put("scale_x", (double)n.scale.x);
        props.put("scale_y", (double)n.scale.y);
        props.put("scale_z", (double)n.scale.z);
        if(n instanceof MeshNode){
            props.put("mesh", ((MeshNode)n).meshPath);
            props.put("color", ((MeshNode)n).color);
        }
        if(n instanceof CameraNode){
            props.put("fov", (double)((CameraNode)n).fov);
            props.put("active", ((CameraNode)n).active);
        }
        if(n instanceof LightNode){
            props.put("color", ((LightNode)n).color);
            props.put("intensity", (double)((LightNode)n).intensity);
        }
        if(n instanceof ScriptNode) props.put("path", ((ScriptNode)n).path);
        if(n instanceof MeshNode || n.type().equals("node")) addPhysicsProps(props, n);
        return props;
    }

    /** set one property through the same schema; returns false if unknown */
    public boolean setProperty(String name, String key, Object value){
        Node n = scene.find(name);
        if(n == null || !properties(name).containsKey(key)) return false;
        if(key.equals("type")) return false;
        try {
            boolean ok;
            switch(key){
                case "name":
                    ok = renameNode(name, String.valueOf(value));
                    if(ok) return true;
                    return false;
                default:
                    break;
            }
            snapshot();
            switch(key){
                case "visible": n.visible = Boolean.parseBoolean(String.valueOf(value)); return true;
                case "active": if(n instanceof CameraNode) ((CameraNode)n).active = Boolean.parseBoolean(String.valueOf(value)); return true;
                case "mesh": if(n instanceof MeshNode) ((MeshNode)n).meshPath = String.valueOf(value); return true;
                case "color": if(n instanceof MeshNode) ((MeshNode)n).color = String.valueOf(value);
                              if(n instanceof LightNode) ((LightNode)n).color = String.valueOf(value);
                              return true;
                case "intensity": if(n instanceof LightNode) ((LightNode)n).intensity = (float)num(value); return true;
                case "reach": if(n instanceof LightNode) ((LightNode)n).reach = (float)num(value); return true;
                case "fov": if(n instanceof CameraNode) ((CameraNode)n).fov = (float)num(value); return true;
                case "path": if(n instanceof ScriptNode) ((ScriptNode)n).path = String.valueOf(value); return true;
                case "dynamic": n.dynamic = Boolean.parseBoolean(String.valueOf(value)); return true;
                case "bounciness": n.bounciness = (float)num(value); return true;
                case "velocity_x": n.velocity.x = (float)num(value); return true;
                case "velocity_y": n.velocity.y = (float)num(value); return true;
                case "velocity_z": n.velocity.z = (float)num(value); return true;
                default:
                    double d = num(value);
                    switch(key){
                        case "position_x": n.position.x = (float)d; return true;
                        case "position_y": n.position.y = (float)d; return true;
                        case "position_z": n.position.z = (float)d; return true;
                        case "rotation_x": n.rotation.x = (float)d; return true;
                        case "rotation_y": n.rotation.y = (float)d; return true;
                        case "rotation_z": n.rotation.z = (float)d; return true;
                        case "scale_x": n.scale.x = (float)d; return true;
                        case "scale_y": n.scale.y = (float)d; return true;
                        case "scale_z": n.scale.z = (float)d; return true;
                        default: return false;
                    }
            }
        } catch(Exception e){
            return false;
        }
    }

    /** physics + dynamic schema for the inspector */
    private void addPhysicsProps(LinkedHashMap<String, Object> props, Node n){
        props.put("dynamic", n.dynamic);
        props.put("bounciness", (double)n.bounciness);
        props.put("velocity_x", (double)n.velocity.x);
        props.put("velocity_y", (double)n.velocity.y);
        props.put("velocity_z", (double)n.velocity.z);
    }

    private double num(Object v){
        if(v instanceof Number) return ((Number)v).doubleValue();
        return Double.parseDouble(String.valueOf(v));
    }

    // ------------------------- viewport + orbit camera -------------------------

    /** orbit the editor camera: dx in degrees around Y, dy in degrees of pitch */
    public void orbit(float dxDeg, float dyDeg){
        viewYaw = (viewYaw + dxDeg) % 360;
        viewPitch = Math.max(2f, Math.min(88f, viewPitch + dyDeg));
    }

    /** wheel zoom factor: >1 zooms in */
    public void zoom(float factor){
        viewDist = Math.max(2f, Math.min(120f, viewDist / factor));
    }

    public Frame renderPreview(int w, int h){
        if(!useOrbitCamera) return new Renderer(projectDir).render(scene, w, h);
        // editor camera: spherical around the origin, always looking at it
        double cp = Math.cos(Math.toRadians(viewPitch)), sp = Math.sin(Math.toRadians(viewPitch));
        double sy = Math.sin(Math.toRadians(viewYaw)), cy = Math.cos(Math.toRadians(viewYaw));
        com.hollow.core.MathX.Vec3 eye = new com.hollow.core.MathX.Vec3(
            (float)(viewDist * cp * sy), (float)(viewDist * sp), (float)(viewDist * cp * cy));
        com.hollow.core.MathX.Vec3 rot = new com.hollow.core.MathX.Vec3(-viewPitch, viewYaw, 0);
        com.hollow.core.MathX.Mat4 view = com.hollow.render.Rasterizer.cameraView(eye, rot);
        com.hollow.core.MathX.Mat4 proj = new com.hollow.core.MathX.Mat4().identity()
            .perspective(60, (float)w / h, 0.1f, 500f);
        com.hollow.render.Frame f = new com.hollow.render.Frame(w, h);
        int clear = com.hollow.render.Colors.parse(scene.clearColor);
        f.clear(clear);
        java.util.List<Rasterizer.Light> lights = new java.util.ArrayList<>();
        java.util.List<Node> meshes = new java.util.ArrayList<>();
        scene.root.walk(n -> {
            if(n instanceof LightNode && n.visible){
                LightNode ln = (LightNode)n;
                lights.add(new Rasterizer.Light(
                    n.worldMatrix().transformDir(ln.lightDir()).norm(),
                    com.hollow.render.Colors.parse(ln.color), ln.intensity,
                    ln.worldPosition(), ln.reach));
            }
            if(n instanceof MeshNode && n.visible) meshes.add(n);
        });
        Rasterizer.Light light = lights.isEmpty()
            ? new Rasterizer.Light(new com.hollow.core.MathX.Vec3(-0.3f, -1f, -0.2f).norm(), 0xFFFFFFFF, 1f)
            : lights.get(0);
        Renderer renderer = new Renderer(projectDir);
        for(Node n : meshes){
            MeshNode mn = (MeshNode)n;
            Rasterizer.drawMesh(f, renderer.meshFor(mn.meshPath), n.worldMatrix(), view, proj, eye,
                                Renderer.pickLights(lights, n.worldPosition(), Renderer.MAX_LIGHTS),
                                com.hollow.render.Colors.parse(mn.color), scene.ambient, scene.fog, clear);
        }
        if(scene.shadowPlaneY >= 0 && light.dir.y < -1e-4f){
            for(Node n : meshes){
                float bottom = n.worldPosition().y - Math.abs(n.scale.y);
                if(bottom > scene.shadowPlaneY + 0.02f)
                    Rasterizer.drawShadow(f, renderer.meshFor(((MeshNode)n).meshPath),
                                          n.worldMatrix(), view, proj, light, scene.shadowPlaneY);
            }
        }
        return f;
    }

    // ------------------------- picking + drag -------------------------

    /** select the mesh under the viewport pixel (or clear selection) */
    public boolean pickAt(float px, float py, int w, int h){
        Node hit = new Renderer(projectDir).pick(scene, w, h, px, py);
        if(hit == null){
            selected = "";
            return false;
        }
        selected = hit.name;
        return true;
    }

    /** move the selected node on the ground plane following a pixel delta */
    public void dragSelected(float dxPx, float dyPx, int w, int h){
        Node n = scene.find(selected);
        if(n == null || n == scene.root) return;
        float yaw = useOrbitCamera ? viewYaw : 0;
        CameraNode cam = scene.activeCamera();
        float fov = useOrbitCamera ? 60 : (cam != null ? cam.fov : 60);
        float dist = useOrbitCamera ? viewDist
            : (n.worldPosition().sub(new com.hollow.core.MathX.Vec3(
                   cam != null ? cam.worldPosition().x : 0,
                   cam != null ? cam.worldPosition().y : 3,
                   cam != null ? cam.worldPosition().z : 9)).len());
        float wpp = 2f * (float)Math.tan(Math.toRadians(fov) * 0.5f) * Math.max(1, dist) / h;
        // camera right (XZ) and forward-ground vectors from yaw
        float rx = (float)Math.cos(Math.toRadians(yaw)), rz = (float)-Math.sin(Math.toRadians(yaw));
        float fx = (float)Math.sin(Math.toRadians(yaw)), fz = (float)Math.cos(Math.toRadians(yaw));
        n.position.x += (dxPx * rx - dyPx * fx) * wpp;
        n.position.z += (dxPx * rz - dyPx * fz) * wpp;
    }

    public void beginDrag(){ snapshot(); }

    // ------------------------- play mode (F5) -------------------------

    public boolean isPlaying(){ return playing; }

    /** HUD lines produced by scripts this play-frame (hud() builtin) */
    public java.util.List<String> playHud(){
        return playHost == null ? java.util.List.of() : playHost.hudLines;
    }

    public void playStart(){
        if(playing) return;
        playHost = new ScriptHost(scene, this::log, projectDir);
        playing = true;
        int started = playHost.start();
        log("play: started (" + started + " script(s))");
    }

    /** one simulated frame while playing (physics + scripts) */
    public void playTick(double delta){
        if(!playing || playHost == null) return;
        playHost.stepWorld(delta);
    }

    public void playInput(String key){
        if(playing && playHost != null) playHost.input(key);
    }

    public void playStop(){
        if(!playing) return;
        playing = false;
        playHost = null;
        log("play: stopped");
    }

    /** run the scene headless: start scripts, step physics+scripts, dispatch inputs */
    public String run(int frames, double delta, List<String> inputs){
        ScriptHost host = new ScriptHost(scene, this::log, projectDir);
        int started = host.start();
        log("run: " + started + " script(s) bound");
        for(int i = 0; i < frames; i++) host.stepWorld(delta);
        for(String key : inputs) host.input(key);
        log("run: " + frames + " frames done");
        return runLog.toString();
    }

    // ------------------------- file dock -------------------------

    public List<String> projectFiles(){
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(projectDir)){
            s.filter(Files::isRegularFile)
             .map(p -> projectDir.relativize(p).toString())
             .sorted()
             .forEach(out::add);
        } catch(Exception ignored){ }
        return out;
    }

    /** Godot-style project manifest */
    public void saveProject(){
        try {
            JsonW w = new JsonW();
            w.obj().kv("hproject", 2).kv("name", scene.name)
              .kv("entry_scene", "scene.hscene").kv("engine", "2.0.0");
            w.endObj();
            Files.writeString(projectDir.resolve("project.json"), w.toString());
        } catch(Exception ignored){ }
    }

    public Map<String,Object> loadProject(Path file){
        try { return Json.parseObj(Files.readString(file)); }
        catch(Exception e){ return new LinkedHashMap<>(); }
    }
}
