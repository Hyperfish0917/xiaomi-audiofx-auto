package mi14.audiofx;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Versioned local data and strict protocol validation, shared by UI and shell child. */
public final class Config {
    public static final String SPEAKER="speaker", DEFAULT_BT="bt-default", KEEP="keep", FLAT="flat", WARM="warm";
    public final LinkedHashMap<String,Rule> rules=new LinkedHashMap<String,Rule>();
    public final LinkedHashMap<String,Preset> presets=new LinkedHashMap<String,Preset>();
    public static final class Rule {
        public String title,effect,eq;
        public boolean spatial;
        public Rule(String t,String f,boolean s,String e){title=t;effect=f;spatial=s;eq=e;}
        public Rule copy(){return new Rule(title,effect,spatial,eq);}
        public String label(){return effectName(effect)+(spatial?" + 沉浸声":"");}
    }
    public static final class Preset {
        public String name; public int[] percent;
        public Preset(String n,int[] p){name=n;percent=p.clone();}
    }
    public static String effectName(String s){return "dolby".equals(s)?"杜比全景声":"misound".equals(s)?"小米音效":"原声";}
    public static String bluetoothKey(String address) {
        if(address==null)return DEFAULT_BT;
        String a=address.trim().toUpperCase(Locale.ROOT);
        if(!a.matches("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}") || a.equals("00:00:00:00:00:00") || a.equals("02:00:00:00:00:00"))return DEFAULT_BT;
        return "bt:"+a;
    }
    public static boolean deviceKey(String s){return SPEAKER.equals(s)||DEFAULT_BT.equals(s)||(s!=null&&s.startsWith("bt:")&&s.equals(bluetoothKey(s.substring(3))));}
    public Rule rule(String key){Rule r=rules.get(key);return r!=null?r:rules.get(SPEAKER.equals(key)?SPEAKER:DEFAULT_BT);}
    public String eqName(String id){return KEEP.equals(id)?"保持现有 EQ":presets.get(id).name;}
    public static Config defaults() {
        Config c=new Config();
        c.presets.put(FLAT,new Preset("平直 · 不增不减",new int[10]));
        c.presets.put(WARM,new Preset("低频增强 · 高频柔和",EqMath.WARM));
        c.rules.put(SPEAKER,new Rule("手机扬声器","dolby",true,WARM));
        c.rules.put(DEFAULT_BT,new Rule("其他蓝牙设备","none",false,FLAT));
        return c;
    }
    public void validate() {
        if(rules.size()>64||presets.size()>32||!rules.containsKey(SPEAKER)||!rules.containsKey(DEFAULT_BT)||!presets.containsKey(FLAT))throw new IllegalArgumentException("方案数量或默认项无效");
        for(Map.Entry<String,Preset> e:presets.entrySet()) {
            if(!e.getKey().matches("[a-zA-Z0-9_-]{1,60}")||KEEP.equals(e.getKey()))throw new IllegalArgumentException("预设标识无效");
            name(e.getValue().name);EqMath.validate(e.getValue().percent);
        }
        if(!EqMath.flat(presets.get(FLAT).percent))throw new IllegalArgumentException("平直预设必须保持全 0");
        for(Map.Entry<String,Rule> e:rules.entrySet()) {
            Rule r=e.getValue();name(r.title);
            if(!deviceKey(e.getKey())||!("dolby".equals(r.effect)||"misound".equals(r.effect)||"none".equals(r.effect)))throw new IllegalArgumentException("设备或音效无效");
            if(!KEEP.equals(r.eq)&&!presets.containsKey(r.eq))throw new IllegalArgumentException("引用的 EQ 不存在");
            if("none".equals(r.effect)&&(SPEAKER.equals(e.getKey())||r.spatial||(!KEEP.equals(r.eq)&&!EqMath.flat(presets.get(r.eq).percent))))throw new IllegalArgumentException("原声仅用于蓝牙，不能同时启用沉浸声或非平直 EQ");
        }
    }
    private static void name(String n){if(n==null||n.trim().length()==0||n.length()>60||n.indexOf('\n')>=0)throw new IllegalArgumentException("名称需为 1～60 字符");}
    public String json() {
        validate();try {
            JSONObject o=new JSONObject().put("schema",1),rs=new JSONObject(),ps=new JSONObject();
            for(Map.Entry<String,Rule> e:rules.entrySet()) {Rule r=e.getValue();rs.put(e.getKey(),new JSONObject().put("title",r.title).put("effect",r.effect).put("spatial",r.spatial).put("eq",r.eq));}
            for(Map.Entry<String,Preset> e:presets.entrySet()) {JSONArray a=new JSONArray();for(int v:e.getValue().percent)a.put(v);ps.put(e.getKey(),new JSONObject().put("name",e.getValue().name).put("percent",a));}
            return o.put("rules",rs).put("presets",ps).toString();
        }catch(Exception e){throw new IllegalArgumentException("配置编码失败",e);}
    }
    public static Config parse(String text) {
        if(text==null||text.length()>60000)throw new IllegalArgumentException("配置过大或缺失");
        try {
            JSONObject o=new JSONObject(text);if(o.getInt("schema")!=1)throw new IllegalArgumentException("未知配置版本");
            Config c=new Config();JSONObject ps=o.getJSONObject("presets"),rs=o.getJSONObject("rules");
            Iterator<String> keys=ps.keys();while(keys.hasNext()){String k=keys.next();JSONObject p=ps.getJSONObject(k);JSONArray a=p.getJSONArray("percent");if(a.length()!=10)throw new IllegalArgumentException("频段数错误");int[] b=new int[10];for(int i=0;i<10;i++){double v=a.getDouble(i);if(v!=Math.rint(v))throw new IllegalArgumentException("百分比必须为整数");b[i]=(int)v;}c.presets.put(k,new Preset(p.getString("name"),b));}
            keys=rs.keys();while(keys.hasNext()){String k=keys.next();JSONObject r=rs.getJSONObject(k);c.rules.put(k,new Rule(r.getString("title"),r.getString("effect"),r.getBoolean("spatial"),r.getString("eq")));}
            c.validate();return c;
        }catch(Exception e){throw new IllegalArgumentException("配置无效："+e.getMessage(),e);}
    }
}
