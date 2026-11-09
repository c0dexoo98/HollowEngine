package com.hollow.scene;

import com.hollow.core.JsonW;
import com.hollow.core.JsonV;

/** Base class for all components (MeshRenderer, Light, Animator, ...).
 *  Component type name == class simple name, used for JSON mapping and lookup.
 *  v2.2 (OBJ-020): components may own CHILD components (e.g. an emitter attached
 *  to a light, sub-emitters of a particle system). Children follow the parent's
 *  lifecycle: attach -> update -> detach, recursively. */
public abstract class Component {
    public Entity entity;

    /** OBJ-020: child component bag (rarely used; empty for plain components) */
    private java.util.List<Component> children;

    /** JSON type id, defaults to class simple name. */
    public String type(){ return getClass().getSimpleName(); }

    public void onAttach() {}
    public void onTransformChanged() {}
    /** fixed-step logic update */
    public void onUpdate(float dt) {}
    /** called when removed from world */
    public void onDetach() {}

    // ---------------- OBJ-020: child components ----------------

    public boolean hasChildren(){ return children != null && !children.isEmpty(); }
    public java.util.List<Component> childComponents(){
        if(children == null) children = new java.util.ArrayList<>();
        return children;
    }
    /** attach a child component (lifecycle runs with this component) */
    public Component addChildComponent(Component child){
        child.entity = entity;
        childComponents().add(child);
        if(entity != null) child.onAttach();
        return this;
    }
    public boolean removeChildComponent(Component child){
        if(children == null) return false;
        boolean removed = children.remove(child);
        if(removed) child.onDetach();
        return removed;
    }
    /** deep iteration: this component then all descendants (pre-order) */
    public void forEachDeep(java.util.function.Consumer<Component> fn){
        fn.accept(this);
        if(children != null) for(Component c : children) c.forEachDeep(fn);
    }

    /** serialize child components (call from save() when supported): appends "children":[...] */
    protected void saveChildren(JsonW w){
        if(children == null || children.isEmpty()) return;
        w.arr("children");
        for(Component c : children){
            w.obj(); c.save(w); w.endObj();
        }
        w.endArr();
    }
    /** restore child components; factory registry resolves "type" of each child */
    protected void loadChildren(JsonV node, Entity owner){
        java.util.List<Object> arr = node.list("children");
        if(arr == null) return;
        for(Object o : arr){
            java.util.Map<String, Object> cn = com.hollow.core.Json.obj(o);
            if(cn == null) continue;
            String type = com.hollow.core.Json.str(cn.get("type"), "");
            if(entity == null || entity.parent == null && owner == null){
                // registry lookup requires a world; walk up through entity
            }
            World w = worldOf();
            if(w == null) continue;
            Component child = Scene.createComponent(w, type, cn);
            if(child != null) addChildComponent(child);
        }
    }
    private World worldOf(){
        if(entity != null){
            // World backref is not stored on Entity; use the entity's registry hook:
            // components only need the registry at load time, provided by Scene.load context.
            return Scene.currentLoadingWorld;
        }
        return null;
    }

    /** serialize custom fields into out */
    public abstract void save(com.hollow.core.JsonW out);
    /** restore fields from node */
    public abstract void load(com.hollow.core.JsonV node);
}
