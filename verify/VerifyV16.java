import com.hollow.game.CooperSystem;
import com.hollow.game.LapidarySystem;
import com.hollow.game.RopemakerSystem;
import com.hollow.game.WheelwrightSystem;
import com.hollow.world.HarbourmasterSystem;
import com.hollow.world.LampLighterSystem;
import com.hollow.world.NightWatchSystem;
import com.hollow.world.SaltworksSystem;

/** v16.0.0 regression suite — lamp rounds, night rounds, quay tides,
 *  salt pans, wheels, rope walks, gem wheels and raised casks. */
public class VerifyV16 {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        lamps();
        rounds();
        tides();
        pans();
        bench();
        walk();
        cleave();
        hoops();
        System.out.println(fails == 0 ? "\nALL " + total + " V16 TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " V16 TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void lamps(){
        LampLighterSystem ls = new LampLighterSystem();
        check("lmp: invalid open refused", !ls.open("") && ls.open("oak") && !ls.open("oak"), "");
        check("lmp: hang guards", !ls.hang("iron", 2) && !ls.hang("oak", -1) && !ls.hang("oak", 7)
              && ls.hang("oak", 4), "");
        check("lmp: oil guards", !ls.deliverOil("iron", 2) && !ls.deliverOil("oak", 0)
              && !ls.deliverOil("oak", 21) && ls.deliverOil("oak", 5), "");

        // dusk rounds: light what the oil feeds; scan guarantees TWO clean nights
        LampLighterSystem clean = null, gutter = null;
        for(int s = 1; s <= 400 && (clean == null || gutter == null); s++){
            LampLighterSystem probe = new LampLighterSystem();
            probe.seed = s;
            probe.open("oak");
            probe.hang("oak", 4);
            probe.deliverOil("oak", 5);
            probe.tickDay();
            if(probe.round("oak").gutters == 0 && probe.round("oak").litNights == 4
               && probe.round("oak").darkNights == 0 && probe.round("oak").oil == 1){
                probe.tickDay();                              // second dusk: 1 flask left
                if(probe.round("oak").gutters == 0 && probe.round("oak").litNights == 5
                   && probe.round("oak").darkNights == 3 && probe.round("oak").oil == 0
                   && clean == null) clean = probe;
            }
            if(probe.round("oak").gutters == 1 && probe.round("oak").litNights == 3
               && probe.round("oak").darkNights == 1 && probe.round("oak").oil == 2
               && gutter == null) gutter = probe;
        }
        if(clean != null){
            check("lmp: full round lit", clean.round("oak").litNights == 5
                  && clean.round("oak").oil == 0 && clean.round("oak").darkNights == 3
                  && clean.round("oak").gutters == 0, "");
        } else {
            check("lmp: full round lit", false, "no clean seed in 400");
        }
        if(gutter != null){
            check("lmp: gutter burns no oil", gutter.round("oak").gutters == 1
                  && gutter.round("oak").litNights == 3 && gutter.round("oak").oil == 2
                  && gutter.round("oak").darkNights == 1, "");
        } else {
            check("lmp: gutter burns no oil", false, "no gutter seed in 400");
        }

        LampLighterSystem empty = new LampLighterSystem();
        empty.open("oak");
        empty.tickDay();
        check("lmp: lampless round stays dark", empty.round("oak").darkNights == 0
              && empty.round("oak").litNights == 0, "");

        String json = clean.save();
        LampLighterSystem ls2 = new LampLighterSystem();
        ls2.load(json);
        check("lmp: save/load", ls2.round("oak") != null && ls2.round("oak").lamps == 4
              && ls2.round("oak").oil == 0 && ls2.round("oak").darkNights == 3, "");
    }

    // =====================================================================
    static void rounds(){
        NightWatchSystem ns = new NightWatchSystem();
        check("ntw: invalid enlist refused", !ns.enlist("") && ns.enlist("oak") && !ns.enlist("oak"), "");
        check("ntw: crew guards", !ns.assignWatchmen("iron", 1) && !ns.assignWatchmen("oak", -1)
              && !ns.assignWatchmen("oak", 5) && ns.assignWatchmen("oak", 2), "");

        // rounds are deterministic; trouble rolls once per night
        NightWatchSystem quiet = null, trouble = null;
        for(int s = 1; s <= 400 && (quiet == null || trouble == null); s++){
            NightWatchSystem probe = new NightWatchSystem();
            probe.seed = s;
            probe.enlist("oak");
            probe.assignWatchmen("oak", 2);
            probe.tickDay();
            if(probe.watch("oak").quietNights == 1 && quiet == null) quiet = probe;
            if(probe.watch("oak").incidents == 1 && trouble == null) trouble = probe;
        }
        if(quiet != null){
            check("ntw: quiet night counted", quiet.watch("oak").roundsDone == 2
                  && quiet.watch("oak").quietNights == 1 && quiet.watch("oak").incidents == 0, "");
        } else {
            check("ntw: quiet night counted", false, "no quiet seed in 400");
        }
        if(trouble != null){
            check("ntw: trouble on the rounds", trouble.watch("oak").roundsDone == 2
                  && trouble.watch("oak").incidents == 1 && trouble.watch("oak").quietNights == 0, "");
        } else {
            check("ntw: trouble on the rounds", false, "no trouble seed in 400");
        }

        if(quiet != null){
            quiet.tickDay(); quiet.tickDay();
            check("ntw: rounds pile up", quiet.watch("oak").roundsDone == 6
                  && quiet.watch("oak").quietNights == 3, "");
        } else {
            check("ntw: rounds pile up", false, "no quiet seed");
        }

        NightWatchSystem idle = new NightWatchSystem();
        idle.enlist("oak");
        idle.tickDay();
        check("ntw: unstaffed watch walks nothing", idle.watch("oak").roundsDone == 0, "");

        String json = quiet.save();
        NightWatchSystem ns2 = new NightWatchSystem();
        ns2.load(json);
        check("ntw: save/load", ns2.watch("oak") != null && ns2.watch("oak").watchmen == 2
              && ns2.watch("oak").roundsDone == 6, "");
    }

    // =====================================================================
    static void tides(){
        HarbourmasterSystem hs = new HarbourmasterSystem();
        check("hbm: invalid charter refused", !hs.charter("") && hs.charter("oak") && !hs.charter("oak"), "");
        check("hbm: berth guards", !hs.setBerths("iron", 2) && !hs.setBerths("oak", 0)
              && !hs.setBerths("oak", 6) && hs.setBerths("oak", 1), "");

        // one roll per port: quarantine / berth / turned away / empty water
        HarbourmasterSystem sick = null, berth = null, turned = null;
        for(int s = 1; s <= 400 && (sick == null || berth == null || turned == null); s++){
            HarbourmasterSystem probe = new HarbourmasterSystem();
            probe.seed = s;
            probe.charter("oak");
            probe.setBerths("oak", 1);
            probe.tickDay();
            if(probe.port("oak").quarantined == 1 && probe.port("oak").occupied == 0 && sick == null) sick = probe;
            if(probe.port("oak").occupied == 1 && probe.port("oak").arrivals == 1 && berth == null) berth = probe;
            // two calling ships, one berth: second is turned away
            HarbourmasterSystem two = new HarbourmasterSystem();
            two.seed = s;
            two.charter("oak");
            two.setBerths("oak", 1);
            two.tickDay(); two.tickDay();
            if(two.port("oak").arrivals == 2 && two.port("oak").occupied == 1
               && two.port("oak").turnedAway == 1 && two.port("oak").quarantined == 0
               && turned == null) turned = two;
        }
        if(sick != null){
            check("hbm: sick ship held at the roads", sick.port("oak").quarantined == 1
                  && sick.port("oak").arrivals == 1 && sick.port("oak").occupied == 0, "");
        } else {
            check("hbm: sick ship held at the roads", false, "no quarantine seed in 400");
        }
        if(berth != null){
            check("hbm: calling ship takes the berth", berth.port("oak").occupied == 1
                  && berth.port("oak").arrivals == 1, "");
        } else {
            check("hbm: calling ship takes the berth", false, "no berth seed in 400");
        }
        if(turned != null){
            check("hbm: no berth, turned away", turned.port("oak").turnedAway == 1
                  && turned.port("oak").occupied == 1 && turned.port("oak").arrivals == 2, "");
        } else {
            check("hbm: no berth, turned away", false, "no two-ship seed in 400");
        }

        if(berth != null){
            berth.sail("oak");
            check("hbm: captain sails", berth.port("oak").occupied == 0 && !berth.sail("oak"), "");
        } else {
            check("hbm: captain sails", false, "no berth seed");
        }

        String json = berth.save();
        HarbourmasterSystem hs2 = new HarbourmasterSystem();
        hs2.load(json);
        check("hbm: save/load", hs2.port("oak") != null && hs2.port("oak").berths == 1
              && hs2.port("oak").occupied == 0 && hs2.port("oak").arrivals == 1, "");
    }

    // =====================================================================
    static void pans(){
        SaltworksSystem ss = new SaltworksSystem();
        check("slt: invalid dig refused", !ss.dig("") && ss.dig("oak") && !ss.dig("oak"), "");
        check("slt: pump guards", !ss.pump("iron", 2) && !ss.pump("oak", 0)
              && !ss.pump("oak", 31) && ss.pump("oak", 11), "");

        // sun boils two measures a day; scan guarantees SIX rain-free days
        SaltworksSystem sunny = null, rainy = null;
        for(int s = 1; s <= 400 && (sunny == null || rainy == null); s++){
            SaltworksSystem probe = new SaltworksSystem();
            probe.seed = s;
            probe.dig("oak");
            probe.pump("oak", 11);
            for(int t = 0; t < 6; t++) probe.tickDay();
            if(probe.works("oak").rainDelays == 0 && probe.works("oak").brine == 0
               && probe.works("oak").salt == 5 && probe.works("oak").harvested == 5
               && sunny == null) sunny = probe;
            SaltworksSystem one = new SaltworksSystem();
            one.seed = s;
            one.dig("oak");
            one.pump("oak", 11);
            one.tickDay();
            if(one.works("oak").brine == 11 && one.works("oak").rainDelays == 1
               && one.works("oak").salt == 0 && rainy == null) rainy = one;
        }
        if(sunny != null){
            check("slt: two measures a day", sunny.works("oak").salt == 5
                  && sunny.works("oak").brine == 0 && sunny.works("oak").harvested == 5, "");
            check("slt: odd leftover waits", sunny.works("oak").rainDelays == 0, "");
        } else {
            check("slt: two measures a day", false, "no sunny seed in 400");
            check("slt: odd leftover waits", false, "no sunny seed");
        }
        if(rainy != null){
            check("slt: rain delays the pans", rainy.works("oak").rainDelays == 1
                  && rainy.works("oak").brine == 11 && rainy.works("oak").salt == 0, "");
        } else {
            check("slt: rain delays the pans", false, "no rain seed in 400");
        }

        String json = sunny.save();
        SaltworksSystem ss2 = new SaltworksSystem();
        ss2.load(json);
        check("slt: save/load", ss2.works("oak") != null && ss2.works("oak").brine == 0
              && ss2.works("oak").salt == 5 && ss2.works("oak").harvested == 5, "");
    }

    // =====================================================================
    static void bench(){
        WheelwrightSystem ww = new WheelwrightSystem();
        check("whl: bad spokes refused", !ww.deliverSpokes(0) && !ww.deliverSpokes(-1), "");
        check("whl: build without spokes", ww.build("cart") == null, "");
        check("whl: spokes paid up front", ww.deliverSpokes(8) && ww.build("cart") != null
              && ww.spokes == 4, "spokes=" + ww.spokes);
        check("whl: bad kind refused", ww.build("") == null, "");

        ww.tickDay();
        check("whl: bench halfway", ww.wheel(1) != null && !ww.wheel(1).done
              && ww.wheel(1).daysOnBench == 1, "");
        ww.tickDay();
        check("whl: road test decides once", ww.wheel(1).done
              && (ww.wheel(1).trueRound ^ ww.wheel(1).wobbly), "");

        WheelwrightSystem round = null, wobbly = null;
        for(int s = 1; s <= 400 && (round == null || wobbly == null); s++){
            WheelwrightSystem probe = new WheelwrightSystem();
            probe.seed = s;
            probe.deliverSpokes(4);
            probe.build("cart");
            probe.tickDay(); probe.tickDay();
            if(probe.wheel(1).trueRound && round == null) round = probe;
            if(probe.wheel(1).wobbly && wobbly == null) wobbly = probe;
        }
        if(round != null){
            check("whl: true round counted", round.trueCount() == 1 && !round.wheel(1).wobbly, "");
        } else {
            check("whl: true round counted", false, "no true seed in 400");
        }
        if(wobbly != null){
            check("whl: wobbly sold as seconds", wobbly.wheel(1).wobbly && wobbly.trueCount() == 0, "");
        } else {
            check("whl: wobbly sold as seconds", false, "no wobbly seed in 400");
        }

        String json = round.save();
        WheelwrightSystem ww2 = new WheelwrightSystem();
        ww2.load(json);
        // saved state: spokes 0 (one build paid from 4), nextId 2 (one wheel)
        ww2.deliverSpokes(4);
        WheelwrightSystem.Wheel w2 = ww2.build("wain");
        check("whl: save/load, id continues", w2 != null && w2.id == 2 && ww2.spokes == 0
              && ww2.wheel(1) != null && ww2.wheel(1).trueRound,
              "id=" + (w2 == null ? -1 : w2.id));
    }

    // =====================================================================
    static void walk(){
        RopemakerSystem rm = new RopemakerSystem();
        check("rop: bad hemp refused", !rm.deliverHemp(0) && !rm.deliverHemp(-1), "");
        check("rop: lay without hemp", rm.lay("mooring") == null, "");
        check("rop: hemp paid up front", rm.deliverHemp(4) && rm.lay("mooring") != null
              && rm.hemp == 2, "hemp=" + rm.hemp);
        check("rop: bad name refused", rm.lay("") == null, "");

        rm.tickDay(); rm.tickDay();
        check("rop: walk halfway", rm.coil(1) != null && !rm.coil(1).done
              && rm.coil(1).daysOnWalk == 2, "");
        rm.tickDay();
        check("rop: strength test decides once", rm.coil(1).done
              && (rm.coil(1).holdsFast ^ rm.coil(1).frayed), "");

        RopemakerSystem sound = null, frayed = null;
        for(int s = 1; s <= 400 && (sound == null || frayed == null); s++){
            RopemakerSystem probe = new RopemakerSystem();
            probe.seed = s;
            probe.deliverHemp(2);
            probe.lay("mooring");
            probe.tickDay(); probe.tickDay(); probe.tickDay();
            if(probe.coil(1).holdsFast && sound == null) sound = probe;
            if(probe.coil(1).frayed && frayed == null) frayed = probe;
        }
        if(sound != null){
            check("rop: sound coil counted", sound.soundCount() == 1 && !sound.coil(1).frayed, "");
        } else {
            check("rop: sound coil counted", false, "no sound seed in 400");
        }
        if(frayed != null){
            check("rop: frayed marked honestly", frayed.coil(1).frayed && frayed.soundCount() == 0, "");
        } else {
            check("rop: frayed marked honestly", false, "no fray seed in 400");
        }

        String json = sound.save();
        RopemakerSystem rm2 = new RopemakerSystem();
        rm2.load(json);
        // saved state: hemp 0, nextId 2 (one coil)
        check("rop: save/load, no hemp magic", rm2.coil(1) != null && rm2.coil(1).holdsFast
              && rm2.hemp == 0 && rm2.lay("anchor") == null, "");
        rm2.deliverHemp(2);
        RopemakerSystem.Coil c2 = rm2.lay("anchor");
        check("rop: id continues", c2 != null && c2.id == 2 && rm2.hemp == 0,
              "id=" + (c2 == null ? -1 : c2.id));
    }

    // =====================================================================
    static void cleave(){
        LapidarySystem lp = new LapidarySystem();
        check("lap: bad stones refused", !lp.deliverRough(0) && !lp.deliverRough(-1), "");
        check("lap: cut without stones", lp.cut("garnet") == null, "");
        check("lap: stone paid up front", lp.deliverRough(3) && lp.cut("garnet") != null
              && lp.rough == 2, "rough=" + lp.rough);
        check("lap: bad name refused", lp.cut("") == null, "");

        lp.tickDay();
        check("lap: wheel halfway", lp.gem(1) != null && !lp.gem(1).done
              && lp.gem(1).daysOnWheel == 1, "");
        lp.tickDay();
        check("lap: cleave decides once", lp.gem(1).done
              && (lp.gem(1).brilliant ^ lp.gem(1).flawed), "");

        LapidarySystem brilliant = null, flawed = null;
        for(int s = 1; s <= 400 && (brilliant == null || flawed == null); s++){
            LapidarySystem probe = new LapidarySystem();
            probe.seed = s;
            probe.deliverRough(1);
            probe.cut("garnet");
            probe.tickDay(); probe.tickDay();
            if(probe.gem(1).brilliant && brilliant == null) brilliant = probe;
            if(probe.gem(1).flawed && flawed == null) flawed = probe;
        }
        if(brilliant != null){
            check("lap: brilliant counted", brilliant.brilliantCount() == 1
                  && !brilliant.gem(1).flawed, "");
        } else {
            check("lap: brilliant counted", false, "no brilliant seed in 400");
        }
        if(flawed != null){
            check("lap: flawed marked honestly", flawed.gem(1).flawed
                  && flawed.brilliantCount() == 0, "");
        } else {
            check("lap: flawed marked honestly", false, "no flawed seed in 400");
        }

        String json = brilliant.save();
        LapidarySystem lp2 = new LapidarySystem();
        lp2.load(json);
        // saved state: rough 0 (probe paid its only stone), nextId 2 (one gem)
        check("lap: save/load", lp2.gem(1) != null && lp2.gem(1).brilliant && lp2.rough == 0
              && lp2.cut("opal") == null, "");
        lp2.deliverRough(1);
        LapidarySystem.Gem g2 = lp2.cut("opal");
        check("lap: id continues", g2 != null && g2.id == 2 && lp2.rough == 0,
              "id=" + (g2 == null ? -1 : g2.id));
    }

    // =====================================================================
    static void hoops(){
        CooperSystem cp = new CooperSystem();
        check("cpr: bad staves refused", !cp.deliverStaves(0) && !cp.deliverStaves(-1), "");
        check("cpr: raise without staves", cp.raise("ale") == null, "");
        check("cpr: staves paid up front", cp.deliverStaves(6) && cp.raise("ale") != null
              && cp.staves == 3, "staves=" + cp.staves);
        check("cpr: bad kind refused", cp.raise("") == null, "");

        cp.tickDay();
        check("cpr: bench halfway", cp.cask(1) != null && !cp.cask(1).done
              && cp.cask(1).daysRaised == 1, "");
        cp.tickDay();
        check("cpr: leak test decides once", cp.cask(1).done
              && (cp.cask(1).tight ^ cp.cask(1).leaks), "");

        CooperSystem tight = null, leaky = null;
        for(int s = 1; s <= 400 && (tight == null || leaky == null); s++){
            CooperSystem probe = new CooperSystem();
            probe.seed = s;
            probe.deliverStaves(3);
            probe.raise("ale");
            probe.tickDay(); probe.tickDay();
            if(probe.cask(1).tight && tight == null) tight = probe;
            if(probe.cask(1).leaks && leaky == null) leaky = probe;
        }
        if(tight != null){
            check("cpr: tight cask counted", tight.tightCount() == 1 && !tight.cask(1).leaks, "");
        } else {
            check("cpr: tight cask counted", false, "no tight seed in 400");
        }
        if(leaky != null){
            check("cpr: leaker marked honestly", leaky.cask(1).leaks && leaky.tightCount() == 0, "");
        } else {
            check("cpr: leaker marked honestly", false, "no leak seed in 400");
        }

        String json = tight.save();
        CooperSystem cp2 = new CooperSystem();
        cp2.load(json);
        // saved state: staves 0 (probe paid its only three), nextId 2 (one cask)
        check("cpr: save/load", cp2.cask(1) != null && cp2.cask(1).tight && cp2.staves == 0
              && cp2.raise("beer") == null, "");
        cp2.deliverStaves(3);
        CooperSystem.Cask c2 = cp2.raise("beer");
        check("cpr: id continues, staves paid", c2 != null && c2.id == 2 && cp2.staves == 0,
              "id=" + (c2 == null ? -1 : c2.id) + " staves=" + cp2.staves);
    }
}
