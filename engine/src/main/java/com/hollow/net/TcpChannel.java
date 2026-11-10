package com.hollow.net;

import com.hollow.core.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;

/** Minimal length-prefixed TCP channel (client + host side).
 *  - connect(host, port): client connect
 *  - accept(port): host side — lazily binds a server socket and polls for exactly one
 *    incoming connection (non-blocking: returns a connected channel or null); a single
 *    daemon thread accepts in the background.
 *  - send(msg): 4-byte big-endian length prefix + payload
 *  - poll(): drains the socket into complete NetMessages without blocking
 *  ~150-line scaffold: no TLS, no compression, no keepalive (Phase 2). */
public final class TcpChannel {
    private static final int MAX_PAYLOAD = 4 * 1024 * 1024;

    private Socket socket;
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean closed = false;
    private final ArrayDeque<Socket> incoming = new ArrayDeque<>();
    private final ArrayDeque<NetMessage> pending = new ArrayDeque<>();

    private DataInputStream in;
    private DataOutputStream out;
    // partial read state for poll()
    private int hdr = -1;                 // -1 = no header read yet
    private byte[] body;
    private int bodyRead = 0;

    // ---------------- client ----------------

    public boolean connect(String host, int port){
        try {
            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
            return true;
        } catch(IOException e){
            Log.w("Net", "connect failed: " + host + ":" + port + " — " + e.getMessage());
            return false;
        }
    }

    // ---------------- host ----------------

    /** non-blocking poll for an incoming connection; binds lazily on first call */
    public TcpChannel accept(int port){
        try {
            if(server == null && !closed){
                server = new ServerSocket(port);
                acceptThread = new Thread(this::acceptLoop, "hollow-accept-" + port);
                acceptThread.setDaemon(true);
                acceptThread.start();
            }
        } catch(IOException e){
            Log.w("Net", "listen failed on port " + port + ": " + e.getMessage());
            return null;
        }
        Socket s;
        synchronized(incoming){ s = incoming.pollFirst(); }
        if(s == null) return null;
        TcpChannel ch = new TcpChannel();
        ch.socket = s;
        try {
            s.setTcpNoDelay(true);
            ch.in = new DataInputStream(s.getInputStream());
            ch.out = new DataOutputStream(s.getOutputStream());
        } catch(IOException e){
            Log.w("Net", "accepted socket failed: " + e.getMessage());
            return null;
        }
        return ch;
    }

    private void acceptLoop(){
        while(!closed && server != null && !server.isClosed()){
            try {
                Socket s = server.accept();
                synchronized(incoming){ incoming.addLast(s); }
            } catch(IOException e){
                if(!closed) Log.d("Net", "accept loop ended");
                return;
            }
        }
    }

    // ---------------- io ----------------

    public boolean send(NetMessage msg){
        if(out == null || closed) return false;
        try {
            out.writeInt(msg.payload.length);
            out.write(msg.payload);
            out.flush();
            return true;
        } catch(IOException e){
            Log.w("Net", "send failed: " + e.getMessage());
            return false;
        }
    }

    /** returns a complete message or null (never blocks) */
    public NetMessage poll(){
        if(in == null || closed) return null;
        try {
            while(true){
                if(hdr < 0){
                    if(in.available() < 4) return null;
                    hdr = in.readInt();
                    if(hdr < 0 || hdr > MAX_PAYLOAD){
                        Log.w("Net", "bad frame size " + hdr + " — closing");
                        close();
                        return null;
                    }
                    body = new byte[hdr];
                    bodyRead = 0;
                }
                int avail = in.available();
                if(avail <= 0) return null;
                int n = in.read(body, bodyRead, Math.min(avail, body.length - bodyRead));
                if(n < 0){ close(); return null; }
                bodyRead += n;
                if(bodyRead >= body.length){
                    NetMessage m = new NetMessage(0, body);
                    hdr = -1; body = null; bodyRead = 0;
                    return m;
                }
            }
        } catch(IOException e){
            Log.d("Net", "poll: connection dropped");
            close();
            return null;
        }
    }

    public boolean isConnected(){
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public void close(){
        closed = true;
        try { if(socket != null) socket.close(); } catch(IOException ignored){}
        try { if(server != null) server.close(); } catch(IOException ignored){}
    }
}
