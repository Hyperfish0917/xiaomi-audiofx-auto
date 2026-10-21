package mi14.audiofx;

import android.app.ActivityManager;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import android.app.Activity;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.Spatializer;
import android.media.audiofx.AudioEffect;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import android.os.UserHandle;
import java.util.List;
import android.app.ActivityThread;
import android.app.Application;
import android.app.ContentProviderHolder;
import android.app.IActivityManager;
import android.app.Instrumentation;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/** Route-guarded native operations from the supplied vendor wrappers. No capability flag edits. */
public final class NativeProbe {
    private static final String SHELL = "com.android.shell";
    private static int failures;
    private static Handler handler;
    private static ShellContext context;
    private static ActivityThread thread;
    private static Field initialApplication;
    private static Object previousApplication;
    private static boolean applicationChanged;
    private static Object center;
    private static Object presenter;
    private static boolean finished;
    private static String mode;
    private static boolean automatic;
    private static int exitCode = 3;
    private static final String CHANGED = "miui.intent.action.ACTION_AUDIO_EFFECT_CHANGED";
    private static final String SURROUND_CHANGED = "com.miui.misound.ACTION_3D_SURROUND_STATE_CHANGED";
    private static String deviceMapBefore;
    private static String lastSpatialBefore;
    private static Config config;
    private static Config.Rule rule;
    private static String selectedKey;
    private static int[] eqPercent;
    private static boolean verifyOnly;
    private static boolean manageEq;
    private static boolean effectChanged, settingsChanged, eqChanged;
    private static ApplyStages.Result stages;
    private static final class Route {
        String key,name,kind;
        Route(String k,String n,String t){key=k;name=n;kind=t;}
    }
    private static Route queryRoute() throws Exception {
        AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes media=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        List<AudioDeviceAttributes> routes=am.getDevicesForAttributes(media);
        int[] types=new int[routes==null?0:routes.size()];
        for(int i=0;i<types.length;i++)types[i]=routes.get(i).getType();
        String kind=RoutePolicy.target(types,am.getMode());
        if(kind==null)return null;
        if("speaker".equals(kind))return new Route(Config.SPEAKER,"手机扬声器",kind);
        String[] addresses=new String[routes.size()];String name="";
        for(int i=0;i<addresses.length;i++){addresses[i]=routes.get(i).getAddress();name=routes.get(i).getName();}
        String key=RoutePolicy.key(types,addresses,am.getMode());
        if(key==null)return null;
        return new Route(key,name,kind);
    }


    /** Read-only helpers for the separate, explicit foreground page process. */
    public static void preparePageIdentity() throws Exception {
        if(Process.myUid()!=2000||Build.VERSION.SDK_INT!=36)throw new SecurityException("Require shell / Android 16");
        if(context!=null)throw new IllegalStateException("Page identity already initialized");
        identity();
    }
    public static String pageRouteJson() throws Exception {
        Route r=queryRoute();if(r==null)throw new IllegalStateException("PAGE_ROUTE_UNSUPPORTED_OR_IN_CALL");
        return new JSONObject().put("key",r.key).put("name",r.name).put("kind",r.kind).toString();
    }
    public static void guardPageRoute(String expected) throws Exception {
        Route r=queryRoute();if(r==null||!expected.equals(r.key))throw new IllegalStateException("PAGE_OUTPUT_CHANGED_OR_IN_CALL");
        if(Settings.Secure.getInt(context.getContentResolver(),"touch_exploration_enabled",1)!=0)
            throw new IllegalStateException("PAGE_TOUCH_EXPLORATION_NOT_OFF");
    }
    public static void closePageIdentity() throws Exception {
        if(context!=null){context.closeReceivers();context.resolver.close();}
        if(applicationChanged){initialApplication.set(thread,previousApplication);applicationChanged=false;}
    }

    private static synchronized void out(String key, Object value) {
        System.out.println(key + "=" + String.valueOf(value));
        System.out.flush();
    }

