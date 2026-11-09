package com.hollow.scene;

/** Base class for all components (MeshRenderer, Light, Animator, ...).
 *  Component type name == class simple name, used for JSON mapping and lookup. */
public abstract class Component {
    public Entity entity;

    /** JSON type id, defaults to class simple name. */
    public String type(){ return getClass().getSimpleName(); }

    public void onAttach() {}
    public void onTransformChanged() {}
    /** fixed-step logic update */
    public void onUpdate(float dt) {}
    /** called when removed from world */
    public void onDetach() {}

    /** serialize custom fields into out */
    public abstract void save(com.hollow.core.JsonW out);
    /** restore fields from node */
    public abstract void load(com.hollow.core.JsonV node);
}
