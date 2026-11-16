import com.hollow.gameplay.QuestSystem;
import com.hollow.game.BasketWeaverSystem;
import com.hollow.game.BookbinderSystem;
import com.hollow.game.PapermakerSystem;
import com.hollow.game.TinkerSystem;
import com.hollow.world.AlmshouseSystem;
import com.hollow.world.HatcherySystem;
import com.hollow.world.PinfoldSystem;
import com.hollow.world.TitheBarnSystem;

/** v18.0.0 regression suite — village pounds, tithe barns, hatchery
 *  tanks, almshouses, tinker benches, paper vats, bookbinder frames,
 *  basket weaver benches, plus FIX18-001 (QuestSystem.progress
 *  guards). */
public class VerifyV18 {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        questProgressFix();
        pounds();
        barns();
        tanks();
        houses();
        tins();
        vats();
        frames();
        osiers();
        System.out.println(fails == 0 ? "\nALL " + total + " V18 TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " V18 TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void questProgressFix(){
        QuestSystem qs = new QuestSystem();
        final int[] fired = {0};
        qs.listeners.add((type, q) -> { if("objective".equals(type)) fired[0]++; });
        QuestSystem.Quest q = new QuestSystem.Quest("q1", "Wolf cull");
        qs.add(q);
        q.objective("wolves", "Cull wolves", 3, (qq, o) -> o.progress);
        check("fx1: unknown quest refused", !qs.progress("nope", "wolves", 1), "");
        qs.start("q1");
        check("fx1: zero bump refused", !qs.progress("q1", "wolves", 0)
              && qs.quest("q1").objectives.get(0).progress == 0, "");
        check("fx1: negative bump refused (no rewind)", !qs.progress("q1", "wolves", -2)
              && qs.quest("q1").objectives.get(0).progress == 0, "");
        check("fx1: unknown objective refused", !qs.progress("q1", "ghosts", 1), "");
        qs.progress("q1", "wolves", 2);
        check("fx1: bump below target no event", fired[0] == 0, "fired=" + fired[0]);
        qs.progress("q1", "wolves", 1);
        check("fx1: crossing fires once", fired[0] == 1, "fired=" + fired[0]);
        qs.progress("q1", "wolves", 5);                       // already complete — no re-fire
        qs.progress("q1", "wolves", 1);
        check("fx1: no event spam after completion", fired[0] == 1
              && qs.quest("q1").objectives.get(0).progress == 3, "fired=" + fired[0]);
        check("fx1: quest auto-completed", "completed".equals(qs.quest("q1").state), "");
        // progress() on a completed quest is refused outright
        check("fx1: completed quest not progressable", !qs.progress("q1", "wolves", 1), "");
    }

    // =====================================================================
    static void pounds(){
        PinfoldSystem pf = new PinfoldSystem();
        check("pin: invalid build refused", !pf.build("") && pf.build("oak") && !pf.build("oak"), "");
        check("pin: impound guards", !pf.impound("iron", 1) && !pf.impound("oak", 0)
              && !pf.impound("oak", 5) && pf.impound("oak", 2), "");

        // empty pound: no roll, no costs
        PinfoldSystem empty = new PinfoldSystem();
        empty.build("oak");
        empty.tickDay();
        check("pin: empty pound idles", empty.pound("oak").penned == 0
              && empty.pound("oak").fines == 0 && empty.pound("oak").feedCosts == 0, "");

        // scan finds one claim day and one clean feeding day
        PinfoldSystem claimed = null, clean = null;
        for(int s = 1; s <= 400 && (claimed == null || clean == null); s++){
            PinfoldSystem probe = new PinfoldSystem();
            probe.seed = s;
            probe.build("oak");
            probe.impound("oak", 2);
            probe.tickDay();
            if(probe.pound("oak").penned == 1 && probe.pound("oak").fines == 2
               && probe.pound("oak").feedCosts == 0 && claimed == null) claimed = probe;
            if(probe.pound("oak").penned == 2 && probe.pound("oak").fines == 0
               && probe.pound("oak").feedCosts == 1 && clean == null) clean = probe;
        }
        if(claimed != null){
            check("pin: claim fines the head", claimed.pound("oak").fines == 2
                  && claimed.pound("oak").penned == 1, "");
        } else {
            check("pin: claim fines the head", false, "no claim seed in 400");
        }
        if(clean != null){
            check("pin: clean day feeds the strays", clean.pound("oak").feedCosts == 1
                  && clean.pound("oak").fines == 0, "");
        } else {
            check("pin: clean day feeds the strays", false, "no feed seed in 400");
        }

        // two clean days pile the feed ledger (scan across BOTH ticks)
        PinfoldSystem two = null;
        for(int s = 1; s <= 400 && two == null; s++){
            PinfoldSystem probe = new PinfoldSystem();
            probe.seed = s;
            probe.build("oak");
            probe.impound("oak", 2);
            probe.tickDay(); probe.tickDay();
            if(probe.pound("oak").feedCosts == 2 && probe.pound("oak").fines == 0
               && probe.pound("oak").penned == 2) two = probe;
        }
        if(two != null){
            check("pin: two feeding days pile up", two.pound("oak").feedCosts == 2
                  && two.pound("oak").penned == 2, "");
        } else {
            check("pin: two feeding days pile up", false, "no two-day seed in 400");
        }

        String json = clean.save();
        PinfoldSystem pf2 = new PinfoldSystem();
        pf2.load(json);
        check("pin: save/load", pf2.pound("oak") != null && pf2.pound("oak").penned == 2
              && pf2.pound("oak").feedCosts == 1, "");
    }

    // =====================================================================
    static void barns(){
        TitheBarnSystem tb = new TitheBarnSystem();
        check("tit: invalid raise refused", !tb.raise("") && tb.raise("elm") && !tb.raise("elm"), "");
        check("tit: store guards", !tb.store("ash", 5) && !tb.store("elm", 0)
              && !tb.store("elm", 51) && tb.store("elm", 25), "");

        // scan: clean tick on 25 sheaves -> a literal tenth (2) handed over
        TitheBarnSystem clean = null, mice = null;
        for(int s = 1; s <= 400 && (clean == null || mice == null); s++){
            TitheBarnSystem probe = new TitheBarnSystem();
            probe.seed = s;
            probe.raise("elm");
            probe.store("elm", 25);
            probe.tickDay();
            if(probe.barn("elm").tithed == 2 && probe.barn("elm").sheaves == 23
               && probe.barn("elm").mice == 0 && clean == null) clean = probe;
            if(probe.barn("elm").tithed == 2 && probe.barn("elm").sheaves == 22
               && probe.barn("elm").mice == 1 && mice == null) mice = probe;
        }
        if(clean != null){
            check("tit: a literal tenth handed over", clean.barn("elm").tithed == 2
                  && clean.barn("elm").sheaves == 23 && clean.barn("elm").mice == 0, "");
        } else {
            check("tit: a literal tenth handed over", false, "no clean seed in 400");
        }
        if(mice != null){
            check("tit: mice take one more", mice.barn("elm").mice == 1
                  && mice.barn("elm").sheaves == 22, "");
        } else {
            check("tit: mice take one more", false, "no mice seed in 400");
        }

        // three clean days on 30: tithes 3+2+2 = 7, remain 23
        TitheBarnSystem three = null;
        for(int s = 1; s <= 400 && three == null; s++){
            TitheBarnSystem probe = new TitheBarnSystem();
            probe.seed = s;
            probe.raise("elm");
            probe.store("elm", 30);
            for(int t = 0; t < 3; t++) probe.tickDay();
            if(probe.barn("elm").tithed == 7 && probe.barn("elm").sheaves == 23
               && probe.barn("elm").mice == 0) three = probe;
        }
        if(three != null){
            check("tit: three clean tithes pile up", three.barn("elm").tithed == 7
                  && three.barn("elm").sheaves == 23, "");
        } else {
            check("tit: three clean tithes pile up", false, "no three-day seed in 400");
        }

        // below a tenth: nothing handed over, stock kept
        TitheBarnSystem small = new TitheBarnSystem();
        small.raise("elm");
        small.store("elm", 5);
        small.tickDay();
        check("tit: below a tenth keeps all", small.barn("elm").tithed == 0
              && small.barn("elm").sheaves == 5, "");

        String json = clean.save();
        TitheBarnSystem tb2 = new TitheBarnSystem();
        tb2.load(json);
        check("tit: save/load", tb2.barn("elm") != null && tb2.barn("elm").sheaves == 23
              && tb2.barn("elm").tithed == 2, "");
    }

    // =====================================================================
    static void tanks(){
        HatcherySystem hs = new HatcherySystem();
        check("hat: invalid fill refused", !hs.fill("") && hs.fill("oak") && !hs.fill("oak"), "");
        check("hat: stock guards", !hs.stock("iron", 2) && !hs.stock("oak", 0)
              && !hs.stock("oak", 31) && hs.stock("oak", 6), "");

        // scan guarantees THREE clean hatches: three pairs swim free
        HatcherySystem clean = null, cold = null;
        for(int s = 1; s <= 400 && (clean == null || cold == null); s++){
            HatcherySystem probe = new HatcherySystem();
            probe.seed = s;
            probe.fill("oak");
            probe.stock("oak", 6);
            for(int t = 0; t < 3; t++) probe.tickDay();
            if(probe.tank("oak").fry == 6 && probe.tank("oak").eggs == 0
               && probe.tank("oak").losses == 0 && clean == null) clean = probe;
            HatcherySystem one = new HatcherySystem();
            one.seed = s;
            one.fill("oak");
            one.stock("oak", 6);
            one.tickDay();
            if(one.tank("oak").losses == 2 && one.tank("oak").fry == 0
               && one.tank("oak").eggs == 4 && cold == null) cold = one;
        }
        if(clean != null){
            check("hat: three pairs swim free", clean.tank("oak").fry == 6
                  && clean.tank("oak").losses == 0 && clean.tank("oak").eggs == 0, "");
        } else {
            check("hat: three pairs swim free", false, "no clean seed in 400");
        }
        if(cold != null){
            check("hat: cold snap takes the pair", cold.tank("oak").losses == 2
                  && cold.tank("oak").eggs == 4 && cold.tank("oak").fry == 0, "");
        } else {
            check("hat: cold snap takes the pair", false, "no cold seed in 400");
        }

        // odd egg: the leftover hatches alone (clean scan over both ticks)
        HatcherySystem odd = null;
        for(int s = 1; s <= 400 && odd == null; s++){
            HatcherySystem probe = new HatcherySystem();
            probe.seed = s;
            probe.fill("oak");
            probe.stock("oak", 3);
            for(int t = 0; t < 2; t++) probe.tickDay();
            if(probe.tank("oak").fry == 3 && probe.tank("oak").losses == 0
               && probe.tank("oak").eggs == 0) odd = probe;
        }
        if(odd != null){
            check("hat: odd egg hatches alone", odd.tank("oak").fry == 3
                  && odd.tank("oak").losses == 0, "");
        } else {
            check("hat: odd egg hatches alone", false, "no odd seed in 400");
        }

        String json = clean.save();
        HatcherySystem hs2 = new HatcherySystem();
        hs2.load(json);
        check("hat: save/load", hs2.tank("oak") != null && hs2.tank("oak").fry == 6
              && hs2.tank("oak").eggs == 0, "");
    }

    // =====================================================================
    static void houses(){
        AlmshouseSystem al = new AlmshouseSystem();
        check("alm: invalid found refused", !al.found("") && al.found("oak") && !al.found("oak"), "");
        check("alm: bed guards", !al.setBeds("iron", 1) && !al.setBeds("oak", -1)
              && !al.setBeds("oak", 7) && al.setBeds("oak", 3), "");

        // clean seed: shelter is certain, the box stays empty
        AlmshouseSystem clean = null, gifted = null;
        for(int s = 1; s <= 400 && (clean == null || gifted == null); s++){
            AlmshouseSystem probe = new AlmshouseSystem();
            probe.seed = s;
            probe.found("oak");
            probe.setBeds("oak", 3);
            probe.tickDay();
            if(probe.house("oak").nights == 3 && probe.house("oak").alms == 0
               && clean == null) clean = probe;
            if(probe.house("oak").nights == 3 && probe.house("oak").alms == 5
               && gifted == null) gifted = probe;
        }
        if(clean != null){
            check("alm: shelter counted honestly", clean.house("oak").nights == 3
                  && clean.house("oak").alms == 0, "");
        } else {
            check("alm: shelter counted honestly", false, "no clean seed in 400");
        }
        if(gifted != null){
            check("alm: benefactor fills the box", gifted.house("oak").alms == 5
                  && gifted.house("oak").nights == 3, "");
        } else {
            check("alm: benefactor fills the box", false, "no gift seed in 400");
        }

        // two clean nights pile the shelter ledger
        AlmshouseSystem two = null;
        for(int s = 1; s <= 400 && two == null; s++){
            AlmshouseSystem probe = new AlmshouseSystem();
            probe.seed = s;
            probe.found("oak");
            probe.setBeds("oak", 3);
            probe.tickDay(); probe.tickDay();
            if(probe.house("oak").nights == 6 && probe.house("oak").alms == 0) two = probe;
        }
        if(two != null){
            check("alm: two clean nights pile up", two.house("oak").nights == 6
                  && two.house("oak").alms == 0, "");
        } else {
            check("alm: two clean nights pile up", false, "no two-day seed in 400");
        }

        AlmshouseSystem empty = new AlmshouseSystem();
        empty.found("oak");
        empty.tickDay();
        check("alm: empty house idles", empty.house("oak").nights == 0
              && empty.house("oak").alms == 0, "");

        String json = clean.save();
        AlmshouseSystem al2 = new AlmshouseSystem();
        al2.load(json);
        check("alm: save/load", al2.house("oak") != null && al2.house("oak").beds == 3
              && al2.house("oak").nights == 3, "");
    }

    // =====================================================================
    static void tins(){
        TinkerSystem tk = new TinkerSystem();
        check("tin: parts guards", !tk.deliverParts(0) && !tk.deliverParts(-1)
              && !tk.deliverParts(9), "");
        check("tin: mend without parts", tk.mend("kettle") == null, "");
        check("tin: part paid up front", tk.deliverParts(2) && tk.mend("kettle") != null
              && tk.parts == 1, "parts=" + tk.parts);
        check("tin: bad item refused", tk.mend("") == null, "");

        tk.tickDay();
        check("tin: bench halfway", tk.repair(1) != null && !tk.repair(1).done
              && tk.repair(1).daysMending == 1, "");
        tk.tickDay();
        check("tin: mending decides once", tk.repair(1).done
              && (tk.repair(1).mended ^ tk.repair(1).beyond), "");

        TinkerSystem mended = null, beyond = null;
        for(int s = 1; s <= 400 && (mended == null || beyond == null); s++){
            TinkerSystem probe = new TinkerSystem();
            probe.seed = s;
            probe.deliverParts(1);
            probe.mend("kettle");
            probe.tickDay(); probe.tickDay();
            if(probe.repair(1).mended && mended == null) mended = probe;
            if(probe.repair(1).beyond && beyond == null) beyond = probe;
        }
        if(mended != null){
            check("tin: mends counted", mended.mendedCount() == 1 && !mended.repair(1).beyond, "");
        } else {
            check("tin: mends counted", false, "no mend seed in 400");
        }
        if(beyond != null){
            check("tin: hopeless marked honestly", beyond.repair(1).beyond
                  && beyond.mendedCount() == 0, "");
        } else {
            check("tin: hopeless marked honestly", false, "no beyond seed in 400");
        }

        String json = mended.save();
        TinkerSystem tk2 = new TinkerSystem();
        tk2.load(json);
        // saved state: parts 0 (probe paid its only part), nextId 2
        check("tin: save/load, no part magic", tk2.repair(1) != null && tk2.repair(1).mended
              && tk2.parts == 0 && tk2.mend("pan") == null, "");
        tk2.deliverParts(1);
        TinkerSystem.Repair r2 = tk2.mend("pan");
        check("tin: id continues", r2 != null && r2.id == 2 && tk2.parts == 0
              && tk2.mend("extra") == null, "id=" + (r2 == null ? -1 : r2.id));
    }

    // =====================================================================
    static void vats(){
        PapermakerSystem pm = new PapermakerSystem();
        check("pap: rags guards", !pm.deliverRags(0) && !pm.deliverRags(-1)
              && !pm.deliverRags(21), "");
        check("pap: make without rags", pm.make("linen") == null, "");
        check("pap: rags paid up front", pm.deliverRags(6) && pm.make("linen") != null
              && pm.rags == 3, "rags=" + pm.rags);
        check("pap: bad kind refused", pm.make("") == null, "");

        pm.tickDay();
        check("pap: vat halfway", pm.batch(1) != null && !pm.batch(1).done
              && pm.batch(1).daysPulped == 1, "");
        pm.tickDay();
        check("pap: loft decides once", pm.batch(1).done, "");

        PapermakerSystem clean = null, moldy = null;
        for(int s = 1; s <= 400 && (clean == null || moldy == null); s++){
            PapermakerSystem probe = new PapermakerSystem();
            probe.seed = s;
            probe.deliverRags(3);
            probe.make("linen");
            probe.tickDay(); probe.tickDay();
            if(probe.batch(1).moldy == false && probe.quires == 4 && probe.molds == 0
               && clean == null) clean = probe;
            if(probe.batch(1).moldy && probe.quires == 1 && probe.molds == 1
               && moldy == null) moldy = probe;
        }
        if(clean != null){
            check("pap: clean pull of four", clean.quires == 4 && clean.molds == 0, "");
        } else {
            check("pap: clean pull of four", false, "no clean seed in 400");
        }
        if(moldy != null){
            check("pap: mold leaves one quire", moldy.quires == 1 && moldy.molds == 1
                  && moldy.batch(1).moldy, "");
        } else {
            check("pap: mold leaves one quire", false, "no mold seed in 400");
        }

        String json = clean.save();
        PapermakerSystem pm2 = new PapermakerSystem();
        pm2.load(json);
        // saved state: rags 0 (probe paid its only three), quires 4, nextId 2
        check("pap: save/load, no rag magic", pm2.batch(1) != null && pm2.quires == 4
              && pm2.rags == 0 && pm2.make("wool") == null, "");
        pm2.deliverRags(3);
        PapermakerSystem.Batch b2 = pm2.make("wool");
        check("pap: id continues", b2 != null && b2.id == 2 && pm2.rags == 0,
              "id=" + (b2 == null ? -1 : b2.id));
    }

    // =====================================================================
    static void frames(){
        BookbinderSystem bb = new BookbinderSystem();
        check("bkb: quires guards", !bb.deliverQuires(0) && !bb.deliverQuires(-1)
              && !bb.deliverQuires(25), "");
        check("bkb: bind without quires", bb.bind("almanac") == null, "");
        check("bkb: quires paid up front", bb.deliverQuires(6) && bb.bind("almanac") != null
              && bb.quires == 4, "quires=" + bb.quires);
        check("bkb: bad title refused", bb.bind("") == null, "");

        bb.tickDay(); bb.tickDay();
        check("bkb: frame halfway", bb.book(1) != null && !bb.book(1).done
              && bb.book(1).daysBound == 2, "");
        bb.tickDay();
        check("bkb: spine test decides once", bb.book(1).done
              && (bb.book(1).sound ^ bb.book(1).loose), "");

        BookbinderSystem sound = null, loose = null;
        for(int s = 1; s <= 400 && (sound == null || loose == null); s++){
            BookbinderSystem probe = new BookbinderSystem();
            probe.seed = s;
            probe.deliverQuires(2);
            probe.bind("almanac");
            probe.tickDay(); probe.tickDay(); probe.tickDay();
            if(probe.book(1).sound && sound == null) sound = probe;
            if(probe.book(1).loose && loose == null) loose = probe;
        }
        if(sound != null){
            check("bkb: sound counted", sound.soundCount() == 1 && !sound.book(1).loose, "");
        } else {
            check("bkb: sound counted", false, "no sound seed in 400");
        }
        if(loose != null){
            check("bkb: loose flagged honestly", loose.book(1).loose && loose.soundCount() == 0, "");
        } else {
            check("bkb: loose flagged honestly", false, "no loose seed in 400");
        }

        String json = sound.save();
        BookbinderSystem bb2 = new BookbinderSystem();
        bb2.load(json);
        // saved state: quires 0 (probe paid its only two), nextId 2
        check("bkb: save/load, no quire magic", bb2.book(1) != null && bb2.book(1).sound
              && bb2.quires == 0 && bb2.bind("psalter") == null, "");
        bb2.deliverQuires(2);
        BookbinderSystem.Book bk2 = bb2.bind("psalter");
        check("bkb: id continues", bk2 != null && bk2.id == 2 && bb2.quires == 0,
              "id=" + (bk2 == null ? -1 : bk2.id));
    }

    // =====================================================================
    static void osiers(){
        BasketWeaverSystem bw = new BasketWeaverSystem();
        check("bsk: osiers guards", !bw.deliverOsiers(0) && !bw.deliverOsiers(-1)
              && !bw.deliverOsiers(16), "");
        check("bsk: weave without rods", bw.weave("creel") == null, "");
        check("bsk: rods paid up front", bw.deliverOsiers(4) && bw.weave("creel") != null
              && bw.osiers == 2, "osiers=" + bw.osiers);
        check("bsk: bad kind refused", bw.weave("") == null, "");

        bw.tickDay();
        check("bsk: single day craft", bw.basket(1) != null && bw.basket(1).done
              && bw.basket(1).daysWoven == 1 && (bw.basket(1).tight ^ bw.basket(1).gappy), "");

        BasketWeaverSystem tight = null, gappy = null;
        for(int s = 1; s <= 400 && (tight == null || gappy == null); s++){
            BasketWeaverSystem probe = new BasketWeaverSystem();
            probe.seed = s;
            probe.deliverOsiers(2);
            probe.weave("creel");
            probe.tickDay();
            if(probe.basket(1).tight && tight == null) tight = probe;
            if(probe.basket(1).gappy && gappy == null) gappy = probe;
        }
        if(tight != null){
            check("bsk: tight counted", tight.tightCount() == 1 && !tight.basket(1).gappy, "");
        } else {
            check("bsk: tight counted", false, "no tight seed in 400");
        }
        if(gappy != null){
            check("bsk: gappy flagged honestly", gappy.basket(1).gappy && gappy.tightCount() == 0, "");
        } else {
            check("bsk: gappy flagged honestly", false, "no gappy seed in 400");
        }

        String json = tight.save();
        BasketWeaverSystem bw2 = new BasketWeaverSystem();
        bw2.load(json);
        // saved state: osiers 0 (probe paid its only two), nextId 2
        check("bsk: save/load, no rod magic", bw2.basket(1) != null && bw2.basket(1).tight
              && bw2.osiers == 0 && bw2.weave("skip") == null, "");
        bw2.deliverOsiers(2);
        BasketWeaverSystem.Basket b2 = bw2.weave("skip");
        check("bsk: id continues", b2 != null && b2.id == 2 && bw2.osiers == 0,
              "id=" + (b2 == null ? -1 : b2.id));
    }
}