    private static synchronized void error(String stage, Throwable t) {
        failures++;
        while (t instanceof InvocationTargetException && t.getCause() != null) t = t.getCause();
        out(stage + ".ERROR", t.getClass().getName() + ": " + t.getMessage());
        t.printStackTrace(System.out);
        System.out.flush();
    }

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException absent) { /* try superclass */ }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object value(Object owner, String name) throws Exception {
        return field(owner.getClass(), name).get(owner);
    }

    private static Object zero(Object owner, String name) throws Exception {
        return owner.getClass().getMethod(name).invoke(owner);
    }

    private static Object privateZero(Object owner, String name) throws Exception {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(owner);
            } catch (NoSuchMethodException absent) { /* try superclass */ }
        }
        throw new NoSuchMethodException(name);
    }

    /** Settings only; the external-provider entry point already passed on this phone. */
    private static final class ExternalResolver extends ContentResolver {
        private final IActivityManager am = ActivityManager.getService();
        private final int userId = Process.myUid() / 100000;
        private final ArrayList<Lease> leases = new ArrayList<Lease>();
        private boolean closed;

        private static final class Lease {
            final IContentProvider provider;
            final IBinder token;
            Lease(IContentProvider provider, IBinder token) {
                this.provider = provider;
                this.token = token;
            }
        }

        ExternalResolver(Context c) { super(c); }

        @Override protected synchronized IContentProvider acquireProvider(Context c, String name) {
            if (closed) throw new IllegalStateException("Resolver closed");
            if (!"settings".equals(name)) throw new SecurityException("Unexpected authority: " + name);
            IBinder token = new Binder();
            boolean acquired = false;
            try {
                ContentProviderHolder holder = am.getContentProviderExternal(name, userId, token, "audiofx-auto-v6");
                if (holder == null) throw new IllegalStateException("Null provider holder");
                acquired = true;
                if (holder.provider == null) throw new IllegalStateException("Null provider binder");
                leases.add(new Lease(holder.provider, token));
                out("resolver.acquire", "settings; activeLeases=" + leases.size());
                return holder.provider;
            } catch (Exception e) {
                if (acquired) {
                    try { am.removeContentProviderExternalAsUser(name, token, userId); }
                    catch (RemoteException releaseError) { e.addSuppressed(releaseError); }
                }
                throw new IllegalStateException("External settings provider acquisition failed", e);
            }
        }

        @Override protected IContentProvider acquireUnstableProvider(Context c, String name) {
            return acquireProvider(c, name);
        }

        private void remove(int index) throws RemoteException {
            Lease lease = leases.get(index);
            am.removeContentProviderExternalAsUser("settings", lease.token, userId);
            leases.remove(index);
        }

        @Override public synchronized boolean releaseProvider(IContentProvider provider) {
            for (int i = leases.size() - 1; i >= 0; --i) {
                if (leases.get(i).provider.asBinder().equals(provider.asBinder())) {
                    try { remove(i); return true; }
                    catch (RemoteException e) { throw new IllegalStateException("Provider release failed", e); }
                }
            }
            return false;
        }

        @Override public boolean releaseUnstableProvider(IContentProvider provider) {
            return releaseProvider(provider);
        }

        @Override public synchronized void unstableProviderDied(IContentProvider provider) {
            error("resolver.providerDied", new IllegalStateException("Settings provider died"));
            for (int i = leases.size() - 1; i >= 0; --i) {
                if (leases.get(i).provider.asBinder().equals(provider.asBinder())) {
                    try { remove(i); } catch (RemoteException e) { error("resolver.dead.release", e); }
                }
            }
        }

        synchronized void close() {
            closed = true;
            // Settings.NameValueCache can retain a stable provider until process exit.
            for (int i = leases.size() - 1; i >= 0; --i) {
                try { remove(i); } catch (RemoteException e) { error("resolver.close", e); }
            }
            out("resolver.remainingLeases", leases.size());
        }
    }

    private static final class ShellContext extends ContextWrapper {
        final ExternalResolver resolver;
        private final Map<BroadcastReceiver, BroadcastReceiver> receivers =
                new IdentityHashMap<BroadcastReceiver, BroadcastReceiver>();
        private int registrationCalls;

        ShellContext(Context base) {
            super(base);
            resolver = new ExternalResolver(this);
        }

        @Override public ContentResolver getContentResolver() { return resolver; }
        @Override public Context getApplicationContext() { return this; }

        @Override public Intent registerReceiver(BroadcastReceiver r, IntentFilter f) {
            return registerReceiver(r, f, null, null, 0);
        }
        @Override public Intent registerReceiver(BroadcastReceiver r, IntentFilter f, int flags) {
            return registerReceiver(r, f, null, null, flags);
        }
        @Override public Intent registerReceiver(BroadcastReceiver r, IntentFilter f, String permission, Handler h) {
            return registerReceiver(r, f, permission, h, 0);
        }
        @Override public Intent registerReceiver(final BroadcastReceiver r, IntentFilter f,
                String permission, Handler h, int flags) {
            if (r == null) return getBaseContext().registerReceiver(null, f, permission, h, flags);
            BroadcastReceiver bridge = receivers.get(r);
            if (bridge == null) {
                bridge = new BroadcastReceiver() {
                    @Override public void onReceive(Context ignored, Intent intent) {
                        try { r.onReceive(ShellContext.this, intent); }
                        catch (Throwable t) { error("receiver.callback", t); }
                    }
                };
                // Track before Binder call, so a partially completed registration can be cleaned up.
                receivers.put(r, bridge);
            }
            registrationCalls++;
            Intent sticky = getBaseContext().registerReceiver(bridge, f, permission, h, flags);
            out("receiver.registerCall." + registrationCalls,
                    "RETURNED; action=" + f.getAction(0) + "; sticky=" + (sticky != null));
            return sticky; // Returning does NOT establish that AMS registered the receiver.
        }

        @Override public void unregisterReceiver(BroadcastReceiver r) {
            BroadcastReceiver bridge = receivers.get(r);
            if (bridge == null) throw new IllegalArgumentException("Receiver not tracked");
            getBaseContext().unregisterReceiver(bridge);
            receivers.remove(r);
        }

        @Override public void sendBroadcast(Intent intent) {
            sendNotification(intent, Process.myUid() / 100000);
        }
        @Override public void sendBroadcastAsUser(Intent intent, UserHandle user) {
            sendNotification(intent, user.getIdentifier());
        }
        private void sendNotification(Intent intent, int userId) {
            String action = intent.getAction();
            if (!CHANGED.equals(action) && !SURROUND_CHANGED.equals(action)) {
                throw new SecurityException("Unexpected broadcast: " + action);
            }
            try {
                if (ActivityThread.getPackageManager().isProtectedBroadcast(action)) {
                    throw new SecurityException("Protected broadcast rejected: " + action);
                }
                // Same caller=null Binder entry as AOSP am broadcast, retaining real shell UID.
                // A return code of 0 means accepted, not proof that every receiver processed it.
                Intent outgoing = new Intent(intent);
                outgoing.addFlags(Intent.FLAG_RECEIVER_FROM_SHELL);
                int result = ActivityManager.getService().broadcastIntentWithFeature(
                        null, null, outgoing, null, null, Activity.RESULT_OK,
                        null, null, null, null, null, -1, null, false, false, userId);
                out("notify.acceptedCode", result + "; action=" + action + "; user=" + userId);
                if (result != ActivityManager.BROADCAST_SUCCESS) {
                    throw new IllegalStateException("Broadcast rejected, result=" + result);
                }
            } catch (RemoteException e) { throw new IllegalStateException("Broadcast Binder failure", e); }
        }

        void closeReceivers() {
            for (BroadcastReceiver r : new ArrayList<BroadcastReceiver>(receivers.keySet())) {
                try { unregisterReceiver(r); }
                catch (Throwable t) { error("receiver.cleanup", t); }
            }
            out("receiver.remainingTracked", receivers.size());
        }
    }

    private static void identity() throws Exception {
        thread = ActivityThread.currentActivityThread();
        if (thread == null) thread = ActivityThread.systemMain();
        Context system = thread.getSystemContext();
        Context child = system.createPackageContext(SHELL, 0);
        Object loadedApk = value(child, "mPackageInfo");
        Method factory = Class.forName("android.app.ContextImpl").getDeclaredMethod(
                "createAppContext", ActivityThread.class, Class.forName("android.app.LoadedApk"));
        factory.setAccessible(true);
        Context fresh = (Context) factory.invoke(null, thread, loadedApk);
        if (fresh.getApplicationInfo().uid != Process.myUid()
                || !SHELL.equals(fresh.getPackageName()) || !SHELL.equals(fresh.getOpPackageName())
                || fresh.getAttributionSource().getUid() != Process.myUid()
                || !SHELL.equals(fresh.getAttributionSource().getPackageName())) {
            throw new SecurityException("Fresh shell identity mismatch");
        }
        context = new ShellContext(fresh);
        out("context.identity", "PASS");
        out("resolver.package", value(context.resolver, "mPackageName"));
        // AudioEffect obtains its attribution from the process Application, not its caller's Context.
        // This changes only this standalone diagnostic process; no UID or AMS process record is forged.
        initialApplication = field(ActivityThread.class, "mInitialApplication");
        previousApplication = initialApplication.get(thread);
        Application shellApplication = Instrumentation.newApplication(Application.class, context);
        initialApplication.set(thread, shellApplication);
        applicationChanged = true;
        AttributionSource attribution = AttributionSource.myAttributionSource();
        out("nativeAttribution.uid", attribution.getUid());
        out("nativeAttribution.package", attribution.getPackageName());
        if (attribution.getUid() != Process.myUid() || !SHELL.equals(attribution.getPackageName())) {
            throw new SecurityException("Native effect attribution mismatch");
        }
        out("nativeAttribution.identity", "PASS");
    }

    private static final class State {
        boolean dolby, dolbyFramework, misound, spatial, dolbyControl, misoundControl;
        boolean coreMatches() { return dolby=="dolby".equals(rule.effect) && dolbyFramework=="dolby".equals(rule.effect) && misound=="misound".equals(rule.effect); }
        boolean targetMatches() { return coreMatches() && spatial==rule.spatial; }
    }

    private static State readState(String label) throws Exception {
        // Read existing handles. Do not silently recreate them while measuring results.
        Object dolby = value(presenter, "mDolbyAudio");
        Object misound = value(presenter, "mMiSound");
        if (dolby == null || misound == null) throw new IllegalStateException("Missing native effect handle");
        Object spatializer = value(presenter, "mSpatializer");
        State s = new State();
        s.dolbyControl = (Boolean) zero(dolby, "hasControl");
        s.misoundControl = (Boolean) zero(misound, "hasControl");
        s.dolby = (Boolean) zero(dolby, "getDsOn");
        s.dolbyFramework = (Boolean) zero(dolby, "getEnabled");
        s.misound = (Boolean) zero(misound, "getEnabled");
        s.spatial = (Boolean) zero(spatializer, "isEnabled");
        out(label + ".dolby", s.dolby);
        out(label + ".dolbyFramework", s.dolbyFramework);
        out(label + ".misound", s.misound);
        out(label + ".spatializer", s.spatial);
        out(label + ".dolbyControl", s.dolbyControl);
        out(label + ".misoundControl", s.misoundControl);
        for (String key : new String[]{"effect_implementer", "last_device", "last_select_effect_none", "last_spatial_status"}) {
            out(label + ".settings." + key, Settings.Global.getString(context.resolver, key));
        }
        return s;
    }

    private static boolean bluetoothMode() {
        return "bt-none".equals(mode) || "inspect-bt-none".equals(mode);
    }

    private static void requireRoute(String label) throws Exception {
        Class<?> utils = Class.forName("android.media.audiofx.EffectUtils");
        boolean headphones = (Boolean) utils.getMethod("isHeadSetOn", Context.class).invoke(null, context);
        boolean bluetooth = (Boolean) utils.getMethod("isBluetoothSetOn").invoke(null);
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        List<AudioDeviceAttributes> routes = am.getDevicesForAttributes(attributes);
        ArrayList<Integer> types = new ArrayList<Integer>();
        boolean routeMatches = routes != null && !routes.isEmpty();
        for (AudioDeviceAttributes d : routes == null ? new ArrayList<AudioDeviceAttributes>() : routes) {
            int type = d.getType();
            types.add(type);
            boolean accepted = bluetoothMode()
                    ? type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    : type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
            if (!accepted) routeMatches = false;
        }
        int audioMode = am.getMode();
        out(label + ".expected", bluetoothMode() ? "BLUETOOTH_AUDIO" : "SPEAKER");
        out(label + ".headsetConnected", headphones);
        out(label + ".bluetoothConnected", bluetooth);
        out(label + ".mediaRouteTypes", types);
        out(label + ".audioMode", audioMode);
        Route actual=queryRoute();
        boolean identityMatches=actual!=null && selectedKey.equals(actual.key);
        if (!identityMatches || !routeMatches || audioMode != AudioManager.MODE_NORMAL) {
            throw new IllegalStateException("Route mismatch: require "
                    + (bluetoothMode() ? "active A2DP/LE headset media output" : "speaker output with all headsets disconnected")
                    + " and no call; no fallback to another route");
        }
        out(label + ".check", "PASS");
    }

    private static AudioEffect effect(String fieldName) throws Exception {
        return (AudioEffect) value(presenter, fieldName);
    }

    private static void writeGuard(String label) throws Exception {
        if(verifyOnly)throw new IllegalStateException("WRITE_DENIED_IN_VERIFY: "+label);
        requireRoute(label + ".route");
        if ("none".equals(rule.effect) && !(Boolean) zero(presenter, "isNoneAvailable")) {
            throw new IllegalStateException("Original sound unavailable on this route");
        }
        if (!effect("mDolbyAudio").hasControl() || !effect("mMiSound").hasControl()) {
            throw new IllegalStateException("Lost native control before " + label);
        }
    }

    private static void nativeStatus(String label, int status) {
        out(label + ".nativeStatus", status);
        if (status != AudioEffect.SUCCESS) {
            throw new IllegalStateException(label + " returned native status " + status);
        }
    }

    private static void setMiSound(boolean enabled) throws Exception {
        writeGuard("misound.enable");
        out("misound.enable.call", "MiSound.setEnabled("+enabled+"); vendor parameter 25 then framework enable");
        // Dynamic dispatch runs the vendor MiSound override. It checks the parameter write
        // and returns the AudioEffect.setEnabled result instead of swallowing it.
        nativeStatus("misound.enable", effect("mMiSound").setEnabled(enabled));
        if ((Boolean) zero(effect("mMiSound"), "getEnabled") != enabled) {
            throw new IllegalStateException("MiSound enable state mismatch");
        }
    }

    private static void setDolby(boolean enabled) throws Exception {
        AudioEffect dolby = effect("mDolbyAudio");
        // Exact setDsOn sequence in framework DolbyAudioEffect and APK e.C0175a.o:
        // parameter 5, little-endian int32 [event=0, count=1, enabled], then setEnabled.
        // Splitting the two operations exposes both real native status values.
        byte[] payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0).putInt(1).putInt(enabled ? 1 : 0).array();
        writeGuard("dolby.parameter");
        out("dolby.parameter.call", "setParameter(5,[0,1," + (enabled ? 1 : 0) + "])");
        nativeStatus("dolby.parameter", dolby.setParameter(5, payload));
        writeGuard("dolby.enable");
        out("dolby.enable.call", "AudioEffect.setEnabled(" + enabled + ")");
        nativeStatus("dolby.enable", dolby.setEnabled(enabled));
    }

    private static void setSpatial(boolean enabled) throws Exception {
        Spatializer spatializer = (Spatializer) value(presenter, "mSpatializer");
        if (spatializer.isEnabled() != enabled) {
            writeGuard("spatial.fade");
            ((AudioManager) context.getSystemService(Context.AUDIO_SERVICE))
                    .setParameters("effect_switch_fade=true");
            out("spatial.fade", "REQUESTED; same parameter as QcomEffectPresenter");
        }
        if (!enabled) {
            writeGuard("spatial.noSpatialFlag");
            out("spatial.noSpatialFlag.call", "MiSound.setNoSpatialFlag(true)");
            effect("mMiSound").getClass().getMethod("setNoSpatialFlag", boolean.class)
                    .invoke(effect("mMiSound"), true);
            out("spatial.noSpatialFlag.return", "NORMAL; vendor method checks native parameter status");
        }
        writeGuard("spatial.enable");
        out("spatial.enable.call", "Spatializer.setEnabled(" + enabled + ")");
        spatializer.setEnabled(enabled);
        out("spatial.enable.return", "NORMAL; delayed readback follows");
    }

    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }

    private static void setting(String key, String v) throws Exception {
        writeGuard("settings." + key);
        boolean accepted = Settings.Global.putString(context.resolver, key, v);
        out("settings.commit." + key, accepted);
        if (!accepted) throw new IllegalStateException("Settings write rejected: " + key);
    }

    private static boolean verifySettings() {
        boolean matches=rule.effect.equals(Settings.Global.getString(context.resolver,"effect_implementer"));
        if("none".equals(rule.effect)) {
            matches &= "1".equals(Settings.Global.getString(context.resolver,"last_select_effect_none"));
            matches &= same(lastSpatialBefore,Settings.Global.getString(context.resolver,"last_spatial_status"));
        } else {
            matches &= (rule.spatial?"1":"0").equals(Settings.Global.getString(context.resolver,"last_spatial_status"));
            if("misound".equals(rule.effect))matches &= "0".equals(Settings.Global.getString(context.resolver,"last_select_effect_none"));
        }
        return matches;
    }
    private static int[] dapRead(int profile,int param,int count,boolean portSpecific) throws Exception {
        int port=bluetoothMode()?4:0;
        int id=portSpecific?33554437+(param<<16)+(port<<8)+(profile<<12):16777221+(param<<16)+(profile<<8);
        byte[] bytes=new byte[(count+2)*4];Arrays.fill(bytes,(byte)0x7f);
        int status=effect("mDolbyAudio").getParameter(id,bytes);
        if(status<count*4)throw new IllegalStateException("Dolby EQ read length/status "+status+" for "+param);
        int[] result=new int[count];ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<count;i++)result[i]=b.getInt();return result;
    }
    private static void dapWrite(int profile,int param,int[] values,boolean portSpecific) throws Exception {
        writeGuard("eq.dolby."+profile+"."+param);
        ByteBuffer b=ByteBuffer.allocate((values.length+(portSpecific?5:4))*4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(portSpecific?33554432:16777216).putInt(values.length+1).putInt(profile);
        if(portSpecific)b.putInt(bluetoothMode()?4:0);
        b.putInt(param);for(int v:values)b.putInt(v);
        nativeStatus("eq.dolby."+profile+"."+param,effect("mDolbyAudio").setParameter(5,b.array()));
    }
    private static MiSoundEq.Port miPort() {
        return new MiSoundEq.Port() {
            @Override public int get(int band,byte[] bytes) throws Exception {
                int status=effect("mMiSound").getParameter(new int[]{2,band},bytes);
                out("eq.misound.read."+band,"bytes="+bytes.length+"; status="+status);
                return status;
            }
            @Override public int set(int band,byte[] bytes) throws Exception {
                writeGuard("eq.misound."+band);
                int status=effect("mMiSound").setParameter(new int[]{2,band},bytes);
                out("eq.misound.write."+band,"gain="+new String(bytes,StandardCharsets.UTF_8)+"; status="+status);
                return status;
            }
            @Override public void trace(String key,String message){out("eq.misound."+key,message);}
        };
    }

    private static void eqError(String phase,Exception t) {
        while(t instanceof InvocationTargetException && t.getCause() instanceof Exception)t=(Exception)t.getCause();
        out(phase+".ERROR",t.getClass().getName()+": "+t.getMessage());
        t.printStackTrace(System.out);
        // Deliberately separate from failures: an EQ error must not undo/skip the mode commit.
    }

    private static void reportEq(String phase, StockEq.Result result) {
        out(phase+".eq.backend.dolby",result.dolby.status);
        out(phase+".eq.backend.misound",result.miSound.status);
        if(result.dolby.error!=null)eqError(phase+".eq.dolby",result.dolby.error);
        if(result.miSound.error!=null)eqError(phase+".eq.misound",result.miSound.error);
        if(result.drift())out("eq.drift","DETECTED");
    }

    private static ApplyStages.EqStatus observeEq(String phase) throws Exception {
        if(!manageEq)return ApplyStages.EqStatus.SKIPPED;
        if(knownMiSoundReadUnsupported()) {
            StockEq.Result result=StockEq.observe(dolbyPort(),miPort(),eqPercent,false,phase);
            reportEq(phase,result);return result.status();
        }
        // Retain the V6.2 single-engine behavior outside the exact target firmware.
        boolean matched;
        if("misound".equals(rule.effect)) {
            float[] actual=MiSoundEq.readAll(miPort());out(phase+".eq.misound",Arrays.toString(actual));
            matched=MiSoundEq.matches(actual,EqMath.miSound(eqPercent));
        } else matched=DolbyEq.matches(dolbyPort(),eqPercent,phase);
        if(!matched)out("eq.drift","DETECTED");
        return matched?ApplyStages.EqStatus.VERIFIED:ApplyStages.EqStatus.FAILED;
    }

    private static DolbyEq.Port dolbyPort() {
        return new DolbyEq.Port() {
            @Override public int profiles() throws Exception {return (Integer)zero(effect("mDolbyAudio"),"getNumOfProfiles");}
            @Override public int[] gains(int profile) throws Exception {return dapRead(profile,110,20,false);}
            @Override public int enabled(int profile) throws Exception {return dapRead(profile,106,1,true)[0];}
            @Override public void gains(int profile,int[] gains) throws Exception {dapWrite(profile,110,gains,false);}
            @Override public void enable(int profile) throws Exception {dapWrite(profile,106,new int[]{1},true);}
            @Override public int save(int parameter,int command) throws Exception {
                writeGuard("eq.dolby.saveDsSetting");
                int status=effect("mDolbyAudio").setParameter(parameter,command);
                out("eq.dolby.saveDsSetting","parameter="+parameter+"; command="+command+"; status="+status);
                return status;
            }
            @Override public void trace(String phase,int profile,int[] gains,int enabled) {
                out(phase+".eq.dolby."+profile,Arrays.toString(gains)+"; enabled="+enabled);
            }
        };
    }

    private static boolean knownMiSoundReadUnsupported() {
        // V6.1 phone evidence: exact original 10-byte getLevel is rejected with -4.
        // Do not generalize this to a different firmware or rerun that failed experiment.
        return "Xiaomi/houji/houji:16/BP2A.250605.031.A3/OS3.0.307.0.WNCCNXM:user/release-keys".equals(Build.FINGERPRINT);
    }

    private static ApplyStages.EqStatus applyEq() throws Exception {
        if(!manageEq) {
            out("eq.scope","none".equals(rule.effect)?"BYPASSED_ORIGINAL":"KEEP_EXISTING");
            return ApplyStages.EqStatus.SKIPPED;
        }
        // The failed Spatializer experiment was GET parameter 257 (-2), not proof about SET.
        // This candidate does not use either operation; it follows the two stock EQ paths.
        if(!"houji".equals(Build.DEVICE))throw new UnsupportedOperationException("EQ mapping validated only for houji");
        if(knownMiSoundReadUnsupported()) {
            out("eq.scope","STOCK_DUAL_PARAMETERS_CANDIDATE; engine enable states unchanged by EQ");
            StockEq.Result result=StockEq.apply(dolbyPort(),miPort(),eqPercent,false);
            eqChanged=result.changed();reportEq("apply",result);return result.status();
        }
        out("eq.scope","V62_SINGLE_ENGINE_FALLBACK; firmware differs from target");
        if("misound".equals(rule.effect)) {
            MiSoundEq.Result result=MiSoundEq.apply(miPort(),eqPercent,!knownMiSoundReadUnsupported());eqChanged=result.changed;
            return result.status;
        }
        eqChanged=DolbyEq.apply(dolbyPort(),eqPercent);
        return ApplyStages.EqStatus.VERIFIED;
    }

    private static void commitEffects() throws Exception {
        requireRoute("preCommit.route");
        State s=readState("preCommit.native");
        out("verify.preCommitNative",s.targetMatches()?"PASS":"FAIL");
        if(!s.targetMatches()||failures!=0)throw new IllegalStateException("Native effect target failed; no settings or EQ commit");
        if(!verifySettings()) {
            if("none".equals(rule.effect))setting("last_select_effect_none","1");
            else {
                setting("last_spatial_status",rule.spatial?"1":"0");
                if("misound".equals(rule.effect))setting("last_select_effect_none","0");
            }
            setting("effect_implementer",rule.effect);settingsChanged=true;
        }
        if(effectChanged||settingsChanged) {
            writeGuard("notify");context.sendBroadcastAsUser(new Intent(CHANGED),UserHandle.ALL);
            out("settings.commit","COMPLETE_BEFORE_EQ");
        }else out("settings.commit","UNCHANGED");
        if(!verifySettings())throw new IllegalStateException("Effect settings readback failed before EQ");
    }

    private static void verifyFinal() {
        boolean effectOk=false;
        ApplyStages.EqStatus eqStatus=stages==null?ApplyStages.EqStatus.NOT_RUN:stages.eqStatus;
        try {
            requireRoute("after.route");State after=readState("after.native");
            boolean nativeOk=after.targetMatches(),settingsOk=verifySettings();
            out("verify.nativeTarget",nativeOk?"PASS":"FAIL");out("verify.effectSetting",settingsOk?"PASS":"FAIL");
            effectOk=nativeOk&&settingsOk&&failures==0&&stages!=null&&stages.effectError==null;
        }catch(Exception t){error("verify.effect",t);}
        if(effectOk&&(eqStatus==ApplyStages.EqStatus.VERIFIED||eqStatus==ApplyStages.EqStatus.UNVERIFIED)) {
            try {eqStatus=observeEq(verifyOnly?"observe":"final");}
            catch(MiSoundEq.UnsupportedRead t){eqError("verify.eq",t);eqStatus=ApplyStages.EqStatus.UNVERIFIED;}
            catch(Exception t){eqError("verify.eq",t);eqStatus=ApplyStages.EqStatus.FAILED;}
        }
        out("EQ_AUDIBLE_RESULT","NOT_MEASURED");
        out("EFFECT_RESULT",effectOk?"PASS":"FAIL");
        String eqResult=eqStatus==ApplyStages.EqStatus.VERIFIED?"PASS":eqStatus==ApplyStages.EqStatus.FAILED?"FAIL":eqStatus.name();
        out("EQ_RESULT",eqResult);out("verify.eqTarget",eqResult);
        exitCode=ApplyStages.exitCode(effectOk,eqStatus);
        out("AUTO_OUTCOME",verifyOnly?(exitCode==0?"VERIFIED_READ_ONLY":exitCode==6?"EQ_UNVERIFIABLE_READ_ONLY":"MISMATCH_OR_ERROR_READ_ONLY"):exitCode==0?(!effectChanged&&!settingsChanged&&!eqChanged?"UNCHANGED":"APPLIED"):exitCode==5||exitCode==6?"EFFECT_APPLIED_EQ_INCOMPLETE":"FAILED");
        finish();
    }

    private static void verifyBeforeCommit() {
        stages=ApplyStages.run(new ApplyStages.EffectStep(){
            @Override public void run() throws Exception {commitEffects();}
        },new ApplyStages.EqStep(){
            @Override public ApplyStages.EqStatus run() throws Exception {return applyEq();}
        });
        if(stages.effectError!=null)error("commit.effect",stages.effectError);
        if(stages.eqError!=null)eqError("apply.eq",stages.eqError);
        handler.postDelayed(new Runnable(){@Override public void run(){verifyFinal();}},500);
    }

    private static void run() throws Exception {
        out("uid", Process.myUid());
        out("pid", Process.myPid());
        out("sdk", Build.VERSION.SDK_INT);
        out("build", Build.DISPLAY);
        if (Process.myUid() != 2000 || Build.VERSION.SDK_INT != 36) {
            throw new SecurityException("Require shell UID 2000 and API 36");
        }
        if (SystemProperties.getBoolean("ro.vendor.audio.fweffect", false)) {
            throw new IllegalStateException("Unexpected framework observer mode");
        }
        deviceMapBefore = SystemProperties.get("persist.audio.effect.device_map", "");
        identity();
        Route route=queryRoute();
        if(route==null) {
            out("AUTO_OUTCOME","DEFER; unsupported, multiple or unstable media route / call");exitCode=4;finish();return;
        }
        selectedKey=route.key;mode=route.kind;rule=config.rule(selectedKey);
        out("ROUTE_JSON",new JSONObject().put("key",selectedKey).put("name",route.name).put("kind",route.kind).toString());
        out("AUTO_TARGET",rule.effect);out("TARGET_SPATIAL",rule.spatial);out("TARGET_EQ",rule.eq);
        manageEq=!"none".equals(rule.effect)&&!Config.KEEP.equals(rule.eq);
        if(manageEq)eqPercent=config.presets.get(rule.eq).percent;
        // Reject the wrong route before constructing AudioEffectCenter or native effect handles.
        try { requireRoute("initial.route"); }
        catch (Exception routeError) {
            if (!automatic) throw routeError;
            out("AUTO_OUTCOME", "DEFER; " + routeError.getMessage());
            exitCode = 4;
            finish();
            return;
        }
        Class<?> centerClass = Class.forName("android.media.audiofx.AudioEffectCenter");
        center = centerClass.getMethod("getInstance", Context.class).invoke(null, context);
        presenter = value(center, "mPresenter");
        if (!"android.media.audiofx.presenter.QcomEffectPresenter".equals(presenter.getClass().getName())) {
            throw new IllegalStateException("Unexpected presenter");
        }
        out("center.init", "RETURNED");
        for (String key : new String[]{"ro.vendor.audio.dolby.dax.support", "ro.vendor.audio.fweffect",
                "ro.vendor.audio.feature.spatial", "ro.vendor.audio.adsp.spatial"}) {
            out("property." + key, SystemProperties.get(key, "<unset-or-unreadable>"));
        }
        out("support.dolby", zero(presenter, "isSupportDolby"));
        out("support.dolbyField", value(presenter, "mIsDolbySupported"));
        out("support.dolbyStatic", Class.forName("android.media.audiofx.EffectUtils")
                .getMethod("isSupportDolby").invoke(null));
        out("support.misoundField", value(presenter, "mIsMisoundSupported"));
        boolean spatialSupported = (Boolean) zero(presenter, "isSupportSpatialAudio");
        boolean surroundSupported = (Boolean) zero(presenter, "isSupportSurround");
        out("support.spatial", spatialSupported);
        out("support.surround", surroundSupported);
        if (!spatialSupported || surroundSupported) throw new IllegalStateException("Unexpected immersion implementation");
        boolean noneAvailable = (Boolean) zero(presenter, "isNoneAvailable");
        out("framework.noneAvailable", noneAvailable);
        if ("none".equals(rule.effect) && !noneAvailable) throw new IllegalStateException("None not available on current route");
        privateZero(presenter, "getDolbyAudioInstanceSafely");
        privateZero(presenter, "getMiSoundInstanceSafely");
        if (!"com.dolby.dax.DolbyAudioEffect".equals(effect("mDolbyAudio").getClass().getName())
                || !"android.media.audiofx.MiSound".equals(effect("mMiSound").getClass().getName())) {
            throw new IllegalStateException("Unexpected native wrapper class");
        }
        out("native.dolby.id", effect("mDolbyAudio").getId());
        out("native.dolby.uuid", effect("mDolbyAudio").getDescriptor().uuid);
        out("native.misound.id", effect("mMiSound").getId());
        out("native.misound.uuid", effect("mMiSound").getDescriptor().uuid);
        if (!"9d4921da-8225-4f29-aefa-39537a04bcaa".equals(effect("mDolbyAudio").getDescriptor().uuid.toString())
                || !"5b8e36a5-144a-4c38-b1d7-0002a5d5c51b".equals(effect("mMiSound").getDescriptor().uuid.toString())) {
            throw new IllegalStateException("Unexpected effect implementation UUID");
        }
        lastSpatialBefore = Settings.Global.getString(context.resolver, "last_spatial_status");
        State before = readState("before.native");
        requireRoute("before.route");
        if (!before.dolbyControl || !before.misoundControl) throw new IllegalStateException("No native control");
        if (ActivityThread.getPackageManager().isProtectedBroadcast(CHANGED)) {
            throw new SecurityException("Effect notification unexpectedly protected");
        }
        out("notify.CHANGED.protected", false);
        if(verifyOnly) {
            stages=new ApplyStages.Result();
            // Observe Dolby even when the MiSound getter is known to be unsupported.
            stages.eqStatus=!manageEq?ApplyStages.EqStatus.SKIPPED:ApplyStages.EqStatus.VERIFIED;
            if(manageEq&&!"houji".equals(Build.DEVICE))stages.eqStatus=ApplyStages.EqStatus.FAILED;
            verifyFinal();return;
        }
        // EQ is optional and runs only AFTER native mode verification and settings commit.
        // No EQ getter/profile query/Spatializer parameter may block an effect transition here.
        effectChanged=!before.targetMatches();
        out("transition.required",effectChanged||!verifySettings());exitCode=2;
        try {
            if(!before.coreMatches()) {
                if("misound".equals(rule.effect)){setDolby(false);setMiSound(true);}
                else {setMiSound(false);setDolby("dolby".equals(rule.effect));}
            }
            State core=readState("after.core");
            out("verify.coreTarget",core.coreMatches()?"PASS":"FAIL");
            if(!core.coreMatches())throw new IllegalStateException("Core effect target absent");
            if(core.spatial!=rule.spatial)setSpatial(rule.spatial);
        }catch(Throwable t){error("nativeSequence",t);out("nativeSequence","STOPPED; delayed readback follows");}
        handler.postDelayed(new Runnable() {
            @Override public void run() { verifyBeforeCommit(); }
        }, effectChanged ? 1500 : 0);
    }

    private static void finish() {
        if (finished) return;
        finished = true;
        if (center != null) {
            try { zero(center, "release"); out("center.release", "RETURNED"); }
            catch (Throwable t) {
                error("center.release", t);
                if (presenter != null) {
                    for (String name : new String[]{"mDolbyAudio", "mMiSound"}) {
                        try {
                            Object effect = value(presenter, name);
                            if (effect != null) zero(effect, "release");
                        } catch (Throwable releaseError) { error(name + ".releaseFallback", releaseError); }
                    }
                }
            }
        }
        if (context != null) {
            context.closeReceivers();
            context.resolver.close();
        }
        if (applicationChanged) {
            try { initialApplication.set(thread, previousApplication); out("application.restore", "PASS"); }
            catch (Throwable t) { error("application.restore", t); }
        }
        if (deviceMapBefore != null) {
            boolean unchanged = deviceMapBefore.equals(SystemProperties.get("persist.audio.effect.device_map", ""));
            out("deviceMap.unchanged", unchanged);
            if (!unchanged) error("deviceMap", new IllegalStateException("Unexpected map change by another component"));
        }
        out("failures", failures);
        if ((exitCode == 0 || exitCode == 5 || exitCode == 6) && failures != 0) exitCode = 2;
        out("probe", exitCode == 0 ? "DONE" : exitCode == 3 ? "PREFLIGHT_STOP" : exitCode == 5 || exitCode == 6 ? "DONE_WITH_EQ_WARNING" : "DONE_WITH_ERRORS");
        if (automatic) out("AUTO_EXIT", exitCode);
        System.exit(exitCode);
    }

    public static void main(String[] args) {
        out("probe", "AFX_AUTO_V64");
        if(args.length!=1||!("auto".equals(args[0])||"verify".equals(args[0]))) {out("usage","auto or verify; JSON configuration on stdin");System.exit(3);}
        verifyOnly="verify".equals(args[0]);mode=args[0];automatic=true;out("mode",mode);out("RUN_KIND",verifyOnly?"VERIFY_ONLY":"APPLY");
        Thread watchdog = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(20000); } catch (InterruptedException ignored) { return; }
                out("probe", "TIMEOUT_20S; target may be partially applied; inspect log and use system settings if needed");
                Runtime.getRuntime().halt(124);
            }
        }, "route-probe-timeout");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            ByteArrayOutputStream input=new ByteArrayOutputStream();byte[] buf=new byte[2048];int n;
            while((n=System.in.read(buf))!=-1){input.write(buf,0,n);if(input.size()>60000)throw new IllegalArgumentException("Configuration too large");}
            config=Config.parse(new String(input.toByteArray(),StandardCharsets.UTF_8));
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Expected main thread");
            handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                @Override public void run() {
                    try { NativeProbe.run(); } catch (Throwable t) { error("main", t); finish(); }
                }
            });
            Looper.loop();
        } catch (Throwable t) { error("looper", t); finish(); }
    }
}

