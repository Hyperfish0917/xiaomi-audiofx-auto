package mi14.audiofx;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Exact MiSound getLevel/setLevel wire format from the supplied framework. */
public final class MiSoundEq {
    public static final int READ_BYTES = 10;
    public interface Reader {
        int get(int band, byte[] value) throws Exception;
        void trace(String key, String value);
    }
    public interface Port extends Reader {
        int set(int band, byte[] value) throws Exception;
    }
    public static final class UnsupportedRead extends Exception {
        public UnsupportedRead(int band) { super("MiSound getLevel rejected band=" + band + "; bytes=10; status=-4"); }
    }
    public static final class Result {
        public final ApplyStages.EqStatus status;
        public final boolean changed;
        Result(ApplyStages.EqStatus status, boolean changed) { this.status=status; this.changed=changed; }
    }
    private MiSoundEq() {}
    public static float read(Reader port, int band) throws Exception {
        byte[] value = new byte[READ_BYTES];
        int n = port.get(band, value);
        if (n == -4) throw new UnsupportedRead(band);
        if (n <= 0 || n > value.length)
            throw new IllegalStateException("MiSound getLevel band="+band+" bytes=10 status="+n);
        int end=0; while(end<n && value[end]!=0)end++;
        float gain=Float.parseFloat(new String(value,0,end,StandardCharsets.UTF_8).trim());
        if (Float.isNaN(gain) || Float.isInfinite(gain)) throw new IllegalStateException("Non-finite MiSound gain at "+band);
        return gain;
    }
    public static float[] readAll(Reader port) throws Exception {
        float[] gains=new float[10];for(int i=0;i<10;i++)gains[i]=read(port,i);return gains;
    }
    public static boolean matches(float[] actual,float[] target) {
        for(int i=0;i<10;i++)if(Math.abs(actual[i]-target[i])>=0.02f)return false;
        return true;
    }
    public static Result apply(Port port,int[] percent) throws Exception {
        return apply(port,percent,true);
    }
    public static Result apply(Port port,int[] percent,boolean readSupported) throws Exception {
        float[] target=EqMath.miSound(percent),before=null;
        if(readSupported) {
            try { before=readAll(port);port.trace("before",Arrays.toString(before)); }
            catch(UnsupportedRead e) { port.trace("readback",e.getMessage()+"; using stock setLevel, not a fabricated zero value"); }
        } else port.trace("readback","KNOWN_UNSUPPORTED_BUILD; getter not retried");
        boolean changed=false;
        for(int i=0;i<10;i++)if(before==null || Math.abs(before[i]-target[i])>=0.02f) {
            int status=port.set(i,Float.toString(target[i]).getBytes(StandardCharsets.UTF_8));
            if(status!=0)throw new IllegalStateException("MiSound setLevel band="+i+" status="+status);
            changed=true;
        }
        if(!readSupported)return new Result(ApplyStages.EqStatus.UNVERIFIED,changed);
        try {
            float[] after=readAll(port);port.trace("after",Arrays.toString(after));
            if(!matches(after,target))throw new IllegalStateException("MiSound EQ readback mismatch");
            return new Result(ApplyStages.EqStatus.VERIFIED,changed);
        } catch(UnsupportedRead e) {
            port.trace("readback",e.getMessage()+"; all requested writes returned 0; audible result unverified");
            return new Result(ApplyStages.EqStatus.UNVERIFIED,changed);
        }
    }
}
