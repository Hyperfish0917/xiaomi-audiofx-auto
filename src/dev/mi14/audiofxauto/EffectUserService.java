package dev.mi14.audiofxauto;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Shell UserService owns the runner. Proven V4 identity setup stays isolated in its child. */
public final class EffectUserService extends Binder {
    private final int ownerUid;
    private final String apkPath;
    private final Object processLock = new Object();
    private java.lang.Process running;
    private boolean stopped;
    private boolean runningPage;

    public EffectUserService(Context context) {
        if (Process.myUid() != 2000) throw new SecurityException("Require Shizuku shell UID 2000");
        ownerUid = context.getApplicationInfo().uid;
        apkPath = context.getApplicationInfo().sourceDir;
        attachInterface(null, Bridge.DESCRIPTOR);
    }

    private void cancel(boolean permanently) {
        synchronized (processLock) {
            if (permanently) stopped = true;
            if (running != null) running.destroyForcibly();
        }
    }

    private String runOnce(String config,boolean verifyOnly,boolean page) {
        if(page)mi14.audiofx.eqpage.PageSyncRequest.parse(config);else mi14.audiofx.Config.parse(config);
        byte[] request=config.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(request.length>60000)throw new IllegalArgumentException("Configuration too large");
        final java.lang.Process process;
        synchronized (processLock) {
            if (stopped || running != null) return "EXIT=2\nUserService stopped or busy\n";
            try {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/app_process", "/system/bin",
                        page?"mi14.audiofx.eqpage.PageSyncRunner":"mi14.audiofx.NativeProbe", page?"sync":verifyOnly?"verify":"auto");
                pb.environment().put("CLASSPATH", apkPath);
                pb.redirectErrorStream(true);
                runningPage=page; running = process = pb.start();
                try(java.io.OutputStream input=process.getOutputStream()){input.write(request);}
            } catch (Exception e) { if(running!=null){running.destroyForcibly();running=null;}runningPage=false;return "EXIT=2\nSpawn failure: " + e + "\n"; }
        }
        final StringBuilder output = new StringBuilder();
        Thread reader = new Thread(new Runnable() {
            @Override public void run() {
                try (InputStream stream = process.getInputStream();
                     InputStreamReader input = new InputStreamReader(stream, "UTF-8")) {
                    char[] chars = new char[2048]; int n;
                    while ((n = input.read(chars)) != -1) {
                        synchronized (output) {
                            output.append(chars, 0, n);
                            if (output.length() > 60000) output.delete(0, output.length() - 60000);
                        }
                    }
                } catch (Exception e) { synchronized (output) { output.append("\nReader: ").append(e); } }
            }
        }, "audiofx-output");
        reader.setDaemon(true); reader.start();
        int exit = 124;
        try {
            if (!process.waitFor(page?40:25, TimeUnit.SECONDS)) process.destroyForcibly();
            else exit = process.exitValue();
            reader.join(2000);
        } catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        finally { synchronized (processLock) { if (running == process) {running = null;runningPage=false;} } }
        synchronized (output) { return "EXIT=" + exit + "\n" + output; }
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == IBinder.INTERFACE_TRANSACTION) { reply.writeString(Bridge.DESCRIPTOR); return true; }
        int caller = Binder.getCallingUid();
        if (code == Bridge.DESTROY) {
            if (caller != ownerUid && caller != 2000 && caller != 0) throw new SecurityException("Caller denied");
            cancel(true);
            if (reply != null) { reply.writeNoException(); reply.writeString("STOPPED"); }
            new Thread(new Runnable() { @Override public void run() {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                System.exit(0);
            }}, "audiofx-exit").start();
            return true;
        }
        if (caller != ownerUid) throw new SecurityException("Only the owning app can request an effect check");
        data.enforceInterface(Bridge.DESCRIPTOR);
        long identity = Binder.clearCallingIdentity();
        try {
            if (code == Bridge.RUN || code == Bridge.VERIFY || code == Bridge.PAGE_SYNC) { String result = runOnce(data.readString(),code==Bridge.VERIFY,code==Bridge.PAGE_SYNC); reply.writeNoException(); reply.writeString(result); return true; }
            if(code==Bridge.CANCEL_PAGE){
                synchronized(processLock){if(runningPage&&running!=null)running.destroyForcibly();}
                reply.writeNoException();reply.writeString("PAGE_CANCEL_REQUESTED");return true;
            }
            if (code == Bridge.CANCEL) { cancel(true); reply.writeNoException(); reply.writeString("STOPPED"); return true; }
            return super.onTransact(code, data, reply, flags);
        } finally { Binder.restoreCallingIdentity(identity); }
    }
}
