package dev.mi14.audiofxauto;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

public final class MonitorService extends Service {
    public static volatile boolean active;
    public static final String START = "dev.mi14.audiofxauto.START";
    public static final String CHECK = "dev.mi14.audiofxauto.CHECK";
    public static final String VERIFY = "dev.mi14.audiofxauto.VERIFY";
    public static final String PAGE_SYNC = "dev.mi14.audiofxauto.PAGE_SYNC";
    public static final String CANCEL_PAGE = "dev.mi14.audiofxauto.CANCEL_PAGE";
    public static final String STOP = "dev.mi14.audiofxauto.STOP";
    public static final String PREFS = "audiofx";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AudioManager audio;
    private PowerManager.WakeLock wake;
    private IBinder remote;
    private boolean destroyed, binding, busy, pending, registered, modeRegistered, audioRegistered;
    private boolean enabled, verifyRequest;
    private boolean pageBusy;
    private int generation, routeRetries;
    private String reason = "启动";
    private Shizuku.UserServiceArgs args;
    private IBinder.DeathRecipient death;
    private String statusText = "正在准备";
    private final Shizuku.OnBinderReceivedListener received = new Shizuku.OnBinderReceivedListener() {
        @Override public void onBinderReceived() { main.post(new Runnable() { @Override public void run() { tryBind(); } }); }
    };
    private final Shizuku.OnBinderDeadListener dead = new Shizuku.OnBinderDeadListener() {
        @Override public void onBinderDead() { main.post(new Runnable() { @Override public void run() {
            remote = null; binding = false; status("等待 Shizuku 重新启动");
        }}); }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, final IBinder service) {
            if (destroyed || !enabled) { detach(); return; }
            binding = false; remote = service;
            death = new IBinder.DeathRecipient() { @Override public void binderDied() {
                main.post(new Runnable() { @Override public void run() {
                    if (remote == service) { remote = null; binding = false; status("控制服务断开，准备重连"); main.postDelayed(new Runnable() {
                        @Override public void run() { tryBind(); }
                    }, 1500); }
                }});
            }};
            try { service.linkToDeath(death, 0); }
            catch (Exception e) { remote = null; status("控制服务连接失败：" + e.getClass().getSimpleName()); return; }
            status("已连接控制服务，检查当前输出");
            event("控制服务连接", true);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null; binding = false;
            if (!destroyed && enabled) { status("控制服务断开，等待重连"); main.postDelayed(new Runnable() {
                @Override public void run() { tryBind(); }
            }, 1500); }
        }
    };
    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] values) { event("音频设备接入", true); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] values) { event("音频设备移除", true); }
    };
    private final AudioManager.OnModeChangedListener modes = new AudioManager.OnModeChangedListener() {
        @Override public void onModeChanged(int mode) { event("音频模式变化 " + mode, true); }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action) || Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                RunLog.add(MonitorService.this, "SCREEN_EVENT " + action + "; " + screenState());
                return; // Unlocking never triggers a check: keep screen-off test evidence honest.
            }
            event("系统事件 " + action, true);
        }
    };
    private final Runnable scheduled = new Runnable() { @Override public void run() { runCheck(); } };
    private final Runnable health = new Runnable() { @Override public void run() {
        if (destroyed || !enabled) return;
        if (remote == null) tryBind(); // Health check only; no periodic audio writes or wakeups.
        main.postDelayed(this, 20000);
    }};

    @Override public void onCreate() {
        super.onCreate();
        args = new Shizuku.UserServiceArgs(new ComponentName(this, EffectUserService.class))
                .tag("mi14-audiofx-runner").version(10).daemon(false).processNameSuffix("audiofx");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
                new NotificationChannel("audiofx", "音效自动切换", NotificationManager.IMPORTANCE_LOW));
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioFxAuto:transition");
        wake.setReferenceCounted(false);
        RunLog.add(this, "SERVICE_CREATE; build=" + android.os.Build.FINGERPRINT);
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MonitorService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "audiofx").setSmallIcon(R.drawable.ic_audio)
                .setContentTitle("音效自动切换 · 测试版").setContentText(statusText)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build()).build();
    }

    private void status(String text) {
        if (destroyed) return;
        statusText = text;
        getSharedPreferences(PREFS, 0).edit().putString("status", text).putLong("status_time", System.currentTimeMillis()).apply();
        RunLog.add(this, text);
        if (enabled) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(14, notification());
    }

    private void registerEvents() {
        if (registered) return;
        audio.registerAudioDeviceCallback(devices, main); audioRegistered = true;
        audio.addOnModeChangedListener(getMainExecutor(), modes); modeRegistered = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        filter.addAction("android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED");
        filter.addAction("android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED");
        filter.addAction("android.bluetooth.action.LE_AUDIO_ACTIVE_DEVICE_CHANGED");
        filter.addAction("android.bluetooth.device.action.NAME_CHANGED");
        filter.addAction("android.bluetooth.adapter.action.STATE_CHANGED");
        filter.addAction(Intent.ACTION_HEADSET_PLUG);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        // Bluetooth broadcasts originate from a privileged app. Payloads are never trusted;
        // they only schedule a fresh privileged query of the real media output route.
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        registered = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? START : intent.getAction();
        if(PAGE_SYNC.equals(action)){
            startPageSync(intent);if(!enabled)stopSelf();return enabled?START_STICKY:START_NOT_STICKY;
        }
        if(CANCEL_PAGE.equals(action)){cancelPage();if(!enabled)stopSelf();return enabled?START_STICKY:START_NOT_STICKY;}
        if (STOP.equals(action)) {
            getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", false).apply();
            status("已停止自动切换；保留当前音效");
            enabled = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY;
        }
        if (VERIFY.equals(action)) {
            if(enabled)event("手动只读检查",true,true);
            else stopSelf();
            return enabled?START_STICKY:START_NOT_STICKY;
        }
        if (intent == null && !getSharedPreferences(PREFS, 0).getBoolean("enabled", false)) { stopSelf(); return START_NOT_STICKY; }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            status("需要附近设备权限"); stopSelf(); return START_NOT_STICKY;
        }
        boolean wasEnabled = enabled; enabled = true;
        try {
            startForeground(14, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            active = true;
            getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", true).apply();
            registerEvents();
            if (!wasEnabled) {
                Shizuku.addBinderReceivedListenerSticky(received);
                Shizuku.addBinderDeadListener(dead);
                main.post(health);
            }
            event(CHECK.equals(action) ? "手动检查" : "开始自动切换", true);
        } catch (Exception e) {
            status("启动失败：" + e); getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", false).apply(); stopSelf();
        }
        return START_STICKY;
    }

    private void tryBind() {
        if (destroyed || !enabled || remote != null || binding) return;
        try {
            if (!Shizuku.pingBinder()) { status("等待 Shizuku 启动"); return; }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { status("等待 Shizuku 授权，请打开 App"); return; }
            if (Shizuku.getUid() != 2000 || Shizuku.getVersion() < 13) { status("需要以无线调试启动的 Shizuku 13 或更新版本"); return; }
            binding = true;
            Shizuku.bindUserService(args, connection);
            main.postDelayed(new Runnable() { @Override public void run() {
                if (!destroyed && enabled && binding && remote == null) { binding = false; status("连接控制服务超时，稍后重连"); }
            }}, 12000);
        } catch (Exception e) { binding = false; status("控制服务连接失败：" + e); }
    }

    private void event(String why, boolean fresh) {event(why,fresh,false);}
    private void event(String why,boolean fresh,boolean readOnly) {
        if(destroyed||!enabled)return;
        // A manual observation must never displace an already queued device change.
        if(readOnly&&(busy||pending||main.hasCallbacks(scheduled))) {
            RunLog.add(this,"VERIFY_SKIPPED_BUSY; wait for current transition");return;
        }
        reason=why;verifyRequest=readOnly;
        if(fresh){generation++;routeRetries=0;}
        RunLog.add(this,"EVENT "+why+"; generation="+generation+"; readOnly="+readOnly+"; "+screenState());
        if(busy){pending=true;if(pageBusy)cancelPage();return;}
        main.removeCallbacks(scheduled);
        try{wake.acquire(35000);}catch(RuntimeException e){RunLog.add(this,"WakeLock: "+e);}
        main.postDelayed(scheduled,750);
    }

    private void releaseWake() { if (wake != null && wake.isHeld()) wake.release(); }

    private void cancelPage(){
        final IBinder service=remote;
        if(pageBusy&&service!=null)new Thread(new Runnable(){@Override public void run(){
            try{Bridge.call(service,Bridge.CANCEL_PAGE);}catch(Exception ignored){}
        }},"audiofx-cancel-page").start();
    }
    private void startPageSync(Intent intent){
        android.os.ResultReceiver ack=intent.getParcelableExtra("reply",android.os.ResultReceiver.class);
        String message=null;
        if(!enabled||remote==null)message="请先开启自动切换，等待控制服务连接";
        else if(busy||pending||main.hasCallbacks(scheduled))message="正在切换音效，请稍后再同步系统页面";
        final mi14.audiofx.eqpage.PageSyncRequest request;
        try{
            if(message!=null)throw new IllegalStateException(message);
            if(ack==null)throw new IllegalStateException("页面启动回调缺失");
            request=new mi14.audiofx.eqpage.PageSyncRequest(ConfigStore.load(this),intent.getStringExtra("expected_key"));
        }catch(Exception e){
            if(ack!=null){android.os.Bundle b=new android.os.Bundle();b.putString("message",e.getMessage());ack.send(1,b);}return;
        }
        generation++;busy=true;pageBusy=true;verifyRequest=false;
        final IBinder service=remote;
        try{wake.acquire(45000);}catch(Exception ignored){}
        getSharedPreferences(PREFS,0).edit().putString("page_status","正在同步，请保持系统 EQ 页面可见").apply();
        status("正在同步系统 EQ 页面，请勿调节滑杆");
        RunLog.add(this,"PAGE_SYNC_START; expected="+ConfigStore.safeLog(request.key));
        worker.execute(new Runnable(){@Override public void run(){
            String value;
            try{value=Bridge.call(service,Bridge.PAGE_SYNC,request.json());}
            catch(Exception e){value="EXIT=2\nPAGE_BINDER_ERROR="+e;}
            final String result=value;
            main.post(new Runnable(){@Override public void run(){
                busy=false;pageBusy=false;releaseWake();
                RunLog.add(MonitorService.this,"PAGE_SYNC_END\n"+ConfigStore.safeLog(result));
                boolean ok=result.startsWith("EXIT=0\n")&&"PAGE_SYNC_MATCH".equals(ConfigStore.line(result,"RESULT"));
                String note=ok?"系统页面已同步 · "+request.config.eqName(request.config.rule(request.key).eq):"系统页面同步未完成，请导出诊断日志";
                getSharedPreferences(PREFS,0).edit().putString("page_status",note).putLong("page_time",System.currentTimeMillis()).apply();
                if(destroyed||!enabled)return;
                status(note);android.widget.Toast.makeText(MonitorService.this,note,android.widget.Toast.LENGTH_LONG).show();
                pending=false;
                // The stock page writes its saved curve on opening. Restore the fresh native profile.
                event("页面同步后恢复当前设备方案",true);
            }});
        }});
        ack.send(0,android.os.Bundle.EMPTY);
    }

    private String screenState() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return "interactive=" + pm.isInteractive() + "; locked=" + keyguard.isKeyguardLocked();
    }

    private void runCheck() {
        if (destroyed || !enabled) { releaseWake(); return; }
        if (remote == null) { tryBind(); releaseWake(); return; }
        if (busy) { pending = true; return; }
        final mi14.audiofx.Config configuration;
        try { configuration=ConfigStore.load(this); }
        catch(Exception e){status("配置读取失败，请打开 App 检查："+e.getMessage());releaseWake();return;}
        busy = true;
        final IBinder service = remote;
        final int currentGeneration = generation;
        final String currentReason = reason;
        final boolean readOnly=verifyRequest;
        RunLog.add(this, "RUN_START generation=" + currentGeneration + "; " + screenState());
        status(readOnly?"正在只读检查音效和 EQ":"正在检查输出并切换音效");
        worker.execute(new Runnable() { @Override public void run() {
            String reply;
            try { reply = Bridge.call(service, readOnly?Bridge.VERIFY:Bridge.RUN, configuration.json()); }
            catch (Exception e) { reply = "EXIT=2\nBinder: " + e; }
            final String result = reply;
            main.post(new Runnable() { @Override public void run() {
                busy = false; releaseWake();
                final String summary=ConfigStore.acceptResult(MonitorService.this,result,configuration);
                RunLog.add(MonitorService.this, "RUN_END generation=" + currentGeneration + "; " + screenState()
                        + "; reason=" + currentReason + "\n" + ConfigStore.safeLog(result));
                if (destroyed || !enabled) return;
                boolean succeeded = result.startsWith("EXIT=0\n");
                boolean deferred = result.startsWith("EXIT=4\n");
                if(readOnly) {
                    if(succeeded)status(summary+"\n只读检查：当前状态与方案匹配");
                    else if(result.startsWith("EXIT=6\n"))status(summary+"\n音效状态匹配；小米 EQ 无法读回确认（本次未写入）");
                    else if("DETECTED".equals(ConfigStore.line(result,"eq.drift")))status(summary+"\nEQ 与方案不符，可能已被覆盖；未重新写入");
                    else if(deferred)status("只读检查暂缓：路由不稳定或通话中；本次未写入");
                    else status("只读检查未通过：状态不符或读取失败；本次未写入，请导出日志");
                } else if (succeeded) status(summary.isEmpty()?"方案已应用":summary);
                else if (result.startsWith("EXIT=5\n")) status(summary+"\n音效已生效，EQ 未完成；请导出日志");
                else if (result.startsWith("EXIT=6\n")) status(summary+"\n音效已生效，EQ 已发送但无法读回确认");
                else if (deferred) status("暂缓切换：通话中、路由未稳定或非目标设备");
                else status("切换未通过，请导出日志；不自动重试写入");
                if (pending || currentGeneration != generation) { pending = false; event("合并后的设备变化", false); return; }
                if (!readOnly && deferred && routeRetries < 3) {
                    routeRetries++;
                    wake.acquire(10000);
                    main.postDelayed(new Runnable() { @Override public void run() {
                        if (generation == currentGeneration) event("路由稳定复查", false);
                    }}, 1000L * routeRetries);
                } else if ((succeeded || result.startsWith("EXIT=6\n")) && !readOnly) {
                    wake.acquire(10000);
                    main.postDelayed(new Runnable() { @Override public void run() {
                        if (generation == currentGeneration) event("切换后只读复查", false, true);
                    }}, 2500);
                }
            }});
        }});
    }

    private void detach() {
        try { if (Shizuku.pingBinder()) Shizuku.unbindUserService(args, connection, true); }
        catch (Exception e) { RunLog.add(this, "Unbind: " + e); }
    }

    @Override public void onDestroy() {
        active = false;
        destroyed = true; enabled = false; generation++;
        main.removeCallbacksAndMessages(null);
        if (registered) try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        if (audioRegistered) audio.unregisterAudioDeviceCallback(devices);
        if (modeRegistered) audio.removeOnModeChangedListener(modes);
        Shizuku.removeBinderReceivedListener(received); Shizuku.removeBinderDeadListener(dead);
        final IBinder service = remote;
        if (service != null) {
            try { if (death != null) service.unlinkToDeath(death, 0); } catch (Exception ignored) {}
            new Thread(new Runnable() { @Override public void run() {
                try { Bridge.call(service, Bridge.CANCEL); } catch (Exception ignored) {}
            }}, "audiofx-cancel").start();
        }
        detach(); worker.shutdown(); releaseWake();
        RunLog.add(this, "SERVICE_DESTROY");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
