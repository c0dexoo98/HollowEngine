import com.hollow.game.CourierSystem;
import com.hollow.game.DebtSystem;
import com.hollow.game.HeraldSystem;
import com.hollow.game.SpyNetworkSystem;
import com.hollow.game.WagerSystem;
import com.hollow.game.WonderSystem;
import com.hollow.world.LighthouseSystem;
import com.hollow.world.MarketDaySystem;
import com.hollow.world.SettlementSystem;

import java.util.ArrayList;
import java.util.List;

/** v10.0.0 regression suite — wonders, edicts, couriers, wagers, debts,
 *  spies, market days and lighthouses. */
public class VerifyV10 {
    static int fails = 0;
    static int total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++;
        if(!ok) fails++;
    }

    public static void main(String[] args){
        wonders();
        herald();
        courier();
        wager();
        debt();
        spies();
        marketDays();
        lighthouses();
        System.out.println(fails == 0 ? "\nALL " + total + " V10 TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " V10 TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // =====================================================================
    static void wonders(){
        WonderSystem ws = new WonderSystem();
        final List<String> events = new ArrayList<>();
        ws.listener = (type, id, detail) -> events.add(type);

        check("wond: invalid define refused", ws.define(null) == null
              && ws.define(new WonderSystem.Wonder("", "x", WonderSystem.Kind.MORALE, 1f, 3)) == null, "");
        WonderSystem.Wonder colossus = ws.define(
            new WonderSystem.Wonder("colossus", "Colossus of Bronze", WonderSystem.Kind.MORALE, 0.2f, 3));
        check("wond: start refuses unknown", !ws.start("nowhere"), "");
        check("wond: start ok then refuses restart", ws.start("colossus") && !ws.start("colossus")
              && colossus.progress > 0, "");
        ws.tickDay();
        check("wond: not started does not advance", ws.define(
            new WonderSystem.Wonder("tower", "Signal Tower", WonderSystem.Kind.RESEARCH, 0.1f, 2)).progress == 0f, "");
        ws.tickDay(); ws.tickDay();
        check("wond: completed after build days", colossus.done && ws.completedCount() == 1
              && events.contains("completed"), "" + colossus.progress);
        check("wond: realm bonus of a kind", Math.abs(ws.realmBonus(WonderSystem.Kind.MORALE) - 0.2f) < 0.001f
              && ws.realmBonus(WonderSystem.Kind.TRADE) == 0f, "");

        ws.start("tower"); ws.tickDay(); ws.tickDay();
        ws.define(new WonderSystem.Wonder("bazaar", "Grand Bazaar", WonderSystem.Kind.MORALE, 0.1f, 2));
        ws.start("bazaar"); ws.tickDay(); ws.tickDay();
        check("wond: bonuses stack", Math.abs(ws.realmBonus(WonderSystem.Kind.MORALE) - 0.3f) < 0.001f
              && ws.completedCount() == 3, "" + ws.realmBonus(WonderSystem.Kind.MORALE));

        String json = ws.save();
        WonderSystem ws2 = new WonderSystem();
        ws2.load(json);
        check("wond: save/load", ws2.completedCount() == 3 && ws2.wonder("colossus").done
              && Math.abs(ws2.realmBonus(WonderSystem.Kind.RESEARCH) - 0.1f) < 0.001f, "");
    }

    // =====================================================================
    static void herald(){
        HeraldSystem hs = new HeraldSystem();
        final List<String> events = new ArrayList<>();
        hs.listener = (type, id, detail) -> events.add(type);

        check("hera: invalid proclaim refused", hs.proclaim("", "tax", 1f, 2) == null
              && hs.proclaim("x", "", 1f, 2) == null, "");
        HeraldSystem.Edict tax = hs.proclaim("Tax Holiday", "tax", 0.2f, 2);
        HeraldSystem.Edict curfew = hs.proclaim("Curfew", "curfew", 0.5f, 4);
        check("hera: in force", tax != null && hs.inForce(tax.id) && hs.activeIds().size() == 2, "");
        check("hera: effect sums per type", Math.abs(hs.effectOf("tax") - 0.2f) < 0.001f
              && Math.abs(hs.effectOf("curfew") - 0.5f) < 0.001f, "");

        HeraldSystem.Edict tax2 = hs.proclaim("Second Levy Cut", "tax", 0.1f, 5);
        check("hera: same-type edicts stack", Math.abs(hs.effectOf("tax") - 0.3f) < 0.001f, "");

        hs.tickDay(); hs.tickDay();
        check("hera: lapses after duration", !hs.inForce(tax.id) && events.contains("lapsed")
              && hs.inForce(tax2.id) && hs.inForce(curfew.id), events.toString());
        check("hera: lapsed effect vanishes", Math.abs(hs.effectOf("tax") - 0.1f) < 0.001f, "");

        String json = hs.save();
        HeraldSystem hs2 = new HeraldSystem();
        hs2.load(json);
        check("hera: save/load", hs2.inForce(tax2.id) && !hs2.inForce(tax.id)
              && Math.abs(hs2.effectOf("curfew") - 0.5f) < 0.001f, "");
    }

    // =====================================================================
    static void courier(){
        CourierSystem cs = new CourierSystem();
        final List<String> events = new ArrayList<>();
        cs.listener = (type, id, detail) -> events.add(type);

        check("cour: invalid send refused", cs.send("oak", "oak", "hi", 2, 0f) == null
              && cs.send("oak", "iron", "", 2, 0f) == null, "");
        CourierSystem.Letter safe = cs.send("oak", "iron", "harvest is rich", 2, 0f);
        CourierSystem.Letter doomed = cs.send("oak", "iron", "secret plans", 2, 1f);
        check("cour: loss roll deterministic", safe != null && doomed != null
              && !safe.lost && doomed.lost && cs.pendingCount() == 2 && events.contains("sent"), "");

        cs.tickDay();
        check("cour: in transit", cs.pendingCount() == 2 && cs.mailboxOf("iron").isEmpty(), "");
        cs.tickDay();
        check("cour: arrival resolves both", cs.pendingCount() == 0 && safe.delivered && doomed.delivered
              && events.contains("delivered") && events.contains("lost"), "");
        check("cour: mailbox only holds real mail", cs.mailboxOf("iron").size() == 1
              && cs.mailboxOf("iron").get(0).body.equals("harvest is rich") && cs.lostCount() == 1, "");

        String json = cs.save();
        CourierSystem cs2 = new CourierSystem();
        cs2.load(json);
        check("cour: save/load", cs2.letters.get(safe.id).delivered && !cs2.letters.get(safe.id).lost
              && cs2.letters.get(doomed.id).lost && cs2.day == cs.day, "");
    }

    // =====================================================================
    static void wager(){
        WagerSystem wg = new WagerSystem();
        final List<String> events = new ArrayList<>();
        wg.listener = (type, id, detail) -> events.add(type);

        check("wage: invalid bets refused", wg.place("", 1, "Aldric", 10, 2f) == null
              && wg.place("hana", 1, "Aldric", 0, 2f) == null
              && wg.place("hana", 1, "Aldric", 10, 0.5f) == null, "");
        WagerSystem.Bet win = wg.place("hana", 1, "Aldric", 100, 2f);
        WagerSystem.Bet lose = wg.place("mori", 1, "Bors", 50, 3f);
        check("wage: placed", win != null && lose != null && wg.betCount() == 2
              && wg.unsettledOf(1).size() == 2 && events.contains("placed"), "");

        wg.settle(1, "Aldric");
        check("wage: winner paid stake x odds", win.settled && win.won && win.payout == 200
              && wg.profitOf("hana") == 100, "" + win.payout);
        check("wage: loser forfeits stake", lose.settled && !lose.won && lose.payout == 0
              && wg.profitOf("mori") == -50, "");
        check("wage: house nets the margin", wg.houseNet == -100 + 50, "" + wg.houseNet);

        int before = wg.houseNet;
        wg.settle(1, "Aldric");                          // second call must not re-settle
        check("wage: settle idempotent", wg.houseNet == before && events.size() == 4, "" + events.size());

        String json = wg.save();
        WagerSystem wg2 = new WagerSystem();
        wg2.load(json);
        check("wage: save/load", wg2.betCount() == 2 && wg2.houseNet == wg.houseNet
              && wg2.bets.get(win.id).won && wg2.bets.get(win.id).payout == 200, "");
    }

    // =====================================================================
    static void debt(){
        DebtSystem ds = new DebtSystem();
        final List<String> events = new ArrayList<>();
        ds.listener = (type, id, detail) -> events.add(type);

        check("debt: invalid borrow refused", ds.borrow("", 100, 1, 5) == null
              && ds.borrow("bank", 0, 1, 5) == null && ds.borrow("bank", 100, -1, 5) == null, "");
        DebtSystem.Loan loan = ds.borrow("iron bank", 200, 10, 3);
        check("debt: principal outstanding", loan != null && loan.remaining == 200
              && ds.outstanding() == 200 && events.contains("borrowed"), "");

        ds.tickDay();
        check("debt: interest accrues", loan.remaining == 210, "" + loan.remaining);
        check("debt: partial repayment", ds.repay(loan.id, 60) == 60 && loan.remaining == 150
              && loan.repaid == 60 && events.contains("repaid"), "");
        check("debt: overpay capped", ds.repay(loan.id, 999) == 150 && loan.remaining == 0
              && ds.repay(loan.id, 10) == 0 && events.contains("cleared"), "");
        check("debt: cleared not outstanding", ds.outstanding() == 0 && ds.defaultedCount() == 0, "");

        DebtSystem.Loan bad = ds.borrow("gaul lender", 100, 5, 1);
        ds.tickDay();                                    // day 1 == dueDay, not overdue yet
        check("debt: due day is grace", !bad.defaulted && bad.remaining == 105, "" + bad.remaining);
        ds.tickDay();                                    // day 2 > dueDay 1 → default
        check("debt: overdue defaults", bad.defaulted && ds.defaultedCount() == 1
              && events.contains("defaulted"), events.toString());
        check("debt: recovery after default", ds.repay(bad.id, 40) == 40 && bad.remaining == 70, "");

        String json = ds.save();
        DebtSystem ds2 = new DebtSystem();
        ds2.load(json);
        check("debt: save/load", ds2.loans.size() == 2 && ds2.loans.get(bad.id).defaulted
              && ds2.loans.get(loan.id).remaining == 0 && ds2.outstanding() == ds.outstanding(), "");
    }

    // =====================================================================
    static void spies(){
        SpyNetworkSystem ss = new SpyNetworkSystem();
        final List<String> events = new ArrayList<>();
        ss.listener = (type, name, detail) -> events.add(type);

        check("spy: invalid recruit refused", !ss.recruit("", "oak", 0.5f)
              && !ss.recruit("kas", "", 0.5f), "");
        check("spy: recruited", ss.recruit("kas", "oak", 1f) && ss.liveCount() == 1, "");
        check("spy: duplicate refused", !ss.recruit("kas", "iron", 0.9f), "");
        check("spy: intel none", ss.intel("iron") == 0f && Math.abs(ss.intel("oak") - 1f) < 0.001f, "");
        check("spy: no informant no plot", !ss.uncoverPlot("smuggling", "iron"), "");

        check("spy: perfect reliability uncovers", ss.uncoverPlot("grain fraud", "oak")
              && ss.informants.get("kas").uncovered == 1 && events.contains("uncovered"), "");
        ss.recruit("wren", "iron", 0f);
        boolean ok = ss.uncoverPlot("tunnel", "iron");   // reliability 0 always fails
        check("spy: zero reliability fails", !ok
              && (ss.liveCount() == 2 || events.contains("burned")), "" + ss.liveCount());

        check("spy: manual burn", ss.burn("kas") && !ss.burn("kas")
              && ss.informants.get("kas").burned && ss.intel("oak") == 0f
              && ss.liveCount() == (ss.informants.get("wren").burned ? 0 : 1), "");

        String json = ss.save();
        SpyNetworkSystem ss2 = new SpyNetworkSystem();
        ss2.load(json);
        check("spy: save/load", ss2.informants.size() == ss.informants.size()
              && ss2.informants.get("kas").burned && ss2.informants.get("kas").uncovered == 1, "");
    }

    // =====================================================================
    static void marketDays(){
        SettlementSystem world = new SettlementSystem();
        SettlementSystem.Settlement oak = world.found("oak", "Oakstead");
        MarketDaySystem md = new MarketDaySystem(world);
        final List<String> events = new ArrayList<>();
        md.listener = (type, s, c) -> events.add(type);

        check("mktd: unknown settlement refused", !md.register("nowhere", 3) && !md.register("oak", 0), "");
        check("mktd: registered", md.register("oak", 3), "");
        check("mktd: day 0 is market day", md.isMarketDay("oak") && md.priceBoost("oak") == 1.25f
              && md.crowdOf("oak") == oak.pop / 2, "");

        md.tickDay();                                    // day 1
        check("mktd: quiet day between markets", !md.isMarketDay("oak") && md.priceBoost("oak") == 1f
              && md.crowdOf("oak") == 0 && events.isEmpty(), "");

        md.tickDay(); md.tickDay();                      // day 3
        check("mktd: market fires on cadence", md.isMarketDay("oak") && events.size() == 1
              && events.contains("market_day"), "" + events.size());
        check("mktd: crowd follows population", md.crowdOf("oak") == oak.pop / 2, "");

        md.unregister("oak");
        check("mktd: unregistered goes quiet", !md.isMarketDay("oak") && md.crowdOf("oak") == 0, "");

        MarketDaySystem md2 = new MarketDaySystem(world);
        md2.register("oak", 3);
        String json = md2.save();
        MarketDaySystem md3 = new MarketDaySystem(world);
        md3.load(json);
        check("mktd: save/load", md3.day == 0 && md3.isMarketDay("oak")
              && md3.priceBoost("oak") == 1.25f, "");
    }

    // =====================================================================
    static void lighthouses(){
        LighthouseSystem ls = new LighthouseSystem();
        final List<String> events = new ArrayList<>();
        ls.listener = (type, s, v) -> events.add(type);

        check("lght: invalid build refused", !ls.build(null) && !ls.build(""), "");
        check("lght: built + duplicate refused", ls.build("portsmouth") && !ls.build("portsmouth")
              && ls.lightCount() == 1, "");
        ls.setLit("portsmouth", false);
        check("lght: dark coast is deadly", Math.abs(ls.wreckChance("portsmouth") - 0.10f) < 0.001f, "");
        ls.setLit("portsmouth", true);
        check("lght: lit level1 chance", Math.abs(ls.wreckChance("portsmouth") - 0.045f) < 0.001f, "");
        check("lght: upgrades raise protection", ls.upgrade("portsmouth") == 2
              && Math.abs(ls.wreckChance("portsmouth") - 0.03f) < 0.001f
              && ls.upgrade("portsmouth") == 3
              && Math.abs(ls.wreckChance("portsmouth") - 0.015f) < 0.001f
              && ls.upgrade("portsmouth") == -1, "");
        check("lght: upkeep scales with level", ls.upkeepOf("portsmouth") == 15 && ls.upkeepOf("nowhere") == 0, "");

        boolean first = ls.shipPasses("portsmouth", 777L);
        boolean second = ls.shipPasses("portsmouth", 777L);
        check("lght: ship roll deterministic", first == second
              && events.contains(first ? "wrecked" : "safe"), first + "/" + second);
        LighthouseSystem.Lighthouse light = ls.light("portsmouth");
        check("lght: safe passes guided", first ? light.shipsGuided == 0 : light.shipsGuided == 2, "");

        String json = ls.save();
        LighthouseSystem ls2 = new LighthouseSystem();
        ls2.load(json);
        check("lght: save/load", ls2.light("portsmouth") != null && ls2.light("portsmouth").level == 3
              && ls2.light("portsmouth").shipsGuided == light.shipsGuided, "");
    }
}
