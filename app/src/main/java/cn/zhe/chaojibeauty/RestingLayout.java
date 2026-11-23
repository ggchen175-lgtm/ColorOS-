package cn.zhe.chaojibeauty;

import android.util.ArrayMap;
import android.view.View;
import android.view.ViewGroup;
import static de.robv.android.xposed.XposedHelpers.callMethod;

/** Compose editor transforms with native row animations. Offsets are in parent coordinates. */
final class RestingLayout {
    private int extraRange,overflow;
    int scrollRange(int nativeRange){return Math.max(0,nativeRange>0?nativeRange+extraRange:overflow);}
    private final ArrayMap<View,Pose> poses=new ArrayMap<>();
    private final ArrayMap<View,Boolean> touched=new ArrayMap<>();
    private static final class Pose {
        float x,y,sx,sy,lastX,lastY,lastSx,lastSy;
        boolean topPivot,explicitPivot;
        float originalPivotY;
        Pose(View v){
            x=lastX=v.getTranslationX(); y=lastY=v.getTranslationY();
            sx=lastSx=v.getScaleX(); sy=lastSy=v.getScaleY();
        }
        void read(View v){
            if(v.getTranslationX()!=lastX)x=v.getTranslationX();
            if(v.getTranslationY()!=lastY)y=v.getTranslationY();
            if(v.getScaleX()!=lastSx)sx=v.getScaleX();
            if(v.getScaleY()!=lastSy)sy=v.getScaleY();
        }
        void apply(View v,float dx,float dy,float w,float h){
            read(v);
            if(h!=1 && !topPivot){
                explicitPivot=v.isPivotSet();originalPivotY=v.getPivotY();v.setPivotY(0);topPivot=true;
            }else if(h==1 && topPivot){
                if(explicitPivot)v.setPivotY(originalPivotY);else v.resetPivot();
                topPivot=false;
            }
            lastSx=sx*w; lastSy=sy*h;
            lastX=x+dx+(v.getWidth()*.5f-v.getPivotX())*(sx-lastSx);
            lastY=y+dy+v.getPivotY()*(lastSy-sy);
            if(v.getTranslationX()!=lastX)v.setTranslationX(lastX);
            if(v.getTranslationY()!=lastY)v.setTranslationY(lastY);
            if(v.getScaleX()!=lastSx)v.setScaleX(lastSx);
            if(v.getScaleY()!=lastSy)v.setScaleY(lastSy);
        }
    }
    void apply(View v,float x,float y,float w,float h){
        if(v==null)return;
        Pose p=poses.get(v);
        if(x==0 && y==0 && w==1 && h==1){
            if(p!=null){p.apply(v,0,0,1,1);poses.remove(v);}
            return;
        }
        if(p==null){p=new Pose(v);poses.put(v,p);}
        touched.put(v,true);p.apply(v,x,y,w,h);
    }
    void clear(){
        for(int i=poses.size()-1;i>=0;i--)poses.valueAt(i).apply(poses.keyAt(i),0,0,1,1);
        poses.clear();touched.clear();extraRange=overflow=0;
    }
    void rows(ViewGroup list,NotificationHistory history,boolean active) {
        float cardW=LiveTune.size("cardW"),cardH=LiveTune.size("cardH");
        float headSize=LiveTune.size("titleSize");
        float cardX=LiveTune.dp(list,"cardX"),cardY=LiveTune.dp(list,"cardY");
        float titleX=LiveTune.dp(list,"titleX"),titleY=LiveTune.dp(list,"titleY");
        if(!active || (cardW==1 && cardH==1 && headSize==1 && cardX==0 && cardY==0 && titleX==0 && titleY==0)){
            if(!poses.isEmpty())clear();
            return;
        }
        touched.clear();
        float flow=0,previousTop=0,previousHeight=0,previousScale=1;
        boolean afterHeader=false,afterCard=false,first=true;
        float extra=0,bottom=0,sectionShift=0;
        for(int i=0;i<list.getChildCount();i++){
            View row=list.getChildAt(i);
            boolean header=history.ownsHeader(row);
            if(row.getVisibility()!=View.VISIBLE || (!header && !history.ordinaryRow(row)))continue;
            Pose p=poses.get(row);
            if(p!=null)p.read(row);
            float top=row.getTop()+(p==null?row.getTranslationY():p.y);
            float height=header?row.getHeight():(int)callMethod(row,"getActualHeight");
            if(!first)flow+=Math.min(previousHeight,Math.max(0,top-previousTop))*(previousScale-1);
            if((header && !afterHeader) || (!header && !afterCard)){
                float shift=header?titleY:cardY;
                // Keep the section header clear of its neighbor while preserving native shelf overlap.
                float gap=Math.max(0,top-previousTop-previousHeight);
                sectionShift+=first?shift:Math.max(-gap,shift);
                if(header)afterHeader=true;else afterCard=true;
            }
            float offset=flow+sectionShift;
            float factor=header?headSize:cardH;
            extra+=height*(factor-1);
            bottom=Math.max(bottom,top+offset+height*factor);
            apply(row,header?titleX:cardX,offset,header?headSize:cardW,header?headSize:cardH);
            previousTop=top;previousHeight=height;previousScale=header?headSize:cardH;first=false;
        }
        extraRange=Math.round(extra+sectionShift);
        overflow=Math.max(0,Math.round(bottom-list.getHeight()));
        for(int i=poses.size()-1;i>=0;i--){
            View stale=poses.keyAt(i);
            if(!touched.containsKey(stale)){poses.valueAt(i).apply(stale,0,0,1,1);poses.removeAt(i);}
        }
    }
}
