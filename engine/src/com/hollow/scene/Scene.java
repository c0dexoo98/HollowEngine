package com.hollow.scene;

import com.hollow.core.Json;
import com.hollow.core.Json.JsonW;
import com.hollow.script.Interp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** HollowEngine 2 — a scene: node tree + ambient settings, saved and
 *  loaded as .hscene JSON. Scripts see the scene through the Native
 *  surface below (find/spawn/remove/count). */
public final class Scene implements Interp.Native {

    public final Node root = new Node("root");
    public String clearColor = "sky";
    public float ambient = 0.35f;
    public String name = "scene";

    public Node createNode(String type, String node){
        String t = type == null ? "node" : type;
        Node n;
        switch(t){
            case "mesh": n = new MeshNode(node); break;
            case "camera": n = new CameraNode(node); break;
            case "light": n = new LightNode(node); break;
            case "script": n = new ScriptNode(node); break;
            case "node": n = new Node(node); break;
            default: throw new IllegalArgumentException("unknown node type '" + type + "'");
        }
        return n;
    }

    public void add(Node n){ root.add(n); }

    public Node find(String nodeName){
        if(nodeName == null || nodeName.isEmpty()) return null;
        return root.find(nodeName);
    }

    public int count(){ return root.childCount(); }

    public CameraNode activeCamera(){
        final CameraNode[] cam = new CameraNode[1];
        root.walk(n -> { if(n instanceof CameraNode && ((CameraNode)n).active && cam[0] == null) cam[0] = (CameraNode)n; });
        return cam[0];
    }

    public void update(double delta){ root.update(delta); }

    // ------------------------- script surface -------------------------

    @Override public boolean hHas(String key){
        switch(key){
            case "name": case "clear_color": case "ambient": case "node_count":
                return true;
            default: return false;
        }
    }
    @Override public Object hGet(String key){
        switch(key){
            case "name": return name;
            case "clear_color": return clearColor;
            case "ambient": return (double)ambient;
            case "node_count": return (double)count();
            default: return null;
        }
    }
    @Override public void hSet(String key, Object v){
        switch(key){
            case "name": name = String.valueOf(v); return;
            case "clear_color": clearColor = String.valueOf(v); return;
            case "ambient": ambient = (float)(double)(Double)v; return;
            default: throw new IllegalStateException("scene has no property '" + key + "'");
        }
    }
    @Override public Object hCall(String fn, List<Object> args){
        switch(fn){
            case "find":
                return find(String.valueOf(args.get(0)));
            case "spawn": {
                return spawn(String.valueOf(args.get(0)), String.valueOf(args.get(1)));
            }
            case "remove": {
                Node n = find(String.valueOf(args.get(0)));
                if(n != null) root.remove(n);
                return n != null;
            }
            case "count":
                return (double)count();
            default:
                throw new IllegalStateException("scene has no method '" + fn + "'");
        }
    }

    /** idempotent spawn: returns the existing node if the name is taken */
    public Node spawn(String type, String name){
        Node existing = find(name);
        if(existing != null) return existing;
        Node n = createNode(type, name);
        add(n);
        return n;
    }
    @Override public String hTypeName(){ return "scene"; }

