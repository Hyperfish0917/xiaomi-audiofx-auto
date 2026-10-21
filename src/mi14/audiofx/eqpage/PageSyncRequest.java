package mi14.audiofx.eqpage;
import mi14.audiofx.Config;
import org.json.JSONObject;
/** Fixed payload: saved configuration plus the expected real output key. */
public final class PageSyncRequest {
    public final Config config; public final String key; public final int[] target;
    public PageSyncRequest(Config c,String k){
        c.validate();if(!Config.deviceKey(k))throw new IllegalArgumentException("请先识别当前输出设备");
        Config.Rule rule=c.rule(k);
        if("none".equals(rule.effect)||Config.KEEP.equals(rule.eq))throw new IllegalArgumentException("当前方案为原声或保持现有 EQ，无需同步系统曲线");
        config=c;key=k;int[] percent=c.presets.get(rule.eq).percent;target=new int[10];
        for(int i=0;i<10;i++)target[i]=quantize(percent[i]);
    }
    public static int quantize(int percent){
        if(percent < -100||percent > 100)throw new IllegalArgumentException("EQ 超出系统范围");
        float gain=percent*6f/100f,floor=(float)Math.floor(gain),part=gain-floor;
        return Math.round((part<0.25f?floor:part<=0.75f?floor+0.5f:floor+1f)*2f);
    }
    public String json(){
        try{return new JSONObject().put("schema",1).put("key",key).put("config",new JSONObject(config.json())).toString();}
        catch(Exception e){throw new IllegalArgumentException("页面同步请求编码失败",e);}
    }
    public static PageSyncRequest parse(String text){
        if(text==null||text.length()>60000)throw new IllegalArgumentException("页面同步请求过大或缺失");
        try{
            JSONObject o=new JSONObject(text);if(o.getInt("schema")!=1)throw new IllegalArgumentException("未知页面请求版本");
            return new PageSyncRequest(Config.parse(o.getJSONObject("config").toString()),o.getString("key"));
        }catch(Exception e){throw new IllegalArgumentException("页面同步请求无效："+e.getMessage(),e);}
    }
}
