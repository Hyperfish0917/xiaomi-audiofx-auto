package mi14.audiofx.eqpage;
import java.util.Arrays;
/** An unchanged window, geometry and curve for 600ms and three samples. */
public final class PageStability {
    private int window,count; private String geometry; private int[] curve; private long since;
    public void reset(){geometry=null;curve=null;count=0;}
    public boolean observe(int id,String shape,int[] values,long now){
        if(shape==null||values==null){reset();return false;}
        if(geometry==null||id!=window||!shape.equals(geometry)||!Arrays.equals(values,curve)){
            window=id;geometry=shape;curve=values.clone();since=now;count=1;return false;
        }
        count++;return count>=3&&now-since>=600;
    }
}
