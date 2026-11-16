import com.hollow.game.AuctionSystem;
import com.hollow.game.DyerSystem;
import com.hollow.game.LoomSystem;
import com.hollow.game.ShearerSystem;
import com.hollow.game.SpinnerSystem;
import com.hollow.world.BleachFieldSystem;
import com.hollow.world.MillPondSystem;
import com.hollow.world.WashHouseSystem;
import com.hollow.world.WoolStapleSystem;

/** v19.0.0 regression suite — wool staples, bleach fields, wash
 *  houses, mill ponds, shearer benches, spinner wheels, looms,
 *  dyer vats, plus FIX19-001 (AuctionSystem empty-name guards). */
public class VerifyV19 {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        auctionFix();
        staples();
        bleachfields();
        washhouses();
        millponds();
        shears();
        wheels();
        looms();
        vats();
        System.out.println(fails == 0 ? "\nALL " + total + " V19 TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " V19 TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void auctionFix(){
        AuctionSystem au = new AuctionSystem();
        AuctionSystem.Listing l = au.list("iron sword", 1, 50, "smith", 3);
        check("fx: empty seller refused", au.list("sword", 1, 50, "", 3) == null, "");
        check("fx: empty bidder refused", !au.bid(l.id, "", 60), "");
        check("fx: listing untouched by empty bid", l.state == AuctionSystem.State.OPEN
              && l.topBid == 0 && l.topBidder.isEmpty(), "");
        // valid flow still works end to end (duration 1 stays open through day 1)
        AuctionSystem.Listing l2 = au.list("wool bale", 2, 10, "farmer", 1);
        check("fx: valid list + reserve bid", l2 != null && au.bid(l2.id, "hana", 10), "");
        au.tickDay();
        au.tickDay();
        check("fx: sale pays proceeds", l2.state == AuctionSystem.State.SOLD
              && au.sellerProceeds("farmer") == 10 && au.sellerProceeds("smith") == 0, "");
    }

    // =====================================================================
    static void staples(){
        WoolStapleSystem ws = new WoolStapleSystem();
        check("stap: invalid open refused", !ws.open("") && ws.open("byram") && !ws.open("byram"), "");
        check("stap: receive guards", !ws.receive("dunmor", 2) && !ws.receive("byram", 0)
              && !ws.receive("byram", 21) && ws.receive("byram", 5), "");
        check("stap: sell guards", !ws.sell("dunmor", 1) && !ws.sell("byram", 0)
              && !ws.sell("byram", 6) && ws.receive("byram", 1), "");

        // scan finds one fair sale and one damp sale
        WoolStapleSystem fair = null, damp = null;
        for(int s = 1; s <= 400 && (fair == null || damp == null); s++){
            WoolStapleSystem probe = new WoolStapleSystem();
            probe.seed = s;
            probe.open("byram");
            probe.receive("byram", 2);
            probe.tickDay();                       // advance the day so the sale rolls
            probe.sell("byram", 2);
            if(probe.staple("byram").gross == 80 && probe.staple("byram").sold == 2
               && probe.staple("byram").bales == 0 && fair == null) fair = probe;
            if(probe.staple("byram").gross == 72 && probe.staple("byram").sold == 2
               && probe.staple("byram").bales == 0 && damp == null) damp = probe;
        }
        if(fair != null){
            check("stap: fair price paid", fair.staple("byram").gross == 80, "");
        } else {
            check("stap: fair price paid", false, "no fair seed in 400");
        }
        if(damp != null){
            check("stap: damp wool dips the price", damp.staple("byram").gross == 72, "");
        } else {
            check("stap: damp wool dips the price", false, "no damp seed in 400");
        }

        // two sales on two days both fair (scan across BOTH sale rolls)
        WoolStapleSystem two = null;
        for(int s = 1; s <= 400 && two == null; s++){
            WoolStapleSystem probe = new WoolStapleSystem();
            probe.seed = s;
            probe.open("byram");
            probe.receive("byram", 2);
            probe.sell("byram", 1);                // day 0 roll
            probe.tickDay();
            probe.sell("byram", 1);                // day 1 roll
            if(probe.staple("byram").gross == 80 && probe.staple("byram").sold == 2
               && probe.staple("byram").bales == 0) two = probe;
        }
        if(two != null){
            check("stap: two fair sales pile up", two.staple("byram").gross == 80
                  && two.staple("byram").sold == 2, "");
        } else {
            check("stap: two fair sales pile up", false, "no two-sale seed in 400");
        }

        String json = fair.save();
        WoolStapleSystem ws2 = new WoolStapleSystem();
        ws2.load(json);
        check("stap: save/load", ws2.staple("byram") != null && ws2.staple("byram").sold == 2
              && ws2.staple("byram").gross == 80 && ws2.staple("byram").bales == 0, "");
    }

    // =====================================================================
    static void bleachfields(){
        BleachFieldSystem bf = new BleachFieldSystem();
        check("blf: invalid stake refused", !bf.stake("") && bf.stake("ashvale") && !bf.stake("ashvale"), "");
        check("blf: lay guards", !bf.lay("dunmor", 1) && !bf.lay("ashvale", 0)
              && !bf.lay("ashvale", 13) && bf.lay("ashvale", 4), "");

        // scan finds three clean sun days and one rain-wasted day
        BleachFieldSystem clean = null, rainy = null;
        for(int s = 1; s <= 400 && (clean == null || rainy == null); s++){
            BleachFieldSystem probe = new BleachFieldSystem();
            probe.seed = s;
            probe.stake("ashvale");
            probe.lay("ashvale", 4);
            probe.tickDay(); probe.tickDay(); probe.tickDay();
            if(probe.field("ashvale").bleached == 4 && probe.field("ashvale").sunDays == 0
               && probe.field("ashvale").rains == 0 && probe.field("ashvale").lengths == 0
               && clean == null) clean = probe;
            BleachFieldSystem one = new BleachFieldSystem();
            one.seed = s;
            one.stake("ashvale");
            one.lay("ashvale", 4);
            one.tickDay();
            if(one.field("ashvale").rains == 1 && one.field("ashvale").sunDays == 0
               && one.field("ashvale").lengths == 4 && rainy == null) rainy = one;
        }
        if(clean != null){
            check("blf: three sun days bleach the batch", clean.field("ashvale").bleached == 4
                  && clean.field("ashvale").sunDays == 0, "");
        } else {
            check("blf: three sun days bleach the batch", false, "no clean seed in 400");
        }
        if(rainy != null){
            check("blf: rain wastes the day", rainy.field("ashvale").rains == 1
                  && rainy.field("ashvale").sunDays == 0 && rainy.field("ashvale").lengths == 4, "");
        } else {
            check("blf: rain wastes the day", false, "no rain seed in 400");
        }

        // partial banking: two clean days leave the batch unfinished
        BleachFieldSystem part = null;
        for(int s = 1; s <= 400 && part == null; s++){
            BleachFieldSystem probe = new BleachFieldSystem();
            probe.seed = s;
            probe.stake("ashvale");
            probe.lay("ashvale", 4);
            probe.tickDay(); probe.tickDay();
            if(probe.field("ashvale").sunDays == 2 && probe.field("ashvale").bleached == 0
               && probe.field("ashvale").rains == 0) part = probe;
        }
        if(part != null){
            check("blf: sun days bank before the finish", part.field("ashvale").sunDays == 2
                  && part.field("ashvale").bleached == 0, "");
        } else {
            check("blf: sun days bank before the finish", false, "no partial seed in 400");
        }

        // two batches across a re-lay (scan covers all six ticks)
        BleachFieldSystem two = null;
        for(int s = 1; s <= 400 && two == null; s++){
            BleachFieldSystem probe = new BleachFieldSystem();
            probe.seed = s;
            probe.stake("ashvale");
            probe.lay("ashvale", 2);
            probe.tickDay(); probe.tickDay(); probe.tickDay();
            probe.lay("ashvale", 2);
            probe.tickDay(); probe.tickDay(); probe.tickDay();
            if(probe.field("ashvale").bleached == 4 && probe.field("ashvale").rains == 0
               && probe.field("ashvale").lengths == 0 && probe.field("ashvale").sunDays == 0) two = probe;
        }
        if(two != null){
            check("blf: second batch bleaches too", two.field("ashvale").bleached == 4
                  && two.field("ashvale").rains == 0, "");
        } else {
            check("blf: second batch bleaches too", false, "no two-batch seed in 400");
        }

        String json = clean.save();
        BleachFieldSystem bf2 = new BleachFieldSystem();
        bf2.load(json);
        check("blf: save/load", bf2.field("ashvale") != null && bf2.field("ashvale").bleached == 4
              && bf2.field("ashvale").sunDays == 0, "");
    }

    // =====================================================================
    static void washhouses(){
        WashHouseSystem wh = new WashHouseSystem();
        check("wsh: invalid build refused", !wh.build("") && wh.build("ashvale") && !wh.build("ashvale"), "");
        check("wsh: bring guards", !wh.bring("dunmor", 2) && !wh.bring("ashvale", 0)
              && !wh.bring("ashvale", 19) && wh.bring("ashvale", 6), "");

        // scan finds clean washing and a scalded trough (scald probe: one tick —
        // a second tick would wash the surviving fleeces and change the ledger)
        WashHouseSystem clean = null, scalded = null;
        for(int s = 1; s <= 400 && (clean == null || scalded == null); s++){
            WashHouseSystem probe = new WashHouseSystem();
            probe.seed = s;
            probe.build("ashvale");
            probe.bring("ashvale", 6);
            probe.tickDay(); probe.tickDay();
            if(probe.trough("ashvale").washed == 6 && probe.trough("ashvale").lanolin == 2
               && probe.trough("ashvale").spoil == 0 && clean == null) clean = probe;
            WashHouseSystem one = new WashHouseSystem();
            one.seed = s;
            one.build("ashvale");
            one.bring("ashvale", 6);
            one.tickDay();
            if(one.trough("ashvale").spoil == 3 && one.trough("ashvale").washed == 0
               && one.trough("ashvale").lanolin == 0 && scalded == null) scalded = one;
        }
        if(scalded != null){
            check("wsh: scald spoils the batch", scalded.trough("ashvale").spoil == 3
                  && scalded.trough("ashvale").lanolin == 0, "");
        } else {
            check("wsh: scald spoils the batch", false, "no scald seed in 400");
        }
        if(clean != null){
            check("wsh: clean wash renders lanolin", clean.trough("ashvale").washed == 6
                  && clean.trough("ashvale").lanolin == 2 && clean.trough("ashvale").fleeces == 0, "");
        } else {
            check("wsh: clean wash renders lanolin", false, "no clean seed in 400");
        }

        // odd leftover fleece washes alone (clean scan over both ticks)
        WashHouseSystem odd = null;
        for(int s = 1; s <= 400 && odd == null; s++){
            WashHouseSystem probe = new WashHouseSystem();
            probe.seed = s;
            probe.build("ashvale");
            probe.bring("ashvale", 4);
            probe.tickDay(); probe.tickDay();
            if(probe.trough("ashvale").washed == 4 && probe.trough("ashvale").lanolin == 2
               && probe.trough("ashvale").spoil == 0) odd = probe;
        }
        if(odd != null){
            check("wsh: odd fleece washes alone", odd.trough("ashvale").washed == 4
                  && odd.trough("ashvale").lanolin == 2, "");
        } else {
            check("wsh: odd fleece washes alone", false, "no odd seed in 400");
        }

        String json = clean.save();
        WashHouseSystem wh2 = new WashHouseSystem();
        wh2.load(json);
        check("wsh: save/load", wh2.trough("ashvale") != null && wh2.trough("ashvale").washed == 6
              && wh2.trough("ashvale").lanolin == 2, "");
    }

    // =====================================================================
    static void millponds(){
        MillPondSystem mp = new MillPondSystem();
        check("mpd: invalid dig refused", !mp.dig("") && mp.dig("oakford") && !mp.dig("oakford"), "");
        check("mpd: unknown pond refused", !mp.fill("dunmor"), "");
        MillPondSystem full = new MillPondSystem();
        full.dig("oakford");
        for(int i = 0; i < 6; i++) full.fill("oakford");               // 48 inches
        check("mpd: full pond refuses more water", !full.fill("oakford")
              && full.pond("oakford").depthInches == 48, "");
        check("mpd: fill raises the head", mp.fill("oakford") && mp.pond("oakford").depthInches == 8, "");

        // empty pond idles: no wheel, no roll
        MillPondSystem dry = new MillPondSystem();
        dry.dig("oakford");
        dry.tickDay();
        check("mpd: empty pond idles", dry.pond("oakford").wheelTurns == 0
              && dry.pond("oakford").depthInches == 0 && dry.pond("oakford").drains == 0, "");

        // wheel takes its water: 16 inches -> one tick leaves 4
        MillPondSystem half = new MillPondSystem();
        half.dig("oakford");
        half.fill("oakford"); half.fill("oakford");
        half.tickDay();
        check("mpd: wheel turns and drains", half.pond("oakford").wheelTurns == 3
              && half.pond("oakford").depthInches == 4, "");
        half.tickDay();
        check("mpd: shallow head still turns", half.pond("oakford").wheelTurns == 6
              && half.pond("oakford").depthInches == 0, "");

        // scan finds a leak day and a sound bank (24 inches: drain leaves 12)
        MillPondSystem leaky = null, sound = null;
        for(int s = 1; s <= 400 && (leaky == null || sound == null); s++){
            MillPondSystem probe = new MillPondSystem();
            probe.seed = s;
            probe.dig("oakford");
            probe.fill("oakford"); probe.fill("oakford"); probe.fill("oakford");
            probe.tickDay();
            if(probe.pond("oakford").drains == 1 && probe.pond("oakford").depthInches == 6
               && probe.pond("oakford").wheelTurns == 3 && leaky == null) leaky = probe;
            if(probe.pond("oakford").drains == 0 && probe.pond("oakford").depthInches == 12
               && probe.pond("oakford").wheelTurns == 3 && sound == null) sound = probe;
        }
        if(leaky != null){
            check("mpd: leak takes six more inches", leaky.pond("oakford").drains == 1
                  && leaky.pond("oakford").depthInches == 6, "");
        } else {
            check("mpd: leak takes six more inches", false, "no leak seed in 400");
        }
        if(sound != null){
            check("mpd: sound bank holds", sound.pond("oakford").drains == 0
                  && sound.pond("oakford").depthInches == 12, "");
        } else {
            check("mpd: sound bank holds", false, "no sound seed in 400");
        }

        String json = sound.save();
        MillPondSystem mp2 = new MillPondSystem();
        mp2.load(json);
        check("mpd: save/load", mp2.pond("oakford") != null && mp2.pond("oakford").depthInches == 12
              && mp2.pond("oakford").wheelTurns == 3, "");
    }

    // =====================================================================
    static void shears(){
        ShearerSystem sh = new ShearerSystem();
        check("shr: bad tag refused", sh.book("") == null, "");
        sh.book("ewe-7");
        sh.tickDay();
        check("shr: single day shearing", sh.job(1) != null && sh.job(1).done
              && sh.job(1).daysWaited == 1 && (sh.job(1).nicked ^ !sh.job(1).nicked), "");

        ShearerSystem clean = null, nicked = null;
        for(int s = 1; s <= 400 && (clean == null || nicked == null); s++){
            ShearerSystem probe = new ShearerSystem();
            probe.seed = s;
            probe.book("ewe-7");
            probe.tickDay();
            if(probe.fleeces == 3 && probe.nicks == 0 && clean == null) clean = probe;
            if(probe.fleeces == 1 && probe.nicks == 1 && nicked == null) nicked = probe;
        }
        if(clean != null){
            check("shr: clean harvest of three", clean.fleeces == 3
                  && clean.harvestedCount() == 1 && !clean.job(1).nicked, "");
        } else {
            check("shr: clean harvest of three", false, "no clean seed in 400");
        }
        if(nicked != null){
            check("shr: nick leaves one fleece", nicked.fleeces == 1 && nicked.nicks == 1
                  && nicked.harvestedCount() == 0, "");
        } else {
            check("shr: nick leaves one fleece", false, "no nick seed in 400");
        }

        String json = clean.save();
        ShearerSystem sh2 = new ShearerSystem();
        sh2.load(json);
        // saved state: one done job, fleeces 3, nextId 2 (no paid stock in this craft)
        check("shr: save/load", sh2.job(1) != null && sh2.job(1).done && sh2.fleeces == 3, "");
        ShearerSystem.Job j2 = sh2.book("ewe-9");
        check("shr: id continues", j2 != null && j2.id == 2, "id=" + (j2 == null ? -1 : j2.id));
    }

    // =====================================================================
    static void wheels(){
        SpinnerSystem sp = new SpinnerSystem();
        check("spn: fleece guards", !sp.deliverFleeces(0) && !sp.deliverFleeces(-1)
              && !sp.deliverFleeces(17), "");
        check("spn: spin without fleeces", sp.spin("coarse") == null, "");
        check("spn: fleeces paid up front", sp.deliverFleeces(4) && sp.spin("coarse") != null
              && sp.fleeces == 2, "fleeces=" + sp.fleeces);
        check("spn: bad grade refused", sp.spin("") == null, "");

        sp.tickDay();
        check("spn: wheel halfway", sp.batch(1) != null && !sp.batch(1).done
              && sp.batch(1).daysSpun == 1, "");
        sp.tickDay();
        check("spn: skeining decides once", sp.batch(1).done, "");

        SpinnerSystem clean = null, broken = null;
        for(int s = 1; s <= 400 && (clean == null || broken == null); s++){
            SpinnerSystem probe = new SpinnerSystem();
            probe.seed = s;
            probe.deliverFleeces(2);
            probe.spin("coarse");
            probe.tickDay(); probe.tickDay();
            if(probe.batch(1).broken == false && probe.skeins == 5 && probe.breaks == 0
               && clean == null) clean = probe;
            if(probe.batch(1).broken && probe.skeins == 2 && probe.breaks == 1
               && broken == null) broken = probe;
        }
        if(clean != null){
            check("spn: full draw of five", clean.skeins == 5 && clean.breaks == 0, "");
        } else {
            check("spn: full draw of five", false, "no clean seed in 400");
        }
        if(broken != null){
            check("spn: broken thread leaves two", broken.skeins == 2 && broken.breaks == 1
                  && broken.batch(1).broken, "");
        } else {
            check("spn: broken thread leaves two", false, "no broken seed in 400");
        }

        String json = clean.save();
        SpinnerSystem sp2 = new SpinnerSystem();
        sp2.load(json);
        // saved state: fleeces 0 (probe paid its only two), skeins 5, nextId 2
        check("spn: save/load, no fleece magic", sp2.batch(1) != null && sp2.skeins == 5
              && sp2.fleeces == 0 && sp2.spin("wool") == null, "");
        sp2.deliverFleeces(2);
        SpinnerSystem.Batch b2 = sp2.spin("wool");
        check("spn: id continues", b2 != null && b2.id == 2 && sp2.fleeces == 0,
              "id=" + (b2 == null ? -1 : b2.id));
    }

    // =====================================================================
    static void looms(){
        LoomSystem lm = new LoomSystem();
        check("loom: skein guards", !lm.deliverSkeins(0) && !lm.deliverSkeins(-1)
              && !lm.deliverSkeins(21), "");
        check("loom: weave without skeins", lm.weave("madder red") == null, "");
        check("loom: skeins paid up front", lm.deliverSkeins(10) && lm.weave("madder red") != null
              && lm.skeins == 5, "skeins=" + lm.skeins);
        check("loom: bad color refused", lm.weave("") == null, "");

        lm.tickDay(); lm.tickDay();
        check("loom: loom halfway", lm.run(1) != null && !lm.run(1).done
              && lm.run(1).daysWoven == 2, "");
        lm.tickDay();
        check("loom: selvedge decides once", lm.run(1).done, "");

        LoomSystem sound = null, mispicked = null;
        for(int s = 1; s <= 400 && (sound == null || mispicked == null); s++){
            LoomSystem probe = new LoomSystem();
            probe.seed = s;
            probe.deliverSkeins(5);
            probe.weave("madder red");
            probe.tickDay(); probe.tickDay(); probe.tickDay();
            if(probe.run(1).mispicked == false && probe.ells == 2 && probe.mispicks == 0
               && sound == null) sound = probe;
            if(probe.run(1).mispicked && probe.ells == 1 && probe.mispicks == 1
               && mispicked == null) mispicked = probe;
        }
        if(sound != null){
            check("loom: two sound ells", sound.ells == 2 && sound.mispicks == 0, "");
        } else {
            check("loom: two sound ells", false, "no sound seed in 400");
        }
        if(mispicked != null){
            check("loom: mispick leaves one flawed ell", mispicked.ells == 1
                  && mispicked.mispicks == 1 && mispicked.run(1).mispicked, "");
        } else {
            check("loom: mispick leaves one flawed ell", false, "no mispick seed in 400");
        }

        String json = sound.save();
        LoomSystem lm2 = new LoomSystem();
        lm2.load(json);
        // saved state: skeins 0 (probe paid its only five), ells 2, nextId 2
        check("loom: save/load, no skein magic", lm2.run(1) != null && lm2.ells == 2
              && lm2.skeins == 0 && lm2.weave("woad blue") == null, "");
        lm2.deliverSkeins(5);
        LoomSystem.Run r2 = lm2.weave("woad blue");
        check("loom: id continues", r2 != null && r2.id == 2 && lm2.skeins == 0,
              "id=" + (r2 == null ? -1 : r2.id));
    }

    // =====================================================================
    static void vats(){
        DyerSystem dy = new DyerSystem();
        check("dyr: ell guards", !dy.deliverElls(0) && !dy.deliverElls(-1)
              && !dy.deliverElls(11), "");
        check("dyr: dip without ells", dy.dip("woad blue") == null, "");
        check("dyr: ell paid up front", dy.deliverElls(2) && dy.dip("woad blue") != null
              && dy.ells == 1, "ells=" + dy.ells);
        check("dyr: bad shade refused", dy.dip("") == null, "");

        dy.tickDay();
        check("dyr: vat halfway", dy.vat(1) != null && !dy.vat(1).done
              && dy.vat(1).daysDipped == 1, "");
        dy.tickDay();
        check("dyr: shade test decides once", dy.vat(1).done
              && (dy.vat(1).trueShade ^ dy.vat(1).muddy), "");

        DyerSystem trued = null, muddy = null;
        for(int s = 1; s <= 400 && (trued == null || muddy == null); s++){
            DyerSystem probe = new DyerSystem();
            probe.seed = s;
            probe.deliverElls(1);
            probe.dip("woad blue");
            probe.tickDay(); probe.tickDay();
            if(probe.vat(1).trueShade && trued == null) trued = probe;
            if(probe.vat(1).muddy && muddy == null) muddy = probe;
        }
        if(trued != null){
            check("dyr: true shade counted", trued.trueCount() == 1 && !trued.vat(1).muddy, "");
        } else {
            check("dyr: true shade counted", false, "no true seed in 400");
        }
        if(muddy != null){
            check("dyr: muddy dip flagged honestly", muddy.vat(1).muddy && muddy.trueCount() == 0, "");
        } else {
            check("dyr: muddy dip flagged honestly", false, "no muddy seed in 400");
        }

        String json = trued.save();
        DyerSystem dy2 = new DyerSystem();
        dy2.load(json);
        // saved state: ells 0 (probe paid its only ell), nextId 2
        check("dyr: save/load, no ell magic", dy2.vat(1) != null && dy2.vat(1).trueShade
              && dy2.ells == 0 && dy2.dip("madder red") == null, "");
        dy2.deliverElls(1);
        DyerSystem.Vat v2 = dy2.dip("madder red");
        check("dyr: id continues", v2 != null && v2.id == 2 && dy2.ells == 0,
              "id=" + (v2 == null ? -1 : v2.id));
    }
}
