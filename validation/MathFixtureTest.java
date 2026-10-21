package mi14.audiofx.eqpage;

import java.util.Arrays;

/** Real phone geometry + independently transcribed MiSound touch/rounding equations. */
public final class MathFixtureTest {
    private static int checks;
    private static void check(boolean ok, String name) {
        checks++; if (!ok) throw new AssertionError(name);
    }
    private static int stockTouch(int before, float screenY, int chartTop, int height) {
        float gain = before * 0.5f;
        float candidate = Math.min(6f, Math.max(-6f, ((height / 2f - (screenY-chartTop))/height)*12f));
        if (Math.abs(candidate-gain) >= 0.5d) gain=candidate;
        float floor = (float)Math.floor(gain), fraction=gain-floor;
        if (fraction<0.25f) gain=floor;
        else if (fraction<=0.75f) gain=floor+0.5f;
        else gain=floor+1f;
        return Math.round(gain*2f);
    }
    private static void invalidLabel(String value) {
        boolean failed=false; try { EqMath.parse(value); } catch (IllegalArgumentException e) { failed=true; }
        check(failed,"invalid label: "+value);
    }
    private static int followPlan(int from, int to, int height) {
        int value = from;
        int[] plan=EqMath.plan(from,to);
        check(plan.length<=2,"bounded path");
        for(int step:plan) {
            float y=EqMath.y(1660,height,value,step);
            check(y>=1661 && y<=1660+height-1,"all endpoint touches inside View");
            value=stockTouch(value,y,1660,height);
            check(value==step,"each planned value reached from "+from+" to "+to+" h="+height);
        }
        return value;
    }
    public static void main(String[] args) {
        int[] observed={0,33,58,33,0,0,-33,-33,-33,0};
        int[] before={0,4,7,4,0,0,-4,-4,-4,0};
        int[] expected={0,25,50,25,0,0,-25,-25,-25,0};
        for (int i=0;i<10;i++) {
            int[] parsed=EqMath.parse("第"+(i+1)+"个，"+observed[i]+"%，均衡器");
            check(parsed[0]==i && parsed[1]==observed[i],"phone label");
            check(EqMath.halfDb(parsed[1])==before[i],"phone half dB");
            check(EqMath.percent(EqMath.WARM[i])==expected[i],"warm percent");
            float y=EqMath.y(1660,668,before[i],EqMath.WARM[i]);
            check(y>1664 && y<2376,"in chart");
            check(stockTouch(before[i],y,1660,668)==EqMath.WARM[i],"warm touch");
        }
        // Full stock range, including the special endpoint threshold in both directions.
        for (int h:new int[]{100,200,668,1024}) for (int from=-12;from<=12;from++) for(int to=-12;to<=12;to++) {
            check(followPlan(from,to,h)==to,"touch grid");
        }
        // Exact failing phone fixture: must back up without clamp or loss, apply warm, restore.
        int[] edgePercent={-100,-92,-92,-100,100,100,100,100,100,42};
        int[] edge={-12,-11,-11,-12,12,12,12,12,12,5};
        check(Arrays.equals(EqMath.readCsv(EqMath.csv(edge)),edge),"full range backup roundtrip");
        for (int i=0;i<10;i++) {
            check(EqMath.halfDb(edgePercent[i])==edge[i],"latest phone percent");
            int applied=followPlan(edge[i],EqMath.WARM[i],668);
            check(followPlan(applied,edge[i],668)==edge[i],"restore latest phone curve");
        }
        check(stockTouch(11,EqMath.y(1660,668,11,12),1660,668)==11,"endpoint threshold negative control");
        check(stockTouch(-11,EqMath.y(1660,668,-11,-12),1660,668)==-11,"negative endpoint threshold");
        // Negative control: including the drawing margin gives the wrong band value.
        float incorrect=1686+668*(0.5f-1.5f/12f);
        check(stockTouch(4,incorrect,1660,668)!=3,"margin error caught");
        invalidLabel("第11个，0%，均衡器"); invalidLabel("第1个，101%，均衡器");
        invalidLabel("声音 25%"); invalidLabel("第1个，25%，均衡器 more");
        boolean failed=false;try{EqMath.halfDb(5);}catch(IllegalArgumentException e){failed=true;}
        check(failed,"reject non-half-dB label");
        check(EqMath.readCsv(EqMath.csv(before))[2]==7,"backup roundtrip");
        for(String bad:new String[]{"0,13,0,0,0,0,0,0,0,0", "0,-13,0,0,0,0,0,0,0,0",
                "0,-2147483648,0,0,0,0,0,0,0,0", "0,0,0"}) {
            failed=false;try{EqMath.readCsv(bad);}catch(IllegalArgumentException e){failed=true;}
            check(failed,"reject malformed or out-of-system backup");
        }
        System.out.println("MATH_FIXTURE_CHECKS="+checks+" PASS");
        System.out.println("ANDROID_UI_INJECTION=NOT_TESTED_ON_HOST");
    }
}
