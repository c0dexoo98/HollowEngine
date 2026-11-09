package com.hollow.scene;

import com.hollow.core.*;

import java.util.*;

/** Entity with transform hierarchy + component bag (Unreal Actor model). */
public final class Entity {
    public final int id;
    public String name;
    public String tag = "";                 // logical tag for queries
    public boolean active = true;
    public boolean destroyed = false;
    public final Map<String, Component> components = new LinkedHashMap<>();

    // --- transform ---
    public final Vec3 position = new Vec3();
    public final Quat rotation = new Quat();
    public final Vec3 scale = new Vec3(1,1,1);
    Entity parent;
    final List<Entity> children = new ArrayList<>();
    public final Mat4 worldMatrix = new Mat4();
    public final Mat4 normalMatrix = new Mat4();
    boolean dirty = true;

    private static int NEXT_ID = 1;
    public static void resetIds(){ NEXT_ID = 1; }

    public Entity(String name){
        this.id = NEXT_ID++;
        this.name = name != null ? name : ("Entity" + id);
    }

    // ---------- hierarchy ----------
    public Entity parent(){ return parent; }
    public List<Entity> children(){ return children; }
    public void addChild(Entity c){
        if(c == this || c == null) return;
        if(c.parent != null) c.parent.children.remove(c);
        c.parent = this;
        children.add(c);
        c.markDirtyDeep();
    }
    public void removeChild(Entity c){
        if(children.remove(c)) c.parent = null;
        c.markDirtyDeep();
    }
    public Entity findChild(String name){
        for(Entity e : children) if(e.name.equals(name)) return e;
        return null;
    }

    // ---------- components ----------
    public Entity add(Component c){
        c.entity = this;
        components.put(c.type(), c);
        c.onAttach();
        return this;
    }
    @SuppressWarnings("unchecked")
    public <T extends Component> T get(Class<T> type){ return (T) components.get(type.getSimpleName()); }
    @SuppressWarnings("unchecked")
    public <T extends Component> T get(String typeName){ return (T) components.get(typeName); }
    public boolean has(String typeName){ return components.containsKey(typeName); }
    public Collection<Component> allComponents(){ return components.values(); }

    // ---------- transform ----------
    public Vec3 worldPosition(){ updateWorld(); return worldMatrix.translation(); }
    public Vec3 forward(){ return rotation.forward(); }
    public void lookAt(Vec3 target){
        Vec3 d = target.cpy().sub(worldPosition());
        if(d.len2() < 1e-8f) return;
        d.norm();
        float yaw = (float)Math.toDegrees(Math.atan2(-d.x, -d.z));
        float pitch = (float)Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, d.y))));
        rotation.set(Quat.fromEuler(pitch, yaw, 0));
        markDirtyDeep();
    }
    public void markDirtyDeep(){
        dirty = true;
        for(Entity c : children) c.markDirtyDeep();
    }
    public void updateWorld(){
        if(dirty){
            Mat4 local = Mat4.trs(position, rotation, scale);
            if(parent != null){ parent.updateWorld(); worldMatrix.set(parent.worldMatrix).mul(local); }
            else worldMatrix.set(local);
            normalMatrix.set(worldMatrix.normalMatrix());
            dirty = false;
            for(Component c : components.values()) c.onTransformChanged();
        }
    }
    public AABB worldAABB(AABB localBounds){
        updateWorld();
        AABB out = new AABB().reset();
        Vec3[] corners = localCornerCache.get();
        int k=0;
        for(int xi=0;xi<2;xi++) for(int yi=0;yi<2;yi++) for(int zi=0;zi<2;zi++){
            Vec3 lp = corners[k++];
            lp.set(xi==0?localBounds.min.x:localBounds.max.x,
                   yi==0?localBounds.min.y:localBounds.max.y,
                   zi==0?localBounds.min.z:localBounds.max.z);
            out.expand(worldMatrix.transformPoint(lp));
        }
        return out;
    }
    private static final ThreadLocal<Vec3[]> localCornerCache = ThreadLocal.withInitial(() ->
            new Vec3[]{ new Vec3(), new Vec3(), new Vec3(), new Vec3(), new Vec3(), new Vec3(), new Vec3(), new Vec3() });

    @Override public String toString(){ return name + "#" + id; }
}
