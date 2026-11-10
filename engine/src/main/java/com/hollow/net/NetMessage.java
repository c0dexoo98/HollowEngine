package com.hollow.net;

/** Wire message: small int type tag + opaque payload (JSON/bytes chosen by the game layer).
 *  Phase 1 scaffold — framing only, no reliability/replication yet. */
public final class NetMessage {
    public final int type;
    public final byte[] payload;

    public NetMessage(int type, byte[] payload){
        this.type = type;
        this.payload = payload != null ? payload : new byte[0];
    }

    @Override public String toString(){ return "Msg{type=" + type + ", bytes=" + payload.length + "}"; }
}
