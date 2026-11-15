import com.hollow.game.ExpeditionSystem;
import com.hollow.game.RumorSystem;
import com.hollow.game.SuccessionSystem;
import com.hollow.game.TournamentSystem;
import com.hollow.world.GranarySystem;
import com.hollow.world.GuildSystem;
import com.hollow.world.PlagueSystem;
import com.hollow.world.RoadSystem;
import com.hollow.world.SettlementSystem;

import java.util.ArrayList;
import java.util.List;

/** v9.0.0 regression suite — rumors, roads, tournaments, guilds, succession,
 *  expeditions, plague and granaries. */
public class VerifyV9 {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        rumors();
        roads();
        tournaments();
        guilds();
        succession();
        expeditions();
        plague();
        granaries();
        System.out.println(fails == 0 ? "\nALL " + total + " V9 TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " V9 TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void rumors(){
        RumorSystem rs = new RumorSystem();
        final List<String> events = new ArrayList<>();
        rs.listener = (type, id, detail) -> events.add(type);

        check("rumo: invalid subject refused", rs.spread("", 0.5f, "x") == null, "");
        RumorSystem.Rumor r = rs.spread("the crown is cursed", 0.9f, "courtier");
        check("rumo: planted + origin hears truth", r != null && rs.truthOf("courtier", r.id) == 0.9f, "");
        check("rumo: unknown npc believes nothing", rs.truthOf("stranger", r.id) == -1f, "");
        float before = rs.truthOf("courtier", r.id);
        rs.hear("courtier", 999, 0.5f);                 // unknown rumor id must be ignored
        check("rumo: unknown rumor ignored", before == 0.9f && rs.truthOf("courtier", 999) == -1f, "");

        float end = rs.hopChain("courtier", r.id, 5);
        check("rumo: hops distort the truth", end >= 0f && end < 0.9f, "" + end);
        check("rumo: last listener remembers", rs.truthOf("listener4", r.id) == end
              && rs.knownBy("listener4").contains(r.id) && events.contains("hops"), "" + end);

        String json = rs.save();
        RumorSystem rs2 = new RumorSystem();
        rs2.load(json);
        check("rumo: save/load", rs2.truthOf("listener4", r.id) == end
              && rs2.rumors.get(r.id) != null && rs2.day == rs.day, "" + rs2.truthOf("listener4", r.id));
    }

    // =====================================================================
    static void roads(){
        RoadSystem rd = new RoadSystem();
        final List<String> events = new ArrayList<>();
        rd.listener = (type, f, t, lvl) -> events.add(type + "@" + lvl);

        check("road: invalid build refused", !rd.build("a", "a") && !rd.build(null, "b"), "");
        check("road: build ok", rd.build("oak", "iron") && rd.roadCount() == 1, "");
        check("road: symmetric key", rd.road("iron", "oak") != null && rd.levelOf("iron", "oak") == 1, "");
        check("road: duplicate refused", !rd.build("iron", "oak"), "");

        check("road: upgrade", rd.upgrade("oak", "iron") == 2 && rd.levelOf("iron", "oak") == 2, "");
        check("road: level2 discount", rd.travelDays(10, "oak", "iron") == 7, "" + rd.travelDays(10, "oak", "iron"));
        check("road: max level caps", rd.upgrade("oak", "iron") == 3 && rd.upgrade("oak", "iron") == -1, "");
        // float precision: 1 - 3×0.15f = 0.5499999… → 10×0.5499… rounds to 5 (not 6)
        check("road: level3 discount floored", rd.travelDays(10, "oak", "iron") == 5, "" + rd.travelDays(10, "oak", "iron"));
        check("road: no road no discount", rd.travelDays(10, "iron", "gaul") == 10
              && Math.abs(rd.migrationBonus("oak", "iron") - 0.3f) < 0.001f, "");

        String json = rd.save();
        RoadSystem rd2 = new RoadSystem();
        rd2.load(json);
        check("road: save/load", rd2.levelOf("iron", "oak") == 3 && rd2.roadCount() == 1, "");
    }

    // =====================================================================
    static void tournaments(){
        TournamentSystem ts = new TournamentSystem();
        ts.define(new TournamentSystem.Knight("Ser Aldric", 0.9f));
        ts.define(new TournamentSystem.Knight("Ser Bors", 0.7f));
        ts.define(new TournamentSystem.Knight("Ser Cato", 0.5f));
        ts.define(new TournamentSystem.Knight("Ser Dren", 0.3f));

        check("tour: invalid schedule refused", ts.schedule("", 100, 50) == null
              && ts.schedule("cup", 0, 50) == null, "");
        TournamentSystem.Tournament t = ts.schedule("Crown Cup", 200, 80);
        check("tour: enrolled", ts.enroll(t.id, "Ser Aldric") && ts.enroll(t.id, "Ser Bors")
              && ts.enroll(t.id, "Ser Cato") && ts.enroll(t.id, "Ser Dren"), "");
        check("tour: duplicate/unknown refused", !ts.enroll(t.id, "Ser Aldric")
              && !ts.enroll(t.id, "Nobody") && !ts.enroll(999, "Ser Bors"), "");
        check("tour: four knights enrolled", t.enrolled.size() == 4, "" + t.enrolled.size());

        String champ = ts.run(t.id, 42L);
        check("tour: champion crowned", !champ.isEmpty() && t.done && t.champion.equals(champ)
              && t.enrolled.contains(champ), champ);
        check("tour: rerun refused", ts.run(t.id, 42L).isEmpty(), "");
        check("tour: title counted", ts.titlesOf(champ) == 1, champ);

        // determinism: same seed, same field → same champion
        TournamentSystem ts2 = new TournamentSystem();
        ts2.define(new TournamentSystem.Knight("Ser Aldric", 0.9f));
        ts2.define(new TournamentSystem.Knight("Ser Bors", 0.7f));
        ts2.define(new TournamentSystem.Knight("Ser Cato", 0.5f));
        ts2.define(new TournamentSystem.Knight("Ser Dren", 0.3f));
        TournamentSystem.Tournament t2 = ts2.schedule("Crown Cup", 200, 80);
        ts2.enroll(t2.id, "Ser Aldric"); ts2.enroll(t2.id, "Ser Bors");
        ts2.enroll(t2.id, "Ser Cato"); ts2.enroll(t2.id, "Ser Dren");
        check("tour: deterministic bracket", ts2.run(t2.id, 42L).equals(champ), ts2.run(t2.id, 0L) + "/" + champ);

        String json = ts.save();
        TournamentSystem ts3 = new TournamentSystem();
        ts3.load(json);
        check("tour: save/load", ts3.tournaments.get(t.id) != null && ts3.tournaments.get(t.id).done
              && ts3.titlesOf(champ) == 1, "");
    }

    // =====================================================================
    static void guilds(){
        GuildSystem gs = new GuildSystem();
        final List<String> events = new ArrayList<>();
        gs.listener = (type, id, detail) -> events.add(type);

        check("gild: invalid found refused", gs.found("", GuildSystem.Kind.CRAFT, 10) == null
              && gs.found("x", null, 10) == null, "");
        GuildSystem.Guild g = gs.found("Golden Scale", GuildSystem.Kind.MERCHANT, 1);
        check("gild: join", gs.join(g.id, "hana") && gs.join(g.id, "mori")
              && !gs.join(g.id, "hana") && !gs.join(999, "kai"), "");
        gs.tickDay();
        check("gild: dues minus upkeep", g.coffers == 1 + 4 - 5 && events.contains("joined"),
              "" + g.coffers);                 // 1 + 2×2 - 5 = 0
        gs.tickDay();
        check("gild: empty coffers disbands", g.disbanded
              && g.members.isEmpty() && events.contains("disbanded"), "" + g.coffers);
        check("gild: buff needs living guild", gs.buffOf(GuildSystem.Kind.MERCHANT, 1) == 0f
              && gs.activeCount() == 0, "");

        GuildSystem.Guild g2 = gs.found("Anvil Brotherhood", GuildSystem.Kind.CRAFT, 50);
        gs.join(g2.id, "smith"); gs.join(g2.id, "fitter");
        check("gild: buff scales with members", Math.abs(gs.buffOf(GuildSystem.Kind.CRAFT, 2) - 0.1f) < 0.001f,
              "" + gs.buffOf(GuildSystem.Kind.CRAFT, 2));
        check("gild: leave", gs.leave(g2.id, "fitter") && g2.members.size() == 1 && !gs.leave(g2.id, "fitter"), "");

        String json = gs.save();
        GuildSystem gs2 = new GuildSystem();
        gs2.load(json);
        check("gild: save/load", gs2.guild(g.id).disbanded && gs2.guild(g2.id).coffers == g2.coffers
              && gs2.day == gs.day, "" + gs2.day);
    }

    // =====================================================================
    static void succession(){
        SuccessionSystem ss = new SuccessionSystem();
        final List<String> events = new ArrayList<>();
        ss.listener = (type, name, legit) -> events.add(type);

        check("succ: invalid crown refused", ss.crown("") == null, "");
        SuccessionSystem.Ruler r = ss.crown("Aldric");
        check("succ: crowned with legitimacy", r != null && r.legitimacy == 60 && ss.hasRuler(), "");
        check("succ: heir can't be the ruler", !ss.nameHeir("Aldric") && ss.nameHeir("Bors"), "");

        for(int i = 0; i < 5; i++) ss.tickDay();
        check("succ: stability accrues legitimacy", ss.legitimacy() == 65 && ss.reignDays() == 5,
              "" + ss.legitimacy());

        check("succ: heir inherits strong", ss.depose() && ss.hasRuler() && ss.current.name.equals("Bors")
              && ss.current.legitimacy == 65 && ss.lineage.contains("Aldric")
              && events.contains("succeeded"), events.toString());

        ss.crown("Cato");
        check("succ: no heir → interregnum", ss.depose() && !ss.hasRuler()
              && events.contains("interregnum"), events.toString());
        check("succ: usurper takes weak crown", ss.seizeThrone("Drax") != null && ss.legitimacy() == 25, "");
        check("succ: no coup over a sitting ruler", ss.seizeThrone("Yara") == null, "");

        ss.current.legitimacy = 15;                     // simulating a weakened reign
        ss.tickDay();
        check("succ: uprising risk flagged", events.contains("uprising_risk"), events.toString());

        String json = ss.save();
        SuccessionSystem ss2 = new SuccessionSystem();
        ss2.load(json);
        check("succ: save/load", ss2.hasRuler() && ss2.current.name.equals("Drax")
              && ss2.legitimacy() == 15 && ss2.lineage.size() == ss.lineage.size(), "");
    }

    // =====================================================================
    static void expeditions(){
        ExpeditionSystem es = new ExpeditionSystem();
        final List<String> events = new ArrayList<>();
        es.listener = (type, id, detail) -> events.add(type);

        es.define(new ExpeditionSystem.Ruin("vault", "Sunken Vault", "crown of dust", 500)
            .danger(0.05f).journey(2));
        check("exp: unknown ruin refused", es.launch("nowhere", "hana", 10, 10) == null, "");
        check("exp: provisions must cover journey", es.launch("vault", "hana", 10, 1) == null, "");
        ExpeditionSystem.Expedition e = es.launch("vault", "hana", 10, 5);
        check("exp: launched", e != null && es.pendingCount() == 1 && events.contains("launched"), "");

        es.tickDay();
        check("exp: journey advances", !e.resolved && Math.abs(e.progress - 1f) < 0.001f, "" + e.progress);
        es.tickDay();
        check("exp: arrival resolves with outcome", e.resolved && es.pendingCount() == 0
              && (es.artifactCount("crown of dust") == 1 || events.contains("casualties")),
              events.toString());
        check("exp: cabinet values artifacts", es.artifactCount("crown of dust") == 1
              ? es.cabinetValue() == 500 : es.cabinetValue() == 0, "" + es.cabinetValue());

        String json = es.save();
        ExpeditionSystem es2 = new ExpeditionSystem();
        es2.load(json);
        check("exp: save/load", es2.ruin("vault") != null && es2.expeditions.get(e.id) != null
              && es2.expeditions.get(e.id).resolved
              && es2.artifactCount("crown of dust") == es.artifactCount("crown of dust"), "");
    }

    // =====================================================================
    static void plague(){
        SettlementSystem world = new SettlementSystem();
        SettlementSystem.Settlement oak = world.found("oak", "Oakstead");
        SettlementSystem.Settlement iron = world.found("iron", "Ironhold");
        PlagueSystem ps = new PlagueSystem(world);
        final List<String> events = new ArrayList<>();
        ps.listener = (type, s, v) -> events.add(type);

        ps.tickDay();                                   // no-op while inactive
        check("plag: no plague no tick effect", !ps.active && ps.totalSick() == 0, "");
        check("plag: outbreak refused for immune", ps.immune.put("oak", true) == null
              && !ps.outbreak("oak", 2), "");
        ps.immune.remove("oak");
        oak.pop = 100;
        check("plag: outbreak plants the sick", ps.outbreak("oak", 50) && ps.sickOf("oak") == 50
              && ps.active && events.contains("outbreak"), "" + ps.sickOf("oak"));

        ps.tickDay();
        // 50 sick → 5 deaths (pop 95, sick 45) → 13 new sick of 50 healthy → 58
        check("plag: deaths + spread deterministic", oak.pop == 95 && ps.sickOf("oak") == 58,
              oak.pop + "/" + ps.sickOf("oak"));
        ps.quarantine("iron", true);
        check("plag: quarantine flags", ps.isQuarantined("iron") && !ps.isQuarantined("oak"), "");
        check("plag: sick counted across towns", ps.totalSick() >= 58, "" + ps.totalSick());

        PlagueSystem ps2 = new PlagueSystem(world);
        ps2.outbreak("oak", 10);
        for(int i = 0; i < 16; i++) ps2.tickDay();
        check("plag: plague burns out by duration", !ps2.active, "" + ps2.day);

        String json = ps.save();
        PlagueSystem ps3 = new PlagueSystem(world);
        ps3.load(json);
        check("plag: save/load", ps3.sickOf("oak") == ps.sickOf("oak") && ps3.isQuarantined("iron")
              && ps3.immune.containsKey("oak") == ps.immune.containsKey("oak"), "");
    }

    // =====================================================================
    static void granaries(){
        SettlementSystem world = new SettlementSystem();
        SettlementSystem.Settlement oak = world.found("oak", "Oakstead");
        oak.food = 100f;
        GranarySystem gr = new GranarySystem(world);
        final List<String> events = new ArrayList<>();
        gr.listener = (type, s, v) -> events.add(type);

        check("gran: no granary no capacity", gr.capacityOf("oak") == 0 && gr.deposit("oak", 10) == 0f, "");
        oak.buildings.add("granary1");
        check("gran: capacity from buildings", gr.capacityOf("oak") == 200, "");
        check("gran: deposit moves food", gr.deposit("oak", 30) == 30f && Math.abs(oak.food - 70f) < 0.01f
              && events.contains("stored"), "" + oak.food);
        check("gran: overflow capped by room+food", gr.deposit("oak", 500) == 70f
              && gr.storesOf("oak") == 100f && Math.abs(oak.food) < 0.01f, "" + gr.storesOf("oak"));
        check("gran: withdraw releases", gr.withdraw("oak", 50) == 50f
              && Math.abs(oak.food - 50f) < 0.01f && Math.abs(gr.storesOf("oak") - 50f) < 0.01f, "");

        gr.tickDay();                                    // spoilage 2% of 50
        check("gran: grain spoils", Math.abs(gr.storesOf("oak") - 49f) < 0.01f && events.contains("spoiled"),
              "" + gr.storesOf("oak"));

        oak.food = 0f;
        float need = oak.pop * oak.foodPerHead;
        gr.tickDay();                                    // famine relief kicks in
        check("gran: famine relief feeds town", Math.abs(oak.food - need) < 0.01f && events.contains("relief"),
              oak.food + "/" + need);

        String json = gr.save();
        GranarySystem gr2 = new GranarySystem(world);
        gr2.load(json);
        check("gran: save/load", Math.abs(gr2.storesOf("oak") - gr.storesOf("oak")) < 0.01f, "");
    }
}
