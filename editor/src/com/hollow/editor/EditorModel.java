package com.hollow.editor;

import com.hollow.core.Json;
import com.hollow.core.Json.JsonW;
import com.hollow.render.Frame;
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

    public EditorModel(){ }
    public EditorModel(Path projectDir){
        this.projectDir = projectDir;
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
        Node n = scene.createNode(type, name);
        scene.add(n);
        selected = name;
        log("added " + type + " '" + name + "'");
        return n;
    }

    public boolean deleteNode(String name){
        Node n = scene.find(name);
        if(n == null || n == scene.root) return false;
        scene.root.remove(n);
        if(selected.equals(name)) selected = "";
        log("deleted '" + name + "'");
        return true;
    }

    public boolean renameNode(String oldName, String newName){
        Node n = scene.find(oldName);
        if(n == null || newName == null || newName.isEmpty() || scene.find(newName) != null) return false;
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
        return props;
    }

    /** set one property through the same schema; returns false if unknown */
    public boolean setProperty(String name, String key, Object value){
        Node n = scene.find(name);
        if(n == null || !properties(name).containsKey(key)) return false;
        if(key.equals("type")) return false;
        try {
            switch(key){
                case "name":
                    if(!renameNode(name, String.valueOf(value))) return false;
                    return true;
                case "visible": n.visible = Boolean.parseBoolean(String.valueOf(value)); return true;
                case "active": if(n instanceof CameraNode) ((CameraNode)n).active = Boolean.parseBoolean(String.valueOf(value)); return true;
                case "mesh": if(n instanceof MeshNode) ((MeshNode)n).meshPath = String.valueOf(value); return true;
                case "color": if(n instanceof MeshNode) ((MeshNode)n).color = String.valueOf(value);
                              if(n instanceof LightNode) ((LightNode)n).color = String.valueOf(value);
                              return true;
                case "intensity": if(n instanceof LightNode) ((LightNode)n).intensity = (float)num(value); return true;
                case "fov": if(n instanceof CameraNode) ((CameraNode)n).fov = (float)num(value); return true;
                case "path": if(n instanceof ScriptNode) ((ScriptNode)n).path = String.valueOf(value); return true;
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

    private double num(Object v){
        if(v instanceof Number) return ((Number)v).doubleValue();
        return Double.parseDouble(String.valueOf(v));
    }

    // ------------------------- viewport + run -------------------------

    public Frame renderPreview(int w, int h){
        return new Renderer(projectDir).render(scene, w, h);
    }

    /** run the scene headless: start scripts, advance frames, dispatch inputs */
    public String run(int frames, double delta, List<String> inputs){
        ScriptHost host = new ScriptHost(scene, this::log, projectDir);
        int started = host.start();
        log("run: " + started + " script(s) bound");
        for(int i = 0; i < frames; i++) host.tickScripts(delta);
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
