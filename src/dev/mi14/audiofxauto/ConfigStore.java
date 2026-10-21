package dev.mi14.audiofxauto;

import android.content.Context;
import android.content.SharedPreferences;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.LinkedHashMap;
import mi14.audiofx.Config;

public final class ConfigStore {
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("device_profiles_v6",0);}
    public static synchronized Config load(Context c){String s=prefs(c).getString("config",null);return s==null?Config.defaults():Config.parse(s);}
    public static synchronized void save(Context c,Config config){if(!prefs(c).edit().putString("config",config.json()).commit())throw new IllegalStateException("配置保存失败");}
    public static synchronized LinkedHashMap<String,String> devices(Context c) {
        LinkedHashMap<String,String> out=new LinkedHashMap<String,String>();
        try{JSONObject o=new JSONObject(prefs(c).getString("devices","{}"));Iterator<String> it=o.keys();while(it.hasNext()){String k=it.next();if(k.startsWith("bt:")&&Config.deviceKey(k))out.put(k,o.getString(k));}}
        catch(Exception e){RunLog.add(c,"设备名称记录读取失败："+e.getClass().getSimpleName());}
        return out;
    }
    public static synchronized void remember(Context c,String key,String name) {
        if(!key.startsWith("bt:")||!Config.deviceKey(key))return;
        try {
            JSONObject o=new JSONObject(prefs(c).getString("devices","{}"));if(o.length()>=64&&!o.has(key))return;
            String n=clean(name);if(n.length()==0)n="蓝牙设备 "+key.substring(key.length()-5);
            if(!n.equals(o.optString(key))) {o.put(key,n);prefs(c).edit().putString("devices",o.toString()).apply();}
        }catch(Exception e){RunLog.add(c,"设备名称保存失败："+e.getClass().getSimpleName());}
    }
    public static String clean(String s){if(s==null)return "";s=s.replaceAll("[\\p{Cntrl}]"," ").trim();return s.length()>60?s.substring(0,60):s;}
    public static String bluetoothName(Context c,String key,String fallback) {
        try{BluetoothAdapter a=((BluetoothManager)c.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();if(a!=null&&key.startsWith("bt:")){BluetoothDevice d=a.getRemoteDevice(key.substring(3));String n=d.getAlias();if(n==null||n.isEmpty())n=d.getName();if(n!=null&&!n.isEmpty())return clean(n);}}
        catch(Exception ignored){}return clean(fallback);
    }
    public static String name(Context c,String key,Config cfg) {
        if(cfg.rules.containsKey(key))return cfg.rules.get(key).title;
        String n=devices(c).get(key);return n!=null?n:Config.SPEAKER.equals(key)?"手机扬声器":"蓝牙设备";
    }
    public static String line(String result,String key){String prefix=key+"=";for(String l:result.split("\n"))if(l.startsWith(prefix))return l.substring(prefix.length());return "";}
    public static String acceptResult(Context c,String result,Config cfg) {
        try {
            String data=line(result,"ROUTE_JSON");if(data.isEmpty()){prefs(c).edit().remove("current_key").remove("current_name").apply();return "";}
            JSONObject o=new JSONObject(data);String key=o.getString("key");if(!Config.deviceKey(key))return "";
            if(key.startsWith("bt:"))remember(c,key,bluetoothName(c,key,o.optString("name")));
            String name=name(c,key,cfg);prefs(c).edit().putString("current_key",key).putString("current_name",name).putLong("current_time",System.currentTimeMillis()).apply();
            return name+" · "+cfg.rule(key).label()+" · "+cfg.eqName(cfg.rule(key).eq);
        }catch(Exception e){RunLog.add(c,"设备识别记录失败："+e.getClass().getSimpleName());return "";}
    }
    public static String currentKey(Context c){return prefs(c).getString("current_key","");}
    public static String safeLog(String text){return text.replaceAll("(?i)(?:[0-9a-f]{2}:){5}([0-9a-f]{2})","**:**:**:**:**:$1");}
}
