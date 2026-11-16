import com.hollow.game.BellfoundrySystem;
import com.hollow.game.KeymakerSystem;
import com.hollow.game.PerfumerSystem;
import com.hollow.game.TailorSystem;
import com.hollow.game.ToymanSystem;
import com.hollow.world.BuoySystem;
import com.hollow.world.ChimneySweepSystem;
import com.hollow.world.TollgateSystem;

/** v15.0.0 regression suite — tollgates, chimneys, buoys, cloth,
 *  bells, essences, keys and fair-day toys. */
public class VerifyV15 {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        gates();
        chimneys();
        moorings();
        stitches();
        pours();
        scents();
        blanks();
        stalls();
        System.out.println(fails == 0 ? "\nALL " + total + " V15 TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " V15 TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void gates(){
        TollgateSystem ts = new TollgateSystem();
        check("tgl: invalid erect refused", !ts.erect("") && ts.erect("oak") && !ts.erect("oak"), "");
        check("tgl: toll guards", !ts.setToll("iron", 2) && !ts.setToll("oak", 0)
              && !ts.setToll("oak", 6) && ts.setToll("oak", 2), "");

        // one traveler, one roll: pays (toll 2) or slips past the bar
        TollgateSystem paid = null, evaded = null;
        for(int s = 1; s <= 400 && (paid == null || evaded == null); s++){
            TollgateSystem probe = new TollgateSystem();
            probe.seed = s;
            probe.erect("oak");
            probe.setToll("oak", 2);
            probe.tickDay();
            if(probe.gate("oak").passages == 1){ if(paid == null) paid = probe; }
            else if(probe.gate("oak").evasions == 1 && evaded == null) evaded = probe;
        }
        if(paid != null){
            check("tgl: traveler pays", paid.gate("oak").passages == 1
                  && paid.gate("oak").revenue == 2 && paid.gate("oak").evasions == 0, "");
        } else {
            check("tgl: traveler pays", false, "no pay seed in 400");
        }
        if(evaded != null){
            check("tgl: slip past the bar", evaded.gate("oak").evasions == 1
                  && evaded.gate("oak").passages == 0 && evaded.gate("oak").revenue == 0, "");
        } else {
            check("tgl: slip past the bar", false, "no evade seed in 400");
        }

        String json = paid.save();
        TollgateSystem ts2 = new TollgateSystem();
        ts2.load(json);
        check("tgl: save/load", ts2.gate("oak") != null && ts2.gate("oak").tollPennies == 2
              && ts2.gate("oak").passages == 1 && ts2.gate("oak").revenue == 2, "");
    }

    // =====================================================================
    static void chimneys(){
        ChimneySweepSystem cs = new ChimneySweepSystem();
        check("chm: invalid register refused", !cs.register("") && cs.register("oak")
              && !cs.register("oak"), "");
        check("chm: crew guards", !cs.assignCrews("iron", 1) && !cs.assignCrews("oak", -1)
              && !cs.assignCrews("oak", 4) && cs.assignCrews("oak", 1), "");

        // one crew holds: soot never rises past zero
        ChimneySweepSystem held = new ChimneySweepSystem();
        held.register("oak");
        held.assignCrews("oak", 1);
        held.tickDay(); held.tickDay(); held.tickDay();
        check("chm: one crew holds", held.hearth("oak").soot == 0
              && held.hearth("oak").crewDays == 3, "soot=" + held.hearth("oak").soot);

        // an untended hearth chokes at ten layers: no-fire seed and fire seed
        ChimneySweepSystem choked = null, alight = null;
        for(int s = 1; s <= 400 && (choked == null || alight == null); s++){
            ChimneySweepSystem probe = new ChimneySweepSystem();
            probe.seed = s;
            probe.register("oak");
            for(int t = 0; t < 5; t++) probe.tickDay();
            if(probe.hearth("oak").fires == 0 && probe.hearth("oak").soot == 10 && choked == null) choked = probe;
            if(probe.hearth("oak").fires == 1 && probe.hearth("oak").soot == 0 && alight == null) alight = probe;
        }
        if(choked != null){
            check("chm: flue chokes at ten", choked.hearth("oak").soot == 10
                  && choked.hearth("oak").fires == 0, "soot=" + choked.hearth("oak").soot);
        } else {
            check("chm: flue chokes at ten", false, "no choke seed in 400");
        }
        if(alight != null){
            check("chm: fire burns it clean", alight.hearth("oak").fires == 1
                  && alight.hearth("oak").soot == 0, "");
        } else {
            check("chm: fire burns it clean", false, "no fire seed in 400");
        }

        String json = choked.save();
        ChimneySweepSystem cs2 = new ChimneySweepSystem();
        cs2.load(json);
        check("chm: save/load", cs2.hearth("oak") != null && cs2.hearth("oak").soot == 10
              && cs2.hearth("oak").crews == 0, "");
    }

    // =====================================================================
    static void moorings(){
        BuoySystem bs = new BuoySystem();
        check("boy: invalid moor refused", !bs.moor("") && bs.moor("oak") && !bs.moor("oak"), "");
        check("boy: reposition guards", !bs.reposition("iron"), "");

        // squall seed: one tick drags four fathoms
        BuoySystem squall = null;
        for(int s = 1; s <= 400 && squall == null; s++){
            BuoySystem probe = new BuoySystem();
            probe.seed = s;
            probe.moor("oak");
            probe.tickDay();
            if(probe.buoy("oak").squalls == 1 && probe.buoy("oak").drift == 4) squall = probe;
        }
        if(squall != null){
            check("boy: squall drags three more", squall.buoy("oak").drift == 4
                  && squall.buoy("oak").squalls == 1, "drift=" + squall.buoy("oak").drift);
        } else {
            check("boy: squall drags three more", false, "no squall seed in 400");
        }

        // eight clean days drift it off station
        BuoySystem adrift = null;
        for(int s = 1; s <= 400 && adrift == null; s++){
            BuoySystem probe = new BuoySystem();
            probe.seed = s;
            probe.moor("oak");
            for(int t = 0; t < 8; t++) probe.tickDay();
            if(probe.buoy("oak").squalls == 0 && probe.buoy("oak").drift == 8
               && !probe.buoy("oak").onStation()) adrift = probe;
        }
        if(adrift != null){
            check("boy: eight fathoms off station", adrift.buoy("oak").drift == 8
                  && !adrift.buoy("oak").onStation(), "");
        } else {
            check("boy: eight fathoms off station", false, "no calm seed in 400");
        }

        if(adrift != null){
            adrift.reposition("oak");
            check("boy: crew resets the mooring", adrift.buoy("oak").drift == 0
                  && adrift.buoy("oak").repositions == 1 && adrift.buoy("oak").onStation(), "");
        } else {
            check("boy: crew resets the mooring", false, "no calm seed");
        }

        String json = adrift == null ? "" : adrift.save();
        BuoySystem bs2 = new BuoySystem();
        bs2.load(json);
        check("boy: save/load", bs2.buoy("oak") != null && bs2.buoy("oak").drift == 0
              && bs2.buoy("oak").repositions == 1, "");
    }

    // =====================================================================
    static void stitches(){
        TailorSystem tl = new TailorSystem();
        check("stc: bad cloth refused", !tl.deliverCloth(0) && !tl.deliverCloth(-1), "");
        check("stc: place without cloth", tl.place("coat", 2) == null, "");
        check("stc: cloth paid up front", tl.deliverCloth(4) && tl.place("coat", 2) != null
              && tl.cloth == 2, "cloth=" + tl.cloth);
        check("stc: bad garment refused", tl.place("", 2) == null && tl.place("cape", 0) == null, "");

        tl.tickDay();
        check("stc: needlework halfway", tl.order(1) != null && !tl.order(1).done
              && tl.order(1).daysWorked == 1, "");
        tl.tickDay();
        check("stc: fitting decides once", tl.order(1).done
              && (tl.order(1).fitted ^ tl.order(1).botched), "");

        // scan a fitted seed and a botched seed
        TailorSystem good = null, bad = null;
        for(int s = 1; s <= 400 && (good == null || bad == null); s++){
            TailorSystem probe = new TailorSystem();
            probe.seed = s;
            probe.deliverCloth(2);
            probe.place("coat", 1);
            probe.tickDay();
            if(probe.order(1).fitted){ if(good == null) good = probe; }
            else if(probe.order(1).botched && bad == null) bad = probe;
        }
        if(good != null){
            check("stc: fitted count", good.fittedCount() == 1 && !good.order(1).botched, "");
        } else {
            check("stc: fitted count", false, "no fitted seed in 400");
        }
        if(bad != null){
            check("stc: botched cloth lost", bad.order(1).botched && bad.fittedCount() == 0, "");
        } else {
            check("stc: botched cloth lost", false, "no botched seed in 400");
        }

        String json = good.save();
        TailorSystem tl2 = new TailorSystem();
        tl2.load(json);
        // saved state: cloth 0 (one order paid from 2), nextId 2 (one order)
        tl2.deliverCloth(2);
        TailorSystem.Order o2 = tl2.place("cape", 1);
        check("stc: save/load, id continues", o2 != null && o2.id == 2 && tl2.cloth == 0,
              "id=" + (o2 == null ? -1 : o2.id) + " cloth=" + tl2.cloth);
    }

    // =====================================================================
    static void pours(){
        BellfoundrySystem bf = new BellfoundrySystem();
        check("bll: bad bronze refused", !bf.deliverBronze(0) && !bf.deliverBronze(-1), "");
        check("bll: cast guards", bf.cast(0) == null && bf.cast(5) == null && bf.cast(2) == null, "");
        check("bll: bronze paid by weight", bf.deliverBronze(6) && bf.cast(3) != null
              && bf.bronze == 3 && bf.bell(1).mouldDays == 3, "bronze=" + bf.bronze);

        bf.tickDay(); bf.tickDay();
        check("bll: mould cures slowly", !bf.bell(1).done && bf.bell(1).daysInMould == 2, "");
        bf.tickDay();
        check("bll: tone decided once", bf.bell(1).done, "");

        // scan a true-tone seed and a cracked seed
        BellfoundrySystem tone = null, crack = null;
        for(int s = 1; s <= 400 && (tone == null || crack == null); s++){
            BellfoundrySystem probe = new BellfoundrySystem();
            probe.seed = s;
            probe.deliverBronze(2);
            probe.cast(1);
            probe.tickDay();
            if(probe.bell(1).cracked){ if(crack == null) crack = probe; }
            else if(tone == null) tone = probe;
        }
        if(tone != null){
            check("bll: true tone rings", tone.trueBellCount() == 1 && tone.ring(1)
                  && tone.bell(1).rings == 1, "");
        } else {
            check("bll: true tone rings", false, "no tone seed in 400");
        }
        if(crack != null){
            check("bll: cracked bell mute", crack.bell(1).cracked && crack.trueBellCount() == 0
                  && !crack.ring(1) && crack.bell(1).rings == 0, "");
        } else {
            check("bll: cracked bell mute", false, "no crack seed in 400");
        }

        String json = tone.save();
        BellfoundrySystem bf2 = new BellfoundrySystem();
        bf2.load(json);
        // saved state: bronze 1 (one cast of 2 paid from 3), nextId 2 (one bell)
        BellfoundrySystem.Bell b2 = bf2.cast(1);
        check("bll: save/load, id continues", b2 != null && b2.id == 2 && bf2.bronze == 0
              && bf2.bell(1) != null && bf2.bell(1).rings == 1,
              "id=" + (b2 == null ? -1 : b2.id) + " bronze=" + bf2.bronze);
    }

    // =====================================================================
    static void scents(){
        PerfumerSystem pf = new PerfumerSystem();
        check("scn: bad petals refused", !pf.deliverPetals(0) && !pf.deliverPetals(-1), "");
        check("scn: distill without petals", pf.distill("rose") == null, "");
        check("scn: petals paid up front", pf.deliverPetals(6) && pf.distill("rose") != null
              && pf.petals == 3, "petals=" + pf.petals);
        check("scn: bad scent refused", pf.distill("") == null, "");

        pf.tickDay();
        check("scn: still runs slowly", pf.essence(1) != null && !pf.essence(1).ready
              && pf.essence(1).daysInStill == 1, "");
        pf.tickDay();
        check("scn: decant decides once", pf.essence(1).ready && pf.essence(1).daysInStill == 2
              && pf.batchCount() == 1, "");

        // shelf life is exact: nine days fine, ten faded
        for(int t = 0; t < 9; t++) pf.tickDay();
        check("scn: nine shelf days fine", !pf.essence(1).faded && pf.essence(1).daysOnShelf == 9, "");
        pf.tickDay();
        check("scn: ten days fades it", pf.essence(1).faded && pf.essence(1).daysOnShelf == 10, "");

        // scan a true scent and an off batch
        PerfumerSystem trueScent = null, off = null;
        for(int s = 1; s <= 400 && (trueScent == null || off == null); s++){
            PerfumerSystem probe = new PerfumerSystem();
            probe.seed = s;
            probe.deliverPetals(3);
            probe.distill("rose");
            probe.tickDay(); probe.tickDay();
            if(probe.essence(1).offBatch){ if(off == null) off = probe; }
            else if(trueScent == null) trueScent = probe;
        }
        if(trueScent != null){
            check("scn: true scent bottled", !trueScent.essence(1).offBatch
                  && trueScent.batchCount() == 1, "");
        } else {
            check("scn: true scent bottled", false, "no true seed in 400");
        }
        if(off != null){
            check("scn: off batch marked honestly", off.essence(1).offBatch
                  && off.batchCount() == 1, "");
        } else {
            check("scn: off batch marked honestly", false, "no off seed in 400");
        }

        String json = trueScent.save();
        PerfumerSystem pf2 = new PerfumerSystem();
        pf2.load(json);
        // saved state: petals 0, nextId 2 (one essence)
        check("scn: save/load, no petal magic", pf2.essence(1) != null && pf2.essence(1).ready
              && pf2.petals == 0 && pf2.distill("jasmine") == null, "");
        pf2.deliverPetals(3);
        PerfumerSystem.Essence e2 = pf2.distill("jasmine");
        check("scn: id continues", e2 != null && e2.id == 2 && pf2.petals == 0,
              "id=" + (e2 == null ? -1 : e2.id));
    }

    // =====================================================================
    static void blanks(){
        KeymakerSystem km = new KeymakerSystem();
        check("key: bad blanks refused", !km.deliverBlanks(0) && !km.deliverBlanks(-1), "");
        check("key: cut without blanks", km.cut("gate") == null, "");
        check("key: blank paid", km.deliverBlanks(3) && km.cut("gate") != null && km.blanks == 2, "");
        check("key: bad lock refused", km.cut("") == null, "");
        check("key: copy guards", !km.copy(9) && km.copy(1), "");

        // one roll per duplicate: good copy or botched blank
        KeymakerSystem good = null, botch = null;
        for(int s = 1; s <= 400 && (good == null || botch == null); s++){
            KeymakerSystem probe = new KeymakerSystem();
            probe.seed = s;
            probe.deliverBlanks(2);
            probe.cut("gate");
            probe.copy(1);
            if(probe.key(1).copies == 1 && probe.blanks == 0 && good == null) good = probe;
            if(probe.key(1).botchedCopies == 1 && probe.blanks == 0 && botch == null) botch = probe;
        }
        if(good != null){
            check("key: working duplicate", good.key(1).copies == 1
                  && good.key(1).botchedCopies == 0, "");
        } else {
            check("key: working duplicate", false, "no good seed in 400");
        }
        if(botch != null){
            check("key: botched blank wasted", botch.key(1).botchedCopies == 1
                  && botch.key(1).copies == 0, "");
        } else {
            check("key: botched blank wasted", false, "no botch seed in 400");
        }
        if(good != null){
            check("key: matches its lock", good.matches(1, "gate") && !good.matches(1, "keep")
                  && !good.matches(9, "gate"), "");
        } else {
            check("key: matches its lock", false, "no good seed");
        }

        String json = good.save();
        KeymakerSystem km2 = new KeymakerSystem();
        km2.load(json);
        // saved state: blanks 0 (cut + copy from 2), nextId 2 (one key)
        check("key: save/load, no blank magic", km2.key(1) != null && km2.key(1).copies == 1
              && km2.blanks == 0 && km2.cut("keep") == null, "");
    }

    // =====================================================================
    static void stalls(){
        ToymanSystem ty = new ToymanSystem();
        check("toy: bad wood refused", !ty.deliverWood(0) && !ty.deliverWood(-1), "");
        check("toy: carve without wood", ty.carve("horse") == null, "");
        check("toy: scrap paid", ty.deliverWood(3) && ty.carve("horse") != null && ty.wood == 2, "");
        check("toy: bad name refused", ty.carve("") == null, "");
        check("toy: paint guards", !ty.paint(9) && ty.paint(1) && !ty.paint(1), "");

        // one crowd roll per painted, unsold toy: sale seed and no-sale seed
        ToymanSystem sale = null, noSale = null;
        for(int s = 1; s <= 400 && (sale == null || noSale == null); s++){
            ToymanSystem probe = new ToymanSystem();
            probe.seed = s;
            probe.deliverWood(1);
            probe.carve("horse");
            probe.paint(1);
            int sold = probe.fairDay();
            if(sold == 1 && probe.takings == 2 && sale == null) sale = probe;
            if(sold == 0 && probe.takings == 0 && !probe.toy(1).sold && noSale == null) noSale = probe;
        }
        if(sale != null){
            check("toy: fair day sale", sale.toy(1).sold && sale.takings == 2, "");
        } else {
            check("toy: fair day sale", false, "no sale seed in 400");
        }
        if(noSale != null){
            check("toy: crowd passes by", !noSale.toy(1).sold && noSale.takings == 0
                  && noSale.stockCount() == 1, "");
        } else {
            check("toy: crowd passes by", false, "no no-sale seed in 400");
        }

        // unpainted stock is never rolled
        ToymanSystem plain = new ToymanSystem();
        plain.deliverWood(1);
        plain.carve("doll");
        plain.fairDay();
        check("toy: unpainted never sold", !plain.toy(1).sold && plain.takings == 0, "");

        String json = sale.save();
        ToymanSystem ty2 = new ToymanSystem();
        ty2.load(json);
        // saved state: wood 0, nextId 2 (one toy)
        check("toy: save/load, no wood magic", ty2.toy(1) != null && ty2.toy(1).sold
              && ty2.takings == 2 && ty2.wood == 0 && ty2.carve("kite") == null, "");
        ty2.deliverWood(1);
        ToymanSystem.Toy k = ty2.carve("kite");
        check("toy: id continues", k != null && k.id == 2 && ty2.wood == 0,
              "id=" + (k == null ? -1 : k.id));
    }
}
