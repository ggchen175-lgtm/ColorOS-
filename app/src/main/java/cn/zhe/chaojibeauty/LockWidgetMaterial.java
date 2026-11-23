package cn.zhe.chaojibeauty;

import android.app.KeyguardManager;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.newInstance;
import static de.robv.android.xposed.XposedHelpers.getBooleanField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

/** Apply optics to ColorOS's own plate node, before the widget paints any foreground content. */
final class LockWidgetMaterial {
    private final LockClockMaterial source;
    LockWidgetMaterial(LockClockMaterial source) { this.source=source; }
    private final Runnable frame=() -> {
        for (View host:this.visible.values()) host.invalidate();
    };
    private static final String CARD = "com.oplus.keyguard.style.widgets.container.view.InstantCardView";
    private static final String PLATE = "com.oplus.posteffect.BlurDrawable";
    private final Set<View> clocks = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Drawable,Lens> plates = new IdentityHashMap<>();
    private final Map<Drawable,View> visible = new IdentityHashMap<>();
    private final ViewTreeObserver.OnGlobalLayoutListener layout = this::refresh;
    private boolean enabled, awake = true;

    void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> clock = loader.loadClass("com.oplus.systemui.keyguard.view.CustomOplusKeyguardStyleClock");
        XposedBridge.hookMethod(clock.getDeclaredMethod("onAttachedToWindow"),new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                View host=(View)p.thisObject;
                if (clocks.add(host) && enabled) { observe(host,true);refresh(); }
            }
        });
        XposedBridge.hookMethod(clock.getDeclaredMethod("onDetachedFromWindow"),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                View host=(View)p.thisObject;
                if (clocks.remove(host) && enabled) { observe(host,false);refresh(); }
            }
        });
    }

    private final Set<Method> draws=new HashSet<>();
    private void hookDraw(Drawable plate) throws ReflectiveOperationException {
        Method method=plate.getClass().getMethod("draw",Canvas.class);
        if (draws.contains(method)) return;
        XposedBridge.hookMethod(method,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Drawable plate=(Drawable)p.thisObject;
                Lens lens=plates.get(plate);
                if (lens==null) return;
                Canvas canvas=(Canvas)p.args[0];
                ShadeWallpaper paper=source.widgetWallpaper();
                if (paper==null || !paper.hasFrame() || !canvas.isHardwareAccelerated()) return;
                if (lens.draw(canvas,plate,visible.get(plate),paper)) {
                    // Let the native drawable run its update/visibility bookkeeping.
                    // The liquid face has already drawn into this native background slot.
                    p.setObjectExtra("widgetCanvasSave",canvas.save());
                    canvas.clipRect(0,0,0,0);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object save=p.getObjectExtra("widgetCanvasSave");
                if (save!=null) ((Canvas)p.args[0]).restoreToCount((int)save);
            }
        });
        draws.add(method);
    }
    void setAmbient(boolean asleep) {
        if (awake != asleep) return;
        awake=!asleep;
        if (enabled) refresh();
    }
    void configure(boolean value) {
        if (enabled==value) return;
        enabled=value;
        source.setWidgetFrameListener(value?frame:null);
        for (View clock:clocks) observe(clock,value);
        refresh();
    }
    private void observe(View v,boolean on) {
        ViewTreeObserver tree=v.getViewTreeObserver();
        if (!tree.isAlive()) return;
        if (on) tree.addOnGlobalLayoutListener(layout);
        else tree.removeOnGlobalLayoutListener(layout);
    }
    private boolean isLocked() {
        if (!enabled || !awake || clocks.isEmpty()) return false;
        return clocks.iterator().next().getContext().getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    void refresh() {
        visible.clear();
        if (isLocked()) for (View clock:clocks) find(clock,false);
        Iterator<Map.Entry<Drawable,Lens>> it=plates.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Drawable,Lens> e=it.next();
            if (!visible.containsKey(e.getKey())) {
                e.getValue().node.discardDisplayList();
                e.getKey().invalidateSelf();
                it.remove();
            }
        }
        for (Map.Entry<Drawable,View> e:visible.entrySet()) {
            Drawable d=e.getKey();
            if (!plates.containsKey(d)) {
                try { hookDraw(d); }
                catch (ReflectiveOperationException | RuntimeException ex) { Log.w("ChaojiBeauty","Native widget draw unavailable",ex);continue; }
                plates.put(d,new Lens());
                d.invalidateSelf();
            }
        }
    }
    private void find(View v,boolean inCard) {
        inCard |= CARD.equals(v.getClass().getName());
        Drawable d=v.getBackground();
        if (inCard && d!=null && PLATE.equals(d.getClass().getName())) visible.put(d,v);
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for (int i=0;i<g.getChildCount();i++) find(g.getChildAt(i),inCard);
        }
    }
    private static final class Lens {
        final RenderNode node=new RenderNode("ColorOS widget liquid plate");
        final Matrix global=new Matrix(), inverse=new Matrix();
        final Path contour=new Path();
        final RectF pathBounds=new RectF();
        final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
        ContainerOptics optics;
        int width,height,generation;
        boolean dark,reported,failed;
        // ColorOS may draw the rounded boundary in its shader and leave drawablePath rectangular.
        private void readContour(Drawable plate,Path nativePath,int w,int h) {
            contour.set(nativePath);
            if (!nativePath.isRect(pathBounds)) return;
            Object provider=getObjectField(plate,"pathProvider");
            if (provider!=null) {
                Object nativeWrapper=getObjectField(plate,"oplusPendingDrawablePath");
                Object wrapper=newInstance(nativeWrapper.getClass(),contour);
                contour.reset();
                callMethod(provider,"getPath",plate,contour,wrapper,null,getBooleanField(plate,"newSmoothCornerType"));
                if (!contour.isEmpty() && !contour.isRect(pathBounds)) return;
            }
            Object corners=callMethod(callMethod(plate,"getDrawableShader"),"getCornerParams");
            float radius=(float)callMethod(corners,"getRadius");
            if (radius<=0) { contour.set(nativePath);return; }
            contour.reset();
            pathBounds.set(0,0,w,h);
            if ("G2".equals(String.valueOf(callMethod(corners,"getType")))) {
                float[] radii=new float[8];java.util.Arrays.fill(radii,radius);
                callMethod(contour,"addSmoothRoundRect16",pathBounds,radii,Path.Direction.CW,callMethod(corners,"getWeight"));
            } else contour.addRoundRect(pathBounds,radius,radius,Path.Direction.CW);
        }
        boolean draw(Canvas canvas,Drawable plate,View host,ShadeWallpaper paper) {
            if (host==null || failed) return false;
            Rect b=plate.getBounds();
            int w=b.width(),h=b.height();
            if (w<=0 || h<=0) return false;
            try {
                // Same native silhouette -> distance field -> GPU refraction path as launcher folders.
                Path nativePath=(Path)getObjectField(plate,"drawablePath");
                boolean reshape;
                synchronized (callMethod(plate,"getDataLock")) {
                    if (nativePath.isEmpty()) return false;
                    int next=android.os.Build.VERSION.SDK_INT>=34 ? nativePath.getGenerationId() : 0;
                    reshape=optics==null || width!=w || height!=h || next!=generation;
                    if (reshape) { readContour(plate,nativePath,w,h);generation=next; }
                }
                if (reshape) {
                    Bitmap mask=Bitmap.createBitmap(w+2,h+2,Bitmap.Config.ARGB_8888);
                    Canvas stamp=new Canvas(mask);
                    stamp.translate(1,1);
                    ink.setColor(Color.WHITE);
                    stamp.drawPath(contour,ink);
                    // The shader keeps this contour buffer as its distance texture.
                    optics=new ContainerOptics(mask,host.getResources().getDisplayMetrics().density);
                    width=w;height=h;node.setPosition(0,0,w,h);
                }
                boolean night=GlassAppearance.dark(host);
                if (reshape || night!=dark) { dark=night;node.setRenderEffect(optics.effect(night,true)); }
                global.reset();host.transformMatrixToGlobal(global);
                if (!global.invert(inverse)) return false;
                RecordingCanvas input=node.beginRecording();
                try {
                    input.translate(-b.left,-b.top);input.concat(inverse);paper.drawFrame(input);
                } finally { node.endRecording(); }
                int alpha=(int)callMethod(plate,"getPaintAlpha");
                node.setAlpha(alpha/255f);
                int save=canvas.save();
                try { canvas.translate(b.left,b.top);canvas.drawRenderNode(node); }
                finally { canvas.restoreToCount(save); }
                if (!reported) {
                    reported=true;
                    Log.i("ChaojiBeauty","Native widget folder optics "+w+"x"+h+" alpha="+alpha+" nativeRect="+nativePath.isRect(pathBounds)+" lensRect="+contour.isRect(pathBounds));
                }
                return true;
            } catch (RuntimeException e) {
                failed=true;
                Log.w("ChaojiBeauty","Using native widget material",e);
                return false;
            }
        }
    }
}
