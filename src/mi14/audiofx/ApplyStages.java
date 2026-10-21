package mi14.audiofx;

/** Commit the selected effect independently of optional EQ. Also used by fault-injection tests. */
public final class ApplyStages {
    public enum EqStatus { SKIPPED, VERIFIED, UNVERIFIED, FAILED, NOT_RUN }
    public interface EffectStep { void run() throws Exception; }
    public interface EqStep { EqStatus run() throws Exception; }
    public static final class Result {
        public Exception effectError, eqError;
        public EqStatus eqStatus = EqStatus.NOT_RUN;
    }
    private ApplyStages() {}
    public static Result run(EffectStep effect, EqStep eq) {
        Result result = new Result();
        try { effect.run(); }
        catch (Exception e) { result.effectError = e; return result; }
        try { result.eqStatus = eq.run(); }
        catch (Exception e) { result.eqError = e; result.eqStatus = EqStatus.FAILED; }
        return result;
    }
    public static int exitCode(boolean effectVerified, EqStatus eqStatus) {
        if (!effectVerified) return 2;
        if (eqStatus == EqStatus.VERIFIED || eqStatus == EqStatus.SKIPPED) return 0;
        if (eqStatus == EqStatus.UNVERIFIED) return 6;
        return 5;
    }
}
