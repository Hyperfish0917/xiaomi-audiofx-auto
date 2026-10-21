package dev.mi14.audiofxauto;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import mi14.audiofx.Config;
import mi14.audiofx.EqMath;
import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private LinearLayout root,body,footer;
    private TextView status,deviceStatus,pageStatus;
    private Switch autoToggle;
    private boolean dark,pendingStart,refreshing,dirty;
    private int background,card,ink,muted,soft,tab;
    private final int blue=0xff3482f6;
    private String screen="main",editKey="",editPreset="",lastDeviceSignature="";
    private Config cfg;
    private Config.Rule draft;
    private int[] draftEq;
    private EditText nameField;
    private final Runnable refresh=new Runnable(){@Override public void run(){updateStatus();main.postDelayed(this,1000);}};
    private final Shizuku.OnRequestPermissionResultListener permission=new Shizuku.OnRequestPermissionResultListener(){
        @Override public void onRequestPermissionResult(int code,int result){if(code==14){if(result==PackageManager.PERMISSION_GRANTED&&pendingStart)start();else{pendingStart=false;updateStatus();}}}
    };
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView label(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("tnum");t.setLineSpacing(dp(2),1);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private void gap(LinearLayout p,int height){p.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    private LinearLayout column(){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);return p;}
    private void addText(LinearLayout p,String s,int size,int color){p.addView(label(s,size,color,false));}
    private LinearLayout card(LinearLayout parent){LinearLayout p=column();p.setPadding(dp(20),dp(18),dp(20),dp(18));p.setBackground(shape(card,26));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);parent.addView(p,lp);return p;}
    private Button button(String title,boolean primary,Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(16);b.setAllCaps(false);b.setTypeface(Typeface.create("sans-serif-medium",0));b.setMinHeight(dp(50));b.setMinimumHeight(dp(50));b.setPadding(dp(16),dp(8),dp(16),dp(8));b.setTextColor(primary?Color.WHITE:blue);b.setBackground(shape(primary?blue:soft,18));b.setStateListAnimator(null);b.setOnClickListener(v->action.run());return b;}
    private void action(LinearLayout p,String title,boolean primary,Runnable r){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);p.addView(button(title,primary,r),lp);}
    private void row(LinearLayout p,String title,String subtitle,Runnable click){
        LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(0,dp(9),0,dp(9));r.setMinimumHeight(dp(62));
        LinearLayout labels=column();labels.addView(label(title,17,ink,true));if(subtitle!=null&&!subtitle.isEmpty()){gap(labels,5);labels.addView(label(subtitle,13,muted,false));}
        r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));if(click!=null){TextView arrow=label("›",26,muted,false);arrow.setPadding(dp(14),0,0,0);r.addView(arrow);r.setOnClickListener(v->click.run());r.setBackground(getDrawable(android.R.drawable.list_selector_background));r.setFocusable(true);}p.addView(r,new LinearLayout.LayoutParams(-1,-2));
    }
    private Switch toggle(LinearLayout p,String title,String sub,boolean checked){
        LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);r.setMinimumHeight(dp(64));LinearLayout l=column();l.addView(label(title,17,ink,true));if(sub!=null){gap(l,5);l.addView(label(sub,13,muted,false));}r.addView(l,new LinearLayout.LayoutParams(0,-2,1));
        Switch s=new Switch(this);s.setChecked(checked);s.setContentDescription(title);s.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Color.WHITE,Color.WHITE}));s.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{blue,dark?0xff555762:0xffd6d9e1}));s.setSwitchMinWidth(dp(48));r.addView(s);p.addView(r);return s;
    }
    private void section(String title){TextView t=label(title,14,muted,true);t.setPadding(dp(6),dp(12),0,dp(12));body.addView(t);}
    private void shell(String title,String subtitle,boolean editor){
        status=null;deviceStatus=null;pageStatus=null;autoToggle=null;root.removeAllViews();
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);body=column();body.setPadding(dp(20),dp(12),dp(20),dp(20));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(editor){TextView back=label("‹  返回",16,blue,true);back.setPadding(0,dp(6),0,dp(16));back.setOnClickListener(v->back());body.addView(back);}
        body.addView(label(title,30,ink,true));if(subtitle!=null){gap(body,7);addText(body,subtitle,14,muted);}gap(body,24);
        footer=new LinearLayout(this);footer.setGravity(Gravity.CENTER);footer.setPadding(dp(20),dp(10),dp(20),dp(10));footer.setBackgroundColor(card);root.addView(footer,new LinearLayout.LayoutParams(-1,-2));
    }
    private void tabs(){String[] titles={"设备","均衡器","设置"};for(int i=0;i<3;i++){final int n=i;TextView t=label(titles[i],15,i==tab?blue:muted,i==tab);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(44));if(i==tab)t.setBackground(shape(soft,18));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(3),0,dp(3),0);footer.addView(t,lp);t.setOnClickListener(v->{tab=n;showMain();});}}
    @Override public void onCreate(Bundle state){
        dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(state);
        background=dark?0xff101115:0xfff5f6f8;card=dark?0xff1b1d23:Color.WHITE;ink=dark?0xfff5f5f7:0xff191b21;muted=dark?0xffa6aab7:0xff7a808e;soft=dark?0xff20314d:0xffeaf2ff;
        root=column();root.setBackgroundColor(background);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});root.requestApplyInsets();
        root.post(()->{WindowInsetsController controller=getWindow().getInsetsController();if(controller!=null)controller.setSystemBarsAppearance(dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);});
        Shizuku.addRequestPermissionResultListener(permission);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0,()->back());
        if(state!=null)tab=state.getInt("tab",0);
        showMain();
        if(state!=null){String page=state.getString("screen","main");if("rule".equals(page)){showRule(state.getString("key"));draft.effect=state.getString("effect",draft.effect);draft.spatial=state.getBoolean("spatial",draft.spatial);draft.eq=state.getString("eq",draft.eq);draft.title=state.getString("name",draft.title);renderRule();dirty=state.getBoolean("dirty");}else if("eq".equals(page)){showEq(state.getString("preset"),state.getString("name"),state.getIntArray("gains"));dirty=state.getBoolean("dirty");}}
    }
    private boolean load(){try{cfg=ConfigStore.load(this);return true;}catch(Exception e){shell("配置需要检查",null,false);addText(body,e.getMessage(),16,ink);action(body,"导出诊断日志",true,()->exportLog());action(body,"恢复默认方案",false,()->new AlertDialog.Builder(this).setMessage("这会重置本应用的音效方案和 EQ 预设，已记录设备名称会保留。").setNegativeButton("取消",null).setPositiveButton("恢复默认",(d,w)->{ConfigStore.save(this,Config.defaults());showMain();}).show());return false;}}
    private void showMain(){if(!load())return;screen="main";dirty=false;editKey="";editPreset="";nameField=null;
        shell(tab==0?"音效自动切换":tab==1?"均衡器":"设置",tab==0?"每台设备，都有适合自己的声音":tab==1?"把喜欢的声音，保存成预设":"运行状态与后台服务",false);tabs();
        if(tab==0)showDevices();else if(tab==1)showPresets();else showSettings();updateStatus();
    }
    private void showDevices(){
        LinearLayout hero=card(body);hero.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{dark?0xff173663:0xffe3edff,dark?0xff1c2940:0xfff1f6ff}));
        addText(hero,"最近识别的输出",13,muted);gap(hero,8);String k=ConfigStore.currentKey(this);deviceStatus=label(k.isEmpty()?"等待识别设备":ConfigStore.name(this,k,cfg),24,ink,true);hero.addView(deviceStatus);gap(hero,10);status=label("",14,muted,false);hero.addView(status);hero.setOnClickListener(v->{String key=ConfigStore.currentKey(this);if(!key.isEmpty())showRule(key);});
        LinearLayout running=card(body);autoToggle=toggle(running,"自动切换","连接或切换输出时，应用设备方案",MonitorService.active);autoToggle.setOnCheckedChangeListener((b,on)->{if(refreshing)return;if(on){pendingStart=true;start();}else stop();});
        section("输出设备");deviceCard(Config.SPEAKER,"手机扬声器");
        LinkedHashMap<String,String> known=ConfigStore.devices(this);for(String key:cfg.rules.keySet())if(key.startsWith("bt:")&&!known.containsKey(key))known.put(key,cfg.rules.get(key).title);
        for(Map.Entry<String,String> e:known.entrySet())deviceCard(e.getKey(),e.getValue());
        LinearLayout defaults=card(body);row(defaults,"其他蓝牙设备",cfg.rule(Config.DEFAULT_BT).label()+" · "+cfg.eqName(cfg.rule(Config.DEFAULT_BT).eq),()->showRule(Config.DEFAULT_BT));
        action(body,"＋ 添加已配对的蓝牙设备",false,()->addDevice());gap(body,12);addText(body,"连接过的音频输出设备会自动记住。点开设备，可修改音效、沉浸声和 EQ。",13,muted);
        lastDeviceSignature=ConfigStore.devices(this).toString()+ConfigStore.currentKey(this);
    }
    private void deviceCard(String key,String fallback){Config.Rule r=cfg.rule(key);String n=cfg.rules.containsKey(key)?r.title:fallback;LinearLayout c=card(body);String suffix=key.equals(ConfigStore.currentKey(this))&&MonitorService.active?"最近识别 · ":"";row(c,n,suffix+r.label()+"\n"+cfg.eqName(r.eq),()->showRule(key));}
    private void showPresets(){
        LinearLayout sync=card(body);row(sync,"同步到系统均衡器","将当前输出设备的预设同步到系统页面",()->syncPage());
        pageStatus=label("",13,muted,false);sync.addView(pageStatus);
        for(Map.Entry<String,Config.Preset> e:cfg.presets.entrySet()){
            String id=e.getKey();Config.Preset p=e.getValue();LinearLayout c=card(body);row(c,p.name,Config.FLAT.equals(id)?"所有频段 0% · 可复制后编辑":"10 段 · 点按编辑",()->showEq(id,null,null));
            EqChartView chart=new EqChartView(this,p.percent.clone(),dark,false);c.addView(chart,new LinearLayout.LayoutParams(-1,dp(180)));chart.setOnClickListener(v->showEq(id,null,null));
        }
        action(body,"＋ 新建 EQ 预设",true,()->showEq("","我的 EQ",new int[10]));gap(body,14);addText(body,"保存后，正在使用此预设的设备会自动应用。需要在系统页面查看时，点上方同步。系统曲线按 0.5 dB 档位显示。",13,muted);
    }
    private void showSettings(){
        LinearLayout page=card(body);row(page,"同步到系统均衡器","打开系统页面并同步当前设备的 EQ",()->syncPage());
        pageStatus=label("",13,muted,false);page.addView(pageStatus);
        LinearLayout run=card(body);row(run,"后台状态",null,null);status=label("",15,muted,false);run.addView(status);
        LinearLayout p=card(body);row(p,"Shizuku 授权",shizukuState(),()->{pendingStart=false;authorize();updateStatus();});row(p,"允许后台运行","可在应用设置中允许自启动和后台运行",()->backgroundSettings());row(p,"重新应用当前方案","重新读取设备并写入当前方案",()->checkNow());row(p,"只读检查当前状态","查看音效与 EQ 是否仍匹配，不重新应用",()->verifyNow());
        LinearLayout tools=card(body);row(tools,"导出诊断日志","包含设备名称、运行状态和 EQ 读回；地址已遮盖",()->exportLog());row(tools,"停止自动切换","保留当前音效，不自动回滚",()->stop());
        section("关于");LinearLayout about=card(body);row(about,"音效自动切换","V6.4 · 系统 EQ 页面同步测试版",null);addText(about,"适配小米 14 / Android 16。方案保存在本机。手机重启后，请先启动 Shizuku，再开启自动切换。",14,muted);gap(about,10);addText(about,"原声关闭手机音效处理，耳机自身的音效由耳机管理。USB、有线、通话及不明确的混合输出暂缓处理。",14,muted);
    }
    private void showRule(String key){if(!load()||key==null||!Config.deviceKey(key))return;screen="rule";editKey=key;draft=cfg.rule(key).copy();draft.title=ConfigStore.name(this,key,cfg);dirty=false;renderRule();}
    private void renderRule(){shell("设备方案",Config.SPEAKER.equals(editKey)?"手机扬声器":Config.DEFAULT_BT.equals(editKey)?"尚未单独设置的蓝牙设备使用此方案":"蓝牙设备 · "+editKey.substring(editKey.length()-5),true);
        LinearLayout identity=card(body);addText(identity,"显示名称",13,muted);nameField=new EditText(this);nameField.setSingleLine(true);nameField.setText(draft.title);nameField.setTextColor(ink);nameField.setTextSize(21);nameField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});identity.addView(nameField);
        LinearLayout effects=card(body);row(effects,"音效模式",Config.effectName(draft.effect),()->{captureName();String[] ids=Config.SPEAKER.equals(editKey)?new String[]{"dolby","misound"}:new String[]{"none","dolby","misound"};String[] labels=new String[ids.length];int selected=0;for(int i=0;i<ids.length;i++){labels[i]=Config.effectName(ids[i]);if(ids[i].equals(draft.effect))selected=i;}new AlertDialog.Builder(this).setTitle("音效模式").setSingleChoiceItems(labels,selected,(d,w)->{draft.effect=ids[w];if("none".equals(draft.effect)){draft.spatial=false;draft.eq=Config.FLAT;}dirty=true;d.dismiss();renderRule();}).setNegativeButton("取消",null).show();});
        Switch spatial=toggle(effects,"沉浸声","none".equals(draft.effect)?"原声模式下不启用": "使用手机系统的沉浸声处理",draft.spatial);spatial.setEnabled(!"none".equals(draft.effect));spatial.setOnCheckedChangeListener((v,on)->{draft.spatial=on;dirty=true;});
        LinearLayout eq=card(body);row(eq,"EQ 预设",cfg.eqName(draft.eq),()->chooseEq());
        if("none".equals(draft.effect))addText(eq,"原声模式下 EQ 不参与处理。要应用自定义 EQ，请选择杜比或小米音效。",13,muted);
        else if(!Config.KEEP.equals(draft.eq)){EqChartView chart=new EqChartView(this,cfg.presets.get(draft.eq).percent.clone(),dark,false);eq.addView(chart,new LinearLayout.LayoutParams(-1,dp(200)));}
        gap(body,8);addText(body,"保存后，当前输出设备会立即应用；其他设备在下次作为音频输出时应用。",13,muted);
        if(editKey.startsWith("bt:")&&cfg.rules.containsKey(editKey))action(body,"恢复为其他蓝牙设备的默认方案",false,()->new AlertDialog.Builder(this).setMessage("移除此设备的单独设置？设备记录仍会保留。").setNegativeButton("取消",null).setPositiveButton("恢复默认",(d,w)->{try{cfg.rules.remove(editKey);ConfigStore.save(this,cfg);changedConfig();showMain();}catch(Exception e){error(e);}}).show());
        footer.addView(button("保存方案",true,()->saveRule()),new LinearLayout.LayoutParams(-1,-2));
    }
    private void captureName(){if(nameField!=null)draft.title=nameField.getText().toString().trim();}
    private void chooseEq(){captureName();ArrayList<String> ids=new ArrayList<>(),names=new ArrayList<>();for(Map.Entry<String,Config.Preset> e:cfg.presets.entrySet())if(!"none".equals(draft.effect)||EqMath.flat(e.getValue().percent)){ids.add(e.getKey());names.add(e.getValue().name);}ids.add(Config.KEEP);names.add("保持现有 EQ（不写入）");int selected=ids.indexOf(draft.eq);new AlertDialog.Builder(this).setTitle("选择 EQ 预设").setSingleChoiceItems(names.toArray(new String[0]),selected,(d,w)->{draft.eq=ids.get(w);dirty=true;d.dismiss();renderRule();}).setNegativeButton("取消",null).show();}
    private void saveRule(){captureName();try{Config latest=ConfigStore.load(this);latest.rules.put(editKey,draft.copy());ConfigStore.save(this,latest);changedConfig();toast("方案已保存");showMain();}catch(Exception e){error(e);}}
    private void showEq(String id,String givenName,int[] gains){if(!load())return;screen="eq";editPreset=id==null?"":id;dirty=false;Config.Preset p=cfg.presets.get(editPreset);draftEq=gains!=null?gains.clone():p!=null?p.percent.clone():new int[10];String name=givenName!=null?givenName:p!=null?p.name:"我的 EQ";boolean flat=Config.FLAT.equals(editPreset);
        shell(flat?"平直预设":"编辑均衡器","从左到右：低频 → 高频",true);LinearLayout n=card(body);addText(n,"预设名称",13,muted);nameField=new EditText(this);nameField.setSingleLine(true);nameField.setText(name);nameField.setTextSize(19);nameField.setTextColor(ink);nameField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});nameField.setEnabled(!flat);n.addView(nameField);
        LinearLayout c=card(body);LinearLayout range=new LinearLayout(this);range.addView(label("−100%",12,muted,false),new LinearLayout.LayoutParams(0,-2,1));TextView max=label("+100%",12,muted,false);max.setGravity(Gravity.END);range.addView(max);c.addView(range);
        EqChartView chart=new EqChartView(this,draftEq,dark,!flat);c.addView(chart,new LinearLayout.LayoutParams(-1,dp(288)));
        TextView band=label("",18,ink,true);c.addView(band);SeekBar slider=new SeekBar(this);slider.setMax(40);slider.setProgress(draftEq[chart.selected()]/5+20);slider.setEnabled(!flat);slider.setProgressTintList(ColorStateList.valueOf(blue));slider.setThumbTintList(ColorStateList.valueOf(blue));slider.setPadding(dp(10),dp(8),dp(10),dp(8));slider.setMinHeight(dp(48));c.addView(slider,new LinearLayout.LayoutParams(-1,dp(48)));
        Runnable bandText=()->{int i=chart.selected();band.setText("第 "+(i+1)+" 段    "+(draftEq[i]>0?"+":"")+draftEq[i]+"%");slider.setContentDescription("第 "+(i+1)+" 段增益百分比");};bandText.run();
        chart.listener((i,v)->{slider.setProgress(v/5+20);dirty=true;bandText.run();});
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int v,boolean user){if(user){draftEq[chart.selected()]=(v-20)*5;dirty=true;chart.refresh();bandText.run();}}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        LinearLayout buttons=new LinearLayout(this);String[] titles={"− 5%","归零","＋ 5%"};for(int i=0;i<3;i++){final int nudge=i;Button b=button(titles[i],false,()->{int at=chart.selected();draftEq[at]=nudge==1?0:Math.max(-100,Math.min(100,draftEq[at]+(nudge==0?-5:5)));slider.setProgress(draftEq[at]/5+20);chart.refresh();bandText.run();dirty=true;});b.setEnabled(!flat);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(dp(2),0,dp(2),0);buttons.addView(b,lp);}c.addView(buttons);
        gap(body,8);addText(body,flat?"平直预设固定为全部 0%。可以复制后自由调整。":"拖动滑杆只修改预览。点保存后，正在使用此预设的设备会自动应用。",13,muted);
        action(body,"复制为新预设",false,()->showEq("",nameField.getText()+" 副本",draftEq));
        if(!flat){action(body,"全部归零",false,()->{Arrays.fill(draftEq,0);chart.refresh();slider.setProgress(20);bandText.run();dirty=true;});if(Config.WARM.equals(editPreset))action(body,"恢复照片中的曲线",false,()->{System.arraycopy(EqMath.WARM,0,draftEq,0,10);chart.refresh();slider.setProgress(draftEq[chart.selected()]/5+20);bandText.run();dirty=true;});if(!editPreset.isEmpty())action(body,"删除这个预设",false,()->deletePreset());footer.addView(button("保存预设",true,()->savePreset()),new LinearLayout.LayoutParams(-1,-2));}
        else footer.addView(button("复制并编辑",true,()->showEq("","我的 EQ",draftEq)),new LinearLayout.LayoutParams(-1,-2));
    }
    private void savePreset(){try{Config latest=ConfigStore.load(this);String id=editPreset.isEmpty()?"eq_"+UUID.randomUUID().toString().replace("-",""):editPreset;latest.presets.put(id,new Config.Preset(nameField.getText().toString().trim(),draftEq));ConfigStore.save(this,latest);changedConfig();toast("EQ 预设已保存");tab=1;showMain();}catch(Exception e){error(e);}}
    private void deletePreset(){int n=0;for(Config.Rule r:cfg.rules.values())if(r.eq.equals(editPreset))n++;new AlertDialog.Builder(this).setTitle("删除预设？").setMessage(n==0?"此预设没有被设备方案引用。":"有 "+n+" 个设备方案使用它，删除后会改用平直预设。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{try{Config latest=ConfigStore.load(this);latest.presets.remove(editPreset);for(Config.Rule r:latest.rules.values())if(r.eq.equals(editPreset))r.eq=Config.FLAT;ConfigStore.save(this,latest);changedConfig();tab=1;showMain();}catch(Exception e){error(e);}}).show();}
    private void addDevice(){if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},17);return;}try{BluetoothAdapter a=((BluetoothManager)getSystemService(BLUETOOTH_SERVICE)).getAdapter();if(a==null){toast("蓝牙不可用");return;}ArrayList<BluetoothDevice> devices=new ArrayList<>(a.getBondedDevices());Collections.sort(devices,(x,y)->String.valueOf(x.getName()).compareToIgnoreCase(String.valueOf(y.getName())));String[] labels=new String[devices.size()];for(int i=0;i<labels.length;i++){BluetoothDevice d=devices.get(i);String key=Config.bluetoothKey(d.getAddress());labels[i]=ConfigStore.bluetoothName(this,key,"蓝牙设备")+"  · "+d.getAddress().substring(12);}if(labels.length==0){toast("请先在系统蓝牙设置中配对设备");return;}new AlertDialog.Builder(this).setTitle("选择已配对设备").setItems(labels,(d,w)->{BluetoothDevice b=devices.get(w);String key=Config.bluetoothKey(b.getAddress());if(!key.startsWith("bt:")){toast("设备标识不可用，请连接后重试");return;}ConfigStore.remember(this,key,ConfigStore.bluetoothName(this,key,b.getName()));showRule(key);}).setNegativeButton("取消",null).show();}catch(Exception e){error(e);}}
    private void verifyNow(){if(!MonitorService.active){toast("请先开启自动切换，等待当前任务结束后检查");return;}startService(new Intent(this,MonitorService.class).setAction(MonitorService.VERIFY));toast("已请求只读检查；切换进行中时请稍后再试");}
    private void syncPage(){
        if(!MonitorService.active){toast("请先开启自动切换，等待当前设备方案应用完成");return;}
        if(!authorize())return;
        try{
            final String key=ConfigStore.currentKey(this);final Config saved=ConfigStore.load(this);
            final mi14.audiofx.eqpage.PageSyncRequest request=new mi14.audiofx.eqpage.PageSyncRequest(saved,key);
            int[] displayed=new int[10];boolean rounded=false;
            for(int i=0;i<10;i++){
                displayed[i]=mi14.audiofx.eqpage.EqMath.percent(request.target[i]);
                if(displayed[i]!=saved.presets.get(saved.rule(key).eq).percent[i])rounded=true;
            }
            String message=ConfigStore.name(this,key,saved)+" · "+saved.eqName(saved.rule(key).eq)+"\n\n系统页面曲线：\n"+mi14.audiofx.eqpage.EqMath.csv(displayed)+" %\n\n将更新系统自定义 EQ。请暂停音乐、保持亮屏及输出设备不变；同步完成前不要拖动滑杆。";
            if(rounded)message+="\n\n系统页面按 0.5 dB 取整；App 中保存的预设数值保持不变。";
            new AlertDialog.Builder(this).setTitle("同步到系统均衡器").setMessage(message)
                .setNegativeButton("取消",null).setPositiveButton("开始同步",(d,w)->{
                    android.os.ResultReceiver reply=new android.os.ResultReceiver(main){
                        @Override protected void onReceiveResult(int code,Bundle data){
                            if(code!=0){toast(data==null?"暂时无法同步":data.getString("message","暂时无法同步"));return;}
                            if(isFinishing()||isDestroyed()){cancelPage();return;}
                            try{startActivity(new Intent().setComponent(new android.content.ComponentName("com.miui.misound","com.miui.misound.dolby.DolbyEqActivity")));}
                            catch(Exception e){cancelPage();error(new IllegalStateException("无法打开系统图形均衡器："+e.getMessage()));}
                        }
                    };
                    startService(new Intent(this,MonitorService.class).setAction(MonitorService.PAGE_SYNC).putExtra("expected_key",key).putExtra("reply",reply));
                }).show();
        }catch(Exception e){error(e);}
    }
    private void cancelPage(){startService(new Intent(this,MonitorService.class).setAction(MonitorService.CANCEL_PAGE));}
    private void changedConfig(){dirty=false;if(MonitorService.active)startService(new Intent(this,MonitorService.class).setAction(MonitorService.CHECK));}
    private String shizukuState(){try{return !Shizuku.pingBinder()?"未启动":Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED?"已授权":"等待授权";}catch(Exception e){return "未就绪";}}
    private boolean authorize(){try{if(!Shizuku.pingBinder()){toast("请先启动 Shizuku");return false;}if(Shizuku.getUid()!=2000||Shizuku.getVersion()<13){toast("需要 shell 模式 Shizuku 13 或更新版本");return false;}if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED)return true;Shizuku.requestPermission(14);}catch(Exception e){toast("Shizuku 尚未就绪");}return false;}
    private void start(){if(Build.VERSION.SDK_INT!=36){pendingStart=false;toast("此版本适用于 Android 16");return;}if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS},15);return;}if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getSharedPreferences(MonitorService.PREFS,0).getBoolean("notificationAsked",false)){getSharedPreferences(MonitorService.PREFS,0).edit().putBoolean("notificationAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},16);return;}if(!authorize()){updateStatus();return;}pendingStart=false;try{ConfigStore.save(this,ConfigStore.load(this));startForegroundService(new Intent(this,MonitorService.class).setAction(MonitorService.START));}catch(Exception e){error(e);}updateStatus();}
    private void stop(){pendingStart=false;getSharedPreferences(MonitorService.PREFS,0).edit().putBoolean("enabled",false).putString("status","已停止，保留当前音效").apply();stopService(new Intent(this,MonitorService.class));main.postDelayed(()->updateStatus(),200);}
    private void checkNow(){if(!MonitorService.active){toast("请先开启自动切换");return;}startService(new Intent(this,MonitorService.class).setAction(MonitorService.CHECK));toast("正在重新检查当前输出");}
    private void backgroundSettings(){try{PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);startActivity(new Intent(pm.isIgnoringBatteryOptimizations(getPackageName())?Settings.ACTION_APPLICATION_DETAILS_SETTINGS:Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName())));}catch(Exception e){toast("请在应用设置中允许后台运行");}}
    private void updateStatus(){if(pageStatus!=null)pageStatus.setText(getSharedPreferences(MonitorService.PREFS,0).getString("page_status","尚未同步 · 仅在主动打开时更新系统页面"));if(status!=null){String last=getSharedPreferences(MonitorService.PREFS,0).getString("status","尚未开始");status.setText((MonitorService.active?"自动切换已开启":"自动切换已停止")+" · Shizuku "+shizukuState()+"\n"+last);}if(autoToggle!=null){refreshing=true;autoToggle.setChecked(MonitorService.active);refreshing=false;}if("main".equals(screen)&&tab==0&&cfg!=null){String sig=ConfigStore.devices(this).toString()+ConfigStore.currentKey(this);if(!sig.equals(lastDeviceSignature)){showMain();return;}if(deviceStatus!=null){String k=ConfigStore.currentKey(this);deviceStatus.setText(k.isEmpty()?"等待识别设备":ConfigStore.name(this,k,cfg));}}}
    private void exportLog(){String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.ROOT).format(new Date());startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,"audiofx_auto_v64_"+stamp+".txt"),20);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==20&&result==RESULT_OK&&data!=null&&data.getData()!=null){try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){if(out==null)throw new IllegalStateException("无法打开文档");String config;try{config=ConfigStore.load(this).json();}catch(Exception e){config="INVALID: "+e.getMessage();}String log="AudioFx Auto V6.4\nBuild="+Build.FINGERPRINT+"\nConfiguration="+ConfigStore.safeLog(config)+"\n\n"+RunLog.read(this);out.write(ConfigStore.safeLog(log).getBytes(StandardCharsets.UTF_8));toast("诊断日志已保存");}catch(Exception e){error(e);}}}
    @Override public void onRequestPermissionsResult(int request,String[] p,int[] grants){super.onRequestPermissionsResult(request,p,grants);if(request==17){if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)addDevice();return;}if(request==15||request==16){getSharedPreferences(MonitorService.PREFS,0).edit().putBoolean("notificationAsked",true).apply();if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED&&pendingStart)start();else{pendingStart=false;toast("自动切换需要附近设备权限");}updateStatus();}}
    private void back(){if(!"main".equals(screen)){boolean nameChanged=nameField!=null&&("rule".equals(screen)?!nameField.getText().toString().trim().equals(ConfigStore.name(this,editKey,cfg)):!editPreset.isEmpty()&&cfg.presets.containsKey(editPreset)&&!nameField.getText().toString().trim().equals(cfg.presets.get(editPreset).name));if(dirty||nameChanged)new AlertDialog.Builder(this).setMessage("放弃尚未保存的修改？").setNegativeButton("继续编辑",null).setPositiveButton("放弃",(d,w)->showMain()).show();else showMain();}else if(tab!=0){tab=0;showMain();}else finish();}
    @Override public void onBackPressed(){back();}
    private void error(Exception e){new AlertDialog.Builder(this).setTitle("未能完成").setMessage(e.getMessage()).setPositiveButton("知道了",null).show();}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();main.post(refresh);}
    @Override protected void onPause(){main.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){Shizuku.removeRequestPermissionResultListener(permission);super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("tab",tab);out.putString("screen",screen);out.putBoolean("dirty",dirty);if(nameField!=null)out.putString("name",nameField.getText().toString());if("rule".equals(screen)&&draft!=null){out.putString("key",editKey);out.putString("effect",draft.effect);out.putBoolean("spatial",draft.spatial);out.putString("eq",draft.eq);}if("eq".equals(screen)){out.putString("preset",editPreset);out.putIntArray("gains",draftEq);}}
}
