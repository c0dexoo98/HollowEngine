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

    // --- v2.2 OBJ-017: physics/render layer bitmask (per-channel filters) ---
    public int layers = 0xFFFFFFFF;         // all 32 layers on by default

    // --- v2.2 OBJ-026: custom metadata (key/value) ---
    private Map<String, Object> meta;

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
        Entity oldParent = c.parent;
        if(oldParent != null) oldParent.children.remove(c);
        c.parent = this;
        children.add(c);
        c.markDirtyDeep();
        if(oldParent != null) oldParent.fireChildRemoved(c, false);
    }
    public void removeChild(Entity c){
        if(children.remove(c)){
            c.parent = null;
            c.markDirtyDeep();
            fireChildRemoved(c, false);
        }
    }
    /** removal without event (used by World.flushDestroy — destroy() already fired) */
    void removeChildSilent(Entity c){
        if(children.remove(c)){
            c.parent = null;
            c.markDirtyDeep();
        }
    }

    /** OBJ-028: safe subtree removal — fires onChildRemoved for every removed child
     *  (bottom-up) and detaches components. The subtree is NOT destroyed here;
     *  pair with World.destroy for full teardown. */
    public void removeSubtree(Entity subtree){
        if(subtree == null) return;
        // recursively notify deepest-first
        for(Entity child : new ArrayList<>(subtree.children)) subtree.removeSubtree(child);
        removeChild(subtree);
        for(Component c : subtree.allComponents()) c.onDetach();
    }

    // ---------- OBJ-028: child-removed listeners ----------
    public interface ChildRemovedListener { void onChildRemoved(Entity child, boolean destroyed); }
    final List<ChildRemovedListener> childListeners = new ArrayList<>();
    public void addListener(ChildRemovedListener l){ childListeners.add(l); }
    public void removeListener(ChildRemovedListener l){ childListeners.remove(l); }
    void fireChildRemoved(Entity child, boolean destroyed){
        for(int i=0;i<childListeners.size();i++) childListeners.get(i).onChildRemoved(child, destroyed);
    }
    public Entity findChild(String name){
        for(Entity e : children) if(e.name.equals(name)) return e;
        return null;
    }

    // ---------- OBJ-027: hierarchy merge/split preserving world transform ----------

    /** reparent under newParent keeping the world transform intact. */
    public void reparentPreservingWorld(Entity newParent){
        Vec3 wp = worldPosition();
        Quat wr = worldRotation();
        Vec3 ws = worldScale();
        if(parent != null) parent.children.remove(this);
        parent = newParent;
        if(newParent != null){
            newParent.children.add(this);
            // local = inverse(newParent.world) * oldWorld
            newParent.updateWorld();
            Mat4 inv = newParent.worldMatrix.invert();
            Mat4 world = Mat4.trs(wp, wr, ws);
            Mat4 local = inv.mul(world);
            decompose(local, position, rotation, scale);
        } else {
            position.set(wp); rotation.set(wr); scale.set(ws);
        }
        markDirtyDeep();
    }

    /** detach from parent keeping the current world transform (becomes root) */
    public void detachPreservingWorld(){ reparentPreservingWorld(null); }

    /** OBJ-029: depth of this entity in the hierarchy (root = 0) */
    public int depth(){
        int d = 0;
        Entity p = parent;
        while(p != null){ d++; p = p.parent; }
        return d;
    }

    /** OBJ-029: pre-order traversal of the subtree rooted at this entity */
    public void traverse(java.util.function.Consumer<Entity> fn){
        fn.accept(this);
        for(Entity c : children) c.traverse(fn);
    }

    /** OBJ-027 helper: decompose a TRS matrix back into components */
    public static void decompose(Mat4 m, Vec3 outPos, Quat outRot, Vec3 outScale){
        outPos.set(m.m[12], m.m[13], m.m[14]);
        float sx = new Vec3(m.m[0], m.m[1], m.m[2]).len();
        float sy = new Vec3(m.m[4], m.m[5], m.m[6]).len();
        float sz = new Vec3(m.m[8], m.m[9], m.m[10]).len();
        // sign of determinant (negative scale flips an axis)
        float det = m.m[0]*(m.m[5]*m.m[10]-m.m[6]*m.m[9])
                  - m.m[4]*(m.m[1]*m.m[10]-m.m[2]*m.m[9])
                  + m.m[8]*(m.m[1]*m.m[6]-m.m[2]*m.m[5]);
        if(det < 0){ sx = -sx; }
        outScale.set(sx, sy, sz);
        if(sx == 0 || sy == 0 || sz == 0){ outRot.identity(); return; }
        Mat4 rot = new Mat4();
        rot.m[0]=m.m[0]/sx; rot.m[1]=m.m[1]/sx; rot.m[2]=m.m[2]/sx;
        rot.m[4]=m.m[4]/sy; rot.m[5]=m.m[5]/sy; rot.m[6]=m.m[6]/sy;
        rot.m[8]=m.m[8]/sz; rot.m[9]=m.m[9]/sz; rot.m[10]=m.m[10]/sz;
        outRot.set(OBB.fromAxes(new Vec3(rot.m[0], rot.m[1], rot.m[2]),
                                new Vec3(rot.m[4], rot.m[5], rot.m[6]),
                                new Vec3(rot.m[8], rot.m[9], rot.m[10])));
    }

    public Vec3 worldRotationEuler(){ return new Vec3(rotation.pitchDeg(), rotation.yawDeg(), 0); }

    /** world rotation (best-effort from world matrix) */
    public Quat worldRotation(){
        updateWorld();
        Vec3 p = new Vec3(), s = new Vec3(); Quat r = new Quat();
        decompose(worldMatrix, p, r, s);
        return r;
    }
    /** world scale */
    public Vec3 worldScale(){
        updateWorld();
        return new Vec3(
            new Vec3(worldMatrix.m[0], worldMatrix.m[1], worldMatrix.m[2]).len(),
            new Vec3(worldMatrix.m[4], worldMatrix.m[5], worldMatrix.m[6]).len(),
            new Vec3(worldMatrix.m[8], worldMatrix.m[9], worldMatrix.m[10]).len());
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

    // ---------- OBJ-026: metadata ----------
    public Map<String, Object> meta(){
        if(meta == null) meta = new LinkedHashMap<>();
        return meta;
    }
    public Entity setMeta(String key, Object value){ meta().put(key, value); return this; }
    public Object getMeta(String key){ return meta == null ? null : meta.get(key); }
    public Object getMeta(String key, Object def){ Object v = getMeta(key); return v != null ? v : def; }
    public boolean hasMeta(String key){ return meta != null && meta.containsKey(key); }

    // ---------- OBJ-017: layers ----------
    public boolean inLayer(int bit){ return (layers & bit) != 0; }
    public Entity setLayers(int mask){ layers = mask; return this; }
    public Entity enableLayer(int bit){ layers |= bit; return this; }
    public Entity disableLayer(int bit){ layers &= ~bit; return this; }

    // ---------- OBJ-019: bone sockets (Entity-level API over anim.Attachment) ----------

    /** pin this entity to a bone of `target` every frame (weapon in hand, hat on head). */
    public Entity attachToBone(Entity target, String bone, Vec3 offset, boolean rotateWithBone){
        com.hollow.anim.Attachment a = get(com.hollow.anim.Attachment.class);
        if(a == null){ a = new com.hollow.anim.Attachment(); add(a); }
        a.target = target != null ? target.name : "";
        a.bone = bone != null ? bone : "";
        if(offset != null) a.offset.set(offset);
        a.rotateWithBone = rotateWithBone;
        return this;
    }
    public Entity attachToBone(Entity target, String bone){ return attachToBone(target, bone, null, true); }
    /** remove any bone attachment (entity returns to free transform) */
    public void detachFromBone(){
        com.hollow.anim.Attachment a = get(com.hollow.anim.Attachment.class);
        if(a != null){ components.remove(a.type()); a.onDetach(); }
    }
    public boolean isBoneAttached(){ return has("Attachment"); }
    public String attachedBone(){
        com.hollow.anim.Attachment a = get(com.hollow.anim.Attachment.class);
        return a != null ? a.bone : null;
    }

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
