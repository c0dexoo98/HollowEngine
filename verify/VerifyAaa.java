import com.hollow.game.Replay;
import com.hollow.gameplay.AbilitySystem;
import com.hollow.gameplay.WaveDirector;
import com.hollow.world.Ocean;
import com.hollow.world.Terrain;
import com.hollow.world.Weather;

import java.util.ArrayList;
import java.util.List;

/**
 * v4.0.0 AAA-systems regression suite — terrain, ocean, weather, GAS, wave director, replay.
 */
public class VerifyAaa {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        terrain();
        ocean();
        weather();
        gas();
        waves();
        replay();
        System.out.println(fails == 0 ? "\nALL " + total + " AAA TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " AAA TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void terrain(){
        Terrain t = new Terrain(0x1234L, 129, 2f, 40f);
        check("terrain: grid allocated", t.heights.length == 129 * 129, "" + t.heights.length);

        float h1 = t.heightAt(50f, 50f);
        float h2 = t.heightAt(50.01f, 50.01f);
        float h3 = t.heightAt(51f, 51f);
        check("terrain: bilinear sampling continuous", Math.abs(h1 - h2) < 1f && Math.abs(h1 - h3) < 6f,
              "" + h1 + " vs " + h2 + " vs " + h3);

        com.hollow.core.Vec3 n = t.normalAt(50f, 50f, new com.hollow.core.Vec3());
        float nl = n.len();
        check("terrain: normal unit-length", Math.abs(nl - 1f) < 0.02f, "len=" + nl);

        float edgeBefore = t.heightAt(60f, 60f + 20f);
        float before = t.heightAt(60f, 60f);
        t.raise(60f, 60f, 6f, 5f);
        float after = t.heightAt(60f, 60f);
        check("terrain: raise brush lifts center", after > before + 3f, before + " -> " + after);
        float edge = t.heightAt(60f, 60f + 20f);
        check("terrain: brush falloff leaves distance intact", Math.abs(edge - edgeBefore) < 1f,
              edgeBefore + " -> " + edge);

        t.flatten(60f, 60f, 8f, 1f);
        float flat = t.heightAt(60f, 60f);
        float flatEdge = t.heightAt(61f, 61f);
        check("terrain: flatten evens the brush area", Math.abs(flat - flatEdge) < 0.5f, flat + " vs " + flatEdge);

        t.smooth(60f, 60f, 6f, 1f);
        check("terrain: smooth keeps height sane", Math.abs(t.heightAt(60f, 60f) - flat) < 2f, "");

        t.paint(40f, 40f, 6f, 3, 0.9f);
        check("terrain: paint sets dominant layer", t.dominantLayer(40f, 40f) == 3, "" + t.dominantLayer(40f, 40f));

        Terrain.Hit hit = new Terrain.Hit();
        // shoot straight down from high above — must hit the ground
        boolean hitDown = t.raycast(new com.hollow.core.Vec3(50f, t.heightAt(50f, 50f) + 30f, 50f),
                                    new com.hollow.core.Vec3(0, -1, 0), 100f, hit);
        check("terrain: downward ray hits ground", hitDown && Math.abs(hit.point.y - t.heightAt(50f, 50f)) < 0.3f,
              hitDown ? ("dy=" + (hit.point.y - t.heightAt(50f, 50f))) : "no hit");
        boolean missUp = t.raycast(new com.hollow.core.Vec3(50f, t.heightAt(50f, 50f) + 10f, 50f),
                                   new com.hollow.core.Vec3(0, 1, 0), 100f, hit);
        check("terrain: upward ray misses", !missUp, "");

        t.setHole(50f, 50f, 3f, true);
        boolean through = t.raycast(new com.hollow.core.Vec3(50f, t.heightAt(50f, 50f) + 30f, 50f),
                                    new com.hollow.core.Vec3(0, -1, 0), 100f, hit);
        check("terrain: hole lets the ray pass", !through, "");

        String json = t.save();
        Terrain t2 = Terrain.load(json);
        float dmax = 0f;
        for(int i = 0; i < t.heights.length; i += 997)
            dmax = Math.max(dmax, Math.abs(t.heights[i] - t2.heights[i]));
        check("terrain: save/load roundtrip (1cm)", dmax < 0.011f, "maxD=" + dmax);
    }

    // =====================================================================
    static void ocean(){
        Ocean sea = new Ocean();
        check("ocean: default waves", sea.waves.size() == 4, "" + sea.waves.size());

        float h = sea.heightAt(3.7f, -2.2f, 1.234f);
        check("ocean: height near sea level", Math.abs(h) < 2.5f, "h=" + h);
        float hAgain = sea.heightAt(3.7f, -2.2f, 1.234f);
        check("ocean: deterministic", h == hAgain, "");

        com.hollow.core.Vec3 n = sea.normalAt(0f, 0f, 0f, new com.hollow.core.Vec3());
        check("ocean: normal unit", Math.abs(n.len() - 1f) < 0.02f, "len=" + n.len());

        float foamMin = 1f, foamMax = 0f;
        for(int i = 0; i < 64; i++){
            float f = sea.foamAt(i * 1.3f, i * -0.7f, i * 0.31f);
            foamMin = Math.min(foamMin, f); foamMax = Math.max(foamMax, f);
        }
        check("ocean: foam within 0..1", foamMin >= 0f && foamMax <= 1f, foamMin + ".." + foamMax);

        check("ocean: submersion clamps", sea.submersionAt(999f, 0f, 0f, 0f, 0.6f) == 0f
              && sea.submersionAt(-999f, 0f, 0f, 0f, 0.6f) == 1f, "");

        Ocean.Body b = new Ocean.Body();
        b.y = -3f; b.draft = 0.6f;               // deep underwater → strong buoyancy
        float yBefore = b.y;
        for(int i = 0; i < 120; i++) sea.floatBody(b, i * 0.016f, 0.016f, 1.6f, 2.5f);
        check("ocean: buoyant body floats up", b.y > yBefore && b.y > -1.5f, "y=" + b.y);
    }

    // =====================================================================
    static void weather(){
        Weather w = new Weather();
        w.blendTime = 1f;
        w.set(Weather.Preset.RAIN);
        for(int i = 0; i < 600; i++) w.update(1f / 60f);   // 10 s of rain
        check("weather: blends to rain", w.current.rain > 0.65f && w.preset == Weather.Preset.RAIN,
              "rain=" + w.current.rain);
        check("weather: wetness accumulates", w.wetness > 0.5f, "wet=" + w.wetness);
        check("weather: slip rises", w.slipFactor() > 0.1f, "" + w.slipFactor());

        w.set(Weather.Preset.CLEAR);
        for(int i = 0; i < 1800; i++) w.update(1f / 60f);   // 30 s of clear — drying is slow/realistic
        check("weather: dries out", w.wetness < 0.2f, "wet=" + w.wetness);

        float[] wind = w.windVector();
        float windLen = (float)Math.sqrt(wind[0] * wind[0] + wind[1] * wind[1]);
        check("weather: wind vector matches speed", Math.abs(windLen - w.current.windSpeed) < 0.01f,
              windLen + " vs " + w.current.windSpeed);

        Weather storm = new Weather();
        storm.blendTime = 0.1f;
        final List<String> events = new ArrayList<>();
        storm.listener = (type, intensity) -> events.add(type);
        storm.set(Weather.Preset.STORM);
        for(int i = 0; i < 3600; i++) storm.update(1f / 60f);   // one minute of storm
        check("weather: thunder fires in storms", events.size() >= 5, "events=" + events.size());

        Weather snow = new Weather();
        snow.blendTime = 1f;
        snow.set(Weather.Preset.SNOW);
        for(int i = 0; i < 120; i++) snow.update(1f / 60f);
        check("weather: snow profile", snow.current.snow > 0.7f && snow.current.rain < 0.05f,
              "snow=" + snow.current.snow);
    }

    // =====================================================================
    static void gas(){
        check("gas: tag hierarchy", AbilitySystem.tagMatches("combat.fire.burn", "combat")
              && AbilitySystem.tagMatches("combat.fire.burn", "combat.fire")
              && !AbilitySystem.tagMatches("combat.fire.burn", "combat.ice"), "");

        AbilitySystem gas = new AbilitySystem("health", 100f, "stamina", 50f, "mana", 50f, "speed", 6f);

        // duration effect: +25% speed for 2 s, grants a tag
        AbilitySystem.Effect haste = new AbilitySystem.Effect("haste")
            .mod("speed", AbilitySystem.Modifier.Op.MUL, 0.25f)
            .tag("buff.haste")
            .forDuration(2f);
        gas.applyEffect(haste);
        float hasted = gas.attr("speed");
        check("gas: multiplicative modifier", Math.abs(hasted - 7.5f) < 0.01f, "speed=" + hasted);
        check("gas: effect grants tag", gas.tags.has("buff.haste"), "");

        gas.update(1f);
        gas.update(1.2f);
        check("gas: effect expires", gas.effect("haste") == null && Math.abs(gas.attr("speed") - 6f) < 0.01f,
              "speed=" + gas.attr("speed"));
        check("gas: granted tag removed on expiry", !gas.tags.has("buff.haste"), "");

        // instant heal
        gas.setBase("health", 40f);
        gas.applyEffect(new AbilitySystem.Effect("potion").mod("health", AbilitySystem.Modifier.Op.ADD, 35f));
        check("gas: instant effect bakes into base", gas.attr("health") == 75f, "" + gas.attr("health"));

        // stacking refresh
        AbilitySystem.Effect poison = new AbilitySystem.Effect("poison")
            .mod("health", AbilitySystem.Modifier.Op.ADD, -2f).every(1f).forDuration(3f);
        gas.applyEffect(poison);
        float hp0 = gas.attr("health");
        gas.update(1f);
        check("gas: periodic effect ticks", gas.attr("health") == hp0 - 2f, hp0 + " -> " + gas.attr("health"));

        // ability: cost + cooldown + tag gates
        AbilitySystem.Ability dash = new AbilitySystem.Ability("dash")
            .costs("stamina", 20f).cooldownSec(2f).requires("buff.haste").runs(owner -> true);
        gas.register(dash);
        boolean refused = !gas.tryActivate("dash");
        check("gas: required tag gates activation", refused && gas.refusals >= 1, "");

        gas.applyEffect(new AbilitySystem.Effect("haste2").tag("buff.haste").forDuration(5f));
        boolean ok = gas.tryActivate("dash");
        check("gas: activation with tag + cost", ok && gas.attr("stamina") == 30f, "stamina=" + gas.attr("stamina"));
        check("gas: cooldown active", gas.onCooldown("dash") && gas.cooldownLeft("dash") > 1.5f, "");
        check("gas: cooldown blocks re-activation", !gas.tryActivate("dash"), "");

        gas.update(2.1f);
        check("gas: cooldown expires", !gas.onCooldown("dash"), "");

        AbilitySystem.Ability expensive = new AbilitySystem.Ability("ult").costs("mana", 999f).runs(o -> true);
        gas.register(expensive);
        check("gas: unaffordable cost refuses", !gas.tryActivate("ult"), "");
    }

    // =====================================================================
    static void waves(){
        final List<String> spawned = new ArrayList<>();
        List<String> events = new ArrayList<>();
        WaveDirector d = new WaveDirector(
            new WaveDirector.WaveDef().enemy("grunt", 3).enemy("brute", 1).pace(0.05f).rest(0.2f),
            new WaveDirector.WaveDef().enemy("grunt", 2).pace(0.05f).rest(0.2f));
        d.spawner = (type, wave) -> { spawned.add(wave + ":" + type); return true; };
        d.listener = (type, wave, detail) -> events.add(type + "@" + wave);
        d.start();
        for(int i = 0; i < 600 && d.state != WaveDirector.State.DONE; i++){
            d.update(1f / 30f);
            if(d.state == WaveDirector.State.FIGHTING) d.notifyDeath();   // game layer reports kills
        }
        check("waves: all spawned", spawned.size() == 6, "" + spawned.size());
        check("waves: wave 0 composition", spawned.contains("0:grunt") && spawned.contains("0:brute"), "");
        check("waves: wave 1 runs after clear", events.contains("wave_start@1"), events.toString());
        check("waves: completion event", events.contains("all_waves_done@1"), events.toString());
        check("waves: deaths tracked", d.totalKilled == 6 && d.totalSpawned == 6,
              "killed=" + d.totalKilled + " spawned=" + d.totalSpawned);

        // endless mode scales counts
        WaveDirector endless = new WaveDirector(new WaveDirector.WaveDef().enemy("grunt", 2).pace(0.05f).rest(0.1f));
        endless.endless = true;
        endless.loopScale = 2f;
        final int[] spawnedCount = {0};
        endless.spawner = (type, wave) -> { spawnedCount[0]++; return true; };
        endless.start();
        for(int i = 0; i < 600 && endless.loopCount() < 1; i++){
            endless.update(1f / 30f);
            if(endless.state == WaveDirector.State.FIGHTING) endless.notifyDeath();
        }
        check("waves: endless loop triggers", endless.loopCount() == 1, "loops=" + endless.loopCount());
        for(int i = 0; i < 600 && spawnedCount[0] < 4; i++){
            endless.update(1f / 30f);
            if(endless.state == WaveDirector.State.FIGHTING) endless.notifyDeath();
        }
        check("waves: loop scales counts (2→4)", spawnedCount[0] >= 4, "" + spawnedCount[0]);

        String json = d.toJson();
        WaveDirector d2 = WaveDirector.fromJson(json);
        check("waves: json roundtrip", d2.waves.size() == d.waves.size()
              && d2.waves.get(0).total() == d.waves.get(0).total(), d2.waves.size() + " waves");
    }

    // =====================================================================
    static void replay(){
        Replay r = new Replay();
        r.epsilon = 0.01f;
        // moving entity: x walks 0 → 10 over 100 ticks
        for(long t = 0; t <= 100; t++)
            r.record(t, "player.pos", t * 0.1f, 5f, 0f, 90f);
        int moveKeys = r.keyCount();
        check("replay: linear walk compresses", moveKeys < 10, "keys=" + moveKeys);
        float[] out = new float[4];
        boolean ok = r.sample(50, "player.pos", out);
        check("replay: interpolated sample", ok && Math.abs(out[0] - 5f) < 0.01f, "x=" + out[0]);
        boolean atEnd = r.sample(100, "player.pos", out);
        check("replay: endpoint sample", atEnd && Math.abs(out[0] - 10f) < 0.01f, "x=" + out[0]);

        // stationary channel collapses to a single key
        for(long t = 0; t <= 100; t++) r.record(t, "player.health", 100f, 0, 0, 0);
        check("replay: stationary channel → 1 key", r.keyCount() - moveKeys == 1,
              "" + (r.keyCount() - moveKeys));

        String json = r.save();
        Replay r2 = Replay.load(json);
        float[] out2 = new float[4];
        boolean ok2 = r2.sample(73, "player.pos", out2);
        check("replay: save/load roundtrip", ok2 && Math.abs(out2[0] - 7.3f) < 0.01f, "x=" + out2[0]);
        check("replay: channels preserved", r2.channelCount() == r.channelCount(), "" + r2.channelCount());
        check("replay: duration ticks", r2.durationTicks() == 100, "" + r2.durationTicks());
    }
}
