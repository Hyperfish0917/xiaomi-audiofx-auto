package dev.mi14.audiofxauto;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Native ten-band editor. Edits only a draft; nothing reaches audio until Save. */
public final class EqChartView extends View {
    public interface Listener {void changed(int band,int percent);}
    private final Paint paint=new Paint(3);
    private final int[] values;
    private final boolean dark,editable;
    private int selected=2;
    private Listener listener;
    public EqChartView(Context c,int[] p,boolean d,boolean edit){super(c);values=p;dark=d;editable=edit;setMinimumHeight(dp(264));setFocusable(true);updateDescription();}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    public void listener(Listener l){listener=l;}
    public int selected(){return selected;}
    public void select(int i){selected=i;invalidate();}
    public void refresh(){invalidate();updateDescription();}
    private void updateDescription(){StringBuilder s=new StringBuilder("十段 EQ，从低频到高频：");for(int i=0;i<10;i++)s.append(i+1).append(" 段 ").append(values[i]).append("%，");setContentDescription(s.toString());}
    private float top(){return dp(39);}
    private float bottom(){return getHeight()-dp(45);}
    private float step(){return (getWidth()-dp(20))/10f;}
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);float t=top(),b=bottom(),mid=(t+b)/2f,step=step();
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
        paint.setColor(dark?0xff45474d:0xffdedfe5);paint.setStrokeWidth(dp(1));
        c.drawLine(dp(10),mid,getWidth()-dp(10),mid,paint);
        for(int i=0;i<10;i++){
            float x=dp(10)+step*(i+.5f),y=mid-(b-t)*values[i]/200f;
            paint.setColor(dark?0xff2a2c32:0xffeef0f4);paint.setStrokeWidth(Math.min(dp(15),step*.46f));c.drawLine(x,t,x,b,paint);
            int accent=values[i]<0?0xffd59a49:0xff3482f6;
            paint.setColor(accent);c.drawLine(x,mid,x,y,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(dark?0xff181a20:0xffffffff);c.drawCircle(x,y,Math.min(dp(9),step*.28f),paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(selected==i&&editable?3:2));paint.setColor(accent);c.drawCircle(x,y,Math.min(dp(9),step*.28f),paint);paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(dp(11));paint.setColor(selected==i&&editable?0xff3482f6:dark?0xffc5c7cf:0xff707582);
            c.drawText((values[i]>0?"+":"")+values[i],x,dp(23),paint);
            paint.setTextSize(dp(12));c.drawText(String.valueOf(i+1),x,getHeight()-dp(22),paint);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(!editable)return super.onTouchEvent(e);
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                selected=Math.max(0,Math.min(9,(int)((e.getX()-dp(10))/step())));
                getParent().requestDisallowInterceptTouchEvent(true);break;
            case MotionEvent.ACTION_MOVE:break;
            case MotionEvent.ACTION_UP:
                getParent().requestDisallowInterceptTouchEvent(false);performClick();break;
            case MotionEvent.ACTION_CANCEL:getParent().requestDisallowInterceptTouchEvent(false);return true;
            default:return true;
        }
        float mid=(top()+bottom())/2;
        int v=Math.max(-100,Math.min(100,Math.round((mid-e.getY())*200f/(bottom()-top())/5f)*5));
        values[selected]=v;refresh();if(listener!=null)listener.changed(selected,v);return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
