import com.maowu.gravitypet.gengpet.RecycleGesture;

/** Behavioral regressions for a bottom-resting pet and deliberate recycling. No Android runtime. */
public final class RecycleGestureTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void invalid(float distance,long dwell){boolean rejected=false;try{new RecycleGesture(distance,dwell);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"reject invalid configuration");}
    public static void main(String[] args){
        RecycleGesture g=new RecycleGesture(72,350);
        check(!g.armed(),"initially safe");
        g.move(100,100,true,1000);check(!g.armed(),"move without begin is ignored");
        g.begin(10,900,0);check(!g.armed(),"down over recycle region cannot arm");
        g.move(12,900,true,10);g.move(12,900,true,2000);check(!g.armed(),"tiny bottom drag and long hold cannot recycle");
        g.begin(0,0,0);g.move(71.9f,0,true,10);g.move(71.9f,0,true,1000);check(!g.armed(),"below distance threshold remains safe");
        g.move(72,0,true,1100);check(!g.armed(),"distance threshold starts a new dwell, not earlier inside time");
        g.move(72,0,true,1449);check(!g.armed(),"349ms dwell is insufficient");
        g.move(72,0,true,1450);check(g.armed(),"72 pixels plus exactly 350ms arms");
        g.move(72,0,false,1451);check(!g.armed(),"exit immediately disarms");
        g.move(72,0,true,1452);check(!g.armed(),"reentry must dwell again");
        g.move(72,0,true,1801);check(!g.armed(),"reentry does not inherit old dwell");
        g.move(72,0,true,1802);check(g.armed(),"reentry full dwell arms");
        g.reset();check(!g.armed(),"reset removes armed state");
        g.move(72,0,true,9999);check(!g.armed(),"stale timer after reset cannot arm");
        g.begin(0,0,0);g.move(100,0,false,10);g.move(100,0,false,2000);check(!g.armed(),"large movement outside never arms");
        g.move(100,0,true,2010);check(!g.armed(),"entering late needs fresh dwell");
        // UP deliberately performs no move, even if its coordinates would be in the zone.
        check(!g.armed(),"UP read cannot first arm or consume earlier outside time");g.reset();
        g.begin(0,0,0);g.move(40,0,false,10);g.move(0,0,true,20);g.move(0,0,true,370);check(g.armed(),"cumulative 80-pixel path qualifies, not just final displacement");
        g.begin(0,0,400);check(!g.armed(),"new gesture does not inherit armed or distance");
        g.move(1,0,true,1000);check(!g.armed(),"new gesture starts distance at zero");
        g.begin(0,0,0);g.move(45,60,true,10);g.move(45,60,true,360);check(g.armed(),"75-pixel diagonal Euclidean path qualifies");
        g.begin(0,0,1000);g.move(80,0,true,1010);g.move(80,0,true,1360);check(g.armed(),"normal monotonic dwell");
        g.move(80,0,true,900);check(!g.armed(),"timestamp rollback disarms");g.move(80,0,true,901);check(!g.armed(),"timestamp rollback restarts dwell");
        g.move(80,0,true,1251);check(g.armed(),"fresh dwell after rollback can complete");
        g.move(Float.NaN,0,true,1252);check(!g.armed(),"invalid coordinate cancels safely");g.move(100,0,true,5000);check(!g.armed(),"invalid gesture cannot be resumed without begin");
        g.begin(Float.POSITIVE_INFINITY,0,0);g.move(100,0,true,1000);check(!g.armed(),"invalid starting coordinate rejected");
        RecycleGesture zero=new RecycleGesture(72,0);zero.begin(0,0,0);check(!zero.armed(),"zero dwell still cannot arm on down");zero.move(72,0,true,0);check(zero.armed(),"zero dwell configuration works on move");
        invalid(0,350);invalid(-1,350);invalid(Float.NaN,350);invalid(Float.POSITIVE_INFINITY,350);invalid(72,-1);
        System.out.println("PASS "+checks+" RecycleGesture behavioral assertions. No Service/Sprite changes.");
    }
}
