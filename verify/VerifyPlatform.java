import com.hollow.platform.PluginHost;
import com.hollow.platform.MobileGovernor;
import com.hollow.render.PlanarReflection;
import com.hollow.render.RendererConfig;
import com.hollow.core.Mat4;
import com.hollow.core.Vec3;

/**
 * v3.0.0 platform regression suite: plugin lifecycle/ordering/quarantine,
 * mobile governor ladder, planar reflection math.
 */
public class VerifyPlatform {
    static int fails = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        if(!ok) fails++;
    }

    /** no-op context for plugin tests */
    static final PluginHost.Context CTX = new PluginHost.Context(){
        public com.hollow.core.Console console(){ return new com.hollow.core.Console(); }
        public com.hollow.script.EventBus events(){ return new com.hollow.script.EventBus(); }
        public java.util.Map<String,Object> state(){ return new java.util.HashMap<>(); }
    };

    public static void main(String[] a){
        // ================= PLUGINS =================
        PluginHost host = new PluginHost(CTX);
        // ordering: core loads before dependent even when registered after
        StringBuilder order = new StringBuilder();
        host.register(new PluginHost.HollowPlugin(){
            public String id(){ return "core-lib"; }
            public void onLoad(PluginHost.Context c){ order.append("core,"); }
        });
        host.register(new PluginHost.HollowPlugin(){
            public String id(){ return "hud-mod"; }
            public String[] dependsOn(){ return new String[]{"core-lib"}; }
            public void onLoad(PluginHost.Context c){ order.append("hud,"); }
        });
        check("plugin: register + load order follows registration for deps",
              order.toString().equals("core,hud,"), "order=" + order);
        check("plugin: dependencyOrder puts core before dependent",
              PluginHost.dependencyOrder(java.util.Arrays.asList(
                  dep("mod-b", "mod-a"), base("mod-a")))
              .stream().map(PluginHost.HollowPlugin::id)
              .collect(java.util.stream.Collectors.joining(",")).equals("mod-a,mod-b"),
              "topo");
        // duplicate id rejected
        check("plugin: duplicate id rejected",
              !host.register(base("core-lib")), "dup accepted");
        // update ticks + quarantine on crash
        final boolean[] crashAfterFirst = { false };
        PluginHost.HollowPlugin crashy = new PluginHost.HollowPlugin(){
            public String id(){ return "crashy"; }
            public void onUpdate(PluginHost.Context c, float dt){
                if(crashAfterFirst[0]) throw new RuntimeException("boom");
            }
        };
        host.register(crashy);
        host.update(0.016f);
        crashAfterFirst[0] = true;
        host.update(0.016f);
        host.update(0.016f);
        check("plugin: crashing plugin quarantined (disabled) after exception",
              !host.loaded.get("crashy").enabled, "still enabled");
        check("plugin: healthy plugins keep ticking through a crash",
              host.loaded.get("hud-mod").enabled, "hud disabled");
        // enable/disable + unload
        host.setEnabled("hud-mod", false);
        long before = host.loaded.get("hud-mod").ticks;
        host.update(0.016f);
        check("plugin: disabled plugin stops ticking",
              host.loaded.get("hud-mod").ticks == before, "ticked");
        final boolean[] unloaded = { false };
        PluginHost.HollowPlugin leaver = new PluginHost.HollowPlugin(){
            public String id(){ return "leaver"; }
            public void onUnload(){ unloaded[0] = true; }
        };
        host.register(leaver);
        check("plugin: unload fires onUnload", host.unload("leaver") && unloaded[0], "unload");
        host.unloadAll();
        check("plugin: unloadAll clears everything", host.loaded.isEmpty(), "left=" + host.loaded.keySet());

        // ================= MOBILE GOVERNOR =================
        MobileGovernor gov = new MobileGovernor();
        gov.level = MobileGovernor.Level.ULTRA;
        // sustained over-budget frames -> step down (2s at 60Hz = 120 misses)
        boolean changed = false;
        for(int s = 0; s < 130; s++) changed = gov.report(30f, 80f, 0.1f) || changed;   // 30ms >> 16.7 budget
        check("governor: sustained frame misses drop quality",
              changed && gov.level != MobileGovernor.Level.ULTRA, "level=" + gov.level);
        // thermal triggers fps cap 30
        gov.report(10f, 60f, 0.9f);
        check("governor: thermal > 0.8 caps fps to 30", gov.fpsCap == 30, "cap=" + gov.fpsCap);
        // battery saver caps fps
        MobileGovernor gov2 = new MobileGovernor();
        gov2.report(10f, 10f, 0f);   // battery 10%
        check("governor: battery < 15% engages saver (cap 30)",
              gov2.batterySaver && gov2.fpsCap == 30, "saver=" + gov2.batterySaver);
        // recovery: headroom climbs back up
        MobileGovernor gov3 = new MobileGovernor();
        gov3.level = MobileGovernor.Level.LOW;
        boolean climbed = false;
        for(int s = 0; s < 320; s++) climbed = gov3.report(8f, 90f, 0.1f) || climbed;   // 8ms << budget
        check("governor: sustained headroom climbs quality",
              climbed && gov3.level != MobileGovernor.Level.LOW, "level=" + gov3.level);
        // config mapping
        RendererConfig min = gov3.configFor(MobileGovernor.Level.MINIMAL);
        check("governor: MINIMAL config = 0.5 scale, no bloom/fxaa",
              min.resolutionScale == 0.5f && !min.bloom && !min.fxaa, "scale=" + min.resolutionScale);
        MobileGovernor govMin = new MobileGovernor();
        govMin.level = MobileGovernor.Level.MINIMAL;
        check("governor: aiUpdateHz degrades with level",
              new MobileGovernor().aiUpdateHz() == 20 && govMin.aiUpdateHz() == 5,
              "hz=" + new MobileGovernor().aiUpdateHz() + "/" + govMin.aiUpdateHz());

        // ================= PLANAR REFLECTION =================
        PlanarReflection pr = new PlanarReflection();
        pr.setPlane(new Vec3(0, 0, 0), new Vec3(0, 1, 0));   // floor y=0
        Vec3 eye = new Vec3(2, 5, 3);
        Vec3 mirrored = pr.mirrorEye(eye);
        check("reflection: eye mirrors across y=0 plane",
              Math.abs(mirrored.x - 2f) < 1e-4f && Math.abs(mirrored.y + 5f) < 1e-4f && Math.abs(mirrored.z - 3f) < 1e-4f,
              "m=" + mirrored);
        // forward vector reflects
        Vec3 fwd = pr.mirrorForward(new Vec3(0, -1, 0));
        check("reflection: down-looking forward becomes up-looking",
              Math.abs(fwd.y - 1f) < 1e-4f, "fwd=" + fwd);
        // camera above plane renders, below skips
        check("reflection: shouldRender gates from above",
              pr.shouldRender(eye) && !pr.shouldRender(new Vec3(0, -1, 0)), "gate");
        // clip plane math: n·x + d = 0 with n=(0,1,0), p=(0,0,0) -> d=0
        float[] clip = pr.clipPlane();
        check("reflection: clip plane (n, d) correct",
              clip[0] == 0f && clip[1] == 1f && clip[2] == 0f && Math.abs(clip[3]) < 1e-6f,
              "clip=" + java.util.Arrays.toString(clip));
        // mirror matrix: point reflection is involutive (M*M = I)
        Mat4 m = PlanarReflection.mirrorMatrix(new Vec3(0, 0, 0), new Vec3(0, 1, 0));
        Vec3 p = new Vec3(1.5f, 4f, -2f);
        Vec3 pm = m.transformPoint(p);
        check("reflection: matrix transform mirrors y",
              Math.abs(pm.x - p.x) < 1e-4f && Math.abs(pm.y + p.y) < 1e-4f && Math.abs(pm.z - p.z) < 1e-4f,
              "pm=" + pm);
        Vec3 back = m.transformPoint(pm);
        check("reflection: involution (double mirror = identity)",
              Math.abs(back.x - p.x) < 1e-3f && Math.abs(back.y - p.y) < 1e-3f && Math.abs(back.z - p.z) < 1e-3f,
              "back=" + back);
        // reflection view matrix valid (transform mirrored eye to origin)
        Mat4 view = pr.reflectionView(eye, new Vec3(0, 0, -1), new Vec3(0, 1, 0));
        Vec3 mEyeWorld = pr.mirrorEye(eye);
        Vec3 eyeInView = view.transformPoint(mEyeWorld);
        check("reflection: view matrix places mirrored eye at origin",
              eyeInView.len() < 1e-3f, "len=" + eyeInView.len());
        // fade by distance
        pr.fadeDistance = 10f; pr.intensity = 0.5f;
        check("reflection: fade falls with distance",
              Math.abs(pr.fadeAt(0f) - 0.5f) < 1e-4f && Math.abs(pr.fadeAt(5f) - 0.25f) < 1e-4f && pr.fadeAt(20f) == 0f,
              "fade(5)=" + pr.fadeAt(5f));

        System.out.println(fails == 0 ? "ALL PLATFORM REGRESSION TESTS PASSED" : fails + " TESTS FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static PluginHost.HollowPlugin base(String id){
        return new PluginHost.HollowPlugin(){ public String id(){ return id; } };
    }
    static PluginHost.HollowPlugin dep(String id, String dep){
        return new PluginHost.HollowPlugin(){
            public String id(){ return id; }
            public String[] dependsOn(){ return new String[]{dep}; }
        };
    }
}
