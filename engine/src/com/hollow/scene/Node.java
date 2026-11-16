package com.hollow.scene;

import com.hollow.core.MathX;
import com.hollow.core.MathX.Mat4;
import com.hollow.core.MathX.Vec3;
import com.hollow.script.Interp;

import java.util.ArrayList;
import java.util.List;

/** HollowEngine 2 — scene-graph node (like Godot's Node3D).
 *  Position/rotation/scale compose down the tree: child world =
 *  parent world ∘ local. Implements Interp.Native so scripts can
 *  read and write properties with dot syntax. */
public class Node implements Interp.Native {

    public String name;
    public Node parent;
    public final List<Node> children = new ArrayList<>();
    public final Vec3 position = new Vec3(0, 0, 0);
    public final Vec3 rotation = new Vec3(0, 0, 0);      // euler degrees
    public final Vec3 scale = new Vec3(1, 1, 1);
    public boolean visible = true;

    public Node(String name){ this.name = name; }

    public String type(){ return "node"; }

    // ------------------------- tree -------------------------

    public void add(Node child){
        if(child == this || child == null) return;
        if(child.parent != null) child.parent.children.remove(child);
        child.parent = this;
        children.add(child);
    }
    public void remove(Node child){
        if(child == null) return;
        children.remove(child);
        child.parent = null;
    }
    public int childCount(){ return children.size(); }
    public Node child(int i){ return children.get(i); }

    public Node find(String name){
        if(this.name.equals(name)) return this;
        for(Node c : children){
            Node f = c.find(name);
            if(f != null) return f;
        }
        return null;
    }

    public void walk(java.util.function.Consumer<Node> fn){
        fn.accept(this);
        // snapshot: scripts may spawn/remove during the walk (signal safety)
        java.util.List<Node> snapshot = new ArrayList<>(children);
        for(Node c : snapshot) c.walk(fn);
    }

    // ------------------------- transforms -------------------------

    public Mat4 localMatrix(){
        return new Mat4().identity()
            .translate(position.x, position.y, position.z)
            .rotateEuler(rotation.x, rotation.y, rotation.z)
            .scale(scale.x, scale.y, scale.z);
    }

    public Mat4 worldMatrix(){
        if(parent == null) return localMatrix();
        return parent.worldMatrix().mul(localMatrix());
    }

    public Vec3 worldPosition(){
        return worldMatrix().transformPoint(new Vec3(0, 0, 0));
    }

    // ------------------------- update -------------------------

    public void update(double delta){
        java.util.List<Node> snapshot = new ArrayList<>(children);
        for(Node c : snapshot) c.update(delta);
    }

    // ------------------------- script surface -------------------------

    @Override public boolean hHas(String key){
        switch(key){
            case "name": case "type": case "visible":
            case "position_x": case "position_y": case "position_z":
            case "rotation_x": case "rotation_y": case "rotation_z":
            case "scale_x": case "scale_y": case "scale_z":
            case "child_count":
                return true;
            default: return false;
        }
    }
    @Override public Object hGet(String key){
        switch(key){
            case "name": return name;
            case "type": return type();
            case "visible": return visible;
            case "position_x": return (double)position.x;
            case "position_y": return (double)position.y;
            case "position_z": return (double)position.z;
            case "rotation_x": return (double)rotation.x;
            case "rotation_y": return (double)rotation.y;
            case "rotation_z": return (double)rotation.z;
            case "scale_x": return (double)scale.x;
            case "scale_y": return (double)scale.y;
            case "scale_z": return (double)scale.z;
            case "child_count": return (double)childCount();
            default: return null;
        }
    }
    @Override public void hSet(String key, Object v){
        double d = (v instanceof Number) ? ((Number)v).doubleValue() : Double.NaN;
        switch(key){
            case "name": name = String.valueOf(v); return;
            case "visible": visible = com.hollow.script.Interp.strOf(v).equals("true"); return;
            case "position_x": position.x = (float)d; return;
            case "position_y": position.y = (float)d; return;
            case "position_z": position.z = (float)d; return;
            case "rotation_x": rotation.x = (float)d; return;
            case "rotation_y": rotation.y = (float)d; return;
            case "rotation_z": rotation.z = (float)d; return;
            case "scale_x": scale.x = (float)d; return;
            case "scale_y": scale.y = (float)d; return;
            case "scale_z": scale.z = (float)d; return;
            default: throw new IllegalStateException("native '" + hTypeName() + "' has no property '" + key + "'");
        }
    }
    @Override public Object hCall(String fn, List<Object> args){
        if(fn.equals("find")){
            Node f = find(String.valueOf(args.get(0)));
            return f;
        }
        if(fn.equals("child")){
            int i = (int)(double)(Double)args.get(0);
            return child(i);
        }
        throw new IllegalStateException("native '" + hTypeName() + "' has no method '" + fn + "'");
    }
    @Override public String hTypeName(){ return type(); }

    @Override public String toString(){ return type() + ":" + name; }
}
