import mi14.audiofx.*;
import java.util.*;

/** Exercises production DolbyEq with a native port substitute. No DSP/listening claim. */
public final class PersistenceTests {
    private static int count;
    private static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);count++;System.out.println("PASS "+name);}
    private static final class Port implements DolbyEq.Port {
        int n=3,saveStatus=0,saves,writes,reads,badRead=-1,rejectWrite=-1;
        int[][] curves=new int[3][20];int[] enabled=new int[3];
        boolean stale,loseOnSave;int saveParameter,saveCommand;
        List<String> events=new ArrayList<>();
        public int profiles(){return n;}
        public int[] gains(int p){reads++;events.add("read:"+p);if(p==badRead)throw new IllegalStateException("read failed");return curves[p].clone();}
        public int enabled(int p){return enabled[p];}
        public void gains(int p,int[] gains){writes++;events.add("write:"+p);if(p==rejectWrite)throw new IllegalStateException("native status -4");if(!stale)curves[p]=gains.clone();}
        public void enable(int p){writes++;events.add("enable:"+p);enabled[p]=1;}
        public int save(int parameter,int command){saves++;events.add("save");saveParameter=parameter;saveCommand=command;if(loseOnSave)curves[0]=new int[20];return saveStatus;}
        public void trace(String phase,int p,int[] gains,int enabled){}
    }
    private static boolean rejects(Port p){try{DolbyEq.apply(p,EqMath.WARM);return false;}catch(Exception e){return true;}}
    public static void main(String[] args)throws Exception {
        Port good=new Port();boolean changed=DolbyEq.apply(good,EqMath.WARM);
        check("all profiles verified before stock save",changed&&good.events.get(good.events.size()-1).equals("save")&&good.reads==6);
        check("stock save command is integer parameter 5 value 150994944",good.saves==1&&good.saveParameter==5&&good.saveCommand==150994944);
        int writes=good.writes;int saves=good.saves;
        check("read-only success performs no write or save",DolbyEq.matches(good,EqMath.WARM,"observe")&&good.writes==writes&&good.saves==saves);
        good.curves[1]=new int[20];
        check("later overwrite is detected without repairing it",!DolbyEq.matches(good,EqMath.WARM,"observe")&&good.writes==writes&&good.saves==saves&&Arrays.equals(good.curves[1],new int[20]));
        Port matching=new Port();for(int i=0;i<3;i++){matching.curves[i]=EqMath.dolby(EqMath.WARM);matching.enabled[i]=1;}
        check("matching prior transient curve is saved without band rewrites",!DolbyEq.apply(matching,EqMath.WARM)&&matching.writes==0&&matching.saves==1);
        Port unreadable=new Port();unreadable.badRead=2;
        check("last-profile preflight failure prevents every mutation",rejects(unreadable)&&unreadable.writes==0&&unreadable.saves==0);
        Port badCount=new Port();badCount.n=17;
        check("invalid profile count prevents reads and mutations",rejects(badCount)&&badCount.reads==0&&badCount.writes==0&&badCount.saves==0);
        Port badEnable=new Port();badEnable.enabled[2]=-4;
        check("invalid enable read is not treated as disabled",rejects(badEnable)&&badEnable.writes==0&&badEnable.saves==0);
        Port reject=new Port();reject.rejectWrite=1;
        check("rejected native write prevents save and remaining writes",rejects(reject)&&reject.saves==0&&!reject.events.contains("write:2"));
        Port stale=new Port();stale.stale=true;
        check("stale readback never saves unverified curve",rejects(stale)&&stale.saves==0);
        Port denied=new Port();denied.saveStatus=-4;
        check("save rejection fails rather than reporting persistent success",rejects(denied)&&denied.saves==1);
        Port changeAtSave=new Port();changeAtSave.loseOnSave=true;DolbyEq.apply(changeAtSave,EqMath.WARM);
        check("post-save drift remains visible to final/read-only verification",!DolbyEq.matches(changeAtSave,EqMath.WARM,"final")&&changeAtSave.saves==1);
        ApplyStages.Result isolated=ApplyStages.run(()->{},()->{DolbyEq.apply(denied,EqMath.WARM);return ApplyStages.EqStatus.VERIFIED;});
        check("save failure preserves independent successful effect status",isolated.effectError==null&&ApplyStages.exitCode(true,isolated.eqStatus)==5);
        final int[] reads={0},sets={0};
        MiSoundEq.Port known=new MiSoundEq.Port(){
            public int get(int b,byte[] v){reads[0]++;throw new AssertionError("known unsupported getter retried");}
            public int set(int b,byte[] v){sets[0]++;return 0;}
            public void trace(String k,String v){}
        };
        MiSoundEq.Result mi=MiSoundEq.apply(known,EqMath.WARM,false);
        check("known firmware never retries rejected MiSound getter",reads[0]==0&&sets[0]==10&&mi.status==ApplyStages.EqStatus.UNVERIFIED);
        System.out.println("PERSISTENCE_RESULT="+count+" passed");
    }
}
