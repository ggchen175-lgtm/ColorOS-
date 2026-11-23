package cn.zhe.chaojibeauty;

import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewConfiguration;

/** One owner for the existing notification haptic. Runs before the OEM consumes scrolling. */
final class TunableFeedback {
    private float origin,extreme,last,x,y;
    private int direction;
    private boolean fired,aboveThreshold;
    private long lastMoved;
    boolean recent(){return android.os.SystemClock.uptimeMillis()-lastMoved<500;}
    private String scene="";
    void begin(float atX,float atY){
        origin=extreme=last=y=atY;x=atX;direction=0;fired=aboveThreshold=false;scene="";
    }
    void move(View v,float atX,float atY,boolean row,boolean content,boolean blank,float edge){
        lastMoved=android.os.SystemClock.uptimeMillis();
        float density=v.getResources().getDisplayMetrics().density;
        float slop=ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
        int repeat=(int)LiveTune.get("repeat",0,0,2);
        float previous=last;last=y=atY;x=atX;
        int dir=atY>origin?1:-1;
        if(direction!=0){
            extreme=direction>0?Math.max(extreme,atY):Math.min(extreme,atY);
            if((extreme-atY)*direction>slop){
                origin=extreme;dir=-direction;extreme=atY;
                if(repeat==1)fired=false;
                aboveThreshold=false;
            }else dir=direction;
        }
        direction=dir;
        scene=row?(dir>0?"rowDown":"rowUp"):content && dir>0?"contentDown":blank && dir<0?"blankUp":"";
        if(scene.isEmpty())return;
        int mode=mode();
        float threshold=LiveTune.get(scene+"Dist",2*slop/density,1,160)*density;
        boolean beyond=(atY-origin)*dir>threshold;
        if(mode==0 && beyond && !aboveThreshold)fire(v,mode,repeat==2);
        aboveThreshold=beyond;
        float line=edge+LiveTune.get(scene+"Line",0,-700,120)*density;
        if(mode==1 && (dir>0?previous<line && atY>=line:previous>line && atY<=line))fire(v,mode,repeat==2);
    }
    void switched(View v,boolean lockUp){
        if(scene.isEmpty() && lockUp)scene="blankUp";
        if(!scene.isEmpty() && mode()==2)fire(v,2,false);
    }
    private int mode(){return (int)LiveTune.get(scene+"Mode",scene.equals("blankUp")?2:0,0,2);}
    private void fire(View v,int reason,boolean recross){
        if((fired && !recross) || LiveTune.get(scene+"On",1,0,1)<.5f)return;
        fired=true;
        boolean allowed=Settings.System.getInt(v.getContext().getContentResolver(),Settings.System.HAPTIC_FEEDBACK_ENABLED,1)!=0;
        int type=(int)LiveTune.get(scene+"Type",0,0,2);
        int feedback=type==1?HapticFeedbackConstants.CLOCK_TICK:type==2?HapticFeedbackConstants.LONG_PRESS:HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE;
        boolean sent=allowed && v.performHapticFeedback(feedback);
        LiveTune.haptic(scene,reason,sent,x,y);
    }
}
