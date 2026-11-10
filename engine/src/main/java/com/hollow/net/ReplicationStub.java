package com.hollow.net;

/** Phase 2: property replication.
 *
 *  Planned (NOT implemented in Phase 1 — scaffold only):
 *  - registerProperty(entityId, "position", ...) → dirty tracking per tick
 *  - delta snapshot messages (NetMessage types 1=SNAPSHOT, 2=RPC, 3=EVENT)
 *  - client-side prediction hooks + server reconciliation
 *  - interest management (grid-based relevancy sets)
 *
 *  Kept as a compile-time seam so games can reference replication APIs before Phase 2 lands. */
public final class ReplicationStub {
    private ReplicationStub() {}

    /** Phase 2: property replication — registers a replicated property slot (no-op in Phase 1). */
    public static void registerProperty(int entityId, String propertyName){
        // Phase 2: property replication
    }

    /** Phase 2: property replication — marks a property dirty for the next snapshot (no-op). */
    public static void markDirty(int entityId, String propertyName){
        // Phase 2: property replication
    }

    /** Phase 2: property replication — applies a remote snapshot to the world (no-op). */
    public static void applySnapshot(byte[] payload){
        // Phase 2: property replication
    }
}
