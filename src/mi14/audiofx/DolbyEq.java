package mi14.audiofx;

import java.util.Arrays;

/** GEQ transaction and stock DolbyEqActivity saveDsSetting command. */
public final class DolbyEq {
    public static final int SAVE_PARAMETER = 5, SAVE_COMMAND = 0x09000000;
    public interface Reader {
        int profiles() throws Exception;
        int[] gains(int profile) throws Exception;
        int enabled(int profile) throws Exception;
        void trace(String phase, int profile, int[] gains, int enabled);
    }
    public interface Port extends Reader {
        void gains(int profile, int[] gains) throws Exception;
        void enable(int profile) throws Exception;
        int save(int parameter, int command) throws Exception;
    }
    private static final class Snapshot {
        int[][] gains;
        int[] enabled;
        Snapshot(int n) { gains=new int[n][];enabled=new int[n]; }
        boolean matches(int[] target) {
            for(int i=0;i<gains.length;i++)if(enabled[i]!=1||!Arrays.equals(gains[i],target))return false;
            return true;
        }
    }
    private DolbyEq() {}
    private static Snapshot read(Reader port,String phase) throws Exception {
        int n=port.profiles();
        if(n<1||n>16)throw new IllegalStateException("Unexpected Dolby profile count: "+n);
        Snapshot snapshot=new Snapshot(n);
        for(int i=0;i<n;i++) {
            int[] gains=port.gains(i);
            if(gains==null||gains.length!=20)throw new IllegalStateException("Invalid GEQ length for profile "+i);
            snapshot.gains[i]=gains.clone();snapshot.enabled[i]=port.enabled(i);
            if(snapshot.enabled[i]!=0&&snapshot.enabled[i]!=1)throw new IllegalStateException("Invalid GEQ enable state");
            port.trace(phase,i,snapshot.gains[i],snapshot.enabled[i]);
        }
        return snapshot;
    }
    /** Reader has no setters; observing a mismatch never corrects it. */
    public static boolean matches(Reader port,int[] percent,String phase) throws Exception {
        return read(port,phase).matches(EqMath.dolby(percent));
    }
    public static boolean apply(Port port,int[] percent) throws Exception {
        int[] target=EqMath.dolby(percent);
        Snapshot before=read(port,"before");boolean changed=false;
        for(int i=0;i<before.gains.length;i++) {
            if(!Arrays.equals(before.gains[i],target)){port.gains(i,target.clone());changed=true;}
            if(before.enabled[i]!=1){port.enable(i);changed=true;}
        }
        Snapshot after=read(port,"postWrite");
        if(after.gains.length!=before.gains.length||!after.matches(target))
            throw new IllegalStateException("Dolby EQ readback mismatch; settings not saved");
        // Also save an already matching curve: V6.1 may have left it unsaved.
        // Never run this command from matches()/read-only verification.
        int status=port.save(SAVE_PARAMETER,SAVE_COMMAND);
        if(status!=0)throw new IllegalStateException("Dolby saveDsSetting status="+status);
        return changed;
    }
}
