import com.hollow.world.AstrologerSystem;
import com.hollow.core.MathX;

public class ProbeV13 {
    public static void main(String[] args){
        int charts = 0, clouded = 0, clearNo = 0;
        for(int s = 1; s <= 400; s++){
            AstrologerSystem p = new AstrologerSystem();
            p.seed = s;
            p.open("oak");
            p.tickDay();
            if(p.observatory("oak").charts > 0) charts++;
            else if(p.observatory("oak").cloudedNights > 0) clouded++;
            else clearNo++;
        }
        System.out.println("charts=" + charts + " clouded=" + clouded + " clearNo=" + clearNo);
        MathX.Rng r = new MathX.Rng(7L);
        System.out.println("two rolls seed7: " + r.range(0f, 1f) + " then " + r.range(0f, 1f));
    }
}
