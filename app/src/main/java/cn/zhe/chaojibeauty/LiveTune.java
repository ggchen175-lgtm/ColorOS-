package cn.zhe.chaojibeauty;

import android.database.ContentObserver;
import android.content.ContentResolver;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.HashMap;
import static de.robv.android.xposed.XposedHelpers.callMethod;

/** Shell-writable settings. The module accepts finite numeric parameters only. */
final class LiveTune {
    static final String KEY="chaoji_live_v1", SESSION="chaoji_live_monitor", TAG="ChaojiTuner";
    private static volatile HashMap<String,Float> values=new HashMap<>();
    private static ContentResolver resolver;
    private static long expires,nextGeometry,lastMove;
    private static Runnable refresh,sample;
    private static final Handler monitor=new Handler(Looper.getMainLooper());
    private static final Runnable readBounds=new Runnable(){
        @Override public void run(){
            if(!active())return;
            if(sample!=null)sample.run();
            monitor.postDelayed(this,90);
        }
    };
    private static int revision;
    private static String applied="-";
    private static final int[] point=new int[2];
    private static final Rect rect=new Rect();
    static void bind(View view,Runnable update,Runnable sampleBounds) {
        refresh=update;sample=sampleBounds;
        if (resolver!=null) return;
        resolver=view.getContext().getContentResolver();
        ContentObserver observer=new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean self,android.net.Uri uri) {
                if (Settings.Global.getUriFor(SESSION).equals(uri)) lease();
                else read();
            }
        };
        resolver.registerContentObserver(Settings.Global.getUriFor(KEY),false,observer);
        resolver.registerContentObserver(Settings.Global.getUriFor(SESSION),false,observer);
        lease(); read();
    }
    private static void lease() {
        expires=Settings.Global.getLong(resolver,SESSION,0);
        nextGeometry=0;
        monitor.removeCallbacks(readBounds);
        if(active()){
            monitor.post(readBounds);
            Log.i(TAG,"ACK "+revision+" "+applied);
        }
    }
    private static void read() {
        String encoded=Settings.Global.getString(resolver,KEY);
        HashMap<String,Float> next=new HashMap<>();
        try {
            JSONObject config=encoded==null || encoded.isEmpty()?new JSONObject():
                new JSONObject(new String(Base64.decode(encoded,Base64.URL_SAFE),StandardCharsets.UTF_8));
            Iterator<String> keys=config.keys();
            while(keys.hasNext()){
                String k=keys.next(); float n=(float)config.getDouble(k);
                if(Float.isFinite(n)) next.put(k,n);
            }
        } catch(Exception invalid){Log.w(TAG,"invalid config");return;}
        applied=encoded==null?"-":encoded;
        values=next; revision++; nextGeometry=0;
        if(refresh!=null) refresh.run();
        Log.i(TAG,"ACK "+revision+" "+(encoded==null?"-":encoded));
    }
    static float get(String k,float fallback,float low,float high) {
        Float v=values.get(k);
        return v==null?fallback:Math.min(high,Math.max(low,v));
    }
    static float size(String key){return get(key,1,.55f,1.55f);}
    static float dp(View host,String key){return get(key,0,-320,320)*host.getResources().getDisplayMetrics().density;}
    static int scaledHeight(View v,int h,boolean title){
        return size(title?"titleSize":"cardH")==1?h:Math.round(h*v.getScaleY());
    }
    static boolean custom(String k){return values.containsKey(k);}
    static boolean active(){return expires>0 && System.currentTimeMillis()/1000<expires;}
    static void touch(MotionEvent e) {
        if(!active() || e.getPointerCount()!=1)return;
        int a=e.getActionMasked();
        if(a==2 && e.getEventTime()-lastMove<12)return;
        lastMove=e.getEventTime();
        Log.i(TAG,"TOUCH "+e.getDownTime()+" "+e.getEventTime()+" "+a+" "+(int)e.getRawX()+" "+(int)e.getRawY());
    }
    static void haptic(String scene,int reason,boolean sent,float x,float y){
        if(active())Log.i(TAG,"HAPTIC "+scene+" "+reason+" "+(sent?1:0)+" "+(int)x+" "+(int)y);
    }
    static void geometry(ViewGroup list,NotificationHistory history,NotificationReveal reveal,boolean shade,boolean lockHistory){
        if(!active() || SystemClock.uptimeMillis()<nextGeometry)return;
        nextGeometry=SystemClock.uptimeMillis()+90;
        try{
            View root=list.getRootView(); list.getLocationOnScreen(point);
            float den=root.getResources().getDisplayMetrics().density;
            JSONObject j=new JSONObject();
            j.put("w",root.getWidth()).put("h",root.getHeight()).put("density",(double)den)
             .put("page",shade?"通知栏":lockHistory?"锁屏通知中心":"锁屏")
             .put("landing",reveal.landing()).put("moving",reveal.dragging() || reveal.settling())
             .put("end",point[1]+history.foldEdge(list)).put("slop",android.view.ViewConfiguration.get(root.getContext()).getScaledTouchSlop());
            JSONObject boxes=new JSONObject();
            add(boxes,"列表",list,-1);
            int scroll = (int)callMethod(list, "getOwnScrollY");
            int inset = reveal.active(list) ? reveal.anchor() : 0;
            j.put("scrollY", scroll).put("scrollRange", (int)callMethod(list,"getScrollRange")).put("restInset", inset);
            JSONArray viewport = boxes.optJSONArray("列表");
            if (viewport != null && inset > 0) {
                int top = viewport.getInt(1);
                viewport.put(1, Math.min(viewport.getInt(3), top + reveal.viewportTop()));
            }
            list.getLocationOnScreen(point);
            int listY=point[1];
            JSONArray faces=new JSONArray();
            for(int i=0;i<list.getChildCount();i++){
                View row=list.getChildAt(i);
                if(!row.isShown() || row.getAlpha()<=0)continue;
                if(history.ownsHeader(row) && row.getGlobalVisibleRect(rect)){
                    reveal.tuneBox(row,rect,scaledHeight(row,row.getHeight(),true),listY);
                    if(!rect.isEmpty())boxes.put("标题和叉号",new JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom));
                }
                else if(faces.length()<24 && history.ordinaryRow(row)){
                    int h=scaledHeight(row,(int)callMethod(row,"getActualHeight"),false);

                    if(row.getGlobalVisibleRect(rect)){
                        row.getLocationOnScreen(point);
                        rect.bottom=Math.min(rect.bottom,point[1]+h);
                        int left=rect.left,top=rect.top,right=rect.right,bottom=rect.bottom;
                        reveal.tuneBox(row,rect,h,listY);
                        if(!rect.isEmpty() && rect.bottom>0 && rect.top<root.getHeight()){
                            JSONArray face=new JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom);
                            faces.put(face);
                            if(!boxes.has("卡片")){
                                boxes.put("卡片",face);
                                boxes.put("卡片触摸",new JSONArray().put(left).put(top).put(right).put(bottom));
                            }
                        }
                    }
                }
            }
            for(int i=0;i<2;i++){View b=history.tuneButton(i);if(b!=null && b.isShown())add(boxes,i==0?"手电筒":"相机",b,-1);}
            if(clockBox!=null)boxes.put("时钟",clockBox);
            if(dateBox!=null)boxes.put("日期",dateBox);
            if(widgetBox!=null)boxes.put("小组件",widgetBox);
            View count=history.tuneCount();
            if(count==null || !count.isShown())count=root.findViewWithTag("ChaojiNotificationCount");
            if(count!=null && count.isShown())add(boxes,"数量",count,-1);
            View status=LockCenter.find(root,"keyguard_header");
            if(!shade && status!=null && status.isShown())add(boxes,"状态栏",status,-1);
            else if(shade && statusBox!=null)boxes.put("状态栏",statusBox);
            else if(root.getRootWindowInsets()!=null){
                int h=root.getRootWindowInsets().getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top;
                int w=root.getWidth(),sw=Math.round(w*size("statusSize")),x=(w-sw)/2+Math.round(dp(root,"statusX")),y=Math.round(dp(root,"statusY"));
                boxes.put("状态栏",new JSONArray().put(x).put(y).put(x+sw).put(y+Math.round(h*size("statusSize"))));
            }
            j.put("cards",faces);
            j.put("boxes",boxes);Log.i(TAG,"GEOM "+j.toString());
        }catch(RuntimeException|org.json.JSONException ignored){ }
    }
    private static JSONArray clockBox,dateBox,widgetBox,statusBox;
    static void statusInk(View panel,float l,float t,float r,float b,float scale){
        panel.getLocationOnScreen(point);
        float cx=panel.getWidth()*.5f,x=point[0]+dp(panel,"statusX")+cx,y=point[1]+dp(panel,"statusY");
        statusBox=new JSONArray().put(Math.round(x+(l-cx)*scale)).put(Math.round(y+t*scale))
                    .put(Math.round(x+(r-cx)*scale)).put(Math.round(y+b*scale));
    }
    static void ink(boolean date,float x,float y,android.graphics.Rect bounds){
        if(!active())return;
        int l=(int)x+bounds.left,t=(int)y+bounds.top;
        JSONArray box=new JSONArray().put(l).put(t).put(l+bounds.width()).put(t+bounds.height());
        if(date)dateBox=box;else clockBox=box;
    }
    static void widget(Rect r){
        widgetBox=r.isEmpty()?null:new JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom);
    }
    private static void add(JSONObject out,String key,View v,int height)throws org.json.JSONException {
        if(!v.getGlobalVisibleRect(rect))return;
        if(height>0){v.getLocationOnScreen(point);rect.bottom=Math.min(rect.bottom,point[1]+height);}
        if(!rect.isEmpty())out.put(key,new JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom));
    }
}
