package com.hollow.debug;

import com.hollow.core.Json;
import com.hollow.core.Profiler;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** EXT-009 — remote profiler: encodes engine profile frames (Profiler CSV + system
 *  stats) into compact JSON packets a desktop client renders as a live table/graph.
 *  Transport is pluggable (TCP socket on device, loopback pair in tests). */
public final class RemoteProfiler {
    private RemoteProfiler() {}

    public static final int PROTO_VERSION = 1;

    public static final class Frame {
        public long tick;
        public double frameMs;
        public long heapUsed;
        public final List<String[]> regions = new ArrayList<>();   // {name, ms, calls}
        public final Map<String, Object> extras = new LinkedHashMap<>();
    }

    /** collector: engine side polls Profiler + MemoryStats once per frame. */
    public static final class Collector {
        public final Frame current = new Frame();
        public long framesSent = 0;
        public int gzipThreshold = 2048;    // compress larger payloads

        public void beginFrame(long tick){ current.tick = tick; current.regions.clear(); current.extras.clear(); }
        public void addRegion(String name, double ms, long calls){
            current.regions.add(new String[]{ name, String.format("%.3f", ms), String.valueOf(calls) });
        }
        public void finishFrame(double frameMs){
            current.frameMs = frameMs;
            current.heapUsed = com.hollow.core.MemoryStats.snapshot().heapUsed;
        }

        public byte[] encode(){
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("t", "prof");
            m.put("v", (double)PROTO_VERSION);
            m.put("tick", (double)current.tick);
            m.put("frameMs", current.frameMs);
            m.put("heap", (double)current.heapUsed);
            List<Object> regs = new ArrayList<>();
            for(String[] r : current.regions){
                Map<String,Object> reg = new LinkedHashMap<>();
                reg.put("name", r[0]); reg.put("ms", Double.parseDouble(r[1])); reg.put("calls", Double.parseDouble(r[2]));
                regs.add(reg);
            }
            m.put("regions", regs);
            m.put("extras", new LinkedHashMap<>(current.extras));
            framesSent++;
            return Json.write(m).getBytes(StandardCharsets.UTF_8);
        }

        /** gzip wrapper for large frames (self-describing: 0x1F8B prefix passes through). */
        public byte[] encodePacked(){
            byte[] raw = encode();
            if(raw.length < gzipThreshold) return raw;
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (GZIPOutputStream gz = new GZIPOutputStream(bos)){ gz.write(raw); }
                return bos.toByteArray();
            } catch(Exception e){ return raw; }
        }
    }

    /** desktop-side decoder + table renderer. */
    public static final class Client {
        public long frames = 0;
        public double lastFrameMs = 0;
        public final List<double[]> history = new ArrayList<>();   // {tick, frameMs, heap}

        public boolean decode(byte[] payload){
            byte[] data = payload;
            if(data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B){
                try {
                    java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(data);
                    try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(bis)){
                        data = gz.readAllBytes();
                    }
                } catch(Exception e){ return false; }
            }
            try {
                Map<String,Object> m = Json.parseObj(new String(data, StandardCharsets.UTF_8));
                if(!"prof".equals(m.get("t"))) return false;
                frames++;
                lastFrameMs = Json.num(m.get("frameMs"), 0);
                history.add(new double[]{ Json.num(m.get("tick"), 0), lastFrameMs, Json.num(m.get("heap"), 0) });
                while(history.size() > 600) history.remove(0);
                return true;
            } catch(Exception e){ return false; }
        }

        /** live table (CLI/desktop UI) */
        public String renderTable(){
            StringBuilder b = new StringBuilder();
            b.append(String.format("frame=%6.2f ms  heap=%6.1f MB  frames=%d%n",
                lastFrameMs,
                history.isEmpty() ? 0 : history.get(history.size()-1)[2] / 1048576.0,
                frames));
            double avg = 0;
            for(double[] h : history) avg += h[1];
            if(!history.isEmpty()) avg /= history.size();
            b.append(String.format("avg=%6.2f ms over %d frames%n", avg, history.size()));
            return b.toString();
        }
    }
}
