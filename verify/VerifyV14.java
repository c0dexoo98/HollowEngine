import com.hollow.game.CandlemakerSystem;
import com.hollow.game.CartographerSystem;
import com.hollow.game.GlassblowerSystem;
import com.hollow.game.PuppeteerSystem;
import com.hollow.world.DredgeSystem;
import com.hollow.world.FalconrySystem;
import com.hollow.world.IcehouseSystem;
import com.hollow.world.WatchtowerSystem;

/** v14.0.0 regression suite — beacon watches, puppet stages, falcon mews,
 *  kilns, ice pits, survey quills, candle racks and dredged channels. */
public class VerifyV14 {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        beacons();
        stage();
        mews();
        kilns();
        ice();
        quills();
        racks();
        channels();
        System.out.println(fails == 0 ? "\nALL " + total + " V14 TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " V14 TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void beacons(){
        WatchtowerSystem ws = new WatchtowerSystem();
        check("bcn: invalid raise refused", !ws.raise("") && ws.raise("oak") && !ws.raise("oak"), "");
        check("bcn: light guards", !ws.light("iron", true) && ws.light("oak", true)
              && !ws.light("oak", true) && ws.light("oak", false), "");

        // deterministic sky: one roll — fog seed and signal seed
        WatchtowerSystem fog = null, signal = null;
        for(int s2 = 1; s2 <= 400 && (fog == null || signal == null); s2++){
            WatchtowerSystem probe = new WatchtowerSystem();
            probe.seed = s2;
            probe.raise("oak");
            probe.light("oak", true);
            probe.tickDay();
            if(probe.tower("oak").fogNights > 0){ if(fog == null) fog = probe; }
            else if(probe.tower("oak").signals > 0 && signal == null) signal = probe;
        }
        if(fog != null){
            check("bcn: fog blots the night", fog.tower("oak").fogNights == 1
                  && fog.tower("oak").signals == 0 && fog.tower("oak").litNights == 1, "");
        } else {
            check("bcn: fog blots the night", false, "no fog seed in 400");
        }
        if(signal != null){
            check("bcn: clear night relays", signal.tower("oak").signals == 1
                  && signal.tower("oak").fogNights == 0, "");
        } else {
            check("bcn: clear night relays", false, "no signal seed in 400");
        }
        check("bcn: doused tower stays dark", ws.tower("oak").litNights == 0
              && ws.tower("oak").signals == 0, "");

        String json = signal.save();
        WatchtowerSystem ws2 = new WatchtowerSystem();
        ws2.load(json);
        check("bcn: save/load", ws2.tower("oak") != null && ws2.tower("oak").lit
              && ws2.tower("oak").signals == 1 && ws2.tower("oak").litNights == 1, "");
    }

    // =====================================================================
    static void stage(){
        PuppeteerSystem ps = new PuppeteerSystem();
        check("ppp: invalid commission refused", ps.commission("", 2) == null
              && ps.commission("fox", 0) == null && ps.commission("fox", 3) != null
              && ps.commission("fox", 2) == null, "");
        PuppeteerSystem.Play p2 = ps.commission("geese", 5);
        check("ppp: second play id 2", p2 != null && p2.id == 2, "");

        check("ppp: unready stage refused", ps.play(1) != null && !ps.play(1).ready()
              && !ps.perform(1), "");
        ps.tickDay(); ps.tickDay(); ps.tickDay();
        check("ppp: rehearsals count", ps.play(1).daysRehearsed == 3 && ps.play(1).ready()
              && ps.play(2).daysRehearsed == 3 && !ps.play(2).ready(), "");
        ps.tickDay();
        check("ppp: ready plays stop counting", ps.play(1).daysRehearsed == 3
              && ps.play(2).daysRehearsed == 4, "");

        // crowd rolls once: applause seed and flop seed
        PuppeteerSystem ap = null, flop = null;
        for(int s2 = 1; s2 <= 400 && (ap == null || flop == null); s2++){
            PuppeteerSystem probe = new PuppeteerSystem();
            probe.seed = s2;
            probe.commission("fox", 1);
            probe.tickDay();
            if(probe.play(1) == null || !probe.play(1).ready()) continue;
            probe.perform(1);
            if(probe.play(1).applause > 0){ if(ap == null) ap = probe; }
            else if(probe.play(1).performances == 1 && flop == null) flop = probe;
        }
        if(ap != null){
            check("ppp: applause night", ap.play(1).applause == 1 && ap.play(1).performances == 1, "");
        } else {
            check("ppp: applause night", false, "no applause seed in 400");
        }
        if(flop != null){
            check("ppp: flop night", flop.play(1).performances == 1 && flop.play(1).applause == 0, "");
        } else {
            check("ppp: flop night", false, "no flop seed in 400");
        }

        String json = ap.save();
        PuppeteerSystem ps2 = new PuppeteerSystem();
        ps2.load(json);
        // regression: reloading must not inflate nextId — the saved nextId (2, one play) allocates
        PuppeteerSystem.Play p3 = ps2.commission("owls", 1);
        check("ppp: save/load, id continues", ps2.play(1) != null && ps2.play(1).ready()
              && p3 != null && p3.id == 2, "id=" + (p3 == null ? -1 : p3.id));
    }

    // =====================================================================
    static void mews(){
        FalconrySystem fs = new FalconrySystem();
        check("mlw: invalid open refused", !fs.open("") && fs.open("oak") && !fs.open("oak"), "");
        check("mlw: stock guards", !fs.stock("iron", 2) && !fs.stock("oak", 0)
              && !fs.stock("oak", 7) && fs.stock("oak", 3), "");

        // one roll per hunt: escape / quarry / empty sky
        FalconrySystem kill = null, loss = null;
        for(int s2 = 1; s2 <= 400 && (kill == null || loss == null); s2++){
            FalconrySystem probe = new FalconrySystem();
            probe.seed = s2;
            probe.open("oak");
            probe.stock("oak", 3);
            probe.tickDay();
            if(probe.mew("oak").quarry > 0){ if(kill == null) kill = probe; }
            else if(probe.mew("oak").escaped > 0 && loss == null) loss = probe;
        }
        if(kill != null){
            check("mlw: quarry comes home", kill.mew("oak").quarry == 1
                  && kill.mew("oak").falcons == 3 && kill.mew("oak").hunts == 1, "");
        } else {
            check("mlw: quarry comes home", false, "no kill seed in 400");
        }
        if(loss != null){
            check("mlw: a bird slips away", loss.mew("oak").falcons == 2
                  && loss.mew("oak").escaped == 1 && loss.mew("oak").hunts == 1, "");
        } else {
            check("mlw: a bird slips away", false, "no escape seed in 400");
        }

        FalconrySystem empty = new FalconrySystem();
        empty.open("oak");
        empty.tickDay();
        check("mlw: empty mew hunts nothing", empty.mew("oak").hunts == 0, "");

        String json = kill.save();
        FalconrySystem fs2 = new FalconrySystem();
        fs2.load(json);
        check("mlw: save/load", fs2.mew("oak") != null && fs2.mew("oak").falcons == 3
              && fs2.mew("oak").quarry == 1, "");
    }

    // =====================================================================
    static void kilns(){
        GlassblowerSystem gs = new GlassblowerSystem();
        check("gls: bad sand refused", !gs.deliverSand(0) && !gs.deliverSand(-1), "");
        check("gls: blow without sand", gs.blow("vase", 2) == null, "");
        check("gls: sand stocked, gather paid", gs.deliverSand(4) && gs.blow("vase", 2) != null
              && gs.sand == 3, "");
        check("gls: bad kind refused", gs.blow("", 2) == null && gs.blow("bowl", 0) == null, "");

        gs.tickDay();
        check("gls: annealing halfway", gs.batch(1) != null && !gs.batch(1).done
              && gs.batch(1).daysInKiln == 1, "");
        gs.tickDay();
        check("gls: kiln decides once", gs.batch(1).done
              && (gs.batch(1).shattered || gs.pieces == 1), "pieces=" + gs.pieces);

        // scan an intact seed and a shattered seed on fresh probes
        GlassblowerSystem ok = null, bad = null;
        for(int s2 = 1; s2 <= 400 && (ok == null || bad == null); s2++){
            GlassblowerSystem probe = new GlassblowerSystem();
            probe.seed = s2;
            probe.deliverSand(2);
            probe.blow("vase", 1);
            probe.tickDay();
            if(probe.batch(1).shattered){ if(bad == null) bad = probe; }
            else if(probe.pieces == 1 && ok == null) ok = probe;
        }
        if(ok != null){
            check("gls: intact batch counted", ok.pieces == 1 && !ok.batch(1).shattered, "");
        } else {
            check("gls: intact batch counted", false, "no intact seed in 400");
        }
        if(bad != null){
            check("gls: shattered batch lost", bad.batch(1).shattered && bad.pieces == 0, "");
        } else {
            check("gls: shattered batch lost", false, "no shatter seed in 400");
        }

        String json = ok.save();
        GlassblowerSystem gs2 = new GlassblowerSystem();
        gs2.load(json);
        check("gls: save/load state", gs2.sand == 1 && gs2.pieces == 1
              && gs2.batch(1) != null && gs2.batch(1).done, "");
        // regression: reloading must not inflate nextId — the saved nextId (2, one batch) allocates
        GlassblowerSystem.Batch b2 = gs2.blow("bowl", 1);
        check("gls: id continues, gather paid", b2 != null && b2.id == 2 && gs2.sand == 0,
              "id=" + (b2 == null ? -1 : b2.id) + " sand=" + gs2.sand);
    }

    // =====================================================================
    static void ice(){
        IcehouseSystem is = new IcehouseSystem();
        check("ice: invalid build refused", !is.build("") && is.build("oak") && !is.build("oak"), "");
        check("ice: cut guards", !is.cutIce("iron", 2) && !is.cutIce("oak", 0)
              && !is.cutIce("oak", 51) && is.cutIce("oak", 10), "");
        check("ice: harvest ledger", is.house("oak").harvested == 10, "");

        is.tickDay(); is.tickDay(); is.tickDay();
        check("ice: a tenth melts daily", is.house("oak").ice == 7 && is.house("oak").melted == 3,
              "ice=" + is.house("oak").ice + " melted=" + is.house("oak").melted);
        is.house("oak").ice = 1;
        is.tickDay();
        check("ice: last block goes", is.house("oak").ice == 0 && is.house("oak").melted == 4, "");
        is.tickDay();
        check("ice: empty pit melts nothing", is.house("oak").melted == 4, "");

        String json = is.save();
        IcehouseSystem is2 = new IcehouseSystem();
        is2.load(json);
        check("ice: save/load", is2.house("oak") != null && is2.house("oak").ice == 0
              && is2.house("oak").melted == 4 && is2.house("oak").harvested == 10, "");
    }

    // =====================================================================
    static void quills(){
        CartographerSystem cs = new CartographerSystem();
        check("crt: invalid commission refused", cs.commission("", 2) == null
              && cs.commission("moor", 0) == null && cs.commission("moor", 3) != null
              && cs.commission("moor", 1) == null, "");

        cs.tickDay(); cs.tickDay(); cs.tickDay();
        check("crt: three field days draft the map", cs.survey(1) != null
              && cs.survey(1).daysDone == 3 && cs.survey(1).drawn && cs.mapCount() == 1, "");

        // quill rolls once: accurate seed and rough seed
        CartographerSystem good = null, rough = null;
        for(int s2 = 1; s2 <= 400 && (good == null || rough == null); s2++){
            CartographerSystem probe = new CartographerSystem();
            probe.seed = s2;
            probe.commission("moor", 1);
            probe.tickDay();
            if(probe.survey(1) == null || !probe.survey(1).drawn) continue;
            if(probe.survey(1).accurate){ if(good == null) good = probe; }
            else if(rough == null) rough = probe;
        }
        if(good != null){
            check("crt: true map", good.survey(1).accurate && good.accurateCount() == 1, "");
        } else {
            check("crt: true map", false, "no accurate seed in 400");
        }
        if(rough != null){
            check("crt: rough map marked honestly", !rough.survey(1).accurate && rough.mapCount() == 1
                  && rough.accurateCount() == 0, "");
        } else {
            check("crt: rough map marked honestly", false, "no rough seed in 400");
        }

        String json = good.save();
        CartographerSystem cs2 = new CartographerSystem();
        cs2.load(json);
        // regression: reloading must not inflate nextId — the saved nextId (2) allocates
        CartographerSystem.Survey s2b = cs2.commission("vale", 1);
        check("crt: save/load, id continues", cs2.mapCount() == 1 && s2b != null && s2b.id == 2,
              "id=" + (s2b == null ? -1 : s2b.id));
    }

    // =====================================================================
    static void racks(){
        CandlemakerSystem ck = new CandlemakerSystem();
        check("cdl: bad wax refused", !ck.deliverWax("", 5) && !ck.deliverWax("oak", 0)
              && !ck.deliverWax("oak", -2), "");
        check("cdl: wax stocked", ck.deliverWax("oak", 30) && ck.waxOf("oak") == 30, "");

        // clean seed: full dip; soot seed: half the rack smoked
        CandlemakerSystem clean = null, soot = null;
        for(int s2 = 1; s2 <= 400 && (clean == null || soot == null); s2++){
            CandlemakerSystem probe = new CandlemakerSystem();
            probe.seed = s2;
            probe.deliverWax("oak", 30);
            probe.tickDay();
            if(probe.sootOf("oak") > 0){ if(soot == null) soot = probe; }
            else if(probe.candlesOf("oak") == 40 && probe.waxOf("oak") == 10 && clean == null) clean = probe;
        }
        if(clean != null){
            check("cdl: full rack dipped", clean.candlesOf("oak") == 40 && clean.waxOf("oak") == 10, "");
        } else {
            check("cdl: full rack dipped", false, "no clean seed in 400");
        }
        if(soot != null){
            check("cdl: soot smokes the rack", soot.candlesOf("oak") == 20 && soot.sootOf("oak") == 1
                  && soot.waxOf("oak") == 10, "candles=" + soot.candlesOf("oak"));
        } else {
            check("cdl: soot smokes the rack", false, "no soot seed in 400");
        }

        // clean run continues until the wax runs dry
        if(clean != null){
            clean.tickDay();
            check("cdl: dipping until dry", clean.candlesOf("oak") == 60 && clean.waxOf("oak") == 0,
                  "candles=" + clean.candlesOf("oak"));
        } else {
            check("cdl: dipping until dry", false, "no clean seed");
        }

        String json = clean == null ? "" : clean.save();
        CandlemakerSystem ck2 = new CandlemakerSystem();
        ck2.load(json);
        check("cdl: save/load", ck2.waxOf("oak") == 0 && ck2.candlesOf("oak") == 60
              && ck2.sootOf("oak") == 0, "");
    }

    // =====================================================================
    static void channels(){
        DredgeSystem ds = new DredgeSystem();
        check("drg: invalid charter refused", !ds.charter("") && ds.charter("oak") && !ds.charter("oak"), "");
        check("drg: crew guards", !ds.assignCrews("iron", 2) && !ds.assignCrews("oak", 5)
              && !ds.assignCrews("oak", -1) && ds.assignCrews("oak", 2), "");

        DredgeSystem calm = new DredgeSystem();          // scan a two-day storm-free run
        DredgeSystem storm = null;
        for(int s2 = 1; s2 <= 400 && storm == null; s2++){
            DredgeSystem probe = new DredgeSystem();
            probe.seed = s2;
            probe.charter("oak");
            probe.tickDay();
            if(probe.harbor("oak").storms > 0) storm = probe;
        }
        DredgeSystem hold = null;
        for(int s2 = 1; s2 <= 400 && hold == null; s2++){
            DredgeSystem probe = new DredgeSystem();
            probe.seed = s2;
            probe.charter("oak");
            probe.assignCrews("oak", 2);
            probe.tickDay(); probe.tickDay();
            if(probe.harbor("oak").storms == 0 && probe.harbor("oak").depth == 12
               && probe.harbor("oak").dredged == 2) hold = probe;
        }
        if(hold != null){
            check("drg: two crews win a fathom a day", hold.harbor("oak").depth == 12
                  && hold.harbor("oak").dredged == 2, "depth=" + hold.harbor("oak").depth);
        } else {
            check("drg: two crews win a fathom a day", false, "no calm seed in 400");
        }
        if(storm != null){
            check("drg: storm carves the channel", storm.harbor("oak").storms == 1
                  && storm.harbor("oak").depth == 5, "depth=" + storm.harbor("oak").depth);
        } else {
            check("drg: storm carves the channel", false, "no storm seed in 400");
        }
        if(calm != null){
            calm.charter("oak");
            calm.assignCrews("oak", 1);
            calm.tickDay();
            check("drg: one crew only holds", calm.harbor("oak").depth == 10,
                  "depth=" + calm.harbor("oak").depth);
        } else {
            check("drg: one crew only holds", false, "unreachable");
        }

        String json = hold.save();
        DredgeSystem ds2 = new DredgeSystem();
        ds2.load(json);
        check("drg: save/load", ds2.harbor("oak") != null && ds2.harbor("oak").depth == 12
              && ds2.harbor("oak").crews == 2 && ds2.harbor("oak").dredged == 2, "");
    }
}
