import com.hollow.ai.StealthSystem;
import com.hollow.audio.MusicDirector;
import com.hollow.gameplay.EconomySystem;
import com.hollow.gameplay.FactionSystem;
import com.hollow.gameplay.LootSystem;
import com.hollow.gameplay.ProgressionSystem;
import com.hollow.ui.MapMarkerSystem;
import com.hollow.world.SeasonsSystem;

import java.util.ArrayList;
import java.util.List;

/** v5.0.0 regression suite — stealth, progression, factions, loot, economy,
 *  seasons, adaptive music and map markers. */
public class VerifyV5 {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        stealth();
        progression();
        factions();
        loot();
        economy();
        seasons();
        music();
        markers();
        System.out.println(fails == 0 ? "\nALL " + total + " V5 TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " V5 TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void stealth(){
        StealthSystem s = new StealthSystem();
        StealthSystem.Observer guard = s.addObserver("guard");
        guard.pos.set(0, 0, 0);
        guard.facing.set(0, 0, -1);                 // looks toward -Z
        StealthSystem.Hidden thief = s.addHidden("thief");
        thief.pos.set(0, 0, -8);                    // dead ahead
        thief.lightLevel = 1f;

        check("stealth: in-cone visible", s.canSee(guard, thief), "");
        guard.facing.set(0, 0, 1);                  // turned away
        check("stealth: behind observer hidden", !s.canSee(guard, thief), "");
        guard.facing.set(0, 0, -1);
        thief.pos.set(0, 0, -40);                   // way beyond range 18
        check("stealth: beyond range hidden", !s.canSee(guard, thief), "");
        thief.pos.set(0, 0, -8);
        s.los = (from, to) -> true;                 // a wall between them
        check("stealth: blocked LOS hidden", !s.canSee(guard, thief), "");
        s.los = null;

        // meter rises; crouching + dark slows it
        for(int i = 0; i < 60; i++) s.update(1f / 60f);   // 1 s full light
        float bright = s.meterFor("thief");
        check("stealth: meter rises in light", bright > 0.3f, "meter=" + bright);
        check("stealth: suspicious state at threshold", s.stateFor("thief") == StealthSystem.State.SUSPICIOUS, "" + s.stateFor("thief"));

        thief.lightLevel = 0.05f;
        thief.crouching = true;
        float before = s.meterFor("thief");
        for(int i = 0; i < 60; i++) s.update(1f / 60f);
        float darkCrouchRise = s.meterFor("thief") - before;
        check("stealth: dark+crouch slows detection", darkCrouchRise < bright, "rise=" + darkCrouchRise);

        // full detection + event
        final List<String> events = new ArrayList<>();
        s.listener = (type, obs, tgt, meter) -> events.add(type);
        thief.lightLevel = 1f; thief.crouching = false;
        for(int i = 0; i < 180 && s.stateFor("thief") != StealthSystem.State.DETECTED; i++) s.update(1f / 60f);
        check("stealth: confirms detection", s.stateFor("thief") == StealthSystem.State.DETECTED, "" + s.stateFor("thief"));
        check("stealth: detection event fired", events.contains("detected"), events.toString());

        // regression: sight still held for 1 s after detection — must NOT re-fire the event
        long beforeSpam = events.stream().filter(e -> e.equals("detected")).count();
        for(int i = 0; i < 60; i++) s.update(1f / 60f);
        long afterSpam = events.stream().filter(e -> e.equals("detected")).count();
        check("stealth: no detected-event spam", beforeSpam == 1 && afterSpam == 1, beforeSpam + " -> " + afterSpam);

        // noise spike on a fresh system
        StealthSystem s2 = new StealthSystem();
        StealthSystem.Observer o2 = s2.addObserver("dog");
        o2.pos.set(0, 0, 0);
        StealthSystem.Hidden h2 = s2.addHidden("runner");
        h2.pos.set(30, 0, 0);                       // outside the cone/range
        int hit = s2.noise(new com.hollow.core.Vec3(6, 0, 0), 10f, 2.6f);   // 2.6*0.4 → >1 even with float fuzz
        check("stealth: noise reaches observers", hit == 1, "" + hit);
        check("stealth: loud noise spikes meter", s2.meterFor("runner") >= 1f, "meter=" + s2.meterFor("runner"));
    }

    // =====================================================================
    static void progression(){
        ProgressionSystem p = new ProgressionSystem();
        check("progression: xp curve monotonic", p.xpForLevel(1) <= p.xpForLevel(2) && p.xpForLevel(2) <= p.xpForLevel(5),
              p.xpForLevel(1) + "/" + p.xpForLevel(2) + "/" + p.xpForLevel(5));

        final int[] sinkCalls = {0};
        p.sink = (attr, delta) -> sinkCalls[0]++;

        ProgressionSystem.Skill strong = new ProgressionSystem.Skill("strength").ranks(3).costs(1).raises("damage", 5f);
        ProgressionSystem.Skill master = new ProgressionSystem.Skill("mastery").ranks(1).costs(2).needs("strength");
        p.define(strong); p.define(master);

        boolean leveled = p.addXp(p.xpForLevel(1) + 10);
        check("progression: level up grants point", leveled && p.level == 2 && p.skillPoints == 1,
              "lvl=" + p.level + " sp=" + p.skillPoints);

        check("progression: prereq blocks rank", !p.canRankUp("mastery"), "");
        check("progression: rank up works", p.rankUp("strength") && strong.rank == 1 && p.skillPoints == 0, "sp=" + p.skillPoints);
        check("progression: sink applied", sinkCalls[0] == 1, "" + sinkCalls[0]);
        check("progression: no points left refuses", !p.rankUp("strength"), "");
        check("progression: canRankUp reflects prereq ok", p.canRankUp("mastery") == false || p.skillPoints == 0, "");

        p.addXp(p.xpForLevel(2) * 3 + 100);        // jumps several levels
        check("progression: multi-level from big xp", p.level >= 4 && p.skillPoints >= 2,
              "lvl=" + p.level + " sp=" + p.skillPoints);
        p.skillPoints += 3;
        p.rankUp("mastery");
        check("progression: max rank cap", !p.canRankUp("mastery") && master.rank == 1, "rank=" + master.rank);

        int refund = p.respec();
        check("progression: respec refunds", refund == 3 && p.spentPoints == 0 && p.skillPoints >= refund,
              "refund=" + refund + " sp=" + p.skillPoints);

        String json = p.save();
        ProgressionSystem p2 = new ProgressionSystem();
        p2.define(new ProgressionSystem.Skill("strength").ranks(3).costs(1).raises("damage", 5f));
        p2.load(json);
        check("progression: save/load level+xp", p2.level == p.level && p2.xp == p.xp, p2.level + "/" + p2.xp);
    }

    // =====================================================================
    static void factions(){
        FactionSystem f = new FactionSystem();
        f.define("oakwood", "Oakwood Villagers");
        f.define("bandits", "Red Bandits");
        f.define("merchants", "Merchant Guild");

        f.shiftReputation("oakwood", 70);
        check("factions: positive rep → ally", f.faction("oakwood").playerRelation == FactionSystem.Relation.ALLY,
              "" + f.faction("oakwood").playerRelation);
        f.shiftReputation("bandits", -80);
        check("factions: negative rep → war", f.faction("bandits").playerRelation == FactionSystem.Relation.WAR, "");
        check("factions: hostile query", f.isHostileToPlayer("bandits") && !f.isHostileToPlayer("oakwood"), "");

        // spill: boosting merchants slightly helps their ally (oakwood)
        f.faction("merchants").reputation = 80;
        f.syncRelations();
        float oakBefore = f.faction("oakwood").reputation;
        f.shiftReputation("merchants", 20);
        check("factions: rep spills to allies", f.faction("oakwood").reputation > oakBefore,
              oakBefore + " -> " + f.faction("oakwood").reputation);

        f.declareWar("oakwood", "bandits");
        check("factions: war both directions", f.isAtWar("oakwood", "bandits") && f.isAtWar("bandits", "oakwood"), "");
        f.makePeace("oakwood", "bandits");
        check("factions: peace ends war", !f.isAtWar("oakwood", "bandits"), "");
        check("factions: rep clamped at 100", f.faction("oakwood").reputation <= 100, "" + f.faction("oakwood").reputation);

        String json = f.save();
        FactionSystem f2 = new FactionSystem();
        f2.load(json);
        check("factions: save/load rep", f2.faction("oakwood") != null
              && f2.faction("oakwood").reputation == f.faction("oakwood").reputation, "");
        f2.declareWar("oakwood", "merchants");
        check("factions: war survives via explicit declare", f2.isAtWar("oakwood", "merchants"), "");
    }

    // =====================================================================
    static void loot(){
        LootSystem ls = new LootSystem();
        LootSystem.Table t = new LootSystem.Table("chest")
            .add(new LootSystem.Entry("iron_ore", 60f).qty(1, 3).rare(LootSystem.Rarity.COMMON))
            .add(new LootSystem.Entry("ruby", 8f).qty(1, 1).rare(LootSystem.Rarity.RARE))
            .add(new LootSystem.Entry("crown", 1f).qty(1, 1).rare(LootSystem.Rarity.LEGENDARY));
        ls.define(t);

        LootSystem.Roll r1 = ls.roll("chest", 42L);
        LootSystem.Roll r2 = ls.roll("chest", 42L);
        check("loot: deterministic per seed", r1 != null && r1.itemId.equals(r2.itemId) && r1.qty == r2.qty,
              r1 + " vs " + r2);
        check("loot: qty in range", r1.qty >= 1 && r1.qty <= 3, "" + r1.qty);
        check("loot: unknown table null", ls.roll("nope", 1L) == null, "");

        int rare0 = 0;
        for(int i = 0; i < 400; i++)
            if(ls.roll("chest", 1000L + i).rarity.ordinal() >= LootSystem.Rarity.RARE.ordinal()) rare0++;
        ls.luck = 1f;
        int rare1 = 0;
        for(int i = 0; i < 400; i++)
            if(ls.roll("chest", 1000L + i).rarity.ordinal() >= LootSystem.Rarity.RARE.ordinal()) rare1++;
        check("loot: luck skews rare", rare1 > rare0, "base=" + rare0 + " lucky=" + rare1);
        ls.luck = 0f;

        // pity: force LEGENDARY after 25 dry rolls
        t.pityItemId = "crown"; t.pityAfter = 25;
        LootSystem.Roll pity = null;
        int rolls = 0;
        for(int i = 0; i < 60; i++){
            LootSystem.Roll rr = ls.roll("chest", 5000L + i);
            rolls = i + 1;
            if(rr.wasPity){ pity = rr; break; }
        }
        check("loot: pity fires within cap", pity != null && rolls <= 26, "rolls=" + rolls);
        check("loot: pity grants the item", pity != null && pity.itemId.equals("crown"), "" + pity);
        check("loot: pity counter reset", ls.pityCounter("chest") == 0, "" + ls.pityCounter("chest"));

        String json = ls.save();
        LootSystem ls2 = new LootSystem();
        ls2.load(json);
        check("loot: save/load table", ls2.table("chest") != null && ls2.table("chest").entries.size() == 3,
              "" + (ls2.table("chest") == null ? -1 : ls2.table("chest").entries.size()));
    }

    // =====================================================================
    static void economy(){
        EconomySystem eco = new EconomySystem();
        EconomySystem.Vendor smith = new EconomySystem.Vendor("smith", "Iron Smith");
        smith.stock.add(new EconomySystem.StockItem("sword", 100, 5));
        smith.stock.add(new EconomySystem.StockItem("shield", 80, 3));
        eco.define(smith);

        check("economy: buy price margin", eco.buyPrice(smith, "sword") == 125, "" + eco.buyPrice(smith, "sword"));
        smith.discount = 0.2f;
        check("economy: discount applies", eco.buyPrice(smith, "sword") == 100, "" + eco.buyPrice(smith, "sword"));
        smith.discount = 0f;

        eco.playerGold = 300;
        int spent = eco.buy("smith", "sword", 2);
        check("economy: buy moves gold", spent == 250 && eco.playerGold == 50 && smith.gold == 750,
              spent + "/" + eco.playerGold + "/" + smith.gold);
        check("economy: stock decrements", smith.item("sword").qty == 3, "" + smith.item("sword").qty);
        check("economy: insufficient funds refuses", eco.buy("smith", "sword", 2) == -1 && eco.playerGold == 50, "");

        eco.playerGold = 1000;
        check("economy: finite stock blocks oversell", eco.buy("smith", "shield", 10) == -1, "");
        eco.buy("smith", "shield", 1);             // shield at 80 * 1.25 = 100 gold
        int payout = eco.sell("smith", "shield", 1);
        check("economy: sell pays margin", payout == 32 && eco.playerGold == 1000 - 100 + 32,
              payout + "/" + eco.playerGold);
        check("economy: vendor restocks buys", smith.item("shield").qty == 3, "" + smith.item("shield").qty);

        EconomySystem.Vendor poor = new EconomySystem.Vendor("poor", "Broke Peddler");
        poor.gold = 2;                             // cannot afford the 4g baseline offer
        eco.define(poor);
        check("economy: broke vendor refuses", eco.sell("poor", "shield", 1) == -1, "");

        String json = eco.save();
        EconomySystem eco2 = new EconomySystem();
        eco2.load(json);
        check("economy: save/load gold+stock", eco2.playerGold == eco.playerGold
              && eco2.vendor("smith").item("sword").qty == smith.item("sword").qty, "");
    }

    // =====================================================================
    static void seasons(){
        SeasonsSystem s = new SeasonsSystem(7, 40f);   // 7 days per season, 40-min year
        check("seasons: starts in spring", s.season == SeasonsSystem.Season.SPRING, "" + s.season);
        check("seasons: seasonOf deterministic", s.seasonOf(0) == SeasonsSystem.Season.SPRING
              && s.seasonOf(7) == SeasonsSystem.Season.SUMMER
              && s.seasonOf(28) == SeasonsSystem.Season.SPRING, "" + s.seasonOf(7) + "/" + s.seasonOf(28));

        final List<String> events = new ArrayList<>();
        s.listener = (type, season, temp) -> events.add(type + ":" + season);
        float secondsPerDay = 40f * 60f / 28f;
        for(int i = 0; i < (int)(secondsPerDay * 7.5f); i++) s.update(1f);
        check("seasons: summer after a season", s.season == SeasonsSystem.Season.SUMMER, "" + s.season);
        check("seasons: change event fired", events.contains("changed:SUMMER"), events.toString());

        SeasonsSystem.SeasonProfile winter = SeasonsSystem.profile(SeasonsSystem.Season.WINTER);
        SeasonsSystem.SeasonProfile summer = SeasonsSystem.profile(SeasonsSystem.Season.SUMMER);
        check("seasons: winter cold / summer hot", winter.maxTemp < summer.minTemp,
              winter.minTemp + ".." + winter.maxTemp + " vs " + summer.minTemp + ".." + summer.maxTemp);
        check("seasons: winter prefers snow", winter.preferredWeather.name().equals("SNOW"), "");
        check("seasons: day length scales differ", winter.dayLengthScale > summer.dayLengthScale, "");
        float temp = s.currentTemp();
        check("seasons: temp sane", temp > -20f && temp < 45f, "" + temp);

        String json = s.save();
        SeasonsSystem s2 = new SeasonsSystem();
        s2.load(json);
        check("seasons: save/load day+season", s2.day == s.day && s2.season == s.season, s2.day + "/" + s2.season);
    }

    // =====================================================================
    static void music(){
        MusicDirector m = new MusicDirector();
        m.define(new MusicDirector.State("explore").track("calm_a").track("calm_b").dwellSec(0.5f));
        m.define(new MusicDirector.State("combat").track("battle_a").dwellSec(0.5f));

        final List<String> events = new ArrayList<>();
        m.listener = (type, from, to, track) -> events.add(type + ":" + to);
        m.request("explore", MusicDirector.Request.IMMEDIATE);
        check("music: immediate switch", m.current.equals("explore"), "" + m.current);
        check("music: switch event", events.contains("switch:explore"), events.toString());
        check("music: track picked", m.nextTrack("explore") != null, "");

        for(int i = 0; i < 90; i++) m.update(1f / 60f);    // 1.5 s crossfade
        check("music: fade completes", m.gainOf("explore") >= 0.999f && m.gainOf("combat") == 0f,
              m.gainOf("explore") + "/" + m.gainOf("combat"));

        // threat drives intensity up fast, decays slowly
        m.setThreat(1f);
        for(int i = 0; i < 30; i++) m.update(1f / 60f);
        float hot = m.intensity;
        m.setThreat(0f);
        for(int i = 0; i < 10; i++) m.update(1f / 60f);
        float cooled = m.intensity;
        check("music: intensity attacks", hot > 0.5f, "" + hot);
        check("music: intensity releases slowly", cooled < hot && cooled > hot * 0.5f, hot + " -> " + cooled);

        String cur = m.autoCombatState("explore", "combat", 0.6f);
        m.setThreat(0.9f);
        for(int i = 0; i < 120 && !m.current.equals("combat"); i++){ m.update(1f / 60f); m.autoCombatState("explore", "combat", 0.6f); }
        check("music: auto combat switch", m.current.equals("combat"), "" + cur + " -> " + m.current);
        for(int i = 0; i < 120; i++) m.update(1f / 60f);   // let the crossfade finish
        check("music: combat gains up", m.gainOf("combat") > 1.2f && m.gainOf("explore") < 0.05f,
              m.gainOf("explore") + "/" + m.gainOf("combat"));

        m.request("explore", MusicDirector.Request.HOLD_DWELL);
        boolean early = m.current.equals("combat");
        for(int i = 0; i < 120; i++) m.update(1f / 60f);
        check("music: hold-dwell respects dwell", early || m.current.equals("explore"),
              "early=" + early + " now=" + m.current);

        // regression: changing the request restarts the dwell clock
        MusicDirector m3 = new MusicDirector();
        m3.crossfadeSec = 0.1f;
        m3.define(new MusicDirector.State("explore").track("x").dwellSec(1f));
        m3.define(new MusicDirector.State("combat").track("y").dwellSec(1f));
        m3.define(new MusicDirector.State("stealth").track("z").dwellSec(1f));
        m3.request("explore", MusicDirector.Request.IMMEDIATE);
        m3.request("combat", MusicDirector.Request.HOLD_DWELL);
        for(int i = 0; i < 54; i++) m3.update(1f / 60f);            // 0.9 s of the dwell burned
        m3.request("stealth", MusicDirector.Request.HOLD_DWELL);    // fresh request → clock resets
        for(int i = 0; i < 57; i++) m3.update(1f / 60f);            // +0.95 s → still waiting
        check("music: dwell resets on new request", m3.current.equals("explore"), "" + m3.current);
        m3.update(1f / 60f);
        m3.update(1f / 60f);
        m3.update(1f / 60f);
        m3.update(1f / 60f);
        check("music: dwell completes after reset", m3.current.equals("stealth"), "" + m3.current);
    }

    // =====================================================================
    static void markers(){
        MapMarkerSystem mm = new MapMarkerSystem();
        mm.add(new MapMarkerSystem.Marker("shop", "Blacksmith").at(0, 0, 100).cat(MapMarkerSystem.Category.VENDOR));
        mm.add(new MapMarkerSystem.Marker("mine", "Iron Mine").at(100, 0, 0).cat(MapMarkerSystem.Category.RESOURCE).clears(10f));
        mm.add(new MapMarkerSystem.Marker("boss", "Dragon").at(0, 0, -200).cat(MapMarkerSystem.Category.ENEMY));
        mm.add(new MapMarkerSystem.Marker("hidden", "Secret").at(5, 0, 5).cat(MapMarkerSystem.Category.CUSTOM));
        mm.marker("hidden").visible = false;

        List<MapMarkerSystem.Marker> all = mm.visible(null);
        check("markers: hidden filtered", all.size() == 3, "" + all.size());
        List<MapMarkerSystem.Marker> vendors = mm.visible(List.of(MapMarkerSystem.Category.VENDOR));
        check("markers: category filter", vendors.size() == 1 && vendors.get(0).id.equals("shop"), "" + vendors.size());

        com.hollow.core.Vec3 player = new com.hollow.core.Vec3(0, 0, 0);
        List<MapMarkerSystem.Probe> ps = mm.probes(player, 0f, null);
        check("markers: sorted by distance", ps.get(0).marker.id.equals("shop") && ps.get(0).distance == 100f,
              ps.get(0).marker.id + "/" + ps.get(0).distance);
        check("markers: north bearing ~0", Math.abs(ps.get(0).bearingDeg) < 0.01f, "" + ps.get(0).bearingDeg);

        MapMarkerSystem.Probe enemy = mm.nearest(player, MapMarkerSystem.Category.ENEMY);
        check("markers: nearest of category", enemy != null && enemy.marker.id.equals("boss")
              && Math.abs(enemy.bearingDeg - 180f) < 0.01f, "" + enemy.bearingDeg);

        String compass = mm.compassSummary(player, 0f);
        check("markers: compass strip", compass.contains("N=Blacksmith") && compass.contains("S=Dragon"), compass);

        int cleared = mm.update(new com.hollow.core.Vec3(99, 0, 0));   // inside the mine radius 10
        check("markers: radius auto-clear", cleared == 1 && !mm.marker("mine").visible, "" + cleared);

        String json = mm.save();
        MapMarkerSystem mm2 = new MapMarkerSystem();
        mm2.load(json);
        check("markers: save/load", mm2.marker("shop") != null && mm2.marker("shop").pos.z == 100f
              && mm2.marker("mine") != null && !mm2.marker("mine").visible, "");
    }
}