    // ------------------------- persistence -------------------------

    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("hscene", 2).kv("name", name).kv("clear_color", clearColor).kv("ambient", ambient);
        w.obj("root");
        writeNodeBody(w, root);
        w.endObj();
        w.endObj();
        return w.toString();
    }

    private void writeNode(JsonW w, Node n){
        w.obj();
        writeNodeBody(w, n);
        w.endObj();
    }

    private void writeNodeBody(JsonW w, Node n){
        w.kv("name", n.name).kv("type", n.type());
        w.arr("position").item((double)n.position.x).item((double)n.position.y).item((double)n.position.z).endArr();
        w.arr("rotation").item((double)n.rotation.x).item((double)n.rotation.y).item((double)n.rotation.z).endArr();
        w.arr("scale").item((double)n.scale.x).item((double)n.scale.y).item((double)n.scale.z).endArr();
        w.kv("visible", n.visible);
        if(n instanceof MeshNode) w.kv("mesh", ((MeshNode)n).meshPath).kv("color", ((MeshNode)n).color);
        if(n instanceof CameraNode){
            CameraNode c = (CameraNode)n;
            w.kv("fov", (double)c.fov).kv("near", (double)c.near).kv("far", (double)c.far).kv("active", c.active);
        }
        if(n instanceof LightNode) w.kv("color", ((LightNode)n).color).kv("intensity", (double)((LightNode)n).intensity);
        if(n instanceof ScriptNode) w.kv("path", ((ScriptNode)n).path);
        w.arr("children");
        for(Node c : n.children) writeNode(w, c);
        w.endArr();
    }

    public void load(String json){
        Map<String,Object> m = Json.parseObj(json);
        name = Json.str(m.get("name"), "scene");
        clearColor = Json.str(m.get("clear_color"), "sky");
        ambient = (float)Json.num(m.get("ambient"), 0.35f);
        Map<String,Object> rm = Json.obj(m.get("root"));
        if(rm == null) return;
        Node r = readNode(rm);
        root.children.clear();
        java.util.List<Node> kids = new ArrayList<>(r.children);
        r.children.clear();
        for(Node c : kids){
            c.parent = null;
            root.add(c);
        }
        root.name = Json.str(rm.get("name"), "root");
    }

    @SuppressWarnings("unchecked")
    private Node readNode(Map<String,Object> m){
        Node n = createNode(Json.str(m.get("type"), "node"), Json.str(m.get("name"), "node"));
        List<Object> pos = (List<Object>)m.getOrDefault("position", java.util.List.of(0d, 0d, 0d));
        List<Object> rot = (List<Object>)m.getOrDefault("rotation", java.util.List.of(0d, 0d, 0d));
        List<Object> scl = (List<Object>)m.getOrDefault("scale", java.util.List.of(1d, 1d, 1d));
        n.position.set((float)Json.num(pos.size() > 0 ? pos.get(0) : null, 0),
                       (float)Json.num(pos.size() > 1 ? pos.get(1) : null, 0),
                       (float)Json.num(pos.size() > 2 ? pos.get(2) : null, 0));
        n.rotation.set((float)Json.num(rot.size() > 0 ? rot.get(0) : null, 0),
                       (float)Json.num(rot.size() > 1 ? rot.get(1) : null, 0),
                       (float)Json.num(rot.size() > 2 ? rot.get(2) : null, 0));
        n.scale.set((float)Json.num(scl.size() > 0 ? scl.get(0) : null, 1),
                    (float)Json.num(scl.size() > 1 ? scl.get(1) : null, 1),
                    (float)Json.num(scl.size() > 2 ? scl.get(2) : null, 1));
        n.visible = Json.bool(m.get("visible"), true);
        if(n instanceof MeshNode){
            ((MeshNode)n).meshPath = Json.str(m.get("mesh"), "");
            ((MeshNode)n).color = Json.str(m.get("color"), "silver");
        }
        if(n instanceof CameraNode){
            CameraNode c = (CameraNode)n;
            c.fov = (float)Json.num(m.get("fov"), 60f);
            c.near = (float)Json.num(m.get("near"), 0.1f);
            c.far = (float)Json.num(m.get("far"), 500f);
            c.active = Json.bool(m.get("active"), true);
        }
        if(n instanceof LightNode){
            ((LightNode)n).color = Json.str(m.get("color"), "white");
            ((LightNode)n).intensity = (float)Json.num(m.get("intensity"), 1.0f);
        }
        if(n instanceof ScriptNode) ((ScriptNode)n).path = Json.str(m.get("path"), "");
        List<Object> kids = Json.arr(m.get("children"));
        if(kids != null) for(Object o : kids){
            Map<String,Object> km = Json.obj(o);
            if(km != null) n.add(readNode(km));
        }
        return n;
    }
}
