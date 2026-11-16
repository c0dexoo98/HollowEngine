import com.hollow.game.ApothecarySystem;
import com.hollow.game.BrewerySystem;
import com.hollow.game.HealerSystem;
import com.hollow.game.MintSystem;
import com.hollow.world.AstrologerSystem;
import com.hollow.world.RatcatcherSystem;
import com.hollow.world.ScribeSystem;
import com.hollow.world.TannerySystem;

import java.util.ArrayList;
import java.util.List;

/** v13.0.0 regression suite — sky charts, remedies, pest control, coinage,
 *  scriptoriums, ale, leather and the hospice. */
public class VerifyV13 {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        stars();
        remedies();
        vermin();
        coinage();
        scriptoriums();
        ales();
        leather();
        hospice();
        System.out.println(fails == 0 ? "\nALL " + total + " V13 TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " V13 TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void stars(){
        AstrologerSystem as = new AstrologerSystem();
        check("ast: invalid open refused", !as.open("") && as.open("oak") && !as.open("oak"), "");

        // deterministic sky: scan a chart night and a clouded night
        AstrologerSystem chart = null, clouded = null;
        for(int s2 = 1; s2 <= 400 && (chart == null || clouded == null); s2++){
            AstrologerSystem probe = new AstrologerSystem();
            probe.seed = s2;
            probe.open("oak");
            probe.tickDay();
            if(probe.observatory("oak").charts > 0){ if(chart == null) chart = probe; }
            else if(probe.observatory("oak").cloudedNights > 0 && clouded == null) clouded = probe;
        }
        if(chart != null){
            check("ast: chart night fixes a chart", chart.observatory("oak").charts == 1
                  && chart.observatory("oak").nights == 1, "");
            check("ast: first omen names the Plough",
                  "The Plough reigns tonight".equals(chart.observatory("oak").latestOmen()),
                  chart.observatory("oak").latestOmen());
        } else {
            check("ast: chart night fixes a chart", false, "no chart seed in 400");
            check("ast: first omen names the Plough", false, "no chart seed in 400");
        }
        if(clouded != null){
            check("ast: clouded night wasted", clouded.observatory("oak").charts == 0
                  && clouded.observatory("oak").cloudedNights == 1, "");
        } else {
            check("ast: clouded night wasted", false, "no cloud seed in 400");
        }
        check("ast: empty ledger omen", "no charts yet".equals(as.observatory("oak").latestOmen()), "");

        // charts stop at the full sky (8)
        AstrologerSystem full = new AstrologerSystem();
        full.seed = chart == null ? 1 : chart.seed;
        full.open("oak");
        for(int i = 0; i < 300; i++) full.tickDay();
        check("ast: sky completes at eight charts", full.observatory("oak").charts == 8,
              "" + full.observatory("oak").charts);

        String json = chart.save();
        AstrologerSystem as2 = new AstrologerSystem();
        as2.load(json);
        check("ast: save/load", as2.observatory("oak") != null
              && as2.observatory("oak").charts == 1 && as2.observatory("oak").nights == 1, "");
    }

    // =====================================================================
    static void remedies(){
        ApothecarySystem ap = new ApothecarySystem();
        check("apc: bad herbs refused", !ap.deliverHerbs(0) && !ap.deliverHerbs(-2), "");
        check("apc: brew without herbs", ap.brew("tonic", 2, 0) == null, "");
        check("apc: herbs stocked", ap.deliverHerbs(10) && ap.herbs == 10, "");

        ApothecarySystem.Batch b1 = ap.brew("tonic", 2, 0);
        check("apc: brew pays default herbs", b1 != null && b1.id == 1 && ap.herbs == 8, "");
        ApothecarySystem.Batch b2 = ap.brew("salve", 1, 3);
        check("apc: parallel brews, cost up front", b2 != null && b2.id == 2 && ap.herbs == 5, "");
        check("apc: unaffordable brew refused", ap.brew("elixir", 1, 9) == null
              && ap.brew("", 1, 0) == null && ap.brew("elixir", 0, 0) == null, "");

        ap.tickDay();
        check("apc: short batch brewed", ap.finishedCount() == 1 && ap.batch(2) != null
              && ap.batch(2).efficacy >= 0f && ap.batch(1).progress == 1, "");
        ap.tickDay();
        check("apc: long batch brewed", ap.finishedCount() == 2 && ap.batch(1) != null
              && ap.batch(1).progress == 2 && ap.batch(1).efficacy >= 0f, "");
        check("apc: efficacy in skill band", b2.efficacy >= 0.6f && b2.efficacy <= 0.8f,
              "e=" + b2.efficacy);
        check("apc: skill sharpened twice", Math.abs(ap.skill - 0.52f) < 0.0001f, "" + ap.skill);

        String json = ap.save();
        ApothecarySystem ap2 = new ApothecarySystem();
        ap2.load(json);
        // regression: reloading must not inflate nextId — the saved nextId (3) allocates
        ApothecarySystem.Batch b3 = ap2.brew("draught", 1, 0);
        check("apc: save/load, id continues", ap2.herbs == 3 && ap2.batch(1) != null
              && ap2.batch(1).progress == 2 && b3 != null && b3.id == 3,
              "id=" + (b3 == null ? -1 : b3.id) + " herbs=" + ap2.herbs);
    }

    // =====================================================================
    static void vermin(){
        RatcatcherSystem rs = new RatcatcherSystem();
        check("rat: invalid open refused", !rs.open("") && rs.open("oak") && !rs.open("oak"), "");
        check("rat: invalid traps refused", !rs.deployTraps("iron", 3) && !rs.deployTraps("oak", 9)
              && !rs.deployTraps("oak", -1) && rs.deployTraps("oak", 3), "");

        rs.ward("oak").rats = 10;                        // no rolls: pure arithmetic
        rs.tickDay();                                    // 10 -> 11 (breed) -> catch 6 -> 5
        rs.tickDay();                                    // 5 -> 5 (no lamb) -> catch 5 -> 0
        check("rat: traps win a small ward", rs.ward("oak").rats == 0
              && rs.ward("oak").catches == 11, "catches=" + rs.ward("oak").catches);

        RatcatcherSystem inf = new RatcatcherSystem();
        inf.open("oak");
        inf.ward("oak").rats = 65;
        inf.tickDay();                                   // 65 -> 74 (breed), no traps
        check("rat: infestation rings the bell", inf.ward("oak").rats == 74
              && inf.ward("oak").infestations == 1, "" + inf.ward("oak").rats);

        RatcatcherSystem cap = new RatcatcherSystem();
        cap.open("oak");
        cap.ward("oak").rats = 95;
        cap.tickDay();
        check("rat: population capped at 100", cap.ward("oak").rats == 100,
              "" + cap.ward("oak").rats);

        String json = rs.save();
        RatcatcherSystem rs2 = new RatcatcherSystem();
        rs2.load(json);
        check("rat: save/load", rs2.ward("oak") != null && rs2.ward("oak").rats == 0
              && rs2.ward("oak").catches == 11 && rs2.ward("oak").traps == 3, "");
    }

    // =====================================================================
    static void coinage(){
        MintSystem ms = new MintSystem();
        check("mnt: bad bullion refused", !ms.deliverBullion(0) && !ms.deliverBullion(-1), "");
        check("mnt: bullion stocked", ms.deliverBullion(5) && ms.bars == 5, "");

        // deterministic scandal: scan a two-day clean run and a day-1 scandal
        MintSystem clean = null, clipped = null;
        for(int s2 = 1; s2 <= 400 && (clean == null || clipped == null); s2++){
            MintSystem probe = new MintSystem();
            probe.seed = s2;
            probe.deliverBullion(5);
            probe.tickDay();
            if(probe.scandals > 0){ if(clipped == null) clipped = probe; }
            else {
                MintSystem twin = new MintSystem();
                twin.seed = s2;
                twin.deliverBullion(5);
                twin.tickDay(); twin.tickDay();
                if(twin.scandals == 0 && clean == null) clean = twin;
            }
        }
        if(clean != null){
            check("mnt: two clean strikes", clean.coins == 40 && clean.bars == 1
                  && clean.struckTotal == 40, "coins=" + clean.coins + " bars=" + clean.bars);
        } else {
            check("mnt: two clean strikes", false, "no clean seed in 400");
        }
        if(clipped != null){
            check("mnt: clipping scandal shaves coins", clipped.coins == 19
                  && clipped.scandals == 1 && clipped.bars == 3, "coins=" + clipped.coins);
        } else {
            check("mnt: clipping scandal shaves coins", false, "no scandal seed in 400");
        }

        String json = clean.save();
        MintSystem ms2 = new MintSystem();
        ms2.load(json);
        check("mnt: save/load", ms2.coins == 40 && ms2.bars == 1 && ms2.struckTotal == 40, "");
    }

    // =====================================================================
    static void scriptoriums(){
        ScribeSystem ss = new ScribeSystem();
        check("scr: invalid found refused", !ss.found("", 2) && ss.found("oak", 2) && !ss.found("oak", 3), "");
        check("scr: scribes clamped to band", ss.found("iron", 99) && ss.library("iron").scribes == 5
              && ss.found("dock", 0) && ss.library("dock").scribes == 1, "");
        check("scr: bad ink refused", !ss.deliverInk("gold", 2) && !ss.deliverInk("oak", 0)
              && ss.deliverInk("oak", 3), "");

        // deterministic moths: scan seed with clean day 1 and a moth day 2
        ScribeSystem moth = null;
        for(int s2 = 1; s2 <= 400 && moth == null; s2++){
            ScribeSystem probe = new ScribeSystem();
            probe.seed = s2;
            probe.found("oak", 2);
            probe.deliverInk("oak", 3);
            probe.tickDay();                             // copied 2, ink 1 (needs clean day 1)
            if(probe.library("oak").mothStrikes != 0 || probe.library("oak").copied != 2) continue;
            probe.tickDay();                             // moth eats 1, then copies 1 → copied 2, ink 0
            if(probe.library("oak").mothStrikes == 1 && probe.library("oak").copied == 2
               && probe.library("oak").ink == 0) moth = probe;
        }
        if(moth != null){
            check("scr: moths eat into the shelf", moth.library("oak").mothStrikes == 1
                  && moth.library("oak").copied == 2 && moth.library("oak").ink == 0,
                  "copied=" + moth.library("oak").copied);
        } else {
            check("scr: moths eat into the shelf", false, "no moth seed in 400");
        }

        // clean run: ink runs dry, copying stops
        ScribeSystem clean = new ScribeSystem();
        clean.seed = 1;
        clean.found("oak", 2);
        clean.deliverInk("oak", 3);
        clean.tickDay(); clean.tickDay(); clean.tickDay();
        check("scr: copying while ink lasts", clean.library("oak").copied == 3
              && clean.library("oak").ink == 0, "copied=" + clean.library("oak").copied);

        String json = moth.save();
        ScribeSystem ss2 = new ScribeSystem();
        ss2.load(json);
        check("scr: save/load", ss2.library("oak") != null && ss2.library("oak").mothStrikes == 1
              && ss2.library("oak").copied == 2, "");
    }

    // =====================================================================
    static void ales(){
        BrewerySystem bs = new BrewerySystem();
        check("brw: bad grain refused", !bs.deliverGrain(0) && !bs.deliverGrain(-1), "");
        check("brw: brew without grain", bs.brew("bitter", 0) == null, "");
        check("brw: grain stocked", bs.deliverGrain(9) && bs.grain == 9, "");

        BrewerySystem.Cask c1 = bs.brew("bitter", 0);
        check("brw: brew pays default grain", c1 != null && c1.id == 1 && bs.grain == 6, "");
        BrewerySystem.Cask c2 = bs.brew("stout", 3);
        check("brw: second cask", c2 != null && c2.id == 2 && bs.grain == 3, "");

        // 4 fermenting days, then the tap roll per cask — scan a both-good seed
        BrewerySystem good = null;
        for(int s2 = 1; s2 <= 400 && good == null; s2++){
            BrewerySystem probe = new BrewerySystem();
            probe.seed = s2;
            probe.deliverGrain(9);
            probe.brew("bitter", 0);
            probe.brew("stout", 3);
            for(int i = 0; i < 4; i++) probe.tickDay();
            if(probe.ales == 20 && probe.cask(1).tapped && probe.cask(2).tapped
               && !probe.cask(1).skunked && !probe.cask(2).skunked) good = probe;
        }
        if(good != null){
            check("brw: two good casks", good.ales == 20 && good.cask(1).daysLeft == 0
                  && good.cask(2).daysLeft == 0, "ales=" + good.ales);
        } else {
            check("brw: two good casks", false, "no both-good seed in 400");
        }

        // skunked cask bottles nothing but is still tapped
        BrewerySystem skunk = null;
        for(int s2 = 1; s2 <= 400 && skunk == null; s2++){
            BrewerySystem probe = new BrewerySystem();
            probe.seed = s2;
            probe.deliverGrain(3);
            probe.brew("bitter", 0);
            for(int i = 0; i < 4; i++) probe.tickDay();
            if(probe.cask(1).tapped && probe.cask(1).skunked && probe.ales == 0) skunk = probe;
        }
        if(skunk != null){
            check("brw: skunked cask lost", skunk.cask(1).skunked && skunk.ales == 0, "");
        } else {
            check("brw: skunked cask lost", false, "no skunk seed in 400");
        }

        // single good cask for the save/load + id continuity path
        BrewerySystem one = null;
        for(int s2 = 1; s2 <= 400 && one == null; s2++){
            BrewerySystem probe = new BrewerySystem();
            probe.seed = s2;
            probe.deliverGrain(9);
            probe.brew("bitter", 0);
            probe.brew("stout", 3);                      // unfinished (4 days left)
            probe.tickDay();                             // bitter daysLeft 3
            if(probe.cask(1).tapped) continue;           // need both unfinished after 1 tick
            one = probe;
        }
        String json = one.save();
        BrewerySystem bs2 = new BrewerySystem();
        bs2.load(json);
        // regression: reloading must not inflate nextId — the saved nextId (3) allocates
        BrewerySystem.Cask c3 = bs2.brew("porter", 0);
        check("brw: save/load, id continues", bs2.cask(1) != null && bs2.cask(1).daysLeft == 3
              && bs2.cask(2) != null && c3 != null && c3.id == 3 && bs2.grain == 0,
              "id=" + (c3 == null ? -1 : c3.id) + " grain=" + bs2.grain);
    }

    // =====================================================================
    static void leather(){
        TannerySystem ts = new TannerySystem();
        check("tan: invalid build refused", !ts.build("") && ts.build("oak") && !ts.build("oak"), "");
        check("tan: bad hides refused", !ts.deliverHides("iron", 2) && !ts.deliverHides("oak", 0)
              && !ts.deliverHides("oak", 21) && ts.deliverHides("oak", 10), "");

        // deterministic soak: clean 5-day run → 8.0 leather; day-1 spoil → 4.0
        TannerySystem clean = null, spoiled = null;
        for(int s2 = 1; s2 <= 400 && (clean == null || spoiled == null); s2++){
            TannerySystem probe = new TannerySystem();
            probe.seed = s2;
            probe.build("oak");
            probe.deliverHides("oak", 10);
            probe.tickDay();
            if(probe.pit("oak").spoils > 0){
                if(spoiled == null) spoiled = probe;
            } else if(clean == null) clean = probe;
        }
        if(clean != null){
            for(int i = 1; i < 5; i++) clean.tickDay();   // finish the soak (4 more days)
            if(clean.pit("oak").spoils == 0 && clean.pit("oak").batches == 1
               && Math.abs(clean.leatherOf("oak") - 8f) < 0.001f){
                check("tan: five-day soak yields 80%", Math.abs(clean.takeLeather("oak") - 8f) < 0.001f
                      && Math.abs(clean.leatherOf("oak")) < 0.001f, "");
            } else {
                // the clean probe spoiled later in its five days — rescan a fully clean seed
                TannerySystem pure = null;
                for(int s2 = 1; s2 <= 400 && pure == null; s2++){
                    TannerySystem probe = new TannerySystem();
                    probe.seed = s2;
                    probe.build("oak");
                    probe.deliverHides("oak", 10);
                    for(int i = 0; i < 5; i++) probe.tickDay();
                    if(probe.pit("oak").spoils == 0 && probe.pit("oak").batches == 1
                       && Math.abs(probe.leatherOf("oak") - 8f) < 0.001f) pure = probe;
                }
                if(pure != null){
                    check("tan: five-day soak yields 80%", Math.abs(pure.takeLeather("oak") - 8f) < 0.001f
                          && Math.abs(pure.leatherOf("oak")) < 0.001f, "");
                } else {
                    check("tan: five-day soak yields 80%", false, "no fully clean seed in 400");
                }
            }
        } else {
            check("tan: five-day soak yields 80%", false, "no clean seed in 400");
        }
        if(spoiled != null){
            for(int i = 1; i < 5; i++) spoiled.tickDay();
            if(spoiled.pit("oak").spoils == 5 && Math.abs(spoiled.leatherOf("oak") - 4f) < 0.001f){
                check("tan: spoilage halves the stock", spoiled.pit("oak").spoils == 5
                      && Math.abs(spoiled.leatherOf("oak") - 4f) < 0.001f, "");
            } else {
                // rescan: spoil on day 1 only, clean afterwards
                TannerySystem oneSpoil = null;
                for(int s2 = 1; s2 <= 400 && oneSpoil == null; s2++){
                    TannerySystem probe = new TannerySystem();
                    probe.seed = s2;
                    probe.build("oak");
                    probe.deliverHides("oak", 10);
                    for(int i = 0; i < 5; i++) probe.tickDay();
                    if(probe.pit("oak").spoils == 5 && Math.abs(probe.leatherOf("oak") - 4f) < 0.001f)
                        oneSpoil = probe;
                }
                if(oneSpoil != null){
                    check("tan: spoilage halves the stock", oneSpoil.pit("oak").spoils == 5
                          && Math.abs(oneSpoil.leatherOf("oak") - 4f) < 0.001f, "");
                } else {
                    check("tan: spoilage halves the stock", false, "no single-spoil seed in 400");
                }
            }
        } else {
            check("tan: spoilage halves the stock", false, "no spoil seed in 400");
        }

        TannerySystem src = clean != null ? clean : new TannerySystem();
        String json = src.save();
        TannerySystem ts2 = new TannerySystem();
        ts2.load(json);
        check("tan: save/load", ts2.pit("oak") != null && Math.abs(ts2.leatherOf("oak")) < 0.001f, "");
    }

    // =====================================================================
    static void hospice(){
        HealerSystem hs = new HealerSystem();
        final List<String> events = new ArrayList<>();
        hs.listener = (type, who, detail) -> events.add(type);

        check("heal: invalid admits refused", !hs.admit("", "fever", 2) && !hs.admit("pip", "", 2)
              && hs.admit("pip", "fever", 2) && !hs.admit("pip", "flux", 2), "");
        check("heal: beds fill at three", hs.admit("wren", "flux", 2) && hs.admit("mira", "sprain", 2)
              && !hs.admit("zed", "cough", 2), "");

        // discharge counts neither way
        check("heal: discharge frees a bed", hs.discharge("wren") && !hs.discharge("wren")
              && hs.bedCount() == 2 && hs.recoveredCount == 0 && hs.lostCount == 0, "");

        // deterministic outcomes: scan seeds for pip recovered vs died (recovery 1 day)
        HealerSystem rec = null, died = null;
        for(int s2 = 1; s2 <= 400 && (rec == null || died == null); s2++){
            HealerSystem probe = new HealerSystem();
            probe.seed = s2;
            probe.admit("pip", "fever", 1);
            probe.tickDay();
            if(probe.recoveredCount == 1 && probe.bedCount() == 0){ if(rec == null) rec = probe; }
            else if(probe.lostCount == 1 && probe.bedCount() == 0 && died == null) died = probe;
        }
        if(rec != null){
            check("heal: recovery frees the bed", rec.recoveredCount == 1 && rec.bedCount() == 0, "");
        } else {
            check("heal: recovery frees the bed", false, "no recovery seed in 400");
        }
        if(died != null){
            check("heal: the ward can lose one", died.lostCount == 1 && died.recoveredCount == 0, "");
        } else {
            check("heal: the ward can lose one", false, "no death seed in 400");
        }

        String json = rec.save();
        HealerSystem hs2 = new HealerSystem();
        hs2.load(json);
        check("heal: save/load", hs2.recoveredCount == 1 && hs2.bedCount() == 0
              && hs2.admit("old", "scar", 5) && hs2.patient("old").daysUnderCare == 0, "");
    }
}
