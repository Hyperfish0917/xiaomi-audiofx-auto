import mi14.audiofx.*;
import java.util.*;
import org.json.*;
public final class ProfileTests {
 private static int count;
 private static void check(String n,boolean ok){if(!ok)throw new AssertionError(n);count++;System.out.println("PASS "+n);}
 private static void bad(String n,Runnable r){boolean failed=false;try{r.run();}catch(IllegalArgumentException e){failed=true;}check(n,failed);}
 public static void main(String[] args)throws Exception{
  Config c=Config.defaults();c.validate();
  check("speaker defaults Dolby + immersion + supplied photo",c.rule("speaker").effect.equals("dolby")&&c.rule("speaker").spatial&&c.rule("speaker").eq.equals("warm"));
  check("new Bluetooth uses original sound + flat",c.rule("bt:AA:BB:CC:DD:EE:01").effect.equals("none")&&!c.rule("bt-default").spatial&&c.rule("bt-default").eq.equals("flat"));
  check("photo vector exact",Arrays.equals(c.presets.get("warm").percent,new int[]{0,25,50,25,0,0,-25,-25,-25,0}));
  check("photo gain conversion",Arrays.equals(EqMath.miSound(EqMath.WARM),new float[]{0,1.5f,3,1.5f,0,0,-1.5f,-1.5f,-1.5f,0}));
  check("stock interpolation and mean normalization",Arrays.equals(EqMath.dolby(EqMath.WARM),new int[]{-2,10,22,34,46,34,22,10,-2,-2,-2,-14,-26,-26,-26,-26,-26,-14,-2,-2}));
  check("flat resets every Dolby band",Arrays.equals(EqMath.dolby(new int[10]),new int[20]));
  check("flat resets every MiSound band",Arrays.equals(EqMath.miSound(new int[10]),new float[10]));
  bad("malformed EQ length",()->EqMath.validate(new int[9]));
  bad("reject excessive positive gain",()->EqMath.validate(new int[]{105,0,0,0,0,0,0,0,0,0}));
  bad("reject excessive negative gain",()->EqMath.validate(new int[]{-105,0,0,0,0,0,0,0,0,0}));
  bad("reject nonstep gain",()->EqMath.validate(new int[]{24,0,0,0,0,0,0,0,0,0}));
  check("MAC canonicalization",Config.bluetoothKey("aa:bb:cc:dd:ee:01").equals("bt:AA:BB:CC:DD:EE:01"));
  check("missing address has default policy",Config.bluetoothKey("").equals("bt-default"));
  check("redacted address has default policy",Config.bluetoothKey("02:00:00:00:00:00").equals("bt-default"));
  check("valid key must be canonical",!Config.deviceKey("bt:aa:bb:cc:dd:ee:01"));
  c.rules.put("bt:AA:BB:CC:DD:EE:01",new Config.Rule("同名设备","none",false,"flat"));
  c.rules.put("bt:AA:BB:CC:DD:EE:02",new Config.Rule("同名设备","misound",true,"warm"));
  check("same name different identity has different settings",!c.rule("bt:AA:BB:CC:DD:EE:01").effect.equals(c.rule("bt:AA:BB:CC:DD:EE:02").effect));
  Config again=Config.parse(c.json());check("serialize roundtrip retains device profile",again.rule("bt:AA:BB:CC:DD:EE:02").spatial&&again.rule("bt:AA:BB:CC:DD:EE:02").eq.equals("warm"));
  check("serialize roundtrip retains photo curve",Arrays.equals(again.presets.get("warm").percent,EqMath.WARM));
  check("unknown device follows changed default",again.rule("bt:AA:BB:CC:DD:EE:03")==again.rules.get("bt-default"));
  Config invalid=Config.defaults();invalid.rules.get("speaker").effect="none";bad("original sound never on speaker",()->invalid.validate());
  Config spatial=Config.defaults();spatial.rules.get("bt-default").spatial=true;bad("original sound excludes immersion",()->spatial.validate());
  Config gains=Config.defaults();gains.rules.get("bt-default").eq="warm";bad("original sound excludes nonflat EQ",()->gains.validate());
  Config dangling=Config.defaults();dangling.presets.remove("warm");bad("deleted referenced preset rejected",()->dangling.validate());
  Config editFlat=Config.defaults();editFlat.presets.get("flat").percent[0]=5;bad("flat protected",()->editFlat.validate());
  Config keep=Config.defaults();keep.rules.get("speaker").eq="keep";keep.validate();check("explicit keep accepts no-write mode",keep.rule("speaker").eq.equals("keep"));
  Config name=Config.defaults();name.rules.get("speaker").title=" ";bad("empty name rejected",()->name.validate());
  bad("invalid JSON never silently resets configuration",()->Config.parse("broken"));
  bad("unknown schema rejected",()->Config.parse(c.json().replace("\"schema\":1","\"schema\":2")));
  JSONObject frac=new JSONObject(c.json());frac.getJSONObject("presets").getJSONObject("warm").getJSONArray("percent").put(0,2.5);bad("fractional configuration rejected",()->Config.parse(frac.toString()));
  String a="AA:BB:CC:DD:EE:01",b="AA:BB:CC:DD:EE:02";
  check("speaker route",RoutePolicy.key(new int[]{2},new String[]{""},0).equals("speaker"));
  check("A2DP specific device",RoutePolicy.key(new int[]{8},new String[]{a},0).equals("bt:"+a));
  check("LE specific device",RoutePolicy.key(new int[]{26},new String[]{a},0).equals("bt:"+a));
  check("same device repeated accepted",RoutePolicy.key(new int[]{8,8},new String[]{a,a},0).equals("bt:"+a));
  check("multiple different BT outputs defer",RoutePolicy.key(new int[]{8,8},new String[]{a,b},0)==null);
  check("speaker + BT mixed output defers",RoutePolicy.key(new int[]{2,8},new String[]{"",a},0)==null);
  check("two unknown BT outputs defer",RoutePolicy.key(new int[]{8,26},new String[]{"",""},0)==null);
  check("call defers",RoutePolicy.key(new int[]{8},new String[]{a},2)==null);
  check("empty route defers",RoutePolicy.key(new int[]{},new String[]{},0)==null);
  check("USB output defers",RoutePolicy.key(new int[]{22},new String[]{"x"},0)==null);
  check("address list mismatch defers",RoutePolicy.key(new int[]{8},new String[]{},0)==null);
  check("single unknown device uses default",RoutePolicy.key(new int[]{8},new String[]{""},0).equals("bt-default"));
  check("switching between connected BT devices changes key",!RoutePolicy.key(new int[]{8},new String[]{a},0).equals(RoutePolicy.key(new int[]{8},new String[]{b},0)));
  int[] source=EqMath.WARM.clone();Config.Preset copy=new Config.Preset("独立副本",source);source[1]=0;check("preset owns its data",copy.percent[1]==25);
  Config.Rule copyRule=c.rule("speaker").copy();copyRule.spatial=false;check("editing draft does not change stored rule",c.rule("speaker").spatial);
  System.out.println("RESULT="+count+" passed");
 }
}
