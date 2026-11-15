import com.hollow.game.BountySystem;
import com.hollow.game.DiplomacySystem;
import com.hollow.gameplay.CookingSystem;
import com.hollow.gameplay.SkillTreeSystem;
import com.hollow.gameplay.SurvivalSystem;
import com.hollow.world.CaravanSystem;
import com.hollow.world.NpcScheduler;
import com.hollow.world.SettlementSystem;
import com.hollow.world.TerritorySystem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v7.0.0 regression suite — settlements, caravans, skill tree, diplomacy,
 *  territory, bounties, NPC schedules and cooking. */
public class VerifyV7 {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        settlements();
        caravans();
        skills();
        diplomacy();
        territory();
        bounties();
        schedules();
        cooking();
        System.out.println(fails == 0 ? "\nALL " + total + " V7 TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " V7 TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void settlements(){
        SettlementSystem ss = new SettlementSystem();
        final List<String> events = new ArrayList<>();
        ss.listener = (type, id, detail) -> events.add(type);

        SettlementSystem.Settlement oak = ss.found("oak", "Oakstead");
        check("settle: founded with defaults", oak.pop == 4 && oak.housing() == 6 && oak.food >= 20f, ss.describe("oak"));

        // surplus → growth (keep pop above housing so the production probe doesn't grow it)
        oak.pop = 20;
        float food0 = oak.food;
        ss.tickDay();
        check("settle: farms produce food", oak.food > food0 - oak.pop * oak.foodPerHead, "" + oak.food);
        oak.pop = 4;
        oak.food = 100f;
        ss.tickDay();
        check("settle: surplus grows pop", oak.pop == 5 && events.contains("grown"), ss.describe("oak"));

        // housing caps growth
        while(oak.pop < oak.housing()){ oak.food = 100f; ss.tickDay(); }
        check("settle: housing caps pop", oak.pop == oak.housing(), oak.pop + "/" + oak.housing());

        // famine when no food and no farmers
        SettlementSystem.Settlement poor = ss.found("poor", "Dusthole");
        poor.farmers = 0; poor.food = 0.1f;
        events.clear();
        ss.tickDay();
        check("settle: famine shrinks pop", poor.pop == 3 && events.contains("famine"), ss.describe("poor"));

        // construction: refused without materials, then builds a house
        poor.wood = 2f;                                  // pin the wallet for the refusal probe
        check("settle: queue refused without wood", !ss.queueBuilding("poor", "house1", 10, 0), "" + poor.wood);
        poor.wood = 15f;
        check("settle: queue accepted", ss.queueBuilding("poor", "house1", 10, 0), "" + poor.wood);
        check("settle: wood paid up front", poor.wood == 5f, "" + poor.wood);
        for(int i = 0; i < 3; i++) ss.tickDay();
        check("settle: house built after days", poor.buildings.contains("house1") && poor.houses == 2
              && events.contains("built"), ss.describe("poor"));
        check("settle: house adds housing + farmers", poor.housing() == 12 && poor.farmers == 1, ss.describe("poor"));

        // regression: queueing a second building mid-construction must not restart the first
        SettlementSystem.Settlement hamlet = ss.found("ham", "Hamlet");
        hamlet.wood = 40f;
        ss.queueBuilding("ham", "house1", 10, 0);
        ss.tickDay();                                   // day 1 of house1's construction
        ss.queueBuilding("ham", "house2", 10, 0);       // must NOT reset house1's progress
        for(int i = 0; i < 2; i++) ss.tickDay();        // house1 reaches day 3 → built on schedule
        check("settle: mid-queue doesn't restart work", hamlet.buildings.contains("house1")
              && !hamlet.buildings.contains("house2"), ss.describe("ham"));

        String json = ss.save();
        SettlementSystem ss2 = new SettlementSystem();
        ss2.load(json);
        SettlementSystem.Settlement oak2 = ss2.settlement("oak");
        check("settle: save/load", oak2 != null && oak2.pop == oak.pop && oak2.houses == oak.houses
              && ss2.day == ss.day, "");
    }

    // =====================================================================
    static void caravans(){
        CaravanSystem cs = new CaravanSystem();
        final List<String> events = new ArrayList<>();
        cs.listener = (type, id, detail) -> events.add(type);

        final Map<String, Float> stocks = new LinkedHashMap<>();
        final Map<String, Integer> treasuries = new LinkedHashMap<>();
        cs.market = new CaravanSystem.Market(){
            public int gold(String id){ return treasuries.getOrDefault(id, 0); }
            public void spend(String id, int amt){ treasuries.put(id, treasuries.getOrDefault(id, 0) - amt); }
            public void earn(String id, int amt){ treasuries.put(id, treasuries.getOrDefault(id, 0) + amt); }
            public float stockpile(String id, String item){ return stocks.getOrDefault(id + ":" + item, 0f); }
        };

        cs.define(new CaravanSystem.Route("oak-iron", "oak", "iron").travelDays(3f));
        check("caravan: route defined", cs.route("oak-iron") != null, "");

        Map<String, Integer> cargo = new LinkedHashMap<>();
        cargo.put("wheat", 10);
        check("caravan: send refused without gold", cs.send("oak-iron", cargo, 1000) == null, "" + cs.gold);
        CaravanSystem.Caravan c = cs.send("oak-iron", cargo, 10);
        check("caravan: sent and paid", c != null && c.id == 1 && cs.gold == 90, "" + cs.gold);
        check("caravan: unknown route refused", cs.send("nowhere", cargo, 1) == null, "");

        // scarcity pricing: starved market pays more than a rich one
        stocks.put("iron:wheat", 0f);
        float scarce = cs.priceAt("iron", "wheat", 10);
        stocks.put("iron:wheat", 50f);
        float rich = cs.priceAt("iron", "wheat", 10);
        check("caravan: scarcity drives price", scarce > rich, scarce + " vs " + rich);

        // keep the destination starved so the sale math below is exact
        stocks.put("iron:wheat", 0f);
        for(int i = 0; i < 3; i++) cs.tickDay();
        check("caravan: arrives after travel days", c.state == CaravanSystem.State.ARRIVED && cs.arrivedCount() == 1,
              "" + c.state);
        check("caravan: profit booked", c.profit == saleOf(c) - 10 && cs.gold == 90 + saleOf(c),
              "profit=" + c.profit + " gold=" + cs.gold);
        check("caravan: destination paid", treasuries.getOrDefault("iron", 0) == -saleOf(c),
              "" + treasuries.get("iron"));

        // robbery on a dangerous road
        CaravanSystem cs2 = new CaravanSystem();
        cs2.define(new CaravanSystem.Route("bad", "a", "b").travelDays(1f).danger(1f));
        CaravanSystem.Caravan c2 = cs2.send("bad", cargo, 1);
        cs2.tickDay();
        check("caravan: robbed on dangerous route", c2.state == CaravanSystem.State.ROBBED, "" + c2.state);

        String json = cs.save();
        CaravanSystem cs3 = new CaravanSystem();
        cs3.load(json);
        check("caravan: save/load", cs3.caravan(1) != null && cs3.caravan(1).state == CaravanSystem.State.ARRIVED
              && cs3.gold == cs.gold, "" + cs3.gold);

        // regression: starved market now pays the documented 1.6x premium (old formula was inert at 1.0x)
        check("caravan: starved pays premium", Math.abs(scarce - 32f) < 0.01f, "" + scarce);

        // regression: multi-line fractional sale truncates once, not per cargo line (old: 20 gold)
        CaravanSystem cs4 = new CaravanSystem();
        cs4.market = cs.market;
        cs4.define(new CaravanSystem.Route("frac", "oak", "iron").travelDays(1f));
        Map<String, Integer> frac = new LinkedHashMap<>();
        frac.put("ore", 10);
        frac.put("stone", 10);
        stocks.put("iron:ore", 38f);
        stocks.put("iron:stone", 38f);
        CaravanSystem.Caravan cf = cs4.send("frac", frac, 1);
        cs4.tickDay();
        check("caravan: fractional sale truncated once", cf.state == CaravanSystem.State.ARRIVED
              && cf.profit == 20 && cs4.gold == 120, "gold=" + cs4.gold + " profit=" + cf.profit);

        // regression: a route withdrawn mid-journey loses the caravan with an event (old: stuck forever)
        CaravanSystem cs5 = new CaravanSystem();
        cs5.listener = (type, id, detail) -> events.add(type);
        cs5.define(new CaravanSystem.Route("tmp", "a", "b").travelDays(2f));
        CaravanSystem.Caravan cl = cs5.send("tmp", cargo, 1);
        cs5.routes.remove("tmp");
        cs5.tickDay();
        check("caravan: route withdrawn loses caravan", cl.state == CaravanSystem.State.LOST
              && events.contains("vanished") && cs5.arrivedCount() == 0, "" + cl.state);
        cs5.tickDay();
        check("caravan: lost caravan stays lost", cl.state == CaravanSystem.State.LOST, "" + cl.state);
        String jsonL = cs5.save();
        CaravanSystem cs6 = new CaravanSystem();
        cs6.load(jsonL);
        check("caravan: lost state round-trips", cs6.caravan(cl.id) != null
              && cs6.caravan(cl.id).state == CaravanSystem.State.LOST, "");
    }
    private static int saleOf(CaravanSystem.Caravan c){
        int units = 0;
        for(Map.Entry<String, Integer> kv : c.cargo.entrySet()) units += kv.getValue();
        return Math.round(units * 2f * 1.6f);   // stockpile 0 → starved premium 1.6x → units x 3.2
    }

    // =====================================================================
    static void skills(){
        SkillTreeSystem st = new SkillTreeSystem();
        final List<String> events = new ArrayList<>();
        st.listener = (type, id, pts) -> events.add(type + ":" + id);

        st.define(new SkillTreeSystem.Node("root", "Basics").tier(0).costs(1).effect("damage", 0.1f));
        st.define(new SkillTreeSystem.Node("blade", "Blade Master").tier(1).costs(2)
              .requires("root").effect("damage", 0.15f));
        st.define(new SkillTreeSystem.Node("orphan", "Orphan").tier(1).costs(1).requires("ghost"));

        check("skill: child locked without prereq", !st.unlock("blade"), "points=" + st.points);
        check("skill: missing prereq refused", !st.unlock("orphan"), "");
        check("skill: no points refused", !st.unlock("root"), "points=" + st.points);

        st.grant(3);
        check("skill: grant adds points", st.points == 3, "" + st.points);
        check("skill: root unlock", st.unlock("root") && st.points == 2 && events.contains("unlocked:root"),
              events.toString());
        check("skill: child unlock after prereq", st.unlock("blade") && st.points == 0, "" + st.points);
        check("skill: bonuses aggregate", Math.abs(st.bonus("damage") - 0.25f) < 0.001f, "" + st.bonus("damage"));
        check("skill: spent tracked", st.spentPoints == 3, "" + st.spentPoints);

        int refunded = st.respec();
        check("skill: respec refunds all", refunded == 3 && st.points == 3 && st.unlockedCount() == 0
              && st.bonus("damage") == 0f, refunded + "/" + st.unlockedCount());

        st.unlock("root");
        String json = st.save();
        SkillTreeSystem st2 = new SkillTreeSystem();
        st2.define(new SkillTreeSystem.Node("root", "Basics").costs(1));
        st2.define(new SkillTreeSystem.Node("blade", "Blade Master").costs(2).requires("root"));
        st2.load(json);
        check("skill: save/load", st2.node("root").unlocked && !st2.node("blade").unlocked
              && st2.points == st.points, "" + st2.points);
    }

    // =====================================================================
    static void diplomacy(){
        DiplomacySystem dp = new DiplomacySystem();
        final List<String> events = new ArrayList<>();
        dp.listener = (type, a, b, detail) -> events.add(type);

        check("diplo: trade below threshold refused", !dp.propose("rome", "carthage", DiplomacySystem.Treaty.TRADE),
              "" + dp.relation("rome", "carthage").value);
        dp.shift("rome", "carthage", 25);
        check("diplo: shift is symmetric", dp.relation("carthage", "rome").value == 25, "");
        check("diplo: trade signed at threshold", dp.propose("rome", "carthage", DiplomacySystem.Treaty.TRADE)
              && dp.hasTreaty("carthage", "rome", DiplomacySystem.Treaty.TRADE), "");
        check("diplo: duplicate treaty refused", !dp.propose("rome", "carthage", DiplomacySystem.Treaty.TRADE), "");
        check("diplo: alliance needs more warmth", !dp.propose("rome", "carthage", DiplomacySystem.Treaty.ALLIANCE),
              "" + dp.relation("rome", "carthage").value);

        dp.declareWar("rome", "carthage");
        check("diplo: war declared", dp.atWar("rome", "carthage")
              && dp.relation("rome", "carthage").value <= -40, "" + dp.relation("rome", "carthage").value);
        check("diplo: war breaks treaties", !dp.hasTreaty("rome", "carthage", DiplomacySystem.Treaty.TRADE)
              && events.contains("treaties_broken"), events.toString());
        check("diplo: treaty during war refused", !dp.propose("rome", "carthage", DiplomacySystem.Treaty.PEACE), "");

        dp.shift("rome", "carthage", 30);
        check("diplo: peace negotiated", dp.makePeace("rome", "carthage") && !dp.atWar("rome", "carthage"), "");

        // regression: a faction has no relation with itself
        dp.declareWar("rome", "rome");
        dp.shift("rome", "rome", 50);
        check("diplo: self-relations ignored", !dp.atWar("rome", "rome")
              && !dp.relations.containsKey("rome|rome") && !dp.propose("rome", "rome", DiplomacySystem.Treaty.TRADE), "");

        // drift: alliance warms, neutral decays
        dp.shift("gaul", "rome", 70);
        dp.propose("gaul", "rome", DiplomacySystem.Treaty.ALLIANCE);
        int gaul0 = dp.relation("gaul", "rome").value;
        dp.shift("greece", "rome", 8);
        int greece0 = dp.relation("greece", "rome").value;
        dp.tickDay();
        check("diplo: alliance drifts warmer", dp.relation("gaul", "rome").value == gaul0 + 2, "");
        check("diplo: neutral decays to zero", dp.relation("greece", "rome").value == greece0 - 1, "");

        String json = dp.save();
        DiplomacySystem dp2 = new DiplomacySystem();
        dp2.load(json);
        check("diplo: save/load", dp2.atWar("gaul", "rome") == false
              && dp2.hasTreaty("gaul", "rome", DiplomacySystem.Treaty.ALLIANCE)
              && dp2.relation("rome", "carthage").value == dp.relation("rome", "carthage").value, "");
    }

    // =====================================================================
    static void territory(){
        TerritorySystem ts = new TerritorySystem();
        final List<String> events = new ArrayList<>();
        ts.listener = (type, region, faction) -> events.add(type + ":" + faction);

        ts.define(new TerritorySystem.Region("valley", "Green Valley").at(0, 0, 0).range(80f));
        check("terr: empty region unowned", ts.ownerOf("valley").isEmpty()
              && ts.controlOf("valley", "red") == 0f, "");

        ts.pushPresence("valley", "red", 40f);
        check("terr: presence grows control", ts.controlOf("valley", "red") == 40f, "");
        ts.pushPresence("valley", "blue", 10f);
        check("terr: rivals bleed from presence", ts.controlOf("valley", "red") < 40f
              && ts.controlOf("valley", "blue") == 10f, "" + ts.controlOf("valley", "red"));
        // regression: blank faction ids must not pollute the control map
        ts.pushPresence("valley", "", 25f);
        check("terr: blank faction ignored", ts.ownerOf("valley").isEmpty()
              && !ts.region("valley").control.containsKey(""), "");

        // push red to capture
        while(ts.controlOf("valley", "red") < 70f) ts.pushPresence("valley", "red", 2f);
        ts.tickDay();
        check("terr: capture on threshold + lead", "red".equals(ts.ownerOf("valley"))
              && events.contains("captured:red"), events.toString());

        // unknown region safe
        ts.pushPresence("nowhere", "red", 10f);
        check("terr: unknown region ignored", ts.ownerOf("nowhere").isEmpty(), "");

        String json = ts.save();
        TerritorySystem ts2 = new TerritorySystem();
        ts2.load(json);
        check("terr: save/load", "red".equals(ts2.ownerOf("valley"))
              && Math.abs(ts2.controlOf("valley", "red") - ts.controlOf("valley", "red")) < 0.01f, "");
    }

    // =====================================================================
    static void bounties(){
        BountySystem bs = new BountySystem();
        final List<String> events = new ArrayList<>();
        bs.listener = (type, id, detail) -> events.add(type);

        BountySystem.Bounty b1 = bs.post("Bandit King", 250, 3, 5);
        BountySystem.Bounty b2 = bs.post("Rats in cellar", 10, 1, 2);
        check("bounty: posted open", b1.state == BountySystem.State.OPEN && bs.openBounties().size() == 2, "");

        check("bounty: claim works", bs.claim(b1.id, "hana") && b1.state == BountySystem.State.CLAIMED, "");
        check("bounty: second claim refused", !bs.claim(b1.id, "mori"), "");
        check("bounty: success pays + tracks", bs.complete(b1.id, true) && b1.state == BountySystem.State.DONE
              && bs.completions("hana") == 1 && bs.earned("hana") == 250, "" + bs.earned("hana"));
        check("bounty: fail returns to board", bs.claim(b2.id, "mori") && bs.complete(b2.id, false)
              && b2.state == BountySystem.State.OPEN && bs.completions("mori") == 0, "");

        bs.tickDay(); bs.tickDay(); bs.tickDay();               // b2 expires at day > 2
        check("bounty: stale bounty expires", b2.state == BountySystem.State.EXPIRED
              && events.contains("expired"), events.toString());

        // regression: a claimed bounty must NOT expire out from under its hunter
        BountySystem.Bounty b3 = bs.post("Ghost of the marsh", 80, 2, 1);   // expires at day > 4
        bs.claim(b3.id, "kai");
        bs.tickDay(); bs.tickDay();
        check("bounty: claimed survives expiry", b3.state == BountySystem.State.CLAIMED, "" + b3.state);

        String json = bs.save();
        BountySystem bs2 = new BountySystem();
        bs2.load(json);
        check("bounty: save/load", bs2.earned("hana") == 250 && bs2.bounties.get(b1.id) != null
              && bs2.bounties.get(b1.id).state == BountySystem.State.DONE, "");
    }

    // =====================================================================
    static void schedules(){
        NpcScheduler sc = new NpcScheduler();
        final List<String> transitions = new ArrayList<>();
        sc.listener = (npc, from, to, hour) -> transitions.add(npc + ":" + to.name() + "@" + hour);

        NpcScheduler.Profile smith = sc.register(new NpcScheduler.Profile("smith")
            .homeAt(10, 0, 10).workAt(0, 0, 0).leisureAt(5, 0, 5).shift(8, 17));
        sc.register(new NpcScheduler.Profile("farmer").homeAt(-10, 0, 4).workAt(-20, 0, 0).shift(6, 14));

        check("sched: night is sleep", sc.activityAt(smith, 23) == NpcScheduler.Activity.SLEEP
              && sc.activityAt(smith, 3) == NpcScheduler.Activity.SLEEP, "");
        check("sched: shift is work", sc.activityAt(smith, 12) == NpcScheduler.Activity.WORK, "");
        check("sched: off-hours leisure", sc.activityAt(smith, 20) == NpcScheduler.Activity.LEISURE, "");

        // regression: night shifts that wrap midnight (22..06) must resolve as WORK
        NpcScheduler.Profile guard = sc.register(new NpcScheduler.Profile("guard")
            .homeAt(3, 0, 3).workAt(1, 0, 1).shift(22, 6));
        check("sched: night shift is work", sc.activityAt(guard, 23) == NpcScheduler.Activity.WORK
              && sc.activityAt(guard, 2) == NpcScheduler.Activity.WORK, "");
        check("sched: guard off-duty by day", sc.activityAt(guard, 12) == NpcScheduler.Activity.LEISURE, "");

        sc.update(12);
        check("sched: work anchor applied", sc.current.get("smith") == NpcScheduler.Activity.WORK
              && sc.anchorOf("smith").dist(smith.work) < 0.01f, "" + sc.anchorOf("smith"));
        sc.update(20);
        check("sched: leisure anchor applied", sc.current.get("smith") == NpcScheduler.Activity.LEISURE
              && sc.anchorOf("smith").dist(smith.leisure) < 0.01f, "");
        sc.update(23);
        check("sched: sleep anchor applied", sc.current.get("smith") == NpcScheduler.Activity.SLEEP
              && sc.anchorOf("smith").dist(smith.home) < 0.01f, "");
        check("sched: transitions logged", transitions.contains("smith:WORK@12")
              && transitions.contains("smith:LEISURE@20") && transitions.contains("smith:SLEEP@23"),
              transitions.toString());

        sc.update(9);
        check("sched: working() lists workers", sc.working().contains("smith") && sc.working().contains("farmer"),
              sc.working().toString());

        String json = sc.save();
        NpcScheduler sc2 = new NpcScheduler();
        sc2.load(json);
        check("sched: save/load", sc2.profile("smith") != null && sc2.profile("smith").workStart == 8
              && sc2.profile("farmer").workEnd == 14, "");
    }

    // =====================================================================
    static void cooking(){
        CookingSystem ck = new CookingSystem();
        final List<String> events = new ArrayList<>();
        ck.listener = (type, recipe, quality) -> events.add(type + (quality.isEmpty() ? "" : ":" + quality));

        Map<String, Integer> pantryMap = new LinkedHashMap<>();
        pantryMap.put("meat", 10);
        pantryMap.put("herbs", 10);
        CookingSystem.Pantry pantry = new CookingSystem.Pantry(){
            public boolean has(Map<String, Integer> need){
                for(Map.Entry<String, Integer> kv : need.entrySet())
                    if(pantryMap.getOrDefault(kv.getKey(), 0) < kv.getValue()) return false;
                return true;
            }
            public void consume(Map<String, Integer> need){
                for(Map.Entry<String, Integer> kv : need.entrySet())
                    pantryMap.put(kv.getKey(), pantryMap.getOrDefault(kv.getKey(), 0) - kv.getValue());
            }
        };
        ck.pantry = pantry;
        ck.define(new CookingSystem.Recipe("stew", "Hunter's Stew").needs("meat", 2).needs("herbs", 1)
              .feeds(30f, 5f, 8f));

        check("cook: unlearned refused", ck.cook("stew", 1f, 1f) == null, "");
        check("cook: unknown refused", !ck.learn("dragon"), "");
        check("cook: learn works", ck.learn("stew") && !ck.learn("stew"), "");

        CookingSystem.Dish d1 = ck.cook("stew", 1f, 1f);
        check("cook: high skill cooks", d1 != null && (d1.quality == CookingSystem.Quality.EXCELLENT
              || d1.quality == CookingSystem.Quality.GOOD), "" + d1.quality);
        check("cook: quality scales feed", d1.hunger >= 30f * 1.2f, "" + d1.hunger);
        check("cook: pantry consumed", pantryMap.get("meat") == 8 && pantryMap.get("herbs") == 9,
              pantryMap.toString());

        CookingSystem.Dish d2 = ck.cook("stew", 0f, 0f);
        check("cook: no skill → poor dish", d2 != null && d2.quality == CookingSystem.Quality.POOR
              && Math.abs(d2.hunger - 30f * 0.6f) < 0.01f, "" + d2.quality + "/" + d2.hunger);

        // missing ingredients blocked
        pantryMap.put("meat", 1);
        check("cook: missing ingredients refused", ck.cook("stew", 1f, 1f) == null
              && events.contains("missing"), events.toString());

        // serve wires to survival
        SurvivalSystem sv = new SurvivalSystem();
        sv.hunger = 40f;
        ck.serve(d1, sv);
        check("cook: serve feeds survival", sv.hunger > 60f, "" + sv.hunger);

        String json = ck.save();
        CookingSystem ck2 = new CookingSystem();
        ck2.define(new CookingSystem.Recipe("stew", "Hunter's Stew"));
        ck2.load(json);
        check("cook: save/load known recipes", ck2.knows("stew") && !ck2.knows("dragon"), "");
    }
}
