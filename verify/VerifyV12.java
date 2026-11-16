import com.hollow.game.ApprenticeSystem;
import com.hollow.game.BakerySystem;
import com.hollow.game.BlacksmithSystem;
import com.hollow.game.CarpenterSystem;
import com.hollow.world.BridgeSystem;
import com.hollow.world.FisherySystem;
import com.hollow.world.MillSystem;
import com.hollow.world.PastureSystem;

import java.util.ArrayList;
import java.util.List;

/** v12.0.0 regression suite — forge, fisheries, apprentices, mills,
 *  bridges, bakeries, pastures and the carpenter workshop. */
public class VerifyV12 {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        forge();
        fisheries();
        apprentices();
        mills();
        bridges();
        bakeries();
        pastures();
        workshop();
        System.out.println(fails == 0 ? "\nALL " + total + " V12 TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " V12 TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void forge(){
        BlacksmithSystem bs = new BlacksmithSystem();
        final List<String> events = new ArrayList<>();
        bs.listener = (type, id, detail) -> events.add(type);

        check("forg: invalid orders refused", bs.takeOrder("", 2) == null
              && bs.takeOrder("nail", 0) == null, "");
        BlacksmithSystem.Commission c = bs.takeOrder("horseshoe", 2);
        check("forg: order taken", c != null && c.id == 1 && bs.activeOrder() == c, "");
        check("forg: one order at a time", bs.takeOrder("nail", 1) == null, "");

        bs.tickDay();
        check("forg: hammering", c.progress == 1 && c.quality < 0f, "");
        bs.tickDay();
        check("forg: piece forged", c.quality >= 0.4f && c.quality <= 0.8f
              && bs.finishedCount() == 1 && events.contains("forged"), "q=" + c.quality);
        check("forg: skill sharpened", Math.abs(bs.skill - 0.52f) < 0.0001f, "" + bs.skill);
        BlacksmithSystem.Commission c2 = bs.takeOrder("nail", 1);
        check("forg: forge free again", c2 != null && c2.id == 2, "");

        String json = bs.save();
        BlacksmithSystem bs2 = new BlacksmithSystem();
        bs2.load(json);
        check("forg: save/load", Math.abs(bs2.skill - 0.52f) < 0.0001f
              && bs2.finishedCount() == 1 && bs2.activeOrder() != null
              && bs2.activeOrder().item.equals("nail"), "");
        bs2.tickDay();
        boolean forgeFree = bs2.activeOrder() == null;   // nail finished → forge free
        BlacksmithSystem.Commission c3 = bs2.takeOrder("blade", 1);
        check("forg: loaded order finishes", forgeFree && c3 != null && c3.id == 3, "");
    }

    // =====================================================================
    static void fisheries(){
        FisherySystem fs = new FisherySystem();
        final List<String> events = new ArrayList<>();
        fs.listener = (type, s, v) -> events.add(type);

        check("fish: invalid open refused", !fs.open("") && fs.open("oak") && !fs.open("oak"), "");
        check("fish: invalid launch refused", !fs.launch("iron", 3) && !fs.launch("oak", 11)
              && !fs.launch("oak", -1) && fs.launch("oak", 3), "");

        // deterministic weather: scan a calm first day and a stormy first day
        FisherySystem calm = null, stormy = null;
        for(int s2 = 1; s2 <= 400 && (calm == null || stormy == null); s2++){
            FisherySystem probe = new FisherySystem();
            probe.seed = s2;
            probe.open("oak");
            probe.launch("oak", 3);
            probe.tickDay();
            if(probe.ground("oak").storms > 0){ if(stormy == null) stormy = probe; }
            else if(calm == null) calm = probe;
        }
        if(calm != null){
            check("fish: calm day hauls by stock", Math.abs(calm.catchOf("oak") - 6f) < 0.001f
                  && Math.abs(calm.ground("oak").stock - 98f) < 0.001f, "" + calm.catchOf("oak"));
            check("fish: haul collects", Math.abs(calm.haul("oak") - 6f) < 0.001f
                  && Math.abs(calm.catchOf("oak")) < 0.001f && calm.haul("oak") == 0f, "");
        } else {
            check("fish: calm day hauls by stock", false, "no calm seed in 400");
            check("fish: haul collects", false, "no calm seed in 400");
        }
        if(stormy != null){
            check("fish: storm keeps fleet ashore", Math.abs(stormy.catchOf("oak")) < 0.001f
                  && stormy.ground("oak").storms == 1, "");
        } else {
            check("fish: storm keeps fleet ashore", false, "no storm seed in 400");
        }

        String json = calm.save();
        FisherySystem fs2 = new FisherySystem();
        fs2.load(json);
        check("fish: save/load", fs2.ground("oak") != null
              && Math.abs(fs2.ground("oak").stock - 98f) < 0.001f
              && Math.abs(fs2.catchOf("oak")) < 0.001f && fs2.ground("oak").boats == 3, "");
    }

    // =====================================================================
    static void apprentices(){
        ApprenticeSystem as = new ApprenticeSystem();
        final List<String> events = new ArrayList<>();
        as.listener = (type, who, detail) -> events.add(type);

        check("apr: invalid binds refused", !as.bind("", "pip", "smith", 3)
              && !as.bind("mira", "", "smith", 3) && !as.bind("zed", "zed", "smith", 3)
              && as.bind("mira", "pip", "smith", 3) && !as.bind("other", "pip", "cook", 3), "");
        check("apr: binding recorded", as.bindingOf("pip") != null
              && as.bindingOf("pip").master.equals("mira"), "");

        as.tickDay(); as.tickDay();
        check("apr: training days count", as.bindingOf("pip").daysTrained == 2, "");
        as.tickDay();
        check("apr: journeyman released", as.journeymen == 1 && as.bindingCount() == 0
              && events.contains("journeyman"), "");

        check("apr: quit works once", as.bind("mira", "pip", "smith", 5) && as.quit("pip")
              && !as.quit("pip") && !as.quit("ghost") && as.journeymen == 1, "");

        ApprenticeSystem as2 = new ApprenticeSystem();
        as2.bind("mira", "pip", "smith", 2);
        as2.bind("mira", "wren", "cook", 3);
        as2.tickDay(); as2.tickDay(); as2.tickDay();
        check("apr: parallel graduations", as2.journeymen == 2 && as2.bindingCount() == 0, "");

        String json = as2.save();
        ApprenticeSystem as3 = new ApprenticeSystem();
        as3.load(json);
        check("apr: save/load", as3.journeymen == 2 && as3.bindingCount() == 0 && as3.day == 3, "");
    }

    // =====================================================================
    static void mills(){
        MillSystem ms = new MillSystem();
        final List<String> events = new ArrayList<>();
        ms.listener = (type, s, v) -> events.add(type);

        check("mill: invalid build refused", !ms.build("", 10) && ms.build("oak", 10)
              && !ms.build("oak", 5), "");
        check("mill: invalid delivery refused", !ms.deliverGrain("iron", 5) && !ms.deliverGrain("oak", 0)
              && !ms.deliverGrain("oak", 11) && ms.deliverGrain("oak", 8), "");

        // deterministic mechanism: scan a working day and a breakdown day
        MillSystem calm = null, broke = null;
        for(int s2 = 1; s2 <= 400 && (calm == null || broke == null); s2++){
            MillSystem probe = new MillSystem();
            probe.seed = s2;
            probe.build("oak", 10);
            probe.deliverGrain("oak", 8);
            probe.tickDay();
            if(probe.mill("oak").broken){ if(broke == null) broke = probe; }
            else if(calm == null) calm = probe;
        }
        if(calm != null){
            check("mill: grinding at daily rate", calm.mill("oak").grain == 3
                  && Math.abs(calm.flourOf("oak") - 5f) < 0.001f, "");
            check("mill: flour collected", Math.abs(calm.takeFlour("oak") - 5f) < 0.001f
                  && Math.abs(calm.flourOf("oak")) < 0.001f && calm.takeFlour("oak") == 0f, "");
        } else {
            check("mill: grinding at daily rate", false, "no calm seed in 400");
            check("mill: flour collected", false, "no calm seed in 400");
        }
        if(broke != null){
            check("mill: breakdown stops grinding", broke.mill("oak").grain == 8
                  && broke.mill("oak").breakdowns == 1 && broke.repair("oak")
                  && !broke.mill("oak").broken && !broke.repair("oak"), "");
        } else {
            check("mill: breakdown stops grinding", false, "no breakdown seed in 400");
        }

        String json = calm.save();
        MillSystem ms2 = new MillSystem();
        ms2.load(json);
        check("mill: save/load", ms2.mill("oak") != null && ms2.mill("oak").grain == 3
              && Math.abs(ms2.flourOf("oak")) < 0.001f, "");
    }

    // =====================================================================
    static void bridges(){
        BridgeSystem bs = new BridgeSystem();
        final List<String> events = new ArrayList<>();
        bs.listener = (type, id, v) -> events.add(type);

        check("brg: invalid builds refused", !bs.build("", "a", "b", 2)
              && !bs.build("x", "a", "a", 2) && bs.build("x", "oak", "iron", 2)
              && !bs.build("x", "oak", "dock", 3) && !bs.build("y", "iron", "oak", 3), "");
        check("brg: pair check either direction", bs.build("z", "iron", "dock", 2)
              && !bs.build("w", "dock", "iron", 2), "");

        // deterministic weather: flood on day 1, traffic resumes the day after repair
        BridgeSystem mixed = null;
        for(int s2 = 1; s2 <= 400 && mixed == null; s2++){
            BridgeSystem probe = new BridgeSystem();
            probe.seed = s2;
            probe.build("x", "oak", "iron", 2);
            probe.tickDay();
            if(!probe.bridge("x").damaged) continue;
            // mirror the asserted sequence on a twin: flood, dead day, repair, calm day
            BridgeSystem twin = new BridgeSystem();
            twin.seed = s2;
            twin.build("x", "oak", "iron", 2);
            twin.tickDay();
            twin.tickDay();
            twin.repair("x");
            twin.tickDay();
            if(!twin.bridge("x").damaged && twin.incomeOf("x") == 16) mixed = probe;
        }
        if(mixed != null){
            check("brg: flood closes the span", mixed.bridge("x").damaged
                  && Math.abs(mixed.bridge("x").condition - 0.5f) < 0.001f
                  && mixed.bridge("x").floods == 1 && mixed.incomeOf("x") == 0, "");
            mixed.tickDay();                             // still damaged → no crossings
            check("brg: damaged span earns nothing", mixed.incomeOf("x") == 0, "");
            check("brg: repair restores condition", mixed.repair("x")
                  && Math.abs(mixed.bridge("x").condition - 1f) < 0.001f && !mixed.repair("x"), "");
            mixed.tickDay();                             // calm post-repair day → 8 crossings
            check("brg: repaired span earns again", mixed.incomeOf("x") == 16, "");
        } else {
            check("brg: flood closes the span", false, "no flood seed in 400");
            check("brg: damaged span earns nothing", false, "no flood seed in 400");
            check("brg: repair restores condition", false, "no flood seed in 400");
            check("brg: repaired span earns again", false, "no flood seed in 400");
        }

        // calm-seed control: full-condition span books 8 crossings x toll
        BridgeSystem calm = null;
        for(int s2 = 1; s2 <= 400 && calm == null; s2++){
            BridgeSystem probe = new BridgeSystem();
            probe.seed = s2;
            probe.build("x", "oak", "iron", 2);
            probe.tickDay();
            if(!probe.bridge("x").damaged && probe.incomeOf("x") == 16) calm = probe;
        }
        if(calm != null){
            check("brg: crossings pay toll", calm.incomeOf("x") == 16, "");
        } else {
            check("brg: crossings pay toll", false, "no calm seed in 400");
        }

        String json = calm.save();
        BridgeSystem bs2 = new BridgeSystem();
        bs2.load(json);
        check("brg: save/load", bs2.bridge("x") != null && bs2.incomeOf("x") == 16
              && !bs2.bridge("x").damaged, "");
    }

    // =====================================================================
    static void bakeries(){
        BakerySystem bs = new BakerySystem();
        final List<String> events = new ArrayList<>();
        bs.listener = (type, s, v) -> events.add(type);

        check("bak: invalid open refused", !bs.open("", 1) && bs.open("oak", 1) && !bs.open("oak", 2), "");
        check("bak: invalid delivery refused", !bs.deliverGrain("iron", 2) && !bs.deliverGrain("oak", 0)
              && bs.deliverGrain("oak", 4), "");

        bs.tickDay();                                   // bake 4 grain → 8 bread; demand buys 5
        check("bak: bake and sell", bs.bakery("oak").bread == 3 && bs.bakery("oak").sold == 5
              && bs.bakery("oak").rotted == 0 && events.contains("baked") && events.contains("sold"), "");
        bs.tickDay();                                   // no grain left → sells the last 3
        check("bak: demand buys the rest", bs.bakery("oak").bread == 0 && bs.bakery("oak").sold == 8, "");

        // stale write-off: ovens 1 caps bread at 8; a full hopper overshoots by 1 on day 3
        BakerySystem bs2 = new BakerySystem();
        bs2.open("oak", 1);
        bs2.deliverGrain("oak", 20);
        bs2.tickDay(); bs2.tickDay(); bs2.tickDay();
        check("bak: stale bread written off", bs2.bakery("oak").bread == 8
              && bs2.bakery("oak").sold == 15 && bs2.bakery("oak").rotted == 1
              && bs2.bakery("oak").grain == 8, "");
        check("bak: totals", bs2.totalSold() == 15, "" + bs2.totalSold());

        String json = bs2.save();
        BakerySystem bs3 = new BakerySystem();
        bs3.load(json);
        check("bak: save/load", bs3.bakery("oak") != null && bs3.bakery("oak").bread == 8
              && bs3.bakery("oak").rotted == 1 && bs3.totalSold() == 15, "");
    }

    // =====================================================================
    static void pastures(){
        PastureSystem ps = new PastureSystem();
        final List<String> events = new ArrayList<>();
        ps.listener = (type, s, v) -> events.add(type);

        check("pst: invalid fence refused", !ps.fence("", 20) && !ps.fence("oak", 0)
              && ps.fence("oak", 20) && !ps.fence("oak", 5), "");

        // 3 clean days: fractional lambs — 20 sheep gain 1 head on day 3 (old round() stalled)
        PastureSystem calm = null;
        for(int s2 = 1; s2 <= 400 && calm == null; s2++){
            PastureSystem probe = new PastureSystem();
            probe.seed = s2;
            probe.fence("oak", 20);
            probe.tickDay(); probe.tickDay(); probe.tickDay();
            if(probe.pasture("oak").wolfVisits == 0) calm = probe;
        }
        if(calm != null){
            check("pst: small flock still grows", calm.pasture("oak").sheep == 21
                  && Math.abs(calm.woolOf("oak") - 6.1f) < 0.001f,
                  "sheep=" + calm.pasture("oak").sheep + " wool=" + calm.woolOf("oak"));
            check("pst: shearing collects", Math.abs(calm.shear("oak") - 6.1f) < 0.001f
                  && Math.abs(calm.woolOf("oak")) < 0.001f, "");
        } else {
            check("pst: small flock still grows", false, "no clean seed in 400");
            check("pst: shearing collects", false, "no clean seed in 400");
        }

        // wolf visit thins the flock by at least one
        PastureSystem wolves = null;
        for(int s2 = 1; s2 <= 400 && wolves == null; s2++){
            PastureSystem probe = new PastureSystem();
            probe.seed = s2;
            probe.fence("oak", 20);
            probe.tickDay();
            if(probe.pasture("oak").wolfVisits > 0) wolves = probe;
        }
        if(wolves != null){
            check("pst: wolves thin the flock", wolves.pasture("oak").sheep == 19
                  && wolves.pasture("oak").wolfVisits == 1, "");
        } else {
            check("pst: wolves thin the flock", false, "no wolf seed in 400");
        }

        // capacity caps the flock
        PastureSystem full = new PastureSystem();
        full.fence("oak", 50);
        full.tickDay();
        check("pst: flock capped by capacity", full.pasture("oak").sheep == 50, "");

        String json = calm.save();
        PastureSystem ps2 = new PastureSystem();
        ps2.load(json);
        check("pst: save/load", ps2.pasture("oak") != null && ps2.pasture("oak").sheep == 21
              && Math.abs(ps2.woolOf("oak")) < 0.001f, "");
    }

    // =====================================================================
    static void workshop(){
        CarpenterSystem cs = new CarpenterSystem();
        final List<String> events = new ArrayList<>();
        cs.listener = (type, id, detail) -> events.add(type);

        check("crp: bad timber refused", !cs.deliverTimber(0) && !cs.deliverTimber(-3), "");
        check("crp: commission without timber", cs.commission("chest", 2, 0) == null, "");
        check("crp: timber stocked", cs.deliverTimber(10) && cs.timber == 10, "");

        CarpenterSystem.Job j1 = cs.commission("chest", 3, 0);
        check("crp: commission pays default timber", j1 != null && j1.id == 1 && cs.timber == 8, "");
        CarpenterSystem.Job j2 = cs.commission("shelf", 1, 5);
        check("crp: parallel jobs, cost up front", j2 != null && j2.id == 2 && cs.timber == 3, "");
        check("crp: unaffordable commission refused", cs.commission("box", 1, 5) == null
              && cs.commission("", 1, 0) == null && cs.commission("box", 0, 0) == null, "");

        cs.tickDay();
        check("crp: short job crafted", cs.craftedCount == 1 && cs.jobCount() == 1
              && cs.job(2) == null && events.contains("crafted"), "");
        cs.tickDay();
        check("crp: long job halfway", cs.craftedCount == 1 && cs.jobCount() == 1
              && cs.job(1) != null && cs.job(1).progress == 2, "");

        String json = cs.save();
        CarpenterSystem cs2 = new CarpenterSystem();
        cs2.load(json);
        // regression: reloading must not inflate nextId — the saved nextId (3) allocates
        CarpenterSystem.Job j3 = cs2.commission("stool", 1, 0);
        check("crp: save/load, id continues", cs2.timber == 1 && cs2.craftedCount == 1
              && cs2.job(1) != null && cs2.job(1).progress == 2 && j3 != null && j3.id == 3,
              "id=" + (j3 == null ? -1 : j3.id) + " timber=" + cs2.timber);
    }
}
