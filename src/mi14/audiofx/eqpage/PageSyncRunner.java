package mi14.audiofx.eqpage;

import android.app.ActivityManager;
import android.app.UiAutomation;
import android.app.UiAutomationConnection;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.HandlerThread;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Process;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import mi14.audiofx.NativeProbe;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Explicit foreground page sync. The native background EQ runner stays separate. */
public final class PageSyncRunner {
    private static final String PKG = "com.miui.misound";
    private static final String FINGERPRINT = "Xiaomi/houji/houji:16/BP2A.250605.031.A3/OS3.0.307.0.WNCCNXM:user/release-keys";
    private static PageSyncRequest request;
    private static boolean identityReady;
    private static final long START = System.nanoTime();
    private static volatile String lastStage = "ENTRY";
    private static IPowerManager power;
    private static IWindowManager windows;
    private static UiAutomation ui;
    private static int touches;
    private static boolean attempted, applyMatch;

    private static void out(String key, Object value) {
        System.out.println(key + "=" + value); System.out.flush();
    }
    private static void stage(String name) {
        lastStage = name;
        out("STAGE", name + " ELAPSED_MS=" + (System.nanoTime() - START) / 1000000L);
    }
    private static void require(boolean ok, String why) {
        if (!ok) throw new IllegalStateException(why);
    }
    private static void unlocked() throws Exception {
        require(power.isInteractive() && !windows.isKeyguardLocked(), "SCREEN_LOCKED_OR_OFF");
        if(identityReady)NativeProbe.guardPageRoute(request.key);
    }
    private static void report(Throwable e) {
        out("LAST_STAGE", lastStage); out("ERROR", e.getClass().getName() + ": " + e.getMessage());
        e.printStackTrace(System.out); System.out.flush();
    }
    private static boolean equal(CharSequence a, String b) { return a != null && b.contentEquals(a); }

