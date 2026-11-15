import com.hollow.gameplay.Inventory;
import com.hollow.gameplay.Combatant;
import com.hollow.gameplay.Weapon;
import com.hollow.gameplay.Dialogue;
import com.hollow.gameplay.QuestSystem;
import com.hollow.gameplay.Tutorial;
import com.hollow.core.Json;
import com.hollow.core.JsonV;

/**
 * v3.0.0 gameplay regression suite: inventory, combat, weapons, dialogue,
 * quests, tutorial — full state machines driven edge to edge.
 */
public class VerifyGameplay {
    static int fails = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        if(!ok) fails++;
    }

    public static void main(String[] a){
        // ================= INVENTORY =================
        Inventory inv = new Inventory();
        int added = inv.add("medkit", 5, 0.5f, true, null);
        check("inventory: add 5 medkits", added == 5 && inv.count("medkit") == 5, "added=" + added);
        inv.add("ammo", 100, 0.01f, true, null);
        check("inventory: weight tracked (5*0.5 + 100*0.01 = 3.5)",
              Math.abs(inv.weight() - 3.5f) < 1e-4f, "w=" + inv.weight());
        added = inv.add("rock", 10000, 1f, true, null);   // would exceed 60kg
        check("inventory: weight limit blocks overage (only ~56 rocks fit)",
              added < 10000 && inv.weight() <= 60f + 1e-4f, "added=" + added + " w=" + inv.weight());
        int removed = inv.remove("medkit", 3);
        check("inventory: remove 3 medkits", removed == 3 && inv.count("medkit") == 2, "r=" + removed);
        // non-stackable distinct slots
        Inventory inv2 = new Inventory();
        inv2.capacity = 3;
        inv2.add("sword", 1, 3f, false, "weapon");
        inv2.add("axe", 1, 4f, false, "weapon");
        inv2.add("bow", 1, 2f, false, "weapon");
        added = inv2.add("spear", 1, 3f, false, "weapon");
        check("inventory: capacity blocks 4th non-stackable", added == 0 && inv2.slots.size() == 3, "added=" + added);
        check("inventory: equip by id + unequip",
              inv2.equip("axe") && inv2.equipped.get("weapon").id.equals("axe") && inv2.unequip("weapon"),
              "equip flow");
        // crafting (capacity raised so inputs fit alongside gear)
        inv2.capacity = 10;
        inv2.maxWeight = 200f;
        inv2.recipes.add(new Inventory.Recipe("arrow", 10, new String[]{"wood","stone"}, new int[]{2,1}));
        inv2.add("wood", 10, 0.5f, true, null);
        inv2.add("stone", 5, 0.8f, true, null);
        check("inventory: craft consumes inputs, yields output",
              inv2.craft(0, 0.05f, true, null) && inv2.count("wood") == 8 && inv2.count("arrow") == 10,
              "wood=" + inv2.count("wood") + " arrows=" + inv2.count("arrow"));
        // JSON roundtrip
        Inventory loaded = new Inventory();
        loaded.load(new JsonV(Json.parse(inv2.toJson())));
        check("inventory: JSON roundtrip preserves stacks",
              loaded.count("arrow") == 10 && loaded.count("wood") == 8 && loaded.capacity == 10,
              "arrows=" + loaded.count("arrow") + " wood=" + loaded.count("wood"));

        // ================= COMBATANT =================
        Combatant hero = new Combatant(100f);
        float d1 = hero.damage(new Combatant.DamageInfo(50f, "physical", "torso"));
        check("combat: torso 50 dmg applied", Math.abs(d1 - 50f) < 1e-3f && hero.hp == 50f, "d=" + d1 + " hp=" + hero.hp);
        float dHead = hero.damage(new Combatant.DamageInfo(10f, "physical", "head"));
        check("combat: head zone 2x (10 -> 20)", Math.abs(dHead - 20f) < 1e-3f && hero.hp == 30f, "d=" + dHead);
        hero.resistances.put("fire", 0.5f);
        float dFire = hero.damage(new Combatant.DamageInfo(40f, "fire", "torso"));
        check("combat: 50% fire resist halves damage", Math.abs(dFire - 20f) < 1e-3f && hero.hp == 10f, "d=" + dFire);
        float healed = hero.heal(500f);
        check("combat: heal clamps to maxHp", healed == 90f && hero.hp == 100f, "h=" + healed);
        boolean[] diedFlag = { false };
        hero.deathHooks.add(c -> diedFlag[0] = true);
        hero.damage(9999f);
        check("combat: death triggers hook + dead flag", hero.dead && diedFlag[0] && hero.hp == 0f, "dead=" + hero.dead);
        hero.revive(0.5f);
        check("combat: revive at 50%", !hero.dead && Math.abs(hero.hp - 50f) < 1e-3f, "hp=" + hero.hp);
        // status DOT
        Combatant victim = new Combatant(100f);
        victim.applyStatus("poison", 2.0f, 0.5f, 5f, 0.3f);
        for(int i = 0; i < 30; i++) victim.updateStatuses(0.1f);   // 3s
        check("combat: poison ticks 4x over 2s (20 dmg) then expires",
              Math.abs(victim.hp - 80f) < 1e-3f && !victim.hasStatus("poison"),
              "hp=" + victim.hp + " poison=" + victim.hasStatus("poison"));
        check("combat: movePenalty active during status then clears",
              victim.movePenalty() == 0f, "pen=" + victim.movePenalty());
        Combatant rooted = new Combatant(100f);
        rooted.applyStatus("slow", 1f, 1f, 0f, 0.5f);
        check("combat: slow applies 0.5 move penalty", rooted.movePenalty() == 0.5f, "pen=" + rooted.movePenalty());
        // damage hook consume
        Combatant shielded = new Combatant(100f);
        shielded.damageHooks.add((c, info) -> info.type.equals("physical"));   // physical fully absorbed
        shielded.damage(50f);
        check("combat: damage hook can absorb all physical", shielded.hp == 100f, "hp=" + shielded.hp);

        // ================= WEAPON =================
        Weapon rifle = new Weapon();
        rifle.id = "rifle"; rifle.magSize = 3; rifle.magAmmo = 3; rifle.fireRate = 600f; // cooldown ~0
        int firedCount = 0;
        for(int t = 0; t < 10; t++){
            Weapon.FireResult r = rifle.tryFire(true, null);
            rifle.update(1f / 60f);
            if(r.fired) firedCount++;
        }
        check("weapon: full-auto drains mag then goes empty",
              firedCount == 3 && rifle.magAmmo == 0 && rifle.tryFire(true, null).empty,
              "fired=" + firedCount + " mag=" + rifle.magAmmo);
        // reload from inventory
        Inventory ammoInv = new Inventory();
        ammoInv.add("ammo_rifle", 2, 0.01f, true, null);
        rifle.ammoId = "ammo_rifle"; rifle.reloadTime = 1f;
        rifle.magAmmo = 0;
        rifle.tryFire(true, ammoInv);          // triggers auto-reload (source captured)
        for(int t = 0; t < 70; t++) rifle.update(1f / 60f);
        check("weapon: auto-reload pulls 2 rounds from inventory (only 2 available)",
              rifle.magAmmo == 2 && ammoInv.count("ammo_rifle") == 0,
              "mag=" + rifle.magAmmo + " inv=" + ammoInv.count("ammo_rifle"));
        // semi mode: one shot per pull
        Weapon pistol = new Weapon();
        pistol.magSize = 10; pistol.magAmmo = 10; pistol.fireRate = 600f; pistol.fireMode = Weapon.FIRE_SEMI;
        int semiShots = 0;
        for(int t = 0; t < 5; t++){
            Weapon.FireResult r = pistol.tryFirePressed(t == 0, null);   // pressed edge only on frame 0
            pistol.update(1f / 60f);
            if(r.fired) semiShots++;
        }
        check("weapon: semi fires exactly 1 per press edge (held does not refire)",
              semiShots == 1, "semi=" + semiShots);
        // library spawn
        Weapon.register(rifle);
        Weapon copy = Weapon.spawn("rifle");
        check("weapon: library spawn copies stats with full mag",
              copy != null && copy.magSize == 3 && copy.magAmmo == 3 && copy.damage == rifle.damage, "copy=" + copy);

        // ================= DIALOGUE =================
        Dialogue dlg = new Dialogue();
        dlg.add(new Dialogue.Node("start", "Merchant", "Welcome, traveler!"))
           .choice("Just browsing.", "browse")
           .choice("Heard you need help.", "quest", d -> d.flag("met_before"), null)
           .choice("Bye.", null);
        dlg.add(new Dialogue.Node("quest", "Merchant", "Wolves near the north road...")
           .choice("I'll handle it.", "done", null, d -> d.setVar("quest_accepted", true)));
        Dialogue.Node doneNode = new Dialogue.Node("done", "Merchant", "Bless you!");
        doneNode.endsDialogue = true;
        dlg.add(doneNode);
        dlg.add(new Dialogue.Node("browse", "Merchant", "Take your time."));
        // first run: quest choice hidden (flag false)
        Dialogue.Node n = dlg.begin();
        check("dialogue: begins at start node", n != null && n.id.equals("start"), "n=" + n);
        check("dialogue: condition hides gated choice (3 -> 2 visible)",
              dlg.visibleChoices().size() == 2, "visible=" + dlg.visibleChoices().size());
        dlg.setVar("met_before", Boolean.TRUE);
        check("dialogue: flag reveals gated choice (3 visible)",
              dlg.visibleChoices().size() == 3, "visible=" + dlg.visibleChoices().size());
        Dialogue.Node qn = dlg.pick(1);   // "Heard you need help" -> quest
        check("dialogue: pick navigates to quest node", qn != null && qn.id.equals("quest"), "qn=" + qn);
        dlg.pick(0);   // accept -> done node
        check("dialogue: effect ran (quest_accepted var)",
              Boolean.TRUE.equals(dlg.getVar("quest_accepted")), "var=" + dlg.getVar("quest_accepted"));
        check("dialogue: visited tracking", dlg.visited("start") && dlg.visited("quest"), "visited");

        // ================= QUESTS =================
        QuestSystem qs = new QuestSystem();
        final int[] killed = { 0 };
        qs.add(new QuestSystem.Quest("wolves", "Wolf Trouble")
            .objective("kill", "Kill 3 wolves", 3, (q, o) -> killed[0])
            .reward(q -> qs.setFlag("wolves_done", true)));
        check("quest: available before start, starts cleanly",
              qs.available().size() == 1 && qs.start("wolves") && "active".equals(qs.quest("wolves").state),
              "avail=" + qs.available().size());
        qs.quests.get("wolves").nextQuestId = "epilogue";
        qs.add(new QuestSystem.Quest("epilogue", "Hero of the Road"));
        qs.update();
        killed[0] = 2;
        qs.update();
        check("quest: partial progress (2/3, not complete)",
              qs.quest("wolves").objectives.get(0).progress == 2
              && "active".equals(qs.quest("wolves").state), "prog=" + qs.quest("wolves").objectives.get(0).progress);
        killed[0] = 3;
        qs.update();
        check("quest: completes at 3/3 + reward granted",
              "completed".equals(qs.quest("wolves").state) && qs.flag("wolves_done"),
              "state=" + qs.quest("wolves").state);
        check("quest: chained next quest auto-started",
              "active".equals(qs.quest("epilogue").state), "state=" + qs.quest("epilogue").state);
        check("quest: journal lists active quest with objectives",
              qs.journal().size() == 1 && qs.journal().get(0).get("id").equals("epilogue"),
              "journal=" + qs.journal().size());
        // flag gating
        QuestSystem qs2 = new QuestSystem();
        qs2.add(new QuestSystem.Quest("gated", "Locked Quest"));
        qs2.quests.get("gated").requiresFlags.add("key");
        check("quest: flag-gated quest stays unavailable",
              qs2.available().isEmpty() && !qs2.start("gated"), "avail=" + qs2.available().size());
        qs2.setFlag("key", true);
        check("quest: flag satisfies gate", qs2.available().size() == 1, "avail=" + qs2.available().size());
        // fail path (epilogue is active from the chain above)
        check("quest: fail from active", qs.fail("epilogue") && "failed".equals(qs.quest("epilogue").state), "fail");

        // ================= TUTORIAL =================
        Tutorial tut = new Tutorial();
        boolean[] moved = { false }, attacked = { false };
        tut.step("move", "Use the joystick to move", () -> moved[0])
           .step("attack", "Tap FIRE to attack", () -> attacked[0]);
        final boolean[] finishedFired = { false };
        tut.listeners.add(new Tutorial.Listener(){
            public void onStepDone(Tutorial.Step s){ }
            public void onTutorialFinished(){ finishedFired[0] = true; }
        });
        tut.begin();
        check("tutorial: begins on first step", tut.current().id.equals("move"), "cur=" + (tut.current() == null ? null : tut.current().id));
        for(int i = 0; i < 10; i++) tut.update(0.016f);
        moved[0] = true;
        tut.update(0.016f);
        check("tutorial: probe completion advances step",
              tut.current() != null && tut.current().id.equals("attack"), "cur=" + (tut.current() == null ? null : tut.current().id));
        check("tutorial: progress 0.5 after first step", Math.abs(tut.progress() - 0.5f) < 1e-4f, "p=" + tut.progress());
        attacked[0] = true;
        tut.update(0.016f);
        check("tutorial: finishes + listener fires",
              tut.finished && finishedFired[0] && tut.progress() == 1f, "fin=" + tut.finished);
        // timeout auto-skip
        Tutorial tut2 = new Tutorial();
        tut2.step("stuck", "Impossible goal", (Tutorial.Step.Probe) () -> false);
        tut2.steps.get(0).timeoutSec = 2f;
        tut2.begin();
        for(int i = 0; i < 150; i++) tut2.update(0.02f);   // 3s
        check("tutorial: timeout auto-completes stuck step", tut2.finished, "fin=" + tut2.finished);

        System.out.println(fails == 0 ? "ALL GAMEPLAY REGRESSION TESTS PASSED" : fails + " TESTS FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
