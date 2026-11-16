import com.hollow.game.ClockmakerSystem;
import com.hollow.game.SaddlerSystem;
import com.hollow.game.StableSystem;
import com.hollow.game.ThatcherSystem;
import com.hollow.world.CharcoalBurnerSystem;
import com.hollow.world.HaywardSystem;
import com.hollow.world.PeatCuttingSystem;
import com.hollow.world.WeirSystem;

/** v17.0.0 regression suite — clamp kilns, river weirs, peat banks,
 *  hayward beats, stables, saddle benches, thatched roofs and
 *  clockmaker benches. */
public class VerifyV17 {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        kilns();
        weirs();
        peat();
        hedges();
        mangers();
        awls();
        ladders();
        regulators();
        System.out.println(fails == 0 ? "\nALL " + total + " V17 TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " V17 TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void kilns(){
        CharcoalBurnerSystem cb = new CharcoalBurnerSystem();
        check("kil: invalid dig refused", !cb.dig("") && cb.dig("oak") && !cb.dig("oak"), "");
        check("kil: wood guards", !cb.loadWood("iron", 4) && !cb.loadWood("oak", 0)
              && !cb.loadWood("oak", 25) && cb.loadWood("oak", 12), "");

        // a proper batch is four logs; below that the clamp waits
        CharcoalBurnerSystem small = new CharcoalBurnerSystem();
        small.dig("oak");
        small.loadWood("oak", 3);
        small.tickDay();
        check("kil: short batch waits", small.kiln("oak").wood == 3
              && small.kiln("oak").charcoal == 0 && small.kiln("oak").clinker == 0, "");

        // scan guarantees THREE clean burns: 3 batches -> 6 sacks
        CharcoalBurnerSystem clean = null, clinker = null;
        for(int s = 1; s <= 400 && (clean == null || clinker == null); s++){
            CharcoalBurnerSystem probe = new CharcoalBurnerSystem();
            probe.seed = s;
            probe.dig("oak");
            probe.loadWood("oak", 12);
            for(int t = 0; t < 3; t++) probe.tickDay();
            if(probe.kiln("oak").clinker == 0 && probe.kiln("oak").charcoal == 6
               && probe.kiln("oak").wood == 0 && clean == null) clean = probe;
            CharcoalBurnerSystem one = new CharcoalBurnerSystem();
            one.seed = s;
            one.dig("oak");
            one.loadWood("oak", 12);
            one.tickDay();
            if(one.kiln("oak").clinker == 1 && one.kiln("oak").charcoal == 0
               && one.kiln("oak").wood == 8 && clinker == null) clinker = one;
        }
        if(clean != null){
            check("kil: three clean burns", clean.kiln("oak").charcoal == 6
                  && clean.kiln("oak").wood == 0 && clean.kiln("oak").clinker == 0, "");
        } else {
            check("kil: three clean burns", false, "no clean seed in 400");
        }
        if(clinker != null){
            check("kil: clinker spends the wood", clinker.kiln("oak").clinker == 1
                  && clinker.kiln("oak").charcoal == 0 && clinker.kiln("oak").wood == 8, "");
        } else {
            check("kil: clinker spends the wood", false, "no clinker seed in 400");
        }

        String json = clean.save();
        CharcoalBurnerSystem cb2 = new CharcoalBurnerSystem();
        cb2.load(json);
        check("kil: save/load", cb2.kiln("oak") != null && cb2.kiln("oak").wood == 0
              && cb2.kiln("oak").charcoal == 6 && cb2.kiln("oak").clinker == 0, "");
    }

    // =====================================================================
    static void weirs(){
        WeirSystem ws = new WeirSystem();
        check("wir: invalid stake refused", !ws.stake("") && ws.stake("oak") && !ws.stake("oak"), "");
        check("wir: trap guards", !ws.setTraps("iron", 1) && !ws.setTraps("oak", -1)
              && !ws.setTraps("oak", 6) && ws.setTraps("oak", 3), "");

        // one roll per day; flood washes one trap's catch
        WeirSystem clean = null, flood = null, two = null;
        for(int s = 1; s <= 400 && (clean == null || flood == null || two == null); s++){
            WeirSystem probe = new WeirSystem();
            probe.seed = s;
            probe.stake("oak");
            probe.setTraps("oak", 3);
            probe.tickDay();
            if(probe.weir("oak").totalCatch == 3 && probe.weir("oak").floods == 0
               && clean == null) clean = probe;
            if(probe.weir("oak").totalCatch == 2 && probe.weir("oak").floods == 1
               && flood == null) flood = probe;
            WeirSystem day2 = new WeirSystem();
            day2.seed = s;
            day2.stake("oak");
            day2.setTraps("oak", 3);
            day2.tickDay(); day2.tickDay();
            if(day2.weir("oak").totalCatch == 6 && day2.weir("oak").floods == 0
               && two == null) two = day2;
        }
        if(clean != null){
            check("wir: full catch landed", clean.weir("oak").totalCatch == 3
                  && clean.weir("oak").floods == 0, "");
        } else {
            check("wir: full catch landed", false, "no clean seed in 400");
        }
        if(flood != null){
            check("wir: flood washes one trap", flood.weir("oak").totalCatch == 2
                  && flood.weir("oak").floods == 1, "");
        } else {
            check("wir: flood washes one trap", false, "no flood seed in 400");
        }
        if(two != null){
            check("wir: two clean days pile up", two.weir("oak").totalCatch == 6
                  && two.weir("oak").floods == 0, "");
        } else {
            check("wir: two clean days pile up", false, "no two-day seed in 400");
        }

        WeirSystem empty = new WeirSystem();
        empty.stake("oak");
        empty.tickDay();
        check("wir: dry weir catches nothing", empty.weir("oak").totalCatch == 0
              && empty.weir("oak").floods == 0, "");

        String json = clean.save();
        WeirSystem ws2 = new WeirSystem();
        ws2.load(json);
        check("wir: save/load", ws2.weir("oak") != null && ws2.weir("oak").traps == 3
              && ws2.weir("oak").totalCatch == 3, "");
    }

    // =====================================================================
    static void peat(){
        PeatCuttingSystem pc = new PeatCuttingSystem();
        check("pet: invalid open refused", !pc.open("") && pc.open("oak") && !pc.open("oak"), "");
        check("pet: cut guards", !pc.cut("iron", 2) && !pc.cut("oak", 0)
              && !pc.cut("oak", 41) && pc.cut("oak", 10), "");

        // scan guarantees FOUR rain-free days: 3+3+3+1 = 10 dried
        PeatCuttingSystem sunny = null, rainy = null;
        for(int s = 1; s <= 400 && (sunny == null || rainy == null); s++){
            PeatCuttingSystem probe = new PeatCuttingSystem();
            probe.seed = s;
            probe.open("oak");
            probe.cut("oak", 10);
            for(int t = 0; t < 4; t++) probe.tickDay();
            if(probe.bank("oak").rainDelays == 0 && probe.bank("oak").dried == 10
               && probe.bank("oak").cutPeat == 0 && sunny == null) sunny = probe;
            PeatCuttingSystem one = new PeatCuttingSystem();
            one.seed = s;
            one.open("oak");
            one.cut("oak", 10);
            one.tickDay();
            if(one.bank("oak").rainDelays == 1 && one.bank("oak").dried == 0
               && one.bank("oak").cutPeat == 10 && rainy == null) rainy = one;
        }
        if(sunny != null){
            check("pet: three blocks a day", sunny.bank("oak").dried == 10
                  && sunny.bank("oak").cutPeat == 0 && sunny.bank("oak").rainDelays == 0, "");
        } else {
            check("pet: three blocks a day", false, "no sunny seed in 400");
        }
        if(rainy != null){
            check("pet: rain dries nothing", rainy.bank("oak").rainDelays == 1
                  && rainy.bank("oak").dried == 0 && rainy.bank("oak").cutPeat == 10, "");
        } else {
            check("pet: rain dries nothing", false, "no rain seed in 400");
        }

        String json = sunny.save();
        PeatCuttingSystem pc2 = new PeatCuttingSystem();
        pc2.load(json);
        check("pet: save/load", pc2.bank("oak") != null && pc2.bank("oak").dried == 10
              && pc2.bank("oak").cutPeat == 0, "");
    }

    // =====================================================================
    static void hedges(){
        HaywardSystem hy = new HaywardSystem();
        check("hyw: invalid appoint refused", !hy.appoint("") && hy.appoint("oak") && !hy.appoint("oak"), "");

        // stray roll only while the hedge lies open (day 1); mends regardless
        HaywardSystem stray = null, quiet = null;
        for(int s = 1; s <= 400 && (stray == null || quiet == null); s++){
            HaywardSystem probe = new HaywardSystem();
            probe.seed = s;
            probe.appoint("oak");
            probe.tickDay();
            if(probe.beat("oak").strays == 1 && probe.beat("oak").hedges == 1
               && probe.beat("oak").roundsDone == 2 && stray == null) stray = probe;
            if(probe.beat("oak").strays == 0 && probe.beat("oak").hedges == 1
               && quiet == null) quiet = probe;
        }
        if(stray != null){
            check("hyw: open hedge lets a beast stray", stray.beat("oak").strays == 1
                  && stray.beat("oak").hedges == 1 && stray.beat("oak").roundsDone == 2, "");
        } else {
            check("hyw: open hedge lets a beast stray", false, "no stray seed in 400");
        }
        if(quiet != null){
            check("hyw: quiet first beat", quiet.beat("oak").strays == 0
                  && quiet.beat("oak").roundsDone == 2, "");
        } else {
            check("hyw: quiet first beat", false, "no quiet seed in 400");
        }

        if(stray != null){
            for(int t = 0; t < 3; t++) stray.tickDay();     // hedge mends to the cap
            check("hyw: tight hedge ends the strays", stray.beat("oak").hedges == 3
                  && stray.beat("oak").strays == 1 && stray.beat("oak").roundsDone == 8, "");
        } else {
            check("hyw: tight hedge ends the strays", false, "no stray seed");
        }

        String json = quiet.save();
        HaywardSystem hy2 = new HaywardSystem();
        hy2.load(json);
        check("hyw: save/load", hy2.beat("oak") != null && hy2.beat("oak").hedges == 1
              && hy2.beat("oak").roundsDone == 2, "");
    }

    // =====================================================================
    static void mangers(){
        StableSystem st = new StableSystem();
        check("stb: oat guards", !st.deliverOats(0) && !st.deliverOats(-1)
              && !st.deliverOats(13) && st.deliverOats(3), "");
        check("stb: bad name refused", st.train("") == null, "");
        check("stb: horse id from one", st.train("bay") != null && st.horse(1) != null, "");

        // three fed days finish; empty manger stalls without progress
        st.tickDay(); st.tickDay(); st.tickDay();
        check("stb: three fed days finish", st.horse(1).trained && st.oats == 0
              && st.horse(1).fedDays == 3 && (st.horse(1).steady ^ st.horse(1).restive), "");

        StableSystem starved = new StableSystem();
        starved.deliverOats(2);
        starved.train("roan");
        starved.tickDay(); starved.tickDay(); starved.tickDay();
        check("stb: empty manger stalls", !starved.horse(1).trained
              && starved.horse(1).daysInTraining == 2 && starved.horse(1).fedDays == 2
              && starved.oats == 0, "");

        StableSystem steady = null, restive = null;
        for(int s = 1; s <= 400 && (steady == null || restive == null); s++){
            StableSystem probe = new StableSystem();
            probe.seed = s;
            probe.deliverOats(3);
            probe.train("bay");
            probe.tickDay(); probe.tickDay(); probe.tickDay();
            if(probe.horse(1).steady && steady == null) steady = probe;
            if(probe.horse(1).restive && restive == null) restive = probe;
        }
        if(steady != null){
            check("stb: steady counted", steady.steadyCount() == 1 && !steady.horse(1).restive, "");
        } else {
            check("stb: steady counted", false, "no steady seed in 400");
        }
        if(restive != null){
            check("stb: restive marked honestly", restive.horse(1).restive
                  && restive.steadyCount() == 0, "");
        } else {
            check("stb: restive marked honestly", false, "no restive seed in 400");
        }

        String json = steady.save();
        StableSystem st2 = new StableSystem();
        st2.load(json);
        // saved state: oats 0, nextId 2 (one horse) — a new horse starts training
        StableSystem.Horse h2 = st2.train("grey");
        check("stb: save/load, id continues", h2 != null && h2.id == 2 && st2.oats == 0
              && st2.horse(1) != null && st2.horse(1).trained,
              "id=" + (h2 == null ? -1 : h2.id));
    }

    // =====================================================================
    static void awls(){
        SaddlerSystem sd = new SaddlerSystem();
        check("sad: bad hides refused", !sd.deliverLeather(0) && !sd.deliverLeather(-1), "");
        check("sad: build without hides", sd.build("riding") == null, "");
        check("sad: hides paid up front", sd.deliverLeather(4) && sd.build("riding") != null
              && sd.leather == 2, "leather=" + sd.leather);
        check("sad: bad kind refused", sd.build("") == null, "");

        sd.tickDay();
        check("sad: bench halfway", sd.saddle(1) != null && !sd.saddle(1).done
              && sd.saddle(1).daysStitched == 1, "");
        sd.tickDay();
        check("sad: fitting decides once", sd.saddle(1).done
              && (sd.saddle(1).fits ^ sd.saddle(1).pinches), "");

        SaddlerSystem fits = null, pinches = null;
        for(int s = 1; s <= 400 && (fits == null || pinches == null); s++){
            SaddlerSystem probe = new SaddlerSystem();
            probe.seed = s;
            probe.deliverLeather(2);
            probe.build("riding");
            probe.tickDay(); probe.tickDay();
            if(probe.saddle(1).fits && fits == null) fits = probe;
            if(probe.saddle(1).pinches && pinches == null) pinches = probe;
        }
        if(fits != null){
            check("sad: fitting counted", fits.fitCount() == 1 && !fits.saddle(1).pinches, "");
        } else {
            check("sad: fitting counted", false, "no fit seed in 400");
        }
        if(pinches != null){
            check("sad: pincher marked honestly", pinches.saddle(1).pinches
                  && pinches.fitCount() == 0, "");
        } else {
            check("sad: pincher marked honestly", false, "no pinch seed in 400");
        }

        String json = fits.save();
        SaddlerSystem sd2 = new SaddlerSystem();
        sd2.load(json);
        // saved state: leather 0 (probe paid its only two), nextId 2
        check("sad: save/load, no hide magic", sd2.saddle(1) != null && sd2.saddle(1).fits
              && sd2.leather == 0 && sd2.build("war") == null, "");
        sd2.deliverLeather(2);
        SaddlerSystem.Saddle s2 = sd2.build("war");
        check("sad: id continues", s2 != null && s2.id == 2 && sd2.leather == 0,
              "id=" + (s2 == null ? -1 : s2.id));
    }

    // =====================================================================
    static void ladders(){
        ThatcherSystem th = new ThatcherSystem();
        check("tha: bad sheaves refused", !th.deliverStraw(0) && !th.deliverStraw(-1), "");
        check("tha: thatch without straw", th.thatch("mill") == null, "");
        check("tha: sheaves paid up front", th.deliverStraw(6) && th.thatch("mill") != null
              && th.straw == 3, "straw=" + th.straw);
        check("tha: bad house refused", th.thatch("") == null, "");

        th.tickDay();
        check("tha: ladder halfway", th.roof(1) != null && !th.roof(1).done
              && th.roof(1).daysThatched == 1, "");
        th.tickDay();
        check("tha: weather test decides once", th.roof(1).done
              && (th.roof(1).watertight ^ th.roof(1).leaks), "");

        ThatcherSystem tight = null, leaky = null;
        for(int s = 1; s <= 400 && (tight == null || leaky == null); s++){
            ThatcherSystem probe = new ThatcherSystem();
            probe.seed = s;
            probe.deliverStraw(3);
            probe.thatch("mill");
            probe.tickDay(); probe.tickDay();
            if(probe.roof(1).watertight && tight == null) tight = probe;
            if(probe.roof(1).leaks && leaky == null) leaky = probe;
        }
        if(tight != null){
            check("tha: watertight counted", tight.tightCount() == 1 && !tight.roof(1).leaks, "");
        } else {
            check("tha: watertight counted", false, "no tight seed in 400");
        }
        if(leaky != null){
            check("tha: leaker flagged honestly", leaky.roof(1).leaks && leaky.tightCount() == 0, "");
        } else {
            check("tha: leaker flagged honestly", false, "no leak seed in 400");
        }

        String json = tight.save();
        ThatcherSystem th2 = new ThatcherSystem();
        th2.load(json);
        // saved state: straw 0 (probe paid its only three), nextId 2
        check("tha: save/load, no sheaf magic", th2.roof(1) != null && th2.roof(1).watertight
              && th2.straw == 0 && th2.thatch("barn") == null, "");
        th2.deliverStraw(3);
        ThatcherSystem.Roof r2 = th2.thatch("barn");
        check("tha: id continues", r2 != null && r2.id == 2 && th2.straw == 0,
              "id=" + (r2 == null ? -1 : r2.id));
    }

    // =====================================================================
    static void regulators(){
        ClockmakerSystem ck = new ClockmakerSystem();
        check("clk: gear guards", !ck.deliverGears(0) && !ck.deliverGears(-1)
              && !ck.deliverGears(21), "");
        check("clk: assemble without gears", ck.assemble("regulator") == null, "");
        check("clk: gears paid up front", ck.deliverGears(8) && ck.assemble("regulator") != null
              && ck.gears == 4, "gears=" + ck.gears);
        check("clk: bad name refused", ck.assemble("") == null, "");

        ck.tickDay(); ck.tickDay();
        check("clk: bench halfway", ck.clock(1) != null && !ck.clock(1).done
              && ck.clock(1).daysAssembled == 2, "");
        ck.tickDay();
        check("clk: regulator decides once", ck.clock(1).done
              && (ck.clock(1).keepsTrue ^ ck.clock(1).drifts), "");

        ClockmakerSystem trueTime = null, drifts = null;
        for(int s = 1; s <= 400 && (trueTime == null || drifts == null); s++){
            ClockmakerSystem probe = new ClockmakerSystem();
            probe.seed = s;
            probe.deliverGears(4);
            probe.assemble("regulator");
            probe.tickDay(); probe.tickDay(); probe.tickDay();
            if(probe.clock(1).keepsTrue && trueTime == null) trueTime = probe;
            if(probe.clock(1).drifts && drifts == null) drifts = probe;
        }
        if(trueTime != null){
            check("clk: true time counted", trueTime.trueCount() == 1 && !trueTime.clock(1).drifts, "");
        } else {
            check("clk: true time counted", false, "no true seed in 400");
        }
        if(drifts != null){
            check("clk: drifter marked honestly", drifts.clock(1).drifts
                  && drifts.trueCount() == 0, "");
        } else {
            check("clk: drifter marked honestly", false, "no drift seed in 400");
        }

        String json = trueTime.save();
        ClockmakerSystem ck2 = new ClockmakerSystem();
        ck2.load(json);
        // saved state: gears 0 (probe paid its only four), nextId 2
        check("clk: save/load, no gear magic", ck2.clock(1) != null && ck2.clock(1).keepsTrue
              && ck2.gears == 0 && ck2.assemble("minute") == null, "");
        ck2.deliverGears(4);
        ClockmakerSystem.Clock c2 = ck2.assemble("minute");
        check("clk: id continues", c2 != null && c2.id == 2 && ck2.gears == 0,
              "id=" + (c2 == null ? -1 : c2.id));
    }
}
