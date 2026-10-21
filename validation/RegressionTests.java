import mi14.audiofx.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fault injection at the production EQ port and production stage coordinator; no Android DSP simulation claim. */
public final class RegressionTests {
    private static int count;
    private static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);count++;System.out.println("PASS "+name);}
    private static final class Port implements MiSoundEq.Port {
        float[] gains=new float[10];int reads,writes,rejectBand=-1;boolean unsupported,denied,stale,malformed,oversize;
        List<Integer> lengths=new ArrayList<>();List<String> events=new ArrayList<>();
        public int get(int band,byte[] value){
            reads++;lengths.add(value.length);
            if(denied)throw new SecurityException("denied");
            if(unsupported||value.length!=10)return -4;
            if(oversize)return 32;
            byte[] text=(malformed?"bad":Float.toString(stale?0:gains[band])).getBytes(StandardCharsets.UTF_8);
            System.arraycopy(text,0,value,0,text.length);return 10;
        }
        public int set(int band,byte[] value){writes++;events.add("set:"+band);if(band==rejectBand)return -4;gains[band]=Float.parseFloat(new String(value,StandardCharsets.UTF_8));return 0;}
        public void trace(String k,String v){events.add(k+":"+v);}
    }
    private static boolean throwsApply(Port p){try{MiSoundEq.apply(p,EqMath.WARM);return false;}catch(Exception e){return true;}}
    public static void main(String[] args)throws Exception{
        Port p=new Port();MiSoundEq.Result r=MiSoundEq.apply(p,EqMath.WARM);
        check("stock 10-byte getter succeeds where V6 32-byte getter failed",r.status==ApplyStages.EqStatus.VERIFIED&&new HashSet<Integer>(p.lengths).equals(Collections.singleton(10)));
        check("wire gain values match supplied preset",Arrays.equals(p.gains,new float[]{0,1.5f,3,1.5f,0,0,-1.5f,-1.5f,-1.5f,0}));
        check("only six changed bands written",p.writes==6&&r.changed);
        MiSoundEq.Result unchanged=MiSoundEq.apply(p,EqMath.WARM);
        check("independent verification avoids repeating writes",!unchanged.changed&&p.writes==6);
        Port flat=new Port();MiSoundEq.Result f=MiSoundEq.apply(flat,new int[10]);check("flat zero really read back and not assumed",f.status==ApplyStages.EqStatus.VERIFIED&&flat.writes==0&&flat.reads==20);
        Port unsupported=new Port();unsupported.unsupported=true;
        MiSoundEq.Result u=MiSoundEq.apply(unsupported,EqMath.WARM);
        check("unsupported getter still allows exact stock setter",unsupported.writes==10&&u.changed);
        check("write accepted is explicitly unverified, never PASS",u.status==ApplyStages.EqStatus.UNVERIFIED);
        check("unsupported getter is bounded to before and after, not ten repeated failures",unsupported.reads==2);
        Port badSet=new Port();badSet.unsupported=true;badSet.rejectBand=3;
        check("native setter error stops remaining bands",throwsApply(badSet)&&badSet.writes==4);
        Port denied=new Port();denied.denied=true;check("permission failure never triggers write fallback",throwsApply(denied)&&denied.writes==0);
        Port malformed=new Port();malformed.malformed=true;check("malformed read never treated as flat",throwsApply(malformed)&&malformed.writes==0);
        Port oversize=new Port();oversize.oversize=true;check("invalid native length rejected before write",throwsApply(oversize)&&oversize.writes==0);
        Port stale=new Port();stale.stale=true;check("accepted write with stale native readback fails",throwsApply(stale));
        List<String> order=new ArrayList<>();
        ApplyStages.Result failure=ApplyStages.run(()->{order.add("native verified");order.add("settings committed");},()->{order.add("EQ getter");throw new IllegalStateException("MiSound status -4");});
        check("EQ error occurs after complete effect commit",order.equals(Arrays.asList("native verified","settings committed","EQ getter")));
        check("EQ error does not convert mode success to mode failure",failure.effectError==null&&failure.eqStatus==ApplyStages.EqStatus.FAILED&&failure.eqError!=null);
        check("partial result has distinct exit code",ApplyStages.exitCode(true,failure.eqStatus)==5);
        int[] eqCalls={0};ApplyStages.Result effectFailure=ApplyStages.run(()->{throw new IllegalStateException("wrong route");},()->{eqCalls[0]++;return ApplyStages.EqStatus.VERIFIED;});
        check("failed route or mode stops every EQ write",effectFailure.effectError!=null&&eqCalls[0]==0&&effectFailure.eqStatus==ApplyStages.EqStatus.NOT_RUN);
        ApplyStages.Result readbackUnavailable=ApplyStages.run(()->{},()->ApplyStages.EqStatus.UNVERIFIED);
        check("unverified EQ has separate non-success exit code",ApplyStages.exitCode(true,readbackUnavailable.eqStatus)==6);
        check("core failure dominates EQ success",ApplyStages.exitCode(false,ApplyStages.EqStatus.VERIFIED)==2);
        check("verified EQ and intentionally bypassed EQ succeed",ApplyStages.exitCode(true,ApplyStages.EqStatus.VERIFIED)==0&&ApplyStages.exitCode(true,ApplyStages.EqStatus.SKIPPED)==0);
        System.out.println("REGRESSION_RESULT="+count+" passed");
    }
}