    private static void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> list, int depth) {
        if (n == null || !equal(n.getPackageName(), PKG)) return;
        require(depth <= 32 && list.size() < 240, "NODE_TREE_LIMIT");
        list.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), list, depth + 1);
    }

    private static final class Snapshot {
        int window;
        Rect chart;
        Rect[] bands = new Rect[10];
        int[] percents = new int[10], steps = new int[10];
        boolean layoutEquals(Snapshot other) {
            return other != null && window == other.window && chart.equals(other.chart)
                && Arrays.equals(bands, other.bands);
        }
        String geometry(){return chart.toShortString()+Arrays.toString(bands);}
        void print(String label) {
            out(label + "_WINDOW", window); out(label + "_CHART", chart.toShortString());
            out(label + "_PERCENT", EqMath.csv(percents)); out(label + "_HALF_DB_UNITS", EqMath.csv(steps));
        }
    }

    /** Never inspect/log text from other packages. Refresh the cache on every sample. */
    private static Snapshot read() throws Exception {
        unlocked();
        require(ui.clearCache(), "CACHE_CLEAR_FAILED");
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        if (root == null || !equal(root.getPackageName(), PKG)) return null;
        List<AccessibilityNodeInfo> nodes = new ArrayList<AccessibilityNodeInfo>();
        collect(root, nodes, 0);
        AccessibilityNodeInfo chart = null;
        for (AccessibilityNodeInfo n : nodes) {
            if (equal(n.getClassName(), "com.miui.misound.view.GeqView")
                || (PKG + ":id/geq_view").equals(n.getViewIdResourceName())) {
                require(chart == null, "MULTIPLE_EQ_CHARTS"); chart = n;
            }
        }
        if (chart == null) return null;
        require(chart.isVisibleToUser() && chart.isEnabled(), "CHART_NOT_VISIBLE_OR_ENABLED");
        Snapshot s = new Snapshot(); s.window = root.getWindowId(); s.chart = new Rect();
        chart.getBoundsInScreen(s.chart);
        require(chart.getWindowId() == s.window, "CHART_WINDOW_MISMATCH");
        Rect rootBounds = new Rect(); root.getBoundsInScreen(rootBounds);
        require(rootBounds.contains(s.chart) && s.chart.height() >= 120, "CHART_CLIPPED");
        List<AccessibilityNodeInfo> children = new ArrayList<AccessibilityNodeInfo>();
        collect(chart, children, 0);
        int count = 0;
        for (AccessibilityNodeInfo n : children) {
            if (!equal(n.getClassName(), "android.widget.SeekBar")) continue;
            require(n.isVisibleToUser() && n.isEnabled() && n.getWindowId() == s.window, "BAND_NOT_READY");
            int[] label = EqMath.parse(n.getText() == null ? "" : n.getText().toString());
            int i = label[0]; require(s.bands[i] == null, "DUPLICATE_BAND");
            Rect r = new Rect(); n.getBoundsInScreen(r);
            require(!r.isEmpty() && s.chart.contains(r), "BAND_CLIPPED");
            s.bands[i] = r; s.percents[i] = label[1]; s.steps[i] = EqMath.halfDb(label[1]); count++;
        }
        require(count == 10, "NEED_TEN_BANDS");
        int topMargin = s.bands[0].top - s.chart.top;
        int bottomMargin = s.chart.bottom - s.bands[0].bottom;
        require(topMargin >= 0 && Math.abs(topMargin - bottomMargin) <= 2
            && topMargin <= s.chart.height() / 8 && s.bands[0].height() >= 100, "UNSUPPORTED_TRACK_GEOMETRY");
        for (int i = 1; i < 10; i++) {
            require(s.bands[i].top == s.bands[0].top && s.bands[i].bottom == s.bands[0].bottom
                && s.bands[i].left > s.bands[i - 1].right, "UNSUPPORTED_BAND_LAYOUT");
        }
        unlocked(); return s;
    }

    private static Snapshot requirePage() throws Exception {
        Snapshot s = read(); require(s != null, "EQ_PAGE_NOT_FOREGROUND"); return s;
    }
    private static Snapshot stablePage(long timeout) throws Exception {
        PageStability stability=new PageStability();Snapshot last=null;String note="NO_EQ_PAGE";
        long end=SystemClock.uptimeMillis()+timeout;
        while(SystemClock.uptimeMillis()<end){
            unlocked();Snapshot next=null;
            try{next=read();}catch(IllegalStateException e){unlocked();note=e.getMessage();}
            if(next==null)stability.reset();
            else{
                if(last!=null&&!last.layoutEquals(next))out("PAGE_SETTLE_CHANGE","LAYOUT_OR_WINDOW");
                if(last!=null&&!Arrays.equals(last.steps,next.steps))out("PAGE_SETTLE_CHANGE","CURVE");
                last=next;
                if(stability.observe(next.window,next.geometry(),next.steps,SystemClock.uptimeMillis()))return next;
            }
            SystemClock.sleep(220);
        }
        if(last!=null)last.print("LAST_UNSTABLE");out("SETTLE_LAST_NOTE",note);
        throw new IllegalStateException("PAGE_DID_NOT_SETTLE");
    }

    private static boolean input(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN); event.setDisplayId(0);
        try { return ui.injectInputEvent(event, true); } finally { event.recycle(); }
    }

    private static Snapshot tap(Snapshot before, int index, int target) throws Exception {
        Snapshot fresh = requirePage();
        require(before.layoutEquals(fresh) && Arrays.equals(before.steps, fresh.steps), "PAGE_CHANGED_BEFORE_TOUCH");
        float x = fresh.bands[index].exactCenterX();
        float y = EqMath.y(fresh.chart.top, fresh.bands[index].height(), fresh.steps[index], target);
        require(x > fresh.bands[index].left && x < fresh.bands[index].right
            && y >= fresh.chart.top + 1 && y <= fresh.chart.bottom - 1, "TOUCH_OUTSIDE_CHART");
        stage("TOUCH_BAND_" + (index + 1));
        out("TOUCH", "band=" + (index + 1) + " x=" + x + " y=" + y + " target_percent=" + EqMath.percent(target));
        unlocked(); attempted = true;
        long down = SystemClock.uptimeMillis(); boolean up = false;
        try {
            require(input(down, MotionEvent.ACTION_DOWN, x, y), "DOWN_INJECTION_FAILED");
            SystemClock.sleep(40);
            unlocked();
            require(input(down, MotionEvent.ACTION_UP, x, y), "UP_INJECTION_FAILED");
            up = true; touches++;
        } finally {
            // Cancel an incomplete gesture; do not deliver a committing UP after a failed guard.
            if (!up) { try { input(down, MotionEvent.ACTION_CANCEL, x, y); } catch (Throwable ignored) {} }
        }
        long end = SystemClock.uptimeMillis() + 1600;
        Snapshot after;
        do {
            SystemClock.sleep(160); after = requirePage();
            require(before.layoutEquals(after), "LAYOUT_CHANGED_AFTER_TOUCH");
            for (int i = 0; i < 10; i++) if (i != index)
                require(after.steps[i] == before.steps[i], "UNEXPECTED_OTHER_BAND_CHANGE_" + (i + 1));
            if (after.steps[index] == target) { after.print("AFTER_BAND_" + (index + 1)); return after; }
        } while (SystemClock.uptimeMillis() < end);
        after.print("MISMATCH"); throw new IllegalStateException("TOUCH_VALUE_MISMATCH_BAND_" + (index + 1));
    }

    private static Snapshot apply(Snapshot before, int[] target) throws Exception {
        // Validate the complete requested curve before the first touch.
        for (int i = 0; i < 10; i++) {
            int value = before.steps[i];
            for (int step : EqMath.plan(value, target[i])) {
                EqMath.y(before.chart.top, before.bands[i].height(), value, step); value = step;
            }
        }
        Snapshot current = before;
        for (int i = 0; i < 10; i++) if (current.steps[i] != target[i]) {
            int[] plan = EqMath.plan(current.steps[i], target[i]);
            if (plan.length > 1) out("ENDPOINT_PLAN_BAND_" + (i + 1), EqMath.csv(plan));
            for (int step : plan) current = tap(current, i, step);
        }
        if (touches == 0) current = tap(current, 0, target[0]); // Commit even when the curve already matches.
        require(Arrays.equals(current.steps, target), "FINAL_CURVE_MISMATCH");
        Snapshot stable = stablePage(4000);
        require(current.layoutEquals(stable) && Arrays.equals(stable.steps, target), "FINAL_CURVE_UNSTABLE");
        applyMatch = true; stable.print("APPLIED"); out("APPLY_MATCH", true); return stable;
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                out("RESULT", "UNCAUGHT_JAVA_ERROR"); out("ERROR_THREAD", t.getName()); report(e);
                Runtime.getRuntime().halt(70);
            }
        });
        out("PAGE_RUNNER", "AFX_PAGE_V64"); out("PID", Process.myPid());
        Thread deadline = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(35000); } catch (InterruptedException e) { return; }
                out("RESULT", "TIMEOUT_35S"); out("LAST_STAGE", lastStage);
                out("WRITE_ATTEMPTED", attempted); out("COMPLETED_TOUCHES", touches); Runtime.getRuntime().halt(124);
            }
        }, "eq-sync-deadline"); deadline.setDaemon(true); deadline.start();
        HandlerThread callbacks = null; int exit = 2;
        try {
            require(args.length==1&&"sync".equals(args[0]),"EXPECTED_SYNC_MODE");
            ByteArrayOutputStream input=new ByteArrayOutputStream();byte[] bytes=new byte[2048];int n;
            while((n=System.in.read(bytes))!=-1){input.write(bytes,0,n);require(input.size()<=60000,"REQUEST_TOO_LARGE");}
            request=PageSyncRequest.parse(new String(input.toByteArray(),StandardCharsets.UTF_8));
            stage("IDENTITY"); out("UID", Process.myUid()); out("SDK", Build.VERSION.SDK_INT);
            require(Process.myUid() == 2000 && Build.VERSION.SDK_INT == 36, "REQUIRES_SHELL_UID_2000_ANDROID_16");
            require(FINGERPRINT.equals(Build.FINGERPRINT), "UNSUPPORTED_FIRMWARE");
            stage("MAIN_LOOPER"); if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            stage("PAGE_SHELL_IDENTITY");NativeProbe.preparePageIdentity();identityReady=true;
            NativeProbe.guardPageRoute(request.key);
            out("ROUTE_JSON",NativeProbe.pageRouteJson());
            out("PAGE_PRESET",request.config.eqName(request.config.rule(request.key).eq));
            out("TARGET_HALF_DB_UNITS",EqMath.csv(request.target));
            require(ActivityManager.getCurrentUser() == 0, "REQUIRES_PRIMARY_USER");
            IPackageManager packages = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
            PackageInfo info = packages.getPackageInfo(PKG, 0L, 0);
            require(info != null && info.getLongVersionCode() == 260507L
                && "15-3.2-3-20260507".equals(info.versionName), "UNSUPPORTED_MISOUND_VERSION");
            out("MISOUND_VERSION", info.versionName);
            stage("POWER_AND_WINDOW_SERVICES");
            power = IPowerManager.Stub.asInterface(ServiceManager.getService("power"));
            windows = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
            require(power != null && windows != null, "MISSING_SYSTEM_SERVICE"); unlocked();
            stage("CALLBACK_THREAD"); callbacks = new HandlerThread("eq-sync-callbacks"); callbacks.start();
            stage("AUTOMATION_CONNECTION_CONSTRUCTOR"); UiAutomationConnection connection = new UiAutomationConnection();
            stage("AUTOMATION_CONSTRUCTOR"); ui = new UiAutomation(callbacks.getLooper(), connection);
            stage("AUTOMATION_CONNECT"); ui.connectWithTimeout(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, 5000);
            out("UI_AUTOMATION_CONNECTED", true);
            stage("READ_STABLE_EQ_PAGE");Snapshot before=stablePage(10000);before.print("BEFORE");
            apply(before,request.target);out("RESULT","PAGE_SYNC_MATCH");exit=0;
        } catch (Throwable e) { out("RESULT", "ERROR"); report(e); }
        finally {
            out("WRITE_ATTEMPTED", attempted); out("COMPLETED_TOUCHES", touches);
            out("APPLY_MATCH", applyMatch); out("REOPEN_VERIFIED_THIS_RUN", false);
            out("DISK_FILES_READ_DIRECTLY", false); out("COLD_START_OR_REBOOT_VERIFIED", false);
            stage("CLEANUP");
            if (ui != null) try { ui.disconnect(); out("DISCONNECTED", true); }
                catch (Throwable e) { out("DISCONNECT_NOTE", e.getClass().getName()); }
            if (callbacks != null) callbacks.quitSafely();
            try{NativeProbe.closePageIdentity();}catch(Throwable e){out("IDENTITY_CLEANUP_NOTE",e.getClass().getSimpleName());}
        }
        stage("EXIT_" + exit); System.exit(exit);
    }
}
