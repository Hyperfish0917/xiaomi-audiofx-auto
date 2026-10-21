package mi14.audiofx.eqpage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure functions. Units in saved curves are half dB, not percentages. */
public final class EqMath {
    public static final int[] WARM = {0, 3, 6, 3, 0, 0, -3, -3, -3, 0};
    private static final Pattern LABEL = Pattern.compile(
        "第(10|[1-9])个[，,]\\s*(-?[0-9]{1,3})\\s*[%％][，,]均衡器");

    public static int[] parse(String text) {
        Matcher m = LABEL.matcher(text.replace('\u2212', '-').trim());
        if (!m.matches()) throw new IllegalArgumentException("UNRECOGNIZED_BAND_LABEL");
        int percent = Integer.parseInt(m.group(2));
        if (Math.abs(percent) > 100) throw new IllegalArgumentException("PERCENT_OUT_OF_RANGE");
        return new int[]{Integer.parseInt(m.group(1)) - 1, percent};
    }

    public static int percent(int halfDb) { return Math.round(halfDb * 100f / 12f); }

    public static int halfDb(int percent) {
        int n = Math.round(percent * 12f / 100f);
        if (percent < -100 || percent > 100 || Math.abs(percent(n) - percent) > 1)
            throw new IllegalArgumentException("CURVE_NOT_ON_HALF_DB_GRID");
        return n;
    }

    /** Touch uses local Y without subtracting the drawing margin in this MiSound build.
     *  Bias by 1/16 dB towards the target to cross its >=0.5 dB update threshold.
     *  ACTION_UP then snaps to the exact requested half-dB value. */
    public static float y(int chartTop, int trackHeight, int current, int target) {
        if (trackHeight < 100 || current < -12 || current > 12 || target < -12 || target > 12)
            throw new IllegalArgumentException("UNSUPPORTED_TOUCH_RANGE");
        float gain = target * 0.5f + Integer.signum(target - current) * 0.0625f;
        // Endpoints are reached through ACTION_UP's half-dB rounding, without an
        // out-of-View touch. Their adjacent half-step needs plan() below.
        gain = Math.max(-5.9375f, Math.min(5.9375f, gain));
        float local = trackHeight * (0.5f - gain / 12f);
        return chartTop + Math.max(1f, Math.min(trackHeight - 1f, local));
    }

    /** At +/-5.5 -> +/-6 the safe interior point is under the 0.5 dB update
     * threshold. First use +/-5, read it back, then touch the endpoint.
     * This is a bounded path to an explicitly requested saved value, not calibration. */
    public static int[] plan(int current, int target) {
        if (current < -12 || current > 12 || target < -12 || target > 12)
            throw new IllegalArgumentException("CURVE_OUTSIDE_SYSTEM_RANGE");
        if ((target == 12 && current == 11) || (target == -12 && current == -11))
            return new int[]{Integer.signum(target) * 10, target};
        return new int[]{target};
    }

    public static String csv(int[] values) {
        StringBuilder s = new StringBuilder();
        for (int v : values) { if (s.length() > 0) s.append(','); s.append(v); }
        return s.toString();
    }

    public static int[] readCsv(String text) {
        String[] pieces = text.split(",", -1);
        if (pieces.length != 10) throw new IllegalArgumentException("BACKUP_MUST_HAVE_TEN_BANDS");
        int[] values = new int[10];
        for (int i = 0; i < 10; i++) {
            values[i] = Integer.parseInt(pieces[i]);
            if (values[i] < -12 || values[i] > 12)
                throw new IllegalArgumentException("BACKUP_OUTSIDE_SYSTEM_PLUS_MINUS_6_DB");
        }
        return values;
    }
}
