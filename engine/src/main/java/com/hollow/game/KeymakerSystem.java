package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v15.0 — keymaker system (HollowEngine 15.0 'trade & tide').
 *  The workshop holds brass blanks; cutting a key for a named lock
 *  costs one blank and always succeeds, but each duplicate struck
 *  rolls the file once — 90% a working copy, the rest botched (the
 *  blank is wasted). Keys remember the lock they were cut for.
 */
public final class KeymakerSystem {

    public static final class Key {
        public final int id;
        public final String lockName;
        public int copies = 0;
        public int botchedCopies = 0;
        public Key(int id, String lockName){
            this.id = id; this.lockName = lockName;
        }
    }

    public interface Listener { void onKeyholeEvent(String type, int keyId, String detail); }
    public Listener listener;

    public final Map<Integer, Key> keys = new LinkedHashMap<>();
    public int blanks = 0;
    public long seed = 0x1CEL;
    public int day = 0;
    private int nextId = 1;

    /** stock the bench with brass blanks */
    public boolean deliverBlanks(int units){
        if(units <= 0) return false;
        blanks += units;
        return true;
    }

    /** cut the master key for a named lock; one blank paid */
    public Key cut(String lockName){
        if(lockName == null || lockName.isEmpty() || blanks < 1) return null;
        blanks--;
        Key k = new Key(nextId++, lockName);
        keys.put(k.id, k);
        if(listener != null) listener.onKeyholeEvent("cut", k.id, lockName);
        return k;
    }

    /** strike a duplicate; one blank and one roll — good or botched */
    public boolean copy(int keyId){
        Key k = keys.get(keyId);
        if(k == null || blanks < 1) return false;
        blanks--;
        MathX.Rng rng = new MathX.Rng(seed ^ (k.id * 7919L) ^ (day * 131L));
        if(rng.range(0f, 1f) < 0.9f){
            k.copies++;
            if(listener != null) listener.onKeyholeEvent("copied", k.id, k.lockName);
        } else {
            k.botchedCopies++;
            if(listener != null) listener.onKeyholeEvent("botched", k.id, k.lockName);
        }
        return true;
    }

    /** does this key open that lock? */
    public boolean matches(int keyId, String lockName){
        Key k = keys.get(keyId);
        return k != null && lockName != null && k.lockName.equals(lockName);
    }

    public Key key(int id){ return keys.get(id); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("blanks", blanks).kv("nextId", nextId);
        w.arr("keys");
        for(Key k : keys.values())
            w.obj().kv("id", k.id).kv("l", k.lockName)
              .kv("c", k.copies).kv("bc", k.botchedCopies).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            blanks = (int)n.flt("blanks", blanks);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> ks = n.list("keys");
            if(ks == null) return;
            for(Object o : ks){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Key k = new Key(nextIdFor(m.get("id")), Json.str(m.get("l"), "?"));
                k.copies = (int)Json.num(m.get("c"), 0);
                k.botchedCopies = (int)Json.num(m.get("bc"), 0);
                keys.put(k.id, k);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
