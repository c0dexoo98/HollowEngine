import com.hollow.game.BalladmakerSystem;
import com.hollow.game.FiddlerSystem;
import com.hollow.game.JugglerSystem;
import com.hollow.game.MinstrelSystem;
import com.hollow.game.TournamentSystem;
import com.hollow.world.BalladHallSystem;
import com.hollow.world.BillPosterSystem;
import com.hollow.world.ChoirSchoolSystem;
import com.hollow.world.RehearsalYardSystem;

/** v20.0.0 regression suite — ballad halls, choir schools, rehearsal
 *  yards, bill walls, minstrels, balladmakers, jugglers, fiddlers,
 *  plus FIX20-001 (TournamentSystem define guard). */
public class VerifyV20 {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        tournamentFix();
        halls();
        choirs();
        yards();
        walls();
        minstrels();
        balladmakers();
        jugglers();
        fiddlers();
        System.out.println(fails == 0 ? "\nALL " + total + " V20 TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " V20 TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void tournamentFix(){
        TournamentSystem ts = new TournamentSystem();
        check("fx: null knight refused", ts.define(null) == null, "");
        check("fx: empty-name knight refused", ts.define(new TournamentSystem.Knight("", 0.5f)) == null, "");
        TournamentSystem.Knight k = ts.define(new TournamentSystem.Knight("Ser Aldric", 0.9f));
        check("fx: valid knight still lands", k != null && ts.knight("Ser Aldric") == k, "");
    }

    // =====================================================================
    static void halls(){
        BalladHallSystem bh = new BalladHallSystem();
        check("hal: invalid build refused", !bh.build("") && bh.build("byram") && !bh.build("byram"), "");
        check("hal: booking guards", !bh.book("dunmor") && bh.book("byram") && bh.book("byram")
              && bh.book("byram") && bh.book("byram") && bh.book("byram") && bh.book("byram")
              && !bh.book("byram"), "");

        // scan finds one full house and one half house
        BalladHallSystem full = null, half = null;
        for(int s = 1; s <= 400 && (full == null || half == null); s++){
            BalladHallSystem probe = new BalladHallSystem();
            probe.seed = s;
            probe.build("byram");
            probe.book("byram");
            probe.tickDay();
            if(probe.hall("byram").gross == 30 && probe.hall("byram").full == 1
               && probe.hall("byram").half == 0 && probe.hall("byram").bookings == 0
               && full == null) full = probe;
            if(probe.hall("byram").gross == 12 && probe.hall("byram").half == 1
               && probe.hall("byram").full == 0 && half == null) half = probe;
        }
        if(full != null){
            check("hal: full house pays thirty", full.hall("byram").gross == 30
                  && full.hall("byram").performed == 1, "");
        } else {
            check("hal: full house pays thirty", false, "no full seed in 400");
        }
        if(half != null){
            check("hal: half house pays twelve", half.hall("byram").gross == 12
                  && half.hall("byram").performed == 1, "");
        } else {
            check("hal: half house pays twelve", false, "no half seed in 400");
        }

        // two booked nights on clean seeds pile up in the gross ledger
        BalladHallSystem two = null;
        for(int s = 1; s <= 400 && two == null; s++){
            BalladHallSystem probe = new BalladHallSystem();
            probe.seed = s;
            probe.build("byram");
            probe.book("byram");
            probe.book("byram");
            probe.tickDay(); probe.tickDay();
            if(probe.hall("byram").gross == 60 && probe.hall("byram").full == 2
               && probe.hall("byram").performed == 2 && probe.hall("byram").bookings == 0) two = probe;
        }
        if(two != null){
            check("hal: two full houses pile up", two.hall("byram").gross == 60
                  && two.hall("byram").full == 2, "");
        } else {
            check("hal: two full houses pile up", false, "no two-full seed in 400");
        }

        String json = full.save();
        BalladHallSystem bh2 = new BalladHallSystem();
        bh2.load(json);
        check("hal: save/load", bh2.hall("byram") != null && bh2.hall("byram").gross == 30
              && bh2.hall("byram").full == 1 && bh2.hall("byram").bookings == 0, "");
    }

    // =====================================================================
    static void choirs(){
        ChoirSchoolSystem cs = new ChoirSchoolSystem();
        check("chr: invalid found refused", !cs.found("") && cs.found("ashvale") && !cs.found("ashvale"), "");
        check("chr: enroll guards", !cs.enroll("dunmor") && cs.enroll("ashvale") && cs.enroll("ashvale")
              && cs.enroll("ashvale") && cs.enroll("ashvale") && cs.enroll("ashvale")
              && cs.enroll("ashvale") && cs.enroll("ashvale") && cs.enroll("ashvale")
              && !cs.enroll("ashvale"), "");

        // scan finds a clean cohort (three voiced after four days) and a croaking day
        ChoirSchoolSystem clean = null, croaky = null;
        for(int s = 1; s <= 400 && (clean == null || croaky == null); s++){
            ChoirSchoolSystem probe = new ChoirSchoolSystem();
            probe.seed = s;
            probe.found("ashvale");
            probe.enroll("ashvale");
            probe.enroll("ashvale");
            probe.enroll("ashvale");
            probe.tickDay(); probe.tickDay(); probe.tickDay(); probe.tickDay();
            if(probe.choir("ashvale").voiced == 3 && probe.choir("ashvale").pupils == 0
               && probe.choir("ashvale").croaks == 0 && probe.choir("ashvale").daysTrained == 0
               && clean == null) clean = probe;
            if(probe.choir("ashvale").croaks == 1 && probe.choir("ashvale").voiced == 2
               && probe.choir("ashvale").daysTrained == 0 && croaky == null) croaky = probe;
        }
        if(clean != null){
            check("chr: four clean days voice the cohort", clean.choir("ashvale").voiced == 3
                  && clean.choir("ashvale").daysTrained == 0, "");
        } else {
            check("chr: four clean days voice the cohort", false, "no clean seed in 400");
        }
        if(croaky != null){
            check("chr: croaks are counted honestly", croaky.choir("ashvale").croaks == 1
                  && croaky.choir("ashvale").voiced == 2, "");
        } else {
            check("chr: croaks are counted honestly", false, "no croak seed in 400");
        }

        // empty school idles: no training day, no roll
        ChoirSchoolSystem idle = new ChoirSchoolSystem();
        idle.found("ashvale");
        idle.tickDay();
        check("chr: empty school idles", idle.choir("ashvale").daysTrained == 0
              && idle.choir("ashvale").voiced == 0, "");

        // halfway banking: two days leave the cohort unfinished
        ChoirSchoolSystem half = null;
        for(int s = 1; s <= 400 && half == null; s++){
            ChoirSchoolSystem probe = new ChoirSchoolSystem();
            probe.seed = s;
            probe.found("ashvale");
            probe.enroll("ashvale");
            probe.tickDay(); probe.tickDay();
            if(probe.choir("ashvale").daysTrained == 2 && probe.choir("ashvale").voiced == 0
               && probe.choir("ashvale").croaks == 0) half = probe;
        }
        if(half != null){
            check("chr: training days bank before voice day", half.choir("ashvale").daysTrained == 2
                  && half.choir("ashvale").voiced == 0, "");
        } else {
            check("chr: training days bank before voice day", false, "no halfway seed in 400");
        }

        String json = clean.save();
        ChoirSchoolSystem cs2 = new ChoirSchoolSystem();
        cs2.load(json);
        check("chr: save/load", cs2.choir("ashvale") != null && cs2.choir("ashvale").voiced == 3
              && cs2.choir("ashvale").pupils == 0, "");
    }

    // =====================================================================
    static void yards(){
        RehearsalYardSystem ry = new RehearsalYardSystem();
        check("yrd: invalid open refused", !ry.open("") && ry.open("byram") && !ry.open("byram"), "");
        check("yrd: reserve guards", !ry.reserve("dunmor") && ry.reserve("byram") && ry.reserve("byram")
              && ry.reserve("byram") && ry.reserve("byram") && !ry.reserve("byram"), "");

        // invariant: every reserved troupe either rehearses or draws a complaint
        RehearsalYardSystem mixed = new RehearsalYardSystem();
        mixed.seed = 7;
        mixed.open("byram");
        mixed.reserve("byram");
        mixed.reserve("byram");
        mixed.tickDay();
        check("yrd: rehearsed + complaints equals troupes", mixed.yard("byram").rehearsed
              + mixed.yard("byram").complaints == 2, "");

        // scan finds an all-quiet day and a complained day
        RehearsalYardSystem quiet = null, noisy = null;
        for(int s = 1; s <= 400 && (quiet == null || noisy == null); s++){
            RehearsalYardSystem probe = new RehearsalYardSystem();
            probe.seed = s;
            probe.open("byram");
            probe.reserve("byram");
            probe.reserve("byram");
            probe.tickDay();
            if(probe.yard("byram").rehearsed == 2 && probe.yard("byram").complaints == 0
               && quiet == null) quiet = probe;
            if(probe.yard("byram").rehearsed == 1 && probe.yard("byram").complaints == 1
               && noisy == null) noisy = probe;
        }
        if(quiet != null){
            check("yrd: quiet day rehearses both", quiet.yard("byram").rehearsed == 2, "");
        } else {
            check("yrd: quiet day rehearses both", false, "no quiet seed in 400");
        }
        if(noisy != null){
            check("yrd: complaint voids one troupe's day", noisy.yard("byram").rehearsed == 1
                  && noisy.yard("byram").complaints == 1, "");
        } else {
            check("yrd: complaint voids one troupe's day", false, "no noisy seed in 400");
        }

        String json = quiet.save();
        RehearsalYardSystem ry2 = new RehearsalYardSystem();
        ry2.load(json);
        check("yrd: save/load", ry2.yard("byram") != null && ry2.yard("byram").rehearsed == 2
              && ry2.yard("byram").troupes == 2, "");
    }

    // =====================================================================
    static void walls(){
        BillPosterSystem bp = new BillPosterSystem();
        check("wal: invalid raise refused", !bp.raise("") && bp.raise("byram") && !bp.raise("byram"), "");
        check("wal: stack guards", !bp.stack("dunmor", 2) && !bp.stack("byram", 0)
              && !bp.stack("byram", 25) && bp.stack("byram", 10), "");

        // scan finds a clean posting day and a shower day
        BillPosterSystem clean = null, shower = null;
        for(int s = 1; s <= 400 && (clean == null || shower == null); s++){
            BillPosterSystem probe = new BillPosterSystem();
            probe.seed = s;
            probe.raise("byram");
            probe.stack("byram", 10);
            probe.tickDay();
            if(probe.wall("byram").posted == 4 && probe.wall("byram").bills == 6
               && probe.wall("byram").faded == 0 && clean == null) clean = probe;
            if(probe.wall("byram").faded == 2 && probe.wall("byram").bills == 8
               && probe.wall("byram").posted == 0 && shower == null) shower = probe;
        }
        if(clean != null){
            check("wal: four bills pasted a clean day", clean.wall("byram").posted == 4
                  && clean.wall("byram").bills == 6, "");
        } else {
            check("wal: four bills pasted a clean day", false, "no clean seed in 400");
        }
        if(shower != null){
            check("wal: shower washes the oldest two", shower.wall("byram").faded == 2
                  && shower.wall("byram").bills == 8 && shower.wall("byram").posted == 0, "");
        } else {
            check("wal: shower washes the oldest two", false, "no shower seed in 400");
        }

        // empty wall idles
        BillPosterSystem idle = new BillPosterSystem();
        idle.raise("byram");
        idle.tickDay();
        check("wal: empty wall idles", idle.wall("byram").posted == 0 && idle.wall("byram").faded == 0, "");

        String json = clean.save();
        BillPosterSystem bp2 = new BillPosterSystem();
        bp2.load(json);
        check("wal: save/load", bp2.wall("byram") != null && bp2.wall("byram").posted == 4
              && bp2.wall("byram").bills == 6, "");
    }

    // =====================================================================
    static void minstrels(){
        MinstrelSystem ms = new MinstrelSystem();
        check("mns: bad song refused", ms.play("") == null, "");
        ms.play("Greensleeves");
        ms.tickDay();
        check("mns: single day booking", ms.booking(1) != null && ms.booking(1).done
              && ms.booking(1).daysWaited == 1, "");

        MinstrelSystem loud = null, quiet = null;
        for(int s = 1; s <= 400 && (loud == null || quiet == null); s++){
            MinstrelSystem probe = new MinstrelSystem();
            probe.seed = s;
            probe.play("Greensleeves");
            probe.tickDay();
            if(probe.silver == 5 && probe.applauseCount() == 1 && loud == null) loud = probe;
            if(probe.silver == 1 && probe.applauseCount() == 0 && quiet == null) quiet = probe;
        }
        if(loud != null){
            check("mns: applause pays five", loud.silver == 5 && loud.booking(1).applause, "");
        } else {
            check("mns: applause pays five", false, "no applause seed in 400");
        }
        if(quiet != null){
            check("mns: silence pays one", quiet.silver == 1 && !quiet.booking(1).applause, "");
        } else {
            check("mns: silence pays one", false, "no silence seed in 400");
        }

        String json = loud.save();
        MinstrelSystem ms2 = new MinstrelSystem();
        ms2.load(json);
        // saved state: one done booking, silver 5, nextId 2 (no paid stock in this craft)
        check("mns: save/load", ms2.booking(1) != null && ms2.booking(1).done && ms2.silver == 5, "");
        MinstrelSystem.Booking b2 = ms2.play("Scarborough Fair");
        check("mns: id continues", b2 != null && b2.id == 2, "id=" + (b2 == null ? -1 : b2.id));
    }

    // =====================================================================
    static void balladmakers(){
        BalladmakerSystem bm = new BalladmakerSystem();
        check("bld: quire guards", !bm.deliverQuires(0) && !bm.deliverQuires(-1)
              && !bm.deliverQuires(13), "");
        check("bld: compose without quires", bm.compose("The Fox") == null, "");
        check("bld: quire paid up front", bm.deliverQuires(2) && bm.compose("The Fox") != null
              && bm.quires == 1, "quires=" + bm.quires);
        check("bld: bad title refused", bm.compose("") == null, "");

        bm.tickDay(); bm.tickDay();
        check("bld: desk halfway", bm.ballad(1) != null && !bm.ballad(1).done
              && bm.ballad(1).daysWritten == 2, "");
        bm.tickDay();
        check("bld: market decides once", bm.ballad(1).done
              && (bm.ballad(1).crowdPleaser ^ bm.ballad(1).flop), "");

        BalladmakerSystem good = null, bad = null;
        for(int s = 1; s <= 400 && (good == null || bad == null); s++){
            BalladmakerSystem probe = new BalladmakerSystem();
            probe.seed = s;
            probe.deliverQuires(1);
            probe.compose("The Fox");
            probe.tickDay(); probe.tickDay(); probe.tickDay();
            if(probe.ballad(1).crowdPleaser && good == null) good = probe;
            if(probe.ballad(1).flop && bad == null) bad = probe;
        }
        if(good != null){
            check("bld: crowd-pleaser counted", good.pleaserCount() == 1
                  && !good.ballad(1).flop, "");
        } else {
            check("bld: crowd-pleaser counted", false, "no pleaser seed in 400");
        }
        if(bad != null){
            check("bld: flop flagged honestly", bad.ballad(1).flop && bad.pleaserCount() == 0, "");
        } else {
            check("bld: flop flagged honestly", false, "no flop seed in 400");
        }

        String json = good.save();
        BalladmakerSystem bm2 = new BalladmakerSystem();
        bm2.load(json);
        // saved state: quires 0 (probe paid its only quire), nextId 2
        check("bld: save/load, no quire magic", bm2.ballad(1) != null && bm2.ballad(1).crowdPleaser
              && bm2.quires == 0 && bm2.compose("The Fox") == null, "");
        bm2.deliverQuires(1);
        BalladmakerSystem.Ballad b2 = bm2.compose("The Fox");
        check("bld: id continues", b2 != null && b2.id == 2 && bm2.quires == 0,
              "id=" + (b2 == null ? -1 : b2.id));
    }

    // =====================================================================
    static void jugglers(){
        JugglerSystem js = new JugglerSystem();
        check("jug: prop guards", js.perform(2) == null && js.perform(8) == null
              && js.perform(5) != null, "");
        js.tickDay();
        check("jug: single day routine", js.routine(1) != null && js.routine(1).done
              && (js.routine(1).clean ^ !js.routine(1).clean), "");

        JugglerSystem clean = null, clumsy = null;
        for(int s = 1; s <= 400 && (clean == null || clumsy == null); s++){
            JugglerSystem probe = new JugglerSystem();
            probe.seed = s;
            probe.perform(5);
            probe.tickDay();
            if(probe.routine(1).clean && probe.cleanRuns == 1 && clean == null) clean = probe;
            if(!probe.routine(1).clean && probe.drops == 1 && clumsy == null) clumsy = probe;
        }
        if(clean != null){
            check("jug: clean routine lands all", clean.cleanRuns == 1 && clean.drops == 0, "");
        } else {
            check("jug: clean routine lands all", false, "no clean seed in 400");
        }
        if(clumsy != null){
            check("jug: drop counted honestly", clumsy.drops == 1 && clumsy.cleanRuns == 0
                  && !clumsy.routine(1).clean, "");
        } else {
            check("jug: drop counted honestly", false, "no drop seed in 400");
        }

        String json = clean.save();
        JugglerSystem js2 = new JugglerSystem();
        js2.load(json);
        // saved state: one done routine, cleanRuns 1, nextId 2
        check("jug: save/load", js2.routine(1) != null && js2.routine(1).done && js2.cleanRuns == 1, "");
        JugglerSystem.Routine r2 = js2.perform(3);
        check("jug: id continues", r2 != null && r2.id == 2, "id=" + (r2 == null ? -1 : r2.id));
    }

    // =====================================================================
    static void fiddlers(){
        FiddlerSystem fs = new FiddlerSystem();
        check("fdd: bad tune refused", fs.bookNight("") == null, "");
        fs.bookNight("Soldier's Joy");
        fs.tickDay();
        check("fdd: single day night", fs.night(1) != null && fs.night(1).done
              && fs.night(1).daysWaited == 1, "");

        FiddlerSystem full = null, snapped = null;
        for(int s = 1; s <= 400 && (full == null || snapped == null); s++){
            FiddlerSystem probe = new FiddlerSystem();
            probe.seed = s;
            probe.bookNight("Soldier's Joy");
            probe.tickDay();
            if(probe.dancers == 8 && probe.snaps == 0 && full == null) full = probe;
            if(probe.dancers == 2 && probe.snaps == 1 && snapped == null) snapped = probe;
        }
        if(full != null){
            check("fdd: full floor counts eight", full.dancers == 8 && !full.night(1).snapped, "");
        } else {
            check("fdd: full floor counts eight", false, "no full seed in 400");
        }
        if(snapped != null){
            check("fdd: snap leaves two dancers", snapped.dancers == 2 && snapped.snaps == 1
                  && snapped.night(1).snapped, "");
        } else {
            check("fdd: snap leaves two dancers", false, "no snap seed in 400");
        }

        String json = full.save();
        FiddlerSystem fs2 = new FiddlerSystem();
        fs2.load(json);
        // saved state: one done night, dancers 8, nextId 2
        check("fdd: save/load", fs2.night(1) != null && fs2.night(1).done && fs2.dancers == 8, "");
        FiddlerSystem.Night n2 = fs2.bookNight("Gregg's Pipes");
        check("fdd: id continues", n2 != null && n2.id == 2, "id=" + (n2 == null ? -1 : n2.id));
    }
}
