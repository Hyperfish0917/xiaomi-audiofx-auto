package mi14.audiofx;

/** Two independent stock parameter paths. Native acceptance is not a listening test. */
public final class StockEq {
    public static final class Backend {
        public ApplyStages.EqStatus status = ApplyStages.EqStatus.NOT_RUN;
        public boolean changed, drift;
        public Exception error;
    }
    public static final class Result {
        public final Backend dolby = new Backend(), miSound = new Backend();
        public ApplyStages.EqStatus status() {
            if (dolby.status == ApplyStages.EqStatus.FAILED || miSound.status == ApplyStages.EqStatus.FAILED)
                return ApplyStages.EqStatus.FAILED;
            if (dolby.status == ApplyStages.EqStatus.VERIFIED && miSound.status == ApplyStages.EqStatus.VERIFIED)
                return ApplyStages.EqStatus.VERIFIED;
            return ApplyStages.EqStatus.UNVERIFIED;
        }
        public boolean changed() { return dolby.changed || miSound.changed; }
        public boolean drift() { return dolby.drift || miSound.drift; }
    }
    private StockEq() {}
    private static void fail(Backend backend, Exception error) {
        backend.status = ApplyStages.EqStatus.FAILED; backend.error = error;
    }
    public static Result apply(DolbyEq.Port dolby, MiSoundEq.Port miSound,
            int[] percent, boolean miReadSupported) {
        EqMath.validate(percent);
        Result result = new Result();
        try {
            result.dolby.changed = DolbyEq.apply(dolby, percent);
            result.dolby.status = ApplyStages.EqStatus.VERIFIED;
        } catch (Exception e) { fail(result.dolby, e); }
        // Each native write still has its own route/call/control guard in NativeProbe.
        try {
            MiSoundEq.Result mi = MiSoundEq.apply(miSound, percent, miReadSupported);
            result.miSound.changed = mi.changed; result.miSound.status = mi.status;
        } catch (Exception e) { fail(result.miSound, e); }
        return result;
    }
    /** Reader interfaces expose no writes, save commands, or engine enable operations. */
    public static Result observe(DolbyEq.Reader dolby, MiSoundEq.Reader miSound,
            int[] percent, boolean miReadSupported, String phase) {
        EqMath.validate(percent);
        Result result = new Result();
        try {
            result.dolby.drift = !DolbyEq.matches(dolby, percent, phase);
            result.dolby.status = result.dolby.drift ? ApplyStages.EqStatus.FAILED : ApplyStages.EqStatus.VERIFIED;
        } catch (Exception e) { fail(result.dolby, e); }
        if (!miReadSupported) {
            miSound.trace("readback", "KNOWN_UNSUPPORTED_BUILD; read-only, getter not retried");
            result.miSound.status = ApplyStages.EqStatus.UNVERIFIED;
        } else try {
            float[] actual = MiSoundEq.readAll(miSound);
            miSound.trace(phase, java.util.Arrays.toString(actual));
            result.miSound.drift = !MiSoundEq.matches(actual, EqMath.miSound(percent));
            result.miSound.status = result.miSound.drift ? ApplyStages.EqStatus.FAILED : ApplyStages.EqStatus.VERIFIED;
        } catch (MiSoundEq.UnsupportedRead e) {
            result.miSound.status = ApplyStages.EqStatus.UNVERIFIED;
            miSound.trace("readback", e.getMessage());
        } catch (Exception e) { fail(result.miSound, e); }
        return result;
    }
}
