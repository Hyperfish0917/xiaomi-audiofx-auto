import mi14.audiofx.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Faults and delayed observations at the production coordinator; no DSP simulation. */
public final class StockEqTests {
    static int count;
    static void check(String name, boolean ok) {
        if(!ok)throw new AssertionError(name);count++;System.out.println("PASS "+name);
    }
    static class Dolby implements DolbyEq.Port {
        int[] curve=new int[20];int enable=0,reads,writes,saves;boolean failRead,failWrite;
        public int profiles(){return 1;}
        public int[] gains(int p){reads++;if(failRead)throw new IllegalStateException("Dolby unreadable");return curve.clone();}
        public int enabled(int p){return enable;}
        public void gains(int p,int[] v){writes++;if(failWrite)throw new IllegalStateException("route changed");curve=v.clone();}
        public void enable(int p){writes++;enable=1;}
        public int save(int p,int command){saves++;return 0;}
        public void trace(String p,int n,int[] gains,int enabled){}
    }
    static class Mi implements MiSoundEq.Port {
        float[] curve=new float[10];int reads,writes;boolean reject,unsupported;
        public int get(int band,byte[] out) {
            reads++;if(unsupported)return -4;
            byte[] v=Float.toString(curve[band]).getBytes(StandardCharsets.UTF_8);
            System.arraycopy(v,0,out,0,v.length);return 10;
        }
        public int set(int band,byte[] v){writes++;if(reject)return -4;curve[band]=Float.parseFloat(new String(v,StandardCharsets.UTF_8));return 0;}
        public void trace(String key,String value){}
    }
    public static void main(String[] args) {
        Dolby d=new Dolby();Mi m=new Mi();
        StockEq.Result r=StockEq.apply(d,m,EqMath.WARM,false);
        check("both stock parameter paths receive requested warm curve",Arrays.equals(d.curve,EqMath.dolby(EqMath.WARM))&&Arrays.equals(m.curve,EqMath.miSound(EqMath.WARM)));
        check("known unsupported getter never called during dual write",m.reads==0&&m.writes==10);
        check("Dolby success does not mask unreadable MiSound",r.dolby.status==ApplyStages.EqStatus.VERIFIED&&r.miSound.status==ApplyStages.EqStatus.UNVERIFIED&&r.status()==ApplyStages.EqStatus.UNVERIFIED);
        int dw=d.writes,ms=m.writes,save=d.saves,dr=d.reads;
        r=StockEq.observe(d,m,EqMath.WARM,false,"later");
        check("delayed observation still reads Dolby when MiSound cannot be read",d.reads>dr&&m.reads==0&&r.status()==ApplyStages.EqStatus.UNVERIFIED);
        check("independent observation changes neither backend nor saves",d.writes==dw&&m.writes==ms&&d.saves==save);
        d.curve=new int[20];r=StockEq.observe(d,m,EqMath.WARM,false,"drift");
        check("Dolby drift dominates MiSound unverified result",r.drift()&&r.status()==ApplyStages.EqStatus.FAILED&&r.miSound.status==ApplyStages.EqStatus.UNVERIFIED);
        check("detected drift is preserved, not silently repaired",Arrays.equals(d.curve,new int[20])&&d.writes==dw&&m.writes==ms&&d.saves==save);
        Dolby bad=new Dolby();bad.failRead=true;Mi independent=new Mi();
        r=StockEq.apply(bad,independent,EqMath.WARM,false);
        check("Dolby failure retained while independent MiSound path is attempted",r.status()==ApplyStages.EqStatus.FAILED&&r.dolby.error!=null&&independent.writes==10&&bad.writes==0);
        Dolby good=new Dolby();Mi reject=new Mi();reject.reject=true;
        r=StockEq.apply(good,reject,EqMath.WARM,false);
        check("MiSound native rejection never hidden behind successful Dolby",r.status()==ApplyStages.EqStatus.FAILED&&r.dolby.status==ApplyStages.EqStatus.VERIFIED&&r.miSound.error!=null&&reject.writes==1);
        Dolby guard=new Dolby();guard.failWrite=true;Mi guarded=new Mi();guarded.reject=true;
        r=StockEq.apply(guard,guarded,EqMath.WARM,false);
        check("each guarded path stops after its first rejected write",r.status()==ApplyStages.EqStatus.FAILED&&guard.writes==1&&guard.saves==0&&guarded.writes==1);
        Dolby both=new Dolby();Mi supported=new Mi();
        r=StockEq.apply(both,supported,EqMath.WARM,true);
        check("verified aggregate requires both native readbacks",r.status()==ApplyStages.EqStatus.VERIFIED&&supported.reads==20);
        supported.curve[2]=0;r=StockEq.observe(both,supported,EqMath.WARM,true,"mi-drift");
        check("MiSound-only drift is detected independently",r.miSound.drift&&r.dolby.status==ApplyStages.EqStatus.VERIFIED&&r.status()==ApplyStages.EqStatus.FAILED);
        Mi unavailable=new Mi();unavailable.unsupported=true;
        r=StockEq.observe(both,unavailable,EqMath.WARM,true,"unknown-firmware");
        check("new unsupported reader reports unverified without any writes",r.status()==ApplyStages.EqStatus.UNVERIFIED&&unavailable.reads==1&&unavailable.writes==0);
        r=StockEq.apply(both,supported,new int[10],false);
        check("flat actively resets both warm curves",Arrays.equals(both.curve,new int[20])&&Arrays.equals(supported.curve,new float[10])&&r.changed());
        Dolby invalid=new Dolby();Mi invalidMi=new Mi();boolean rejected=false;
        try{StockEq.apply(invalid,invalidMi,new int[9],false);}catch(IllegalArgumentException e){rejected=true;}
        check("invalid curve rejected before either backend is touched",rejected&&invalid.reads==0&&invalid.writes==0&&invalidMi.reads==0&&invalidMi.writes==0);
        System.out.println("STOCK_EQ_RESULT="+count+" passed");
    }
}
