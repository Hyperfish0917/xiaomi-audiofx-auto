package mi14.audiofx;

/** Percent is the stock UI scale: +/-100% = +/-6 dB. No guessed frequency labels. */
public final class EqMath {
    public static final int[] WARM = {0,25,50,25,0,0,-25,-25,-25,0};
    private EqMath() {}
    public static void validate(int[] p) {
        if (p == null || p.length != 10) throw new IllegalArgumentException("EQ 必须有 10 个频段");
        for (int x:p) if (x < -100 || x > 100 || x % 5 != 0)
            throw new IllegalArgumentException("EQ 范围为 -100%～100%，步长 5%");
    }
    public static boolean flat(int[] p) { validate(p); for (int x:p) if(x!=0)return false; return true; }
    public static float[] miSound(int[] p) {
        validate(p); float[] out=new float[10];
        for(int i=0;i<10;i++)out[i]=p[i]*0.06f;
        return out;
    }
    /** APK J.e interpolation, followed by APK J.c integer mean normalization. */
    public static int[] dolby(int[] p) {
        float[] d=miSound(p);int[] out=new int[20];int sum=0;
        for(int i=0;i<10;i++) {
            out[2*i]=(int)(d[i]*16f);
            out[2*i+1]=(int)((i==9?d[i]:(d[i]+d[i+1])/2f)*16f);
        }
        for(int x:out)sum+=x; int mean=sum/20;
        for(int i=0;i<20;i++)out[i]-=mean;
        return out;
    }
}
