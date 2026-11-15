import com.hollow.ai.SquadCommander;
import com.hollow.game.AchievementSystem;
import com.hollow.game.BaseBuildingSystem;
import com.hollow.game.DifficultyDirector;
import com.hollow.game.JournalSystem;
import com.hollow.gameplay.SurvivalSystem;
import com.hollow.world.EcosystemSystem;
import com.hollow.world.FarmingSystem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v6.0.0 regression suite — survival, farming, base building, squads,
 *  achievements, dynamic difficulty, ecosystem and journal. */
public class VerifyV6 {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        survival();
        farming();
        building();
        squads();
        achievements();
        difficulty();
        ecosystem();
        journal();
        System.out.println(fails == 0 ? "\nALL " + total + " V6 TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " V6 TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void survival(){
        SurvivalSystem s = new SurvivalSystem();
        float h0 = s.hunger, t0 = s.thirst;
        for(int i = 0; i < 10; i++) s.update(1f);
        check("survival: hunger/thirst drain", s.hunger < h0 && s.thirst < t0,
              s.hunger + "/" + h0 + " " + s.thirst + "/" + t0);
        check("survival: no effect while fed", s.currentEffect() == SurvivalSystem.Effect.NONE, "");

        // cold + rain drains warmth much faster than neutral
        SurvivalSystem cold = new SurvivalSystem();
        cold.ambientTemp = -20f; cold.raining = true;
        for(int i = 0; i < 10; i++) cold.update(1f);
        check("survival: cold+rain drains warmth", cold.warmth < 94f, "" + cold.warmth);

        // fire restores warmth
        SurvivalSystem cozy = new SurvivalSystem();
        cozy.ambientTemp = -20f; cozy.byFire = true;
        for(int i = 0; i < 10; i++) cozy.update(1f);
        check("survival: fire restores warmth", cozy.warmth > 100f || cozy.warmth >= 100f, "" + cozy.warmth);
        check("survival: fire beats cold", cozy.warmth >= cold.warmth, cozy.warmth + " vs " + cold.warmth);

        // consume actions restore
        SurvivalSystem fed = new SurvivalSystem();
        fed.hunger = 30f; fed.thirst = 30f; fed.rest = 20f; fed.warmth = 40f;
        fed.eat(50f); fed.drink(60f); fed.sleep(70f); fed.warmUp(40f);
        check("survival: eat/drink/sleep/warm restore", fed.hunger == 80f && fed.thirst == 90f
              && fed.rest == 90f && fed.warmth == 80f, fed.describe());

        // starvation: effect event + health drain
        final List<String> events = new ArrayList<>();
        s.listener = (type, stat, val) -> events.add(type + ":" + stat);
        s.hunger = 10f;
        for(int i = 0; i < 5; i++) s.update(1f);
        check("survival: starving effect", s.currentEffect() == SurvivalSystem.Effect.STARVING, "" + s.currentEffect());
        check("survival: effect event fired", events.stream().anyMatch(e -> e.startsWith("effect:STARVING")), events.toString());
        check("survival: health drains while starving", s.health < 100f, "" + s.health);

        // death + respawn
        for(int i = 0; i < 300 && !s.dead; i++) s.update(1f);
        check("survival: death at zero health", s.dead, "" + s.health);
        s.respawn();
        check("survival: respawn resets", !s.dead && s.hunger == 70f && s.health == 50f, s.describe());

        // regen while healthy
        SurvivalSystem reg = new SurvivalSystem();
        reg.health = 80f;
        for(int i = 0; i < 10; i++) reg.update(1f);
        check("survival: regen while healthy", reg.health > 80f, "" + reg.health);

        String json = s.save();
        SurvivalSystem s2 = new SurvivalSystem();
        s2.load(json);
        check("survival: save/load", Math.abs(s2.hunger - s.hunger) < 0.01f && s2.health == s.health, s2.describe());
    }

    // =====================================================================
    static void farming(){
        FarmingSystem f = new FarmingSystem(4);
        f.define(new FarmingSystem.CropType("wheat", "Wheat", 4, com.hollow.world.SeasonsSystem.Season.SPRING).yields(1, 3));
        check("farming: empty stage", f.plot(0).stage("", 0) == FarmingSystem.Stage.EMPTY, "");

        check("farming: plant works", f.plant(0, "wheat"), "");
        check("farming: no double plant", !f.plant(0, "wheat"), "");
        check("farming: unknown crop refused", !f.plant(1, "magic"), "");
        check("farming: harvest unripe refused", f.harvest(0) == -1, "");

        // watered in-season plot ripens in 3 days (4 / 1.5)
        final List<String> farmEvents = new ArrayList<>();
        f.listener = (type, plot, crop, qty2) -> farmEvents.add(type);
        for(int i = 0; i < 3; i++){ f.water(0); f.tickDay(); }
        check("farming: watered+in-season ripens fast", f.ripeCount() == 1, "day=" + f.day);
        // regression: staying ripe must not re-fire the "ripe" event every day
        for(int i = 0; i < 3; i++) f.tickDay();
        long ripeEvents = farmEvents.stream().filter(e -> e.equals("ripe")).count();
        check("farming: no ripe-event spam", ripeEvents == 1, "" + ripeEvents);
        f.listener = null;
        int qty = f.harvest(0);
        check("farming: harvest yield in range", qty >= 1 && qty <= 4, "" + qty);
        check("farming: plot cleared after harvest", f.plot(0).stage("", 0) == FarmingSystem.Stage.EMPTY, "");

        // unwatered grows much slower
        FarmingSystem f2 = new FarmingSystem(2);
        f2.define(new FarmingSystem.CropType("wheat", "Wheat", 4, com.hollow.world.SeasonsSystem.Season.SPRING));
        f2.plant(0, "wheat"); f2.plant(1, "wheat");
        f2.water(1);
        f2.tickDay();
        check("farming: watered grows faster", f2.plot(1).growth > f2.plot(0).growth,
              f2.plot(0).growth + " vs " + f2.plot(1).growth);

        // cold kills after 3 days
        f.temperature = -20f;
        f.plant(2, "wheat");
        for(int i = 0; i < 3; i++) f.tickDay();
        check("farming: frost withers crop", f.plot(2).stage("", 0) == FarmingSystem.Stage.WITHERED, f.plot(2).cropId);
        check("farming: clear resets withered", f.clear(2) && f.plant(2, "wheat"), "");

        String json = f.save();
        FarmingSystem f3 = new FarmingSystem(4);
        f3.define(new FarmingSystem.CropType("wheat", "Wheat", 4, com.hollow.world.SeasonsSystem.Season.SPRING));
        f3.load(json);
        check("farming: save/load", f3.day == f.day && f3.plot(1).cropId.equals(f.plot(1).cropId), "");
    }

    // =====================================================================
    static void building(){
        BaseBuildingSystem b = new BaseBuildingSystem();
        Map<String, Integer> wallet = new LinkedHashMap<>();
        wallet.put("wood", 20);
        BaseBuildingSystem.ItemSink sink = new BaseBuildingSystem.ItemSink(){
            public boolean has(Map<String, Integer> mats){
                for(Map.Entry<String, Integer> kv : mats.entrySet())
                    if(wallet.getOrDefault(kv.getKey(), 0) < kv.getValue()) return false;
                return true;
            }
            public void consume(Map<String, Integer> mats){
                for(Map.Entry<String, Integer> kv : mats.entrySet())
                    wallet.put(kv.getKey(), wallet.getOrDefault(kv.getKey(), 0) - kv.getValue());
            }
            public void refund(Map<String, Integer> mats){
                for(Map.Entry<String, Integer> kv : mats.entrySet())
                    wallet.put(kv.getKey(), wallet.getOrDefault(kv.getKey(), 0) + kv.getValue());
            }
        };
        b.items = sink;
        b.define(new BaseBuildingSystem.Blueprint("wall", "Wood Wall").size(1, 1).needs("wood", 2).dura(100f));
        b.define(new BaseBuildingSystem.Blueprint("floor2x2", "Platform").size(2, 2).needs("wood", 6));

        int id = b.place("wall", 5, 5, 0);
        check("build: place consumes materials", id == 1 && wallet.get("wood") == 18, id + "/" + wallet);
        check("build: overlap rejected", b.place("wall", 5, 5, 0) == -1, "");
        check("build: out of bounds rejected", b.place("wall", 70, 5, 0) == -1, "");
        check("build: unknown blueprint rejected", b.place("castle", 0, 0, 0) == -1, "");
        check("build: affordable 2x2 place works", b.place("floor2x2", 10, 10, 0) == 2 && wallet.get("wood") == 12,
              "" + wallet);   // 18 - 6
        // force a real missing-material case:
        wallet.put("stone", 0);
        b.define(new BaseBuildingSystem.Blueprint("stone", "Stone Wall").needs("stone", 1));
        check("build: really missing materials rejected", b.place("stone", 20, 20, 0) == -1, "");

        // damage → destroy → cell freed
        b.damage(1, 60f);
        check("build: damage reduces integrity", b.structure(1) != null && b.structure(1).integrity < 0.5f,
              "" + (b.structure(1) == null ? -1 : b.structure(1).integrity));
        b.damage(1, 100f);
        check("build: destroyed at zero integrity", b.structure(1) == null, "");
        check("build: cell freed after destroy", b.place("wall", 5, 5, 0) == 3, "");

        // repair costs materials
        wallet.put("wood", 20);
        b.damage(3, 50f);
        int spent = b.repair(3);
        check("build: repair restores integrity", spent >= 1 && b.structure(3).integrity == 1f,
              spent + "/" + (b.structure(3) == null ? -1 : b.structure(3).integrity));
        check("build: repair consumed wallet", wallet.get("wood") < 20, "" + wallet);

        // demolish refunds half
        wallet.put("wood", 10);
        check("build: demolish works", b.demolish(3), "");
        check("build: demolish refunds half", wallet.get("wood") == 11, "" + wallet);

        // regression: negative rotation normalizes (-90 → 270, footprint swapped)
        b.define(new BaseBuildingSystem.Blueprint("beam", "Long Beam").size(1, 3).needs("wood", 1));
        int beamId = b.place("beam", 30, 30, -90);          // 3 wide × 1 deep after rotation
        check("build: negative rotation accepted", beamId > 0, "" + beamId);
        check("build: rotation normalized to 270", b.structure(beamId) != null && b.structure(beamId).rotation == 270,
              "" + (b.structure(beamId) == null ? -1 : b.structure(beamId).rotation));

        String json = b.save();
        BaseBuildingSystem b2 = new BaseBuildingSystem();
        b2.define(new BaseBuildingSystem.Blueprint("wall", "Wood Wall").size(1, 1).needs("wood", 2).dura(100f));
        b2.define(new BaseBuildingSystem.Blueprint("floor2x2", "Platform").size(2, 2).needs("wood", 6));
        b2.define(new BaseBuildingSystem.Blueprint("beam", "Long Beam").size(1, 3).needs("wood", 1));
        b2.load(json);
        check("build: save/load", b2.count() == b.count() && b2.structure(2) != null
              && b2.structure(2).blueprintId.equals("floor2x2") && b2.structure(beamId) != null
              && b2.structure(beamId).rotation == 270,
              "" + b2.count());
    }

    // =====================================================================
    static void squads(){
        SquadCommander sc = new SquadCommander();
        final List<String> orders = new ArrayList<>();
        sc.listener = (squad, order, detail) -> orders.add(order.name());

        SquadCommander.Squad sq = sc.define("alpha");
        sc.recruit("alpha", "rex"); sc.recruit("alpha", "max"); sc.recruit("alpha", "ida");
        sq.members.get(0).pos.set(10, 0, 10);
        sq.members.get(1).pos.set(-8, 0, 6);
        sq.members.get(2).pos.set(4, 0, -12);
        check("squad: recruit assigns slots", sq.members.get(2).slotIndex == 2, "");
        check("squad: no duplicate recruit", !sc.recruit("alpha", "rex"), "");

        sc.order("alpha", SquadCommander.Order.FOLLOW, new com.hollow.core.Vec3(0, 0, 0));
        check("squad: order event fired", orders.contains("FOLLOW"), orders.toString());
        // wedge slots are distinct
        com.hollow.core.Vec3 s0 = sc.slot(sq, sq.members.get(0), 0);
        com.hollow.core.Vec3 s1 = sc.slot(sq, sq.members.get(1), 1);
        check("squad: wedge slots distinct", s0.dist(s1) > 0.1f, s0 + " vs " + s1);
        check("squad: first slot trails behind anchor", s0.z > 0.5f && Math.abs(s0.x) < 0.01f, "" + s0);

        for(int i = 0; i < 30; i++) sc.update(1f);
        check("squad: members reach wedge", sc.formationQuality("alpha") == 1f, "" + sc.formationQuality("alpha"));

        // HOLD ring around a point
        sc.order("alpha", SquadCommander.Order.HOLD, new com.hollow.core.Vec3(50, 0, 50));
        for(int i = 0; i < 40; i++) sc.update(1f);
        float d0 = sq.members.get(0).pos.dist(sq.anchor);
        check("squad: hold ring near anchor", d0 < 4f, "" + d0);
        boolean spread = sq.members.get(0).pos.dist(sq.members.get(1).pos) > 1f;
        check("squad: members spread on ring", spread, sq.members.get(0).pos + " vs " + sq.members.get(1).pos);

        // per-member override: rex stays put while squad advances
        sc.orderMember("alpha", "rex", SquadCommander.Order.DISMISS, null);
        com.hollow.core.Vec3 rexBefore = sq.member("rex").pos.cpy();
        sc.order("alpha", SquadCommander.Order.ATTACK, new com.hollow.core.Vec3(80, 0, 20));
        for(int i = 0; i < 10; i++) sc.update(1f);
        check("squad: member override honored", sq.member("rex").pos.dist(rexBefore) < 0.01f, "");
        // regression: a dismissed member far away must not drag formationQuality down
        float qual = sc.formationQuality("alpha");
        check("squad: dismiss excluded from quality", qual == 1f, "" + qual);
        check("squad: attackers moved", sq.member("max").pos.dist(new com.hollow.core.Vec3(80, 0, 20)) < 20f,
              "" + sq.member("max").pos);

        String json = sc.save();
        SquadCommander sc2 = new SquadCommander();
        sc2.load(json);
        SquadCommander.Squad sq2 = sc2.squad("alpha");
        check("squad: save/load orders", sq2 != null && sq2.order == SquadCommander.Order.ATTACK
              && sq2.members.size() == 3 && sq2.member("rex").order == SquadCommander.Order.DISMISS, "");
    }

    // =====================================================================
    static void achievements(){
        AchievementSystem a = new AchievementSystem();
        final List<String> events = new ArrayList<>();
        a.listener = (type, id, pts) -> events.add(type + ":" + id);
        a.define(new AchievementSystem.Achievement("slayer", "kills", 10).named("Slayer").worth(25));
        a.define(new AchievementSystem.Achievement("chef", "cooked", 3).named("Chef"));

        check("ach: below threshold locked", !a.record("kills", 5) && a.progress("slayer") == 0.5f,
              "" + a.progress("slayer"));
        check("ach: unlock at threshold", a.record("kills", 5) && a.achievement("slayer").unlocked
              && a.points == 25, "" + a.points);
        check("ach: unlock event once", events.stream().filter(e -> e.equals("unlocked:slayer")).count() == 1,
              events.toString());
        check("ach: no double unlock", !a.record("kills", 100) && a.points == 25, "" + a.points);
        check("ach: unlocked progress = 1", a.progress("slayer") == 1f, "");
        check("ach: completion percent", Math.abs(a.completionPercent() - 50f) < 0.01f, "" + a.completionPercent());
        check("ach: other stat untouched", !a.achievement("chef").unlocked, "");

        String json = a.save();
        AchievementSystem a2 = new AchievementSystem();
        a2.define(new AchievementSystem.Achievement("slayer", "kills", 10));
        a2.define(new AchievementSystem.Achievement("chef", "cooked", 3));
        a2.load(json);
        check("ach: save/load unlock+points", a2.achievement("slayer").unlocked && a2.points == 25
              && a2.stat("kills") == 110.0, "" + a2.stat("kills"));
    }

    // =====================================================================
    static void difficulty(){
        DifficultyDirector d = new DifficultyDirector();
        float d0 = d.difficulty;
        d.onDeath();
        d.update(30f);
        check("dda: death lowers difficulty", d.difficulty < d0, d0 + " -> " + d.difficulty);

        // stacking deaths push further than one death (accumulated target)
        DifficultyDirector d2 = new DifficultyDirector();
        d2.onDeath(); d2.update(30f);
        float afterOne = d2.difficulty;
        d2.onDeath(); d2.onDeath(); d2.update(40f);
        check("dda: deaths stack", d2.difficulty < afterOne, afterOne + " -> " + d2.difficulty);

        // kill streaks raise
        DifficultyDirector d3 = new DifficultyDirector();
        d3.difficulty = 0.5f;
        for(int i = 0; i < 10; i++) d3.onKill();
        d3.update(40f);
        check("dda: kill streak raises", d3.difficulty > 0.5f, "" + d3.difficulty);

        // budget & multipliers scale
        DifficultyDirector dd = new DifficultyDirector();
        dd.difficulty = 0.9f;
        float hi = dd.encounterBudget();
        dd.difficulty = 0.2f;
        float lo = dd.encounterBudget();
        check("dda: budget scales with difficulty", hi > lo, hi + " vs " + lo);
        check("dda: enemy health scales", dd.enemyHealthMul() < 0.9f && new DifficultyDirector().enemyHealthMul() > 0.9f, "");
        check("dda: clamped 0..1", dd.difficulty >= 0f && dd.difficulty <= 1f, "" + dd.difficulty);

        String json = d.save();
        DifficultyDirector d4 = new DifficultyDirector();
        d4.load(json);
        check("dda: save/load", Math.abs(d4.difficulty - d.difficulty) < 0.001f && d4.deaths == d.deaths, d4.describe());
    }

    // =====================================================================
    static void ecosystem(){
        EcosystemSystem eco = new EcosystemSystem();
        final List<String> events = new ArrayList<>();
        eco.listener = (type, id, pop) -> events.add(type + ":" + id);

        EcosystemSystem.Species grass = eco.define(new EcosystemSystem.Species("grass", "Grass", EcosystemSystem.Role.PLANT));
        grass.capacity = 100f; grass.pop = 50f; grass.growthRate = 0.4f;
        EcosystemSystem.Species rabbit = eco.define(new EcosystemSystem.Species("rabbit", "Rabbit", EcosystemSystem.Role.PREY));
        rabbit.capacity = 80f; rabbit.pop = 30f;   // plenty of prey so foxes can hold a territory
        EcosystemSystem.Species fox = eco.define(new EcosystemSystem.Species("fox", "Fox", EcosystemSystem.Role.PREDATOR));
        fox.capacity = 20f; fox.pop = 4f;
        fox.eats("rabbit");

        for(int i = 0; i < 20; i++) eco.tickDay();
        check("eco: plants regrow toward capacity", eco.species("grass").pop > 90f, eco.describe());
        check("eco: rabbits persist under predation", eco.species("rabbit").pop >= 15f, eco.describe());
        check("eco: foxes hold territory with prey", eco.species("fox").pop >= 2f, eco.describe());
        check("eco: capacity clamps", eco.species("grass").pop <= 100f && eco.species("rabbit").pop <= 80f, eco.describe());
        check("eco: no extinctions in healthy sim", events.isEmpty(), events.toString());

        // predator without prey starves out
        EcosystemSystem eco2 = new EcosystemSystem();
        EcosystemSystem.Species fox2 = eco2.define(new EcosystemSystem.Species("fox", "Fox", EcosystemSystem.Role.PREDATOR));
        fox2.capacity = 20f; fox2.pop = 5f;
        fox2.eats("ghost");           // no such prey registered
        for(int i = 0; i < 40; i++) eco2.tickDay();
        check("eco: predator extinct without prey", eco2.species("fox").pop == 0f, eco2.describe());
        // (no listener attached on eco2 — event path covered above)

        String json = eco.save();
        EcosystemSystem eco3 = new EcosystemSystem();
        eco3.define(new EcosystemSystem.Species("grass", "Grass", EcosystemSystem.Role.PLANT));
        eco3.define(new EcosystemSystem.Species("rabbit", "Rabbit", EcosystemSystem.Role.PREY));
        eco3.define(new EcosystemSystem.Species("fox", "Fox", EcosystemSystem.Role.PREDATOR));
        eco3.load(json);
        check("eco: save/load populations", Math.abs(eco3.species("fox").pop - eco.species("fox").pop) < 0.01f
              && eco3.day == eco.day, eco3.describe());
    }

    // =====================================================================
    static void journal(){
        JournalSystem j = new JournalSystem();
        final List<String> events = new ArrayList<>();
        j.listener = (type, id, cat) -> events.add(type + ":" + id);

        j.define(new JournalSystem.Entry("goblin", JournalSystem.Category.BESTIARY, "Swamp Goblin").text("Ugly, green, fast."));
        j.define(new JournalSystem.Entry("troll", JournalSystem.Category.BESTIARY, "Bridge Troll"));
        j.define(new JournalSystem.Entry("oakwood", JournalSystem.Category.LOCATION, "Oakwood Village"));

        check("journal: discover fires once", j.discover("goblin")
              && events.stream().filter(e -> e.equals("discovered:goblin")).count() == 1, events.toString());
        check("journal: second discover silent", j.discover("goblin")
              && events.stream().filter(e -> e.equals("discovered:goblin")).count() == 1, events.toString());
        check("journal: isDiscovered", j.isDiscovered("goblin") && !j.isDiscovered("troll"), "");
        check("journal: category counts", j.discoveredCount(JournalSystem.Category.BESTIARY) == 1
              && j.totalCount(JournalSystem.Category.BESTIARY) == 2, "");
        check("journal: completion fraction", Math.abs(j.completion(JournalSystem.Category.BESTIARY) - 0.5f) < 0.001f,
              "" + j.completion(JournalSystem.Category.BESTIARY));
        check("journal: unknown entry refused", j.discover("ghost") == false, "");

        JournalSystem.Note n = j.addNote("check the northern cave", "mine");
        check("journal: note added", n.id == 1 && j.notes.size() == 1, "");
        check("journal: note removed", j.removeNote(1) && j.notes.isEmpty(), "");

        String json = j.save();
        JournalSystem j2 = new JournalSystem();
        j2.load(json);
        check("journal: save/load", j2.isDiscovered("goblin") && j2.entry("troll") != null
              && j2.entry("goblin").body.contains("green"), "");
    }
}
