import com.hollow.game.GraveyardSystem;
import com.hollow.game.InnSystem;
import com.hollow.game.MatchmakerSystem;
import com.hollow.game.SchoolSystem;
import com.hollow.world.ApiarySystem;
import com.hollow.world.OrchardSystem;
import com.hollow.world.SettlementSystem;
import com.hollow.world.VitalSystem;
import com.hollow.world.WellSystem;

import java.util.ArrayList;
import java.util.List;

/** v11.0.0 regression suite — inns, apiaries, marriages, wells, schools,
 *  orchards, vital statistics and graveyards. */
public class VerifyV11 {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        inns();
        apiaries();
        matchmaking();
        wells();
        schools();
        orchards();
        vitals();
        graveyards();
        System.out.println(fails == 0 ? "\nALL " + total + " V11 TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " V11 TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void inns(){
        InnSystem is = new InnSystem();
        final List<String> events = new ArrayList<>();
        is.listener = (type, name, v) -> events.add(type);

        check("inn: invalid build refused", !is.build("", 4, 0.5f) && is.build("Red Fox", 4, 0.5f)
              && !is.build("Red Fox", 6, 0.9f), "");
        check("inn: checkIn unknown refused", !is.checkIn("No Such", 1), "");
        check("inn: overbooking refused", is.checkIn("Red Fox", 3) && !is.checkIn("Red Fox", 2)
              && !is.checkIn("Red Fox", 0), "");
        is.tickDay();                                    // occupied 3, rooms 4 → brawl roll possible
        InnSystem.Inn fox = is.inn("Red Fox");
        check("inn: night booked guests leave", fox.occupied == 0 && fox.nights == 1 && fox.income > 0
              && events.contains("night"), "income=" + fox.income);

        // deterministic tavern math: a lone guest in a 4-room inn cannot brawl
        InnSystem is2 = new InnSystem();
        is2.build("Calm", 4, 0.5f);
        is2.checkIn("Calm", 1);
        is2.tickDay();
        check("inn: lone guest pays full rate", is2.inn("Calm").income == 5
              && is2.inn("Calm").brawls == 0, "" + is2.inn("Calm").income);

        // same seed + same story → same brawl count (deterministic)
        InnSystem is3 = new InnSystem();
        is3.build("Rowdy", 4, 0.9f);
        is3.checkIn("Rowdy", 4);
        is3.tickDay();
        InnSystem is4 = new InnSystem();
        is4.build("Rowdy", 4, 0.9f);
        is4.checkIn("Rowdy", 4);
        is4.tickDay();
        check("inn: brawl roll deterministic", is3.inn("Rowdy").brawls == is4.inn("Rowdy").brawls,
              is3.inn("Rowdy").brawls + " vs " + is4.inn("Rowdy").brawls);

        String json = is.save();
        InnSystem is5 = new InnSystem();
        is5.load(json);
        check("inn: save/load", is5.inn("Red Fox") != null && is5.inn("Red Fox").income == fox.income
              && is5.inn("Red Fox").nights == 1 && is5.day == is.day, "");
    }

    // =====================================================================
    static void apiaries(){
        ApiarySystem as = new ApiarySystem();
        final List<String> events = new ArrayList<>();
        as.listener = (type, s, v) -> events.add(type);

        check("api: invalid place refused", !as.place("", 1f) && as.place("oak", 1f) && !as.place("oak", 0.5f), "");
        as.tickDay(); as.tickDay(); as.tickDay();
        check("api: honey accumulates by strength", Math.abs(as.poolOf("oak") - 6f) < 0.001f,
              "" + as.poolOf("oak"));
        check("api: harvest takes the pool", Math.abs(as.harvest("oak") - 6f) < 0.001f
              && Math.abs(as.poolOf("oak")) < 0.001f && as.hive("oak").harvests == 1
              && events.contains("harvested"), "");
        check("api: empty harvest", as.harvest("oak") == 0f && as.harvest("nowhere") == 0f, "");
        check("api: pool unknown settlement", as.poolOf("nowhere") == 0f, "");

        // weak colonies never swarm (guard is strength > 0.2)
        ApiarySystem as2 = new ApiarySystem();
        as2.place("weak", 0.1f);
        for(int i = 0; i < 20; i++) as2.tickDay();
        check("api: weak hive never swarms", as2.hive("weak").swarms == 0, "");

        String json = as.save();
        ApiarySystem as3 = new ApiarySystem();
        as3.load(json);
        check("api: save/load", as3.hive("oak") != null
              && Math.abs(as3.hive("oak").strength - as.hive("oak").strength) < 0.001f
              && as3.hive("oak").harvests == 1 && Math.abs(as3.poolOf("oak")) < 0.001f, "");
    }

    // =====================================================================
    static void matchmaking(){
        MatchmakerSystem ms = new MatchmakerSystem();
        final List<String> events = new ArrayList<>();
        ms.listener = (type, who, v) -> events.add(type);

        check("mm: invalid proposals refused", !ms.propose("ana", "ana", 0.9f)
              && !ms.propose("ana", "bob", 0.2f) && !ms.propose("", "bob", 0.9f), "");
        check("mm: proposal accepted", ms.propose("ana", "bob", 0.8f) && ms.coupleCount() == 1, "");
        check("mm: both partners mapped", ms.marriageOf("ana") == ms.marriageOf("bob")
              && ms.marriageOf("ana") != null, "");
        check("mm: wedded folk refused", !ms.propose("ana", "cyd", 0.9f)
              && !ms.propose("dee", "bob", 0.9f), "");

        MatchmakerSystem ms2 = new MatchmakerSystem();
        ms2.propose("ana", "bob", 0.8f);
        for(int i = 0; i < 10; i++) ms2.tickDay();
        MatchmakerSystem ms3 = new MatchmakerSystem();
        ms3.propose("ana", "bob", 0.8f);
        for(int i = 0; i < 10; i++) ms3.tickDay();
        check("mm: child roll deterministic", ms2.childrenBorn == ms3.childrenBorn
              && ms2.marriageOf("ana").children == ms3.marriageOf("ana").children,
              ms2.childrenBorn + " vs " + ms3.childrenBorn);

        check("mm: divorce frees both", ms.divorce("ana") && ms.marriageOf("bob") == null
              && ms.coupleCount() == 0 && !ms.divorce("ana"), "");
        check("mm: remarriage allowed", ms.propose("bob", "cyd", 0.5f) && ms.coupleCount() == 1, "");

        String json = ms.save();
        MatchmakerSystem ms4 = new MatchmakerSystem();
        ms4.load(json);
        check("mm: save/load", ms4.coupleCount() == 1 && ms4.marriageOf("bob") != null
              && Math.abs(ms4.marriageOf("bob").compatibility - 0.5f) < 0.001f
              && ms4.childrenBorn == ms.childrenBorn, "");
    }

    // =====================================================================
    static void wells(){
        WellSystem ws = new WellSystem();
        final List<String> events = new ArrayList<>();
        ws.listener = (type, s, v) -> events.add(type);

        check("well: invalid dig refused", !ws.dig("", 3) && ws.dig("oak", 2) && !ws.dig("oak", 5), "");
        check("well: shallow well no access", !ws.waterAccess("oak"), "");
        check("well: deep well access", ws.dig("iron", 5) && ws.waterAccess("iron"), "");

        WellSystem.Well well = ws.well("iron");
        well.clean = false;                              // game layer may flag contamination directly
        check("well: dirty well no access", !ws.waterAccess("iron"), "");
        check("well: purify restores", ws.purify("iron") && ws.waterAccess("iron")
              && !ws.purify("iron"), "");
        check("well: unknown purify refused", !ws.purify("nowhere"), "");
        ws.tickDay();
        check("well: tick keeps calendar", ws.day == 1, "");

        String json = ws.save();
        WellSystem ws2 = new WellSystem();
        ws2.load(json);
        check("well: save/load", ws2.well("oak") != null && ws2.well("oak").depth == 2
              && ws2.well("iron").clean && ws2.waterAccess("iron"), "");
    }

    // =====================================================================
    static void schools(){
        SchoolSystem ss = new SchoolSystem();
        final List<String> events = new ArrayList<>();
        ss.listener = (type, pupil, detail) -> events.add(type);

        check("sch: invalid found refused", !ss.found("", "t", 2, 2) && !ss.found("oak", "", 2, 2)
              && ss.found("oak", "Mira", 2, 2) && !ss.found("oak", "Other", 3, 2), "");
        check("sch: duplicate pupil refused", ss.enroll("oak", "pip") && !ss.enroll("oak", "pip"), "");
        check("sch: full class refused", ss.enroll("oak", "wren") && !ss.enroll("oak", "cyd"), "");
        check("sch: unknown school refused", !ss.enroll("iron", "pip"), "");

        ss.tickDay();
        check("sch: lessons advance", ss.school("oak").pupils.get("pip") == 1, "");
        ss.tickDay();
        check("sch: graduation", ss.graduatedCount() == 2 && ss.school("oak").pupils.isEmpty()
              && Math.abs(ss.literacyRate() - 1f) < 0.001f && events.contains("graduated"), "");

        ss.enroll("oak", "tama");
        check("sch: dropout", ss.dropout("oak", "tama") && ss.school("oak").pupils.isEmpty()
              && !ss.dropout("oak", "tama"), "");
        // ever enrolled 3, graduated 2 → literacy 2/3
        check("sch: literacy counts dropouts", Math.abs(ss.literacyRate() - 2f / 3f) < 0.001f,
              "" + ss.literacyRate());

        String json = ss.save();
        SchoolSystem ss2 = new SchoolSystem();
        ss2.load(json);
        check("sch: save/load", ss2.school("oak") != null && ss2.graduatedCount() == 2
              && ss2.everEnrolled == 3 && ss2.day == ss.day, "");
    }

    // =====================================================================
    static void orchards(){
        OrchardSystem os = new OrchardSystem();
        final List<String> events = new ArrayList<>();
        os.listener = (type, s, v) -> events.add(type);

        check("orc: invalid plant refused", !os.plant("", 10) && os.plant("oak", 10) && !os.plant("oak", 5), "");
        for(int i = 0; i < 3; i++) os.tickDay();        // age 3 → blossom phase (frost roll 0.05)
        check("orc: blossom or frost fired", events.contains("blossom") || events.contains("frost"),
              events.toString());
        for(int i = 3; i < 7; i++) os.tickDay();        // age 7 → ripe
        // 10 trees x 0.7 maturity x 0.5 — the default seed fires a first-cycle frost
        // (masked for v11.0 by the flavor-only frost bug itself)
        check("orc: young orchard partial yield", Math.abs(os.fruitOf("oak") - 3.5f) < 0.001f,
              "" + os.fruitOf("oak"));
        check("orc: harvest takes fruit", Math.abs(os.harvest("oak") - 3.5f) < 0.001f
              && Math.abs(os.fruitOf("oak")) < 0.001f && os.orchard("oak").harvests == 1, "");

        for(int i = 7; i < 15; i++) os.tickDay();        // age 15 → second ripe day, maturity capped
        check("orc: mature orchard full yield", Math.abs(os.harvest("oak") - 10f) < 0.001f,
              "" + os.fruitOf("oak"));

        // regression: a blossom-day frost halves the ripe-day yield (old: frost was flavor-only)
        OrchardSystem frosted = null;
        for(int s2 = 1; s2 <= 400 && frosted == null; s2++){
            OrchardSystem probe = new OrchardSystem();
            probe.seed = s2;
            probe.plant("oak", 10);
            probe.tickDay(); probe.tickDay(); probe.tickDay();
            if(probe.orchard("oak").frosts > 0) frosted = probe;
        }
        if(frosted != null){
            for(int i = 3; i < 7; i++) frosted.tickDay();
            check("orc: frost halves the yield", Math.abs(frosted.fruitOf("oak") - 3.5f) < 0.001f,
                  "" + frosted.fruitOf("oak"));     // 10 trees x 0.7 maturity x 0.5 spoil
        } else {
            check("orc: frost halves the yield", false, "no frost seed found in 400");
        }

        // control: a frost-free first cycle pays the full young yield (10 trees x 0.7)
        OrchardSystem cleanS = null;
        for(int s2 = 1; s2 <= 400 && cleanS == null; s2++){
            OrchardSystem probe = new OrchardSystem();
            probe.seed = s2;
            probe.plant("oak", 10);
            probe.tickDay(); probe.tickDay(); probe.tickDay();
            if(probe.orchard("oak").frosts == 0) cleanS = probe;
        }
        if(cleanS != null){
            for(int i = 3; i < 7; i++) cleanS.tickDay();
            check("orc: clean cycle full young yield", Math.abs(cleanS.fruitOf("oak") - 7f) < 0.001f,
                  "" + cleanS.fruitOf("oak"));
        } else {
            check("orc: clean cycle full young yield", false, "no clean seed found in 400");
        }

        String json = os.save();
        OrchardSystem os2 = new OrchardSystem();
        os2.load(json);
        check("orc: save/load", os2.orchard("oak") != null && os2.orchard("oak").age == 15
              && Math.abs(os2.orchard("oak").fruit) < 0.001f && os2.orchard("oak").harvests == 2, "");
    }

    // =====================================================================
    static void vitals(){
        SettlementSystem world = new SettlementSystem();
        SettlementSystem.Settlement oak = world.found("oak", "Oakstead");
        oak.pop = 100;
        VitalSystem vs = new VitalSystem(world);
        final List<String> events = new ArrayList<>();
        vs.listener = (type, s, v) -> events.add(type);

        vs.tickDay();                                    // 100 pop: 1 born, 1 died → net 0
        check("vit: balanced defaults", oak.pop == 100 && vs.stats("oak").born == 1
              && vs.stats("oak").died == 1 && vs.stats("oak").net() == 0, "");
        vs.birthRate = 0.05f;
        vs.deathRate = 0f;
        vs.tickDay();
        check("vit: births grow the town", oak.pop == 105 && vs.stats("oak").born == 6
              && vs.stats("oak").died == 1 && events.contains("born"), "");

        oak.pop = 1;
        vs.deathRate = 0.9f;
        vs.tickDay();                                    // died = min(pop, round(0.9)) = 1 → pop 0
        check("vit: pop never negative", oak.pop == 0, "" + oak.pop);
        check("vit: unknown settlement zero stats", vs.stats("nowhere").born == 0
              && vs.stats("nowhere").died == 0, "");
        check("vit: totals bookkeeping", vs.totalBorn() == 6 && vs.totalDied() == 2, "");

        String json = vs.save();
        VitalSystem vs2 = new VitalSystem(world);
        vs2.load(json);
        check("vit: save/load", Math.abs(vs2.birthRate - 0.05f) < 0.0001f
              && Math.abs(vs2.deathRate - 0.9f) < 0.0001f && vs2.totalBorn() == 6
              && vs2.stats("oak").died == 2, "");
    }

    // =====================================================================
    static void graveyards(){
        GraveyardSystem gs = new GraveyardSystem();
        final List<String> events = new ArrayList<>();
        gs.listener = (type, s, v) -> events.add(type);

        check("grav: invalid consecrate refused", !gs.consecrate("", 2) && gs.consecrate("oak", 2)
              && !gs.consecrate("oak", 4), "");
        check("grav: bury unknown yard refused", !gs.bury("iron", "old tom"), "");
        check("grav: buries while plots last", gs.bury("oak", "old tom") && gs.bury("oak", "marta")
              && gs.yard("oak").occupied == 2 && events.contains("buried"), "");
        check("grav: full ground refuses", !gs.bury("oak", "third") && gs.yard("oak").refusedFull == 1
              && events.contains("graveyard_full"), "");
        check("grav: empty name refused", !gs.bury("oak", ""), "");

        for(int i = 0; i < 30; i++) gs.tickDay();        // day 30: oldest graves (day 0) fall silent
        check("grav: old plots reused", gs.yard("oak").occupied == 0 && events.contains("reused"), "");
        check("grav: reuse frees ground", gs.bury("oak", "newcomer") && gs.yard("oak").burials == 3, "");

        String json = gs.save();
        GraveyardSystem gs2 = new GraveyardSystem();
        gs2.load(json);
        check("grav: save/load", gs2.yard("oak") != null && gs2.yard("oak").occupied == 1
              && gs2.yard("oak").burials == 3 && gs2.plotReuseDays == 30, "");
    }
}
