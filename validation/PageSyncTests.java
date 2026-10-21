import java.util.Arrays;
import mi14.audiofx.Config;
import mi14.audiofx.eqpage.PageSyncRequest;
import mi14.audiofx.eqpage.PageStability;
/** Config and animated-page regressions, not Android UI emulation. */
public final class PageSyncTests {
    private static int checks;
    private static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    private static void rejects(Runnable action,String name){boolean failed=false;try{action.run();}catch(IllegalArgumentException e){failed=true;}check(failed,name);}
    public static void main(String[] args){
        Config c=Config.defaults();String original=c.json();
        PageSyncRequest r=new PageSyncRequest(c,Config.SPEAKER);
        check(Arrays.equals(r.target,new int[]{0,3,6,3,0,0,-3,-3,-3,0}),"warm curve");
        PageSyncRequest copy=PageSyncRequest.parse(r.json());
        check(copy.key.equals(Config.SPEAKER)&&Arrays.equals(copy.target,r.target),"payload roundtrip");
        check(c.json().equals(original),"saved config unchanged");
        rejects(()->new PageSyncRequest(c,"arbitrary"),"unknown key");
        rejects(()->new PageSyncRequest(c,Config.DEFAULT_BT),"original sound");
        Config keep=Config.defaults();keep.rules.get(Config.SPEAKER).eq=Config.KEEP;
        rejects(()->new PageSyncRequest(keep,Config.SPEAKER),"keep EQ");
        rejects(()->PageSyncRequest.parse("{}"),"bad payload");
        for(int p:new int[]{-100,-25,-5,5,25,100})check(PageSyncRequest.quantize(p)==Math.round(p*0.12f),"quantized "+p);
        rejects(()->PageSyncRequest.quantize(101),"bounds");
        PageStability s=new PageStability();int[] a={0,3,6},b={0,4,6};
        check(!s.observe(1,"A",a,0),"first sample");
        check(!s.observe(1,"A",a,300),"two samples");
        check(s.observe(1,"A",a,600),"stable after 600ms");
        check(!s.observe(2,"A",a,800),"new window resets");
        check(!s.observe(2,"B",a,1000),"animation changes geometry");
        check(!s.observe(2,"B",a,1300),"animation still settling");
        check(s.observe(2,"B",a,1600),"settled animation accepted");
        check(!s.observe(2,"B",b,1800),"curve change resets");
        s.reset();check(!s.observe(2,"B",b,2400),"explicit reset");
        for(int i=1;i<10;i++)check(!s.observe(2,"B",i%2==0?b:a,2400+i*300),"unstable curve never passes");
        s.reset();int[] mutable={0};s.observe(1,"C",mutable,0);mutable[0]=3;
        check(!s.observe(1,"C",mutable,800),"snapshot values copied");
        System.out.println("PageSyncTests: "+checks+" checks passed");
    }
}
