package cn.zhe.chaojibeauty;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.*;
import java.lang.reflect.Method;
import java.util.*;
import de.robv.android.xposed.*;

/** Replace only the native plate draw. The QS owner still draws its icon, label and interaction. */
final class ControlCenterGlass {
    private static final String TAG="CJBControl";
    private final Map<View,Plate> owners=new WeakHashMap<>();
    private final Set<View> roots=Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Method> installed=new HashSet<>();
    private View slider;
    private boolean enabled;

    void install(ClassLoader loader) {
        Class<?> root=XposedHelpers.findClass("com.oplus.systemui.plugins.qs.OplusQSRootView",loader);
        XposedBridge.hookAllConstructors(root,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p) {
                View v=(View)p.thisObject;
                if (roots.add(v)) v.addOnAttachStateChangeListener(new Observer());
            }
        });
        Class<?> seek=XposedHelpers.findClass("com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar",loader);
        XposedHelpers.findAndHookMethod(seek,"onDraw",Canvas.class,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                View v=(View)p.thisObject;
                if (enabled && owners.containsKey(v)) {p.setObjectExtra("previous",slider);slider=v;}
            }
            @Override protected void afterHookedMethod(MethodHookParam p){
                if (slider==p.thisObject) slider=(View)p.getObjectExtra("previous");
            }
        });
        bindDraw(XposedHelpers.findClass("com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable",loader));
    }

    private final class Observer implements View.OnAttachStateChangeListener,ViewTreeObserver.OnGlobalLayoutListener {
        View root;
        @Override public void onViewAttachedToWindow(View v){
            root=v;v.getViewTreeObserver().addOnGlobalLayoutListener(this);
            v.post(this::onGlobalLayout);
        }
        @Override public void onViewDetachedFromWindow(View v){
            if(v.getViewTreeObserver().isAlive())v.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            root=null;
        }
        @Override public void onGlobalLayout(){if(enabled && root!=null && root.isShown())scan(root);}
    }

    void configure(boolean on){
        if (enabled==on)return;
        enabled=on;
        for(View v:owners.keySet())invalidate(v);
        owners.clear();
        if(on)for(View root:roots)if(root.isAttachedToWindow())root.post(()->scan(root));
    }

    private static void invalidate(View v){
        if(v.getBackground()!=null)v.getBackground().invalidateSelf();
        v.invalidate();
    }
    private static String id(View v){
        if(v.getId()==View.NO_ID || v.getId()==0)return "";
        try{return v.getResources().getResourceEntryName(v.getId());}catch(RuntimeException e){return "";}
    }
    private enum Kind { TILE, ICON, HEADER, MEDIA, DEVICE, SLIDER, INDICATOR }
    private static Kind kind(View v){
        switch(id(v)){
            case "qs_tile_bg":return Kind.TILE;
            case "oplus_qs_tile_icon_bg":return Kind.ICON;
            case "qs_edit_button":case "quicksettings_settings_button_icon":return Kind.HEADER;
            case "bg":return v.getParent() instanceof View && v.getParent().getClass().getName().contains("MediaPanel")?Kind.MEDIA:null;
            case "square_device_constraintlayout":case "square_entrance_constraintLayout":return Kind.DEVICE;
            case "vertical_seekbar":return Kind.SLIDER;
            case "indicator_view":return v.getBackground()!=null && v.getBackground().getClass().getName().endsWith("TileTransitionDrawable")?Kind.INDICATOR:null;
            default:return null;
        }
    }
    private void scan(View v){
        if(!enabled)return;
        Kind k=kind(v);
        if(k!=null && (k==Kind.SLIDER || v.getBackground()!=null)){
            Plate plate=owners.get(v);
            if(plate==null){plate=new Plate(k);owners.put(v,plate);invalidate(v);}
            Drawable bg=v.getBackground();
            if(bg!=null && plate.boundClass!=bg.getClass()){
                plate.boundClass=bg.getClass();
                // The media subclass draws album lighting after its superclass paints the base.
                bindDraw(k==Kind.MEDIA ? bg.getClass().getSuperclass() : bg.getClass());
            }
        }
        if(v instanceof ViewGroup){
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++)scan(g.getChildAt(i));
        }
    }
    private void bindDraw(Class<?> type){
        try{
            Method draw=type.getMethod("draw",Canvas.class);
            if(!installed.add(draw))return;
            XposedBridge.hookMethod(draw,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(!enabled)return;
                    Drawable d=(Drawable)p.thisObject;
                    View v=d.getCallback() instanceof View?(View)d.getCallback():null;
                    boolean fill=false;
                    if(slider!=null){
                        if(d==XposedHelpers.getObjectField(slider,"baseMixColorDrawable"))v=slider;
                        else if(d==XposedHelpers.getObjectField(slider,"activeMixColorDrawable")){v=slider;fill=true;}
                    }
                    if(v==null || (v!=slider && v.getBackground()!=d))return;
                    Plate plate=owners.get(v);
                    if(plate!=null && plate.draw((Canvas)p.args[0],v,d,fill))p.setResult(null);
                }
            });
        }catch(ReflectiveOperationException | RuntimeException e){Log.w(TAG,"Control background "+type,e);}
    }

    private static final class Plate {
        final Kind kind;
        private static final class Geometry {
            final Path path;
            final ContainerOptics optics;
            final Paint surface;
            Geometry(Path path,ContainerOptics optics){
                this.path=path;this.optics=optics;surface=optics.surfacePaint();
            }
        }
        // Shared geometry only. Each RenderNode samples its current destination on the GPU.
        private static final android.util.LruCache<String,Geometry> shapes=new android.util.LruCache<>(20);
        Class<?> boundClass;
        Path shape;
        final RectF box=new RectF();
        final Outline outline=new Outline();
        final Paint tint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final RenderNode node=new RenderNode("ColorOS control glass");
        ContainerOptics material;
        Paint rim;
        Drawable original;
        int width,height;
        boolean dark,failed,reported;
        Plate(Kind kind){this.kind=kind;}
        private void geometry(View v,Drawable d,int w,int h){
            box.set(0,0,w,h);
            float radius=Math.min(w,h)*.5f;
            Float weight=null;
            if(kind==Kind.SLIDER) radius=XposedHelpers.getFloatField(v,"mCurProgressRadius");
            else if(kind==Kind.DEVICE){
                Object provider=XposedHelpers.callMethod(d,"getPathProvider");
                radius=((Number)XposedHelpers.callMethod(provider,"getCornerRadius",d)).floatValue();
                weight=(Float)XposedHelpers.callMethod(provider,"getCornerWeight",d);
            }else {
                radius=((Number)XposedHelpers.callMethod(d,"getCornerRadius")).floatValue();
                weight=(Float)XposedHelpers.callMethod(d,"getCornerWeight");
            }
            if(radius<=0)radius=Math.min(w,h)*.5f;
            float density=v.getResources().getDisplayMetrics().density;
            float smooth=weight!=null?weight:0f;
            String key=w+"/"+h+"/"+radius+"/"+smooth+"/"+density;
            Geometry geometry=shapes.get(key);
            if(geometry==null){
                Path path=new Path();
                if(smooth>0){
                    float[] radii=new float[8];Arrays.fill(radii,radius);
                    XposedHelpers.callMethod(path,"addSmoothRoundRect16",box,radii,Path.Direction.CW,smooth);
                }else path.addRoundRect(box,radius,radius,Path.Direction.CW);
                Bitmap stamp=Bitmap.createBitmap(w+2,h+2,Bitmap.Config.ARGB_8888);
                Canvas mask=new Canvas(stamp);mask.translate(1,1);tint.setColor(Color.WHITE);mask.drawPath(path,tint);
                ContainerOptics optics=new ContainerOptics(stamp,density);
                geometry=new Geometry(path,optics);
                shapes.put(key,geometry);
            }
            shape=geometry.path;material=geometry.optics;rim=new Paint(geometry.surface);
            node.setPosition(0,0,w,h);
            outline.setPath(shape);node.setOutline(outline);node.setClipToOutline(true);
            node.beginRecording();node.endRecording();
            original=d;width=w;height=h;
        }
        private int light(View v){
            if(kind==Kind.ICON || kind==Kind.INDICATOR)return 116;
            if(kind==Kind.DEVICE)return id(v).equals("square_device_constraintlayout")?100:15;
            if(kind!=Kind.TILE)return 15;
            for(ViewParent p=v.getParent();p instanceof View;p=p.getParent()){
                if(!id((View)p).equals("oplus_separate_qs_resizeable_tile_container"))continue;
                Object state=XposedHelpers.callMethod(p,"getTileState");
                if(state!=null && XposedHelpers.getIntField(state,"state")==2)return width>height?34:113;
                break;
            }
            return 13;
        }
        boolean draw(Canvas canvas,View v,Drawable d,boolean fill){
            if(failed || !canvas.isHardwareAccelerated())return false;
            Rect b=d.getBounds();int w=b.width(),h=b.height();
            if(w<=0 || h<=0)return false;
            int save=canvas.save();
            try{
                canvas.translate(b.left,b.top);
                if(fill){
                    if(material==null)return false;
                    tint.setColor(0xa8ffffff);canvas.drawPath(shape,tint);return true;
                }
                boolean changed=original!=d || w!=width || h!=height;
                if(changed)geometry(v,d,w,h);
                boolean night=GlassAppearance.dark(v);
                if(changed || night!=dark){
                    dark=night;
                    XposedHelpers.callMethod(node,"setBackdropRenderEffect",material.transmission(night,0,0));
                }
                float alpha=d.getAlpha()/255f;
                node.setAlpha(alpha);canvas.drawRenderNode(node);
                tint.setColor(Color.argb(Math.round(light(v)*alpha),255,255,255));canvas.drawPath(shape,tint);
                rim.setAlpha(Math.round(255*alpha));canvas.drawRect(box,rim);
                if(!reported){reported=true;Log.i(TAG,kind+" "+id(v)+" "+w+"x"+h);}
                return true;
            }catch(RuntimeException | LinkageError e){
                failed=true;Log.w(TAG,"Native fallback "+kind,e);return false;
            }finally{canvas.restoreToCount(save);}
        }
    }
}


