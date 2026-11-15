import com.hollow.game.AuctionSystem;
import com.hollow.game.ChronicleSystem;
import com.hollow.game.JusticeSystem;
import com.hollow.game.ResearchSystem;
import com.hollow.game.TreasurySystem;
import com.hollow.world.FestivalSystem;
import com.hollow.world.MigrationSystem;
import com.hollow.world.QuarrySystem;
import com.hollow.world.SettlementSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** v8.0.0 regression suite — research, quarries, treasury, migration,
 *  justice, auction house, festivals and the world chronicle. */
public class VerifyV8 {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        research();
        quarries();
        treasury();
        migration();
        justice();
        auctions();
        festivals();
        chronicle();
        System.out.println(fails == 0 ? "\nALL " + total + " V8 TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " V8 TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void research(){
        ResearchSystem rs = new ResearchSystem();
        final List<String> events = new ArrayList<>();
        rs.listener = (type, id, know) -> events.add(type + ":" + id);

        rs.define(new ResearchSystem.Tech("wheel", "The Wheel").costs(10).effect("build_speed", 0.1f));
        rs.define(new ResearchSystem.Tech("irrigation", "Irrigation").costs(20)
              .requires("wheel").effect("farm_yield", 0.15f));
        rs.define(new ResearchSystem.Tech("alchemy", "Alchemy").costs(5).requires("ghost"));

        check("res: no knowledge no unlock", !rs.unlock("wheel"), "" + rs.knowledge);
        check("res: missing prereq refused", !rs.unlock("alchemy"), "");
        rs.scholars = 5;                                    // 2.5 knowledge/day
        for(int i = 0; i < 4; i++) rs.tickDay();
        check("res: scholars produce knowledge", Math.abs(rs.knowledge - 10f) < 0.001f, "" + rs.knowledge);
        check("res: unlock spends knowledge", rs.unlock("wheel") && Math.abs(rs.knowledge) < 0.001f
              && events.contains("unlocked:wheel"), "" + rs.knowledge);
        check("res: bonus aggregates", Math.abs(rs.bonus("build_speed") - 0.1f) < 0.001f, "");

        rs.libraryBonus = 2f;                               // 5/day now
        for(int i = 0; i < 4; i++) rs.tickDay();            // 20
        check("res: library speeds research", rs.unlock("irrigation") && rs.unlockedCount() == 2, "" + rs.knowledge);

        String json = rs.save();
        ResearchSystem rs2 = new ResearchSystem();
        rs2.define(new ResearchSystem.Tech("wheel", "The Wheel").costs(10));
        rs2.define(new ResearchSystem.Tech("irrigation", "Irrigation").costs(20).requires("wheel"));
        rs2.load(json);
        check("res: save/load", rs2.tech("wheel").unlocked && rs2.tech("irrigation").unlocked
              && rs2.scholars == 5 && Math.abs(rs2.libraryBonus - 2f) < 0.001f, "" + rs2.knowledge);
    }

    // =====================================================================
    static void quarries(){
        QuarrySystem qs = new QuarrySystem();
        final List<String> events = new ArrayList<>();
        qs.listener = (type, id, amt) -> events.add(type + ":" + id);

        QuarrySystem.Node mine = qs.define(new QuarrySystem.Node("mine1", "stone").workers(4).yield(1f)
            .reserve(100f, 100f).regen(2f));
        float got = qs.tickDay();
        check("quarry: workers extract", Math.abs(got - 4f) < 0.001f && Math.abs(mine.reserves - 98f) < 0.001f,
              got + "/" + mine.reserves);
        Map<String, Float> byRes = qs.extractionByResource();
        check("quarry: output by resource", Math.abs(byRes.getOrDefault("stone", 0f) - 4f) < 0.001f, "" + byRes);

        // depletion: 3 reserves left, 10 workers demand 10
        mine.reserves = 3f; mine.workers = 10;
        qs.tickDay();
        check("quarry: node depletes", mine.depleted && events.contains("depleted:mine1")
              && qs.depletedCount() == 1, "" + mine.depleted);
        float got2 = qs.tickDay();
        check("quarry: depleted yields nothing", Math.abs(got2) < 0.001f, "" + got2);

        // recovery once reserves regrow past the threshold (workers idle meanwhile)
        mine.workers = 0;
        qs.tickDay();                                       // reserves regen toward minReserve
        qs.tickDay();                                       // recovered fires once reserves >= 5
        check("quarry: node recovers", !mine.depleted && events.contains("recovered:mine1"), "" + mine.reserves);
        check("quarry: reserves capped at max", mine.reserves <= mine.maxReserves, "" + mine.reserves);

        String json = qs.save();
        QuarrySystem qs2 = new QuarrySystem();
        qs2.load(json);
        check("quarry: save/load", qs2.node("mine1") != null && qs2.node("mine1").workers == 0
              && qs2.day == qs.day, "" + qs2.day);
    }

    // =====================================================================
    static void treasury(){
        TreasurySystem tr = new TreasurySystem();
        final List<String> events = new ArrayList<>();
        tr.listener = (type, bal, amt) -> events.add(type);

        check("trea: income books gold", tr.income("trade", 100, "wool sale") == 100
              && tr.ledgerSize() == 1, "" + tr.balance);
        check("trea: insolvent expense refused", !tr.expense("build", 500, "castle"), "" + tr.balance);
        check("trea: expense books negative", tr.expense("build", 30, "barn") && tr.balance == 70
              && tr.netOf("build") == -30, "" + tr.balance);

        // low-balance warning fires once per crossing
        tr.expense("gift", 55, "envoys");                   // → 15 ≤ 20
        check("trea: low balance warned", events.contains("low_balance"), events.toString());
        tr.income("tax", 50, "harvest");                    // → 65, warning reset
        tr.expense("gift", 50, "envoys2");                  // → 15 again
        long warns = events.stream().filter(e -> e.equals("low_balance")).count();
        check("trea: warning re-arms", warns == 2, "" + warns);

        List<Integer> pops = new ArrayList<>();
        pops.add(6); pops.add(12);
        check("trea: taxes per head", tr.collectTaxes(pops, 1) == 33, "" + tr.balance);   // 15 + 18

        tr.dailyWages = 10;
        tr.tickDay();
        check("trea: wages paid daily", tr.balance == 23 && tr.netOf("wages") == -10, "" + tr.balance);

        TreasurySystem tr2 = new TreasurySystem();
        tr2.ledgerLimit = 3;
        for(int i = 0; i < 6; i++) tr2.income("tax", 1, "d" + i);
        check("trea: ledger cap holds", tr2.ledgerSize() == 3 && tr2.recent(10).size() == 3, "" + tr2.ledgerSize());

        String json = tr.save();
        TreasurySystem tr3 = new TreasurySystem();
        tr3.load(json);
        check("trea: save/load", tr3.balance == tr.balance && tr3.ledgerSize() == tr.ledgerSize()
              && tr3.dailyWages == 10, "" + tr3.balance);
    }

    // =====================================================================
    static void migration(){
        SettlementSystem world = new SettlementSystem();
        SettlementSystem.Settlement rich = world.found("rich", "Greenhollow");
        rich.pop = 2; rich.food = 30f;
        SettlementSystem.Settlement poor = world.found("poor", "Dusthole");
        poor.pop = 5; poor.food = 2f;                        // starving → pushes settlers out
        MigrationSystem mg = new MigrationSystem(world);
        final List<String> events = new ArrayList<>();
        mg.listener = (type, from, to, n) -> events.add(type + ":" + from + ">" + to + "x" + n);

        int realm0 = mg.realmPopulation();
        check("mig: realm counted", realm0 == 7, "" + realm0);
        mg.tickDay();
        check("mig: famine pushes settlers", poor.pop < 5 && events.stream().anyMatch(e -> e.startsWith("moved:poor>")),
              poor.pop + "/" + events.toString());
        check("mig: surplus pulls settlers", rich.pop > 2 && rich.pop <= rich.housing(), "" + rich.pop);
        check("mig: population conserved", mg.realmPopulation() == 7, "" + mg.realmPopulation());

        // healthy towns don't migrate
        rich.food = 30f; poor.food = 20f; poor.pop = 3;      // both comfortable now
        events.clear();
        mg.tickDay();
        check("mig: stability is quiet", events.isEmpty(), events.toString());

        String json = mg.save();
        MigrationSystem mg2 = new MigrationSystem(world);
        mg2.load(json);
        check("mig: save/load", mg2.day == mg.day && Math.abs(mg2.pushFoodRatio - mg.pushFoodRatio) < 0.001f, "");
    }

    // =====================================================================
    static void justice(){
        JusticeSystem js = new JusticeSystem();
        final List<String> events = new ArrayList<>();
        js.listener = (type, criminal, val) -> events.add(type + ":" + criminal);

        js.recordCrime("thief", "pickpocket", 4);
        js.recordCrime("thief", "burglary", 4);
        js.recordCrime("thief", "assault", 4);
        check("just: crimes stack wanted", js.wantedLevel("thief") == 12, "" + js.wantedLevel("thief"));
        check("just: wanted at threshold", js.isWanted("thief"), "");
        check("just: below threshold free", !js.isWanted("punk"), "" + js.wantedLevel("punk"));

        check("just: arrest jails + resets wanted", js.arrest("thief") && js.isImprisoned("thief")
              && js.sentenceOf("thief") == 2 && js.wantedLevel("thief") == 0
              && events.contains("arrested:thief"), "" + js.sentenceOf("thief"));
        check("just: cannot arrest the free", !js.arrest("punk"), "");
        js.tickDay(); js.tickDay();
        check("just: sentence served releases", !js.isImprisoned("thief") && events.contains("released:thief"),
              events.toString());

        // a jailed criminal cannot buy their way out with a fine
        js.recordCrime("jailbird", "heist", 12);
        js.arrest("jailbird");
        check("just: jailed can't pay fines", !js.payFine("jailbird", 5), "");

        js.recordCrime("bandit", "toll", 6);
        check("just: fine reduces wanted", !js.payFine("bandit", 4) && js.wantedLevel("bandit") == 2, "");
        check("just: full fine clears", js.payFine("bandit", 5) && js.wantedLevel("bandit") == 0, "");

        js.recordCrime("poacher", "snare", 5);
        for(int i = 0; i < 3; i++) js.tickDay();            // decay 1/day while free
        check("just: wanted decays", js.wantedLevel("poacher") == 2, "" + js.wantedLevel("poacher"));

        String json = js.save();
        JusticeSystem js2 = new JusticeSystem();
        js2.load(json);
        check("just: save/load", js2.wantedLevel("poacher") == 2 && !js2.isImprisoned("thief")
              && js2.day == js.day, "");
    }
    // =====================================================================
    static void auctions(){
        AuctionSystem au = new AuctionSystem();
        final List<String> events = new ArrayList<>();
        au.listener = (type, id, detail) -> events.add(type);

        AuctionSystem.Listing l1 = au.list("iron sword", 1, 50, "smith", 3);
        AuctionSystem.Listing l2 = au.list("wool bale", 5, 10, "farmer", 2);
        check("auc: listings open", au.openCount() == 2 && l1 != null, "");
        check("auc: invalid listing refused", au.list("", 1, 5, "x", 1) == null
              && au.list("rice", 0, 5, "x", 1) == null, "");
        check("auc: bid below reserve refused", !au.bid(l1.id, "hana", 49), "");
        check("auc: seller cannot bid", !au.bid(l1.id, "smith", 60), "");
        check("auc: reserve met", au.bid(l1.id, "hana", 50), "");
        check("auc: outbid needs increment", !au.bid(l1.id, "mori", 50)
              && au.bid(l1.id, "mori", 51), "");

        au.tickDay(); au.tickDay(); au.tickDay(); au.tickDay();   // close happens when day > expiresDay
        check("auc: sold pays net of fee", l1.state == AuctionSystem.State.SOLD
              && au.sellerProceeds("smith") == 51 - 51 * 5 / 100
              && events.contains("sold"), "" + au.sellerProceeds("smith"));
        check("auc: unsold expires", l2.state == AuctionSystem.State.EXPIRED
              && au.sellerProceeds("farmer") == 0, "" + l2.state);

        String json = au.save();
        AuctionSystem au2 = new AuctionSystem();
        au2.load(json);
        check("auc: save/load", au2.listing(l1.id) != null
              && au2.listing(l1.id).state == AuctionSystem.State.SOLD
              && au2.sellerProceeds("smith") == au.sellerProceeds("smith"), "");
    }

    // =====================================================================
    static void festivals(){
        FestivalSystem fe = new FestivalSystem();
        final List<String> events = new ArrayList<>();
        fe.listener = (type, id, detail) -> events.add(type);

        check("fest: invalid refused", fe.schedule("", "oak", 1, 2, 10) == null, "");
        FestivalSystem.Festival f1 = fe.schedule("Harvest Fair", "oak", 2, 2, 30);
        FestivalSystem.Festival f2 = fe.schedule("Winterfire", "iron", 1, 1, 10);
        check("fest: scheduled pending", f1.state == FestivalSystem.State.SCHEDULED
              && !fe.isActive("oak"), "" + f1.state);

        fe.tickDay();                                        // day 1: Winterfire starts
        check("fest: starts on its day", fe.isActive("iron") && fe.moraleOf("iron") == 15f
              && !fe.isActive("oak") && events.contains("started"), events.toString());
        fe.tickDay();                                        // day 2: Harvest Fair starts; 1-day Winterfire ends
        check("fest: short festival ends", f2.state == FestivalSystem.State.ENDED
              && fe.isActive("oak") && f1.state == FestivalSystem.State.ACTIVE, "" + f2.state);
        fe.tickDay();                                        // day 3: fair still live
        check("fest: morale live", Math.abs(fe.moraleOf("oak") - 15f) < 0.001f
              && fe.active().size() == 1 && fe.moraleOf("iron") == 0f, "" + fe.active().size());
        fe.tickDay();                                        // day 4: fair's duration (2+2) reached
        check("fest: fair ends", f1.state == FestivalSystem.State.ENDED && fe.moraleOf("oak") == 0f, "");

        String json = fe.save();
        FestivalSystem fe2 = new FestivalSystem();
        fe2.load(json);
        check("fest: save/load", fe2.festival(f1.id) != null
              && fe2.festival(f1.id).state == FestivalSystem.State.ENDED && fe2.day == fe.day, "");
    }

    // =====================================================================
    static void chronicle(){
        ChronicleSystem ch = new ChronicleSystem();
        final List<String> events = new ArrayList<>();
        ch.listener = (type, id, fame) -> events.add(type);

        check("chro: invalid refused", ch.record("", "nothing", 1) == null, "");
        ch.record("war", "Rome crushed Carthage at the gates", 80);
        ch.record("festival", "Oakstead lit the Winterfire", 20);
        ch.record("famine", "Dusthole lost its harvest", 5);
        ch.record("capture", "Red banner rose over the valley", 60);
        check("chro: entries recorded", ch.size() == 4 && ch.byType("festival").size() == 1, "");
        check("chro: top by fame", ch.top(2).get(0).type.equals("war")
              && ch.top(2).get(1).fame == 60, "" + ch.top(2).get(0).type);
        check("chro: legendary flagged", ch.legendary().size() == 2 && events.contains("legendary"), "");

        ch.day = 40;
        ch.record("trade", "A caravan crossed the desert", 1);
        check("chro: latest first", ch.latest(1).get(0).text.contains("desert"), "");

        // bounded book: lowest fame evicted first
        ch.maxEntries = 4;
        ch.record("minor", "A goat ate the mayor's hat", 1);
        check("chro: eviction keeps fame", ch.size() == 4
              && !ch.byType("famine").isEmpty() && ch.byType("trade").isEmpty(), "" + ch.size());

        String t1 = ch.retell(3);
        String t2 = ch.retell(3);
        check("chro: retell deterministic", t1.equals(t2) && t1.contains("day "), t1);
        check("chro: empty retell safe", new ChronicleSystem().retell(3).contains("empty"), "");

        String json = ch.save();
        ChronicleSystem ch2 = new ChronicleSystem();
        ch2.load(json);
        check("chro: save/load", ch2.size() == ch.size() && ch2.top(1).get(0).fame == 80, "");
    }
}
