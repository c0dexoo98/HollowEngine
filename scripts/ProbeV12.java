import com.hollow.game.BlacksmithSystem;
import com.hollow.world.BridgeSystem;
import com.hollow.core.MathX;

/** empirical probe for two VerifyV12 mysteries */
public class ProbeV12 {
    public static void main(String[] args){
        // mystery 1: forge save/load id continuity
        BlacksmithSystem bs = new BlacksmithSystem();
        bs.takeOrder("horseshoe", 2);
        bs.tickDay(); bs.tickDay();
        BlacksmithSystem.Commission c2 = bs.takeOrder("nail", 1);
        System.out.println("c2=" + (c2 == null ? "null" : c2.id));
        String json = bs.save();
        System.out.println("json=" + json);
        BlacksmithSystem bs2 = new BlacksmithSystem();
        bs2.load(json);
        System.out.println("bs2 nextId=" + (bs2.activeOrder() == null ? "no-active" : "active")
            + " orders=" + bs2.orders.size());
        bs2.tickDay();
        BlacksmithSystem.Commission c3 = bs2.takeOrder("blade", 1);
        System.out.println("c3=" + (c3 == null ? "null" : c3.id));

        // mystery 2: bridge day rolls across seeds
        int floods = 0, calm16 = 0, other = 0;
        for(int s = 1; s <= 400; s++){
            BridgeSystem probe = new BridgeSystem();
            probe.seed = s;
            probe.build("x", "oak", "iron", 2);
            probe.tickDay();
            if(probe.bridge("x").damaged) floods++;
            else if(probe.incomeOf("x") == 16) calm16++;
            else other++;
        }
        System.out.println("day1 floods=" + floods + " calm16=" + calm16 + " other=" + other);
        BridgeSystem one = new BridgeSystem();
        one.seed = 1;
        one.build("x", "oak", "iron", 2);
        one.tickDay();
        System.out.println("seed1 damaged=" + one.bridge("x").damaged
            + " income=" + one.incomeOf("x") + " cond=" + one.bridge("x").condition);
        MathX.Rng r = new MathX.Rng(1L ^ (1L * 211L) ^ "x".hashCode());
        for(int i = 0; i < 5; i++) System.out.println("roll " + r.range(0f, 1f));

        // mystery 3: bridge twins for day-1 flood seeds
        for(int s = 1; s <= 400; s++){
            BridgeSystem p = new BridgeSystem();
            p.seed = s;
            p.build("x", "oak", "iron", 2);
            p.tickDay();
            if(!p.bridge("x").damaged) continue;
            BridgeSystem t = new BridgeSystem();
            t.seed = s;
            t.build("x", "oak", "iron", 2);
            t.tickDay(); t.tickDay(); t.tickDay();
            System.out.println("floodSeed=" + s + " twin3 income=" + t.incomeOf("x")
                + " dmg=" + t.bridge("x").damaged + " cond=" + t.bridge("x").condition);
        }

        // mystery 4: forge loaded-order id after the nextId fix
        BlacksmithSystem b1 = new BlacksmithSystem();
        b1.takeOrder("horseshoe", 2);
        b1.tickDay(); b1.tickDay();
        b1.takeOrder("nail", 1);
        BlacksmithSystem b2 = new BlacksmithSystem();
        b2.load(b1.save());
        b2.tickDay();
        BlacksmithSystem.Commission cc = b2.takeOrder("blade", 1);
        System.out.println("forge c3=" + (cc == null ? "null" : cc.id)
            + " activeAfter=" + (b2.activeOrder() == null ? "none" : b2.activeOrder().item)
            + " nextIdState=" + b2.orders.size());
    }
}
