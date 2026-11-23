package cn.zhe.chaojibeauty;

import android.app.KeyguardManager;
import android.animation.ValueAnimator;
import android.view.animation.PathInterpolator;
import android.content.*;
import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.*;
import java.util.*;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Bind the plugin's native clock wrappers after it has attached its scene-specific views. */
final class LockClockMaterial {
    private final NotificationGlass cards;
    private final WidgetAccessoryLayout accessories;
    LockClockMaterial(NotificationGlass cards,WidgetAccessoryLayout accessories) {
        this.cards=cards;
        this.accessories=accessories;
    }

    private final Set<Class<?>> installed = new HashSet<>();
    private final Map<View,ClockFaceMaterial> materials = new WeakHashMap<>();
    private final Map<View,RenderEffect> replaced = new WeakHashMap<>();
    private final int[] xy = new int[2];
    private ShadeWallpaper stream;
    private Runnable widgetFrame;
    void setWidgetFrameListener(Runnable listener) {
        widgetFrame=listener;
        if (listener!=null && !ambient) openLockWallpaper();
    }
    ShadeWallpaper widgetWallpaper() { return ambient ? null : stream; }
    private boolean needsWallpaper() { return enabled || cards.isEnabled() || widgetFrame!=null; }
    private View host;
    private View widgetLayer;
    private int lift;
    private int originalWidgetScroll;
    private ViewGroup root;
    private boolean originalForceDark;
    private boolean enabled, reported;
    private long retryAt;
    private boolean dark, ambient, waking;
    private float glassAmount=1f, glassTarget=1f;
    private ValueAnimator inkTransition;
    // CSAODTransitionSettings has a 0.4 s default clock response and zero bounce.
    private static final int RESPONSE_MS=400;
    private long wakeUntil;
    private final PathInterpolator easing=new PathInterpolator(.22f,0f,.12f,1f);
    private final ViewTreeObserver.OnGlobalLayoutListener layout = this::bind;
    private final ViewTreeObserver.OnPreDrawListener prepare = this::prepareFrame;

    private boolean prepareFrame() {
        if(enabled && host!=null){
            if(root!=null && replaced.isEmpty())suspendNativeEffects();
            int layoutY=NotificationViewport.layoutY(host,viewportLocation);
            host.getLocationInWindow(viewportLocation);
            int hostShift=viewportLocation[1]-layoutY;
            if(sectionProgress==0)restHostShift=hostShift;
            // Only supply travel that the native keyguard host has not applied itself.
            float remaining=Math.max(0,headerScroll-Math.max(0,restHostShift-hostShift));
            if(remaining!=drawScroll){
                drawScroll=remaining;
                host.invalidate();
                invalidateGlyphs();
            }
        }
        if (enabled && (ambient || waking)) {
            boolean scaled=false;
            for (Map.Entry<View,ClockFaceMaterial> entry:materials.entrySet()) {
                if (entry.getKey().getParent() instanceof View) {
                    View mount=(View)entry.getKey().getParent();
                    scaled |= Math.abs(mount.getScaleY()-1f)>.0001f;
                    if (entry.getValue().mountChanged(mount)) mount.invalidate();
                }
            }
            if (!ambient && !scaled && SystemClock.uptimeMillis()>=wakeUntil) waking=false;
        }
        if (enabled && root!=null && dark != GlassAppearance.dark(root.getRootView())) {
            dark = GlassAppearance.dark(root.getRootView());
            invalidateGlyphs();
        }
        if (!needsWallpaper() && stream!=null) release();
        if (stream!=null && stream.unavailable()) {
            release();
            retryAt=SystemClock.uptimeMillis()+5000;
        }
        if (!ambient && host!=null && host.isShown() && stream==null &&
                host.getContext().getSystemService(PowerManager.class).isInteractive()) openLockWallpaper();
        return true;
    }

    private void openLockWallpaper() {
        if (!needsWallpaper() || stream!=null || host==null || !host.isAttachedToWindow()
                || SystemClock.uptimeMillis()<retryAt) return;
        View window=host.getRootView();
        if (window.getWidth()<=0 || window.getHeight()<=0 ||
                !host.getContext().getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
        try {
            stream=new ShadeWallpaper(window,this::frame);
            stream.setBounds(0,0,window.getWidth(),window.getHeight());
            stream.open();
        } catch (RuntimeException e) { failed(e); }
    }
    void setAmbient(boolean asleep) {
        if (ambient==asleep) return;
        waking=!asleep;
        ambient=asleep;
        wakeUntil=SystemClock.uptimeMillis()+2*RESPONSE_MS;
        if (asleep) {
            if (needsWallpaper() && stream!=null && stream.hasFrame() && glassAmount>0f) transitionInk(0f);
            else {
                resetInk(0f);
                closeWallpaper();
            }
        } else {
            resetInk(1f);
            cards.lockWallpaperFrame(stream,glassAmount);
            openLockWallpaper();
        }
        if (enabled && root!=null) {
            if (asleep) suspendNativeEffects();
            invalidateGlyphs();
        }
    }

    private void transitionInk(float target) {
        if (target==glassTarget && (inkTransition!=null || glassAmount==target)) return;
        if (inkTransition!=null) inkTransition.cancel();
        glassTarget=target;
        inkTransition=ValueAnimator.ofFloat(glassAmount,target);
        inkTransition.setDuration(Math.max(1,Math.round(RESPONSE_MS*Math.abs(target-glassAmount))));
        inkTransition.setInterpolator(easing);
        inkTransition.addUpdateListener(a -> {
            glassAmount=(float)a.getAnimatedValue();
            cards.lockWallpaperFrame(stream,glassAmount);
            invalidateGlyphs();
            if (a.getAnimatedFraction()>=1f && ambient && glassTarget==0f) closeWallpaper();
        });
        inkTransition.start();
    }

    private static final String[] CHARGE_PROGRESS = {"keyguard_charging_progress","keyguard_wireless_charging_progress",
            "keyguard_charging_progress_fastcharge","keyguard_charging_progress_vooc","keyguard_charging_progress_supervooc",
            "keyguard_charging_progress_airvooc","keyguard_charging_progress_pps","keyguard_charge_full"};

    // ChargeUtils uses a localized format string as the queue key. Match that
    // resource only, so battery protection and temperature messages can still surface.
    private boolean isChargeProgress(String text) {
        if (host==null || text==null || text.isEmpty()) return false;
        android.content.res.Resources res=host.getResources();
        for (String name:CHARGE_PROGRESS) {
            int id=res.getIdentifier(name,"string","com.android.systemui");
            if (id==0) continue;
            String format=res.getString(id);
            if (text.equals(format)) return true;
            String marked=res.getString(id,"@");
            int slot=marked.indexOf('@');
            if (slot>=0 && text.startsWith(marked.substring(0,slot)) && text.endsWith(marked.substring(slot+1))
                    && text.length()>marked.length()-1) return true;
        }
        return false;
    }

    void install(ClassLoader loader) throws ReflectiveOperationException {
        try {
            Class<?> lifecycle=loader.loadClass("com.android.systemui.keyguard.WakefulnessLifecycle");
            XposedBridge.hookMethod(lifecycle.getDeclaredMethod("dispatchStartedWakingUp",int.class),new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) return;
                    setAmbient(false);
                    openLockWallpaper();
                }
            });
        } catch (ReflectiveOperationException e) { Log.w("ChaojiBeauty","Early lock wallpaper wake unavailable",e); }
        try {
            Class<?> controller=loader.loadClass("com.oplus.systemui.keyguard.clockstyle.KeyguardStyleClockControllerImpl");
            XposedBridge.hookMethod(controller.getDeclaredMethod("getStyleClockBottomPositionForNotification"),new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (enabled && root!=null && host!=null && !p.hasThrowable()) {
                        int boundary=cameraBoundary>0?cameraBoundary:contentBoundary();
                        if (boundary>0) p.setResult(boundary);
                        else if (lift>0 && (int)p.getResult()>host.getWidth()/2) p.setResult((int)p.getResult()-lift);
                    }
                }
            });
        } catch (ReflectiveOperationException e) { Log.w("ChaojiBeauty","Keyguard list anchor unavailable",e); }
        try {
            Class<?> tips=loader.loadClass("com.oplus.systemui.keyguard.tips.LockIconTipsController");
            XposedBridge.hookMethod(tips.getDeclaredMethod("access$showChargeTips",tips,boolean.class,String.class,String.class),new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { if (enabled && isChargeProgress((String)p.args[2])) p.setResult(null); }
            });
            XposedBridge.hookMethod(tips.getDeclaredMethod("getInitialTipsMessage",int.class),new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (enabled && !p.hasThrowable() && (int)p.args[0]==5 && isChargeProgress((String)p.getResult())) p.setResult("");
                }
            });
        } catch (ReflectiveOperationException e) { Log.w("ChaojiBeauty","Charging hint unavailable",e); }
        // The plugin can reinstall its bitmap effect after the wallpaper starts producing.
        XposedBridge.hookMethod(View.class.getDeclaredMethod("setRenderEffect",RenderEffect.class),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                View v=(View)p.thisObject;
                if (replaced.containsKey(v)) {
                    replaced.put(v,(RenderEffect)p.args[0]);
                    p.args[0]=null;
                }
            }
        });
        Class<?> clock=loader.loadClass("com.oplus.systemui.keyguard.view.CustomOplusKeyguardStyleClock");
        Class<?> drawOwner=clock;
        java.lang.reflect.Method draw=null;
        while(draw==null && drawOwner!=null){
            try { draw=drawOwner.getDeclaredMethod("dispatchDraw",Canvas.class); }
            catch(NoSuchMethodException e){drawOwner=drawOwner.getSuperclass();}
        }
        if(draw!=null)XposedBridge.hookMethod(draw,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                if(p.thisObject!=host || sectionProgress<=0)return;
                Canvas c=(Canvas)p.args[0];
                int save=sectionProgress==0?c.save():c.saveLayerAlpha(0,0,host.getWidth(),host.getHeight(),Math.round(255*(1-sectionProgress)));
                c.translate(0,-drawScroll);
                p.setObjectExtra("sectionClock",save);
            }
            @Override protected void afterHookedMethod(MethodHookParam p){
                Object save=p.getObjectExtra("sectionClock");
                if(save!=null)((Canvas)p.args[0]).restoreToCount((int)save);
            }
        });
        XposedBridge.hookMethod(clock.getDeclaredMethod("onAttachedToWindow"),new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                detach();
                host=(View)p.thisObject;
                host.getViewTreeObserver().addOnGlobalLayoutListener(layout);
                host.getViewTreeObserver().addOnPreDrawListener(prepare);
                bind();
            }
        });
        XposedBridge.hookMethod(clock.getDeclaredMethod("onDetachedFromWindow"),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { if(host==p.thisObject) detach(); }
        });
    }

    private void bind() {
        if (!enabled || host==null) return;
        View found=LockStylePreview.findType(host,"ClockViewRoot");
        if (!(found instanceof ViewGroup)) return;
        if (root!=found) {
            if (root!=null) root.setForceDarkAllowed(originalForceDark);
            root=(ViewGroup)found;
            originalForceDark=root.isForceDarkAllowed();
        }
        root.setForceDarkAllowed(false);
        View card=ClockFaceMaterial.firstCard(host);
        if (cameraBoundary==0) positionWidgets(card);
        suspendNativeEffects();
        for (int i=0;i<root.getChildCount();i++) {
            View outer=root.getChildAt(i);
            if (!(outer instanceof ViewGroup)) continue;
            ViewGroup group=(ViewGroup)outer;
            for (int j=0;j<group.getChildCount();j++) {
                View wrapper=group.getChildAt(j);
                if (!(wrapper instanceof ViewGroup) || ((ViewGroup)wrapper).getChildCount()!=1) continue;
                View piece=((ViewGroup)wrapper).getChildAt(0);
                boolean date=piece.getClass().getSimpleName().equals("DateMessageView");
                if (!date && !piece.getClass().getSimpleName().equals("ClockTimeView")) continue;
                try {
                    if (!materials.containsKey(piece)) materials.put(piece,new ClockFaceMaterial(date));
                    materials.get(piece).anchorLock(card,host);
                    if (!installed.contains(wrapper.getClass())) hookWrapper(wrapper.getClass());
                } catch (ReflectiveOperationException | RuntimeException e) { Log.w("ChaojiBeauty","Clock wrapper unavailable",e); }
            }
        }
    }

    private int cameraBoundary;
    private float sectionProgress, headerScroll, drawScroll;
    private int restHostShift;
    private final int[] viewportLocation=new int[2];
    int notificationAnchor(View list){
        if(host==null || root==null)return 0;
        int boundary=contentBoundary();
        return boundary<=0?0:boundary+NotificationViewport.layoutY(host,viewportLocation)-NotificationViewport.layoutY(list,viewportLocation);
    }

    void notificationProgress(float progress,float offset){
        if(progress==sectionProgress && offset==headerScroll)return;
        headerScroll=offset;
        if(offset==0)drawScroll=0;
        sectionProgress=progress;
        if(host!=null){host.invalidate();invalidateGlyphs();}
    }


    void cameraHandoff(boolean leaving) {
        cameraBoundary=leaving && host!=null?contentBoundary():0;
    }

    private int contentBoundary() {
        int ink=0;
        for (Map.Entry<View,ClockFaceMaterial> e:materials.entrySet()) {
            View piece=e.getKey();
            if (piece!=null && piece.isShown()) ink=Math.max(ink,e.getValue().contentBottom(piece,host));
        }
        return accessories.headerBottom(host,widgetLayer,ink);
    }

    private void positionWidgets(View card) {
        View layer=LockStylePreview.findType(host,"WidgetPluginContainer");
        if (layer!=widgetLayer) {
            restoreWidgets();
            widgetLayer=layer;
            if (layer!=null) originalWidgetScroll=layer.getScrollY();
        }
        if (layer==null || !host.isShown()) return;
        // Visibility can change before the camera covers the outgoing keyguard.
        // Keep the last settled inset until the widget provider has a measured row.
        if (card==null && lift!=0 && ClockFaceMaterial.findCard(layer,false)!=null) return;
        int width=host.getWidth();
        int top=0;
        if (card!=null && width>0 && host.getHeight()>width) {
            for (View v=card;v!=host && v.getParent() instanceof View;v=(View)v.getParent())
                top+=v.getTop()-(v.getParent()==layer?originalWidgetScroll:((View)v.getParent()).getScrollY());
        }
        // The reference clock ends at 615/1280 of screen width. Its card starts 13 points later.
        int target=ClockFaceMaterial.portraitWidgetTop(width);
        lift=top>0 && top<width*.85f ? Math.max(0,top-target) : 0;
        // This full-screen content host never scrolls. Keep the content inset
        // separate from the vendor translation used by wake and camera transitions.
        if (layer.getScrollY()!=originalWidgetScroll+lift) layer.setScrollY(originalWidgetScroll+lift);
    }

    private void restoreWidgets() {
        if (widgetLayer!=null) widgetLayer.setScrollY(originalWidgetScroll);
        widgetLayer=null;
        lift=0;
    }

    private void hookWrapper(Class<?> cls) throws ReflectiveOperationException {
        XposedBridge.hookMethod(cls.getDeclaredMethod("dispatchDraw",Canvas.class),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                ViewGroup wrapper=(ViewGroup)p.thisObject;
                if (!enabled || wrapper.getChildCount()!=1) return;
                View child=wrapper.getChildAt(0);
                ClockFaceMaterial material=materials.get(child);
                if (material==null || child.getVisibility()!=View.VISIBLE || child.getAlpha()<=0f) return;
                Canvas c=(Canvas)p.args[0];
                int save=c.save();
                try {
                    child.getLocationOnScreen(xy);
                    c.translate(child.getLeft()-wrapper.getScrollX(),child.getTop()-wrapper.getScrollY());
                    c.concat(child.getMatrix());
                    if (child.getAlpha()<1f) c.saveLayerAlpha(0,0,child.getWidth(),child.getHeight(),Math.round(255*child.getAlpha()));
                    boolean drawn=ambient && glassAmount==0f
                            ? material.drawForeground(c,child,wrapper,ambient || waking)
                            : material.draw(c,child,xy[0],xy[1]-drawScroll,xy[1]+headerScroll-drawScroll,
                                    stream,ambient || waking,glassAmount);
                    if (drawn) {
                        p.setResult(null);
                        if (!reported) { reported=true; Log.i("ChaojiBeauty","Live keyguard glyphs rendered"); }
                    }
                } catch (ReflectiveOperationException | RuntimeException e) { failed(e); }
                finally { c.restoreToCount(save); }
            }
        });
        installed.add(cls);
    }

    private void frame() {
        cards.lockWallpaperFrame(stream,glassAmount);
        if (widgetFrame!=null) widgetFrame.run();
        if (!enabled || root==null) return;
        suspendNativeEffects();
        invalidateGlyphs();
    }

    private void suspendNativeEffects() {
        if (replaced.isEmpty()) try {
            RenderEffect[] effects=LockStylePreview.clockEffects(root);
            String[] getters={"getClockTimeViewMount","getDateMessageViewMount"};
            for (int i=0;i<getters.length;i++) {
                View mount=(View)root.getClass().getMethod(getters[i]).invoke(root);
                if (mount!=null) { mount.setRenderEffect(null); replaced.put(mount,effects[i]); }
            }
        } catch (ReflectiveOperationException e) { Log.w("ChaojiBeauty","Clock mount effect unavailable",e); }
    }

    void invalidateGlyphs() {
        for (View v:materials.keySet()) if (v!=null && v.getParent() instanceof View)
            ((View)v.getParent()).postInvalidateOnAnimation();
    }

    private void failed(Exception e) {
        retryAt=SystemClock.uptimeMillis()+5000;
        release();
        Log.w("ChaojiBeauty","Lock clock material unavailable",e);
    }

    void configure(boolean on) {
        if (enabled==on) return;
        enabled=on;
        if (on) bind();
        else { restoreWidgets(); release(); if (root!=null) root.setForceDarkAllowed(originalForceDark); }
    }

    private void closeWallpaper() {
        ShadeWallpaper previous=stream;
        stream=null;
        if (previous!=null) { cards.lockWallpaperFrame(null,0f); previous.close(); }
    }

    private void resetInk(float target) {
        if (inkTransition!=null) { inkTransition.cancel(); inkTransition=null; }
        glassTarget=glassAmount=target;
    }

    void release() {
        resetInk(ambient?0f:1f);
        closeWallpaper();
        if (!replaced.isEmpty()) {
            Map<View,RenderEffect> restore=new HashMap<>(replaced);
            replaced.clear();
            for (Map.Entry<View,RenderEffect> e:restore.entrySet()) e.getKey().setRenderEffect(e.getValue());
        }
        for (View v:materials.keySet()) if(v!=null && v.getParent() instanceof View) ((View)v.getParent()).invalidate();
    }

    private void detach() {
        if (host==null) return;
        restoreWidgets();
        release();
        if (host.getViewTreeObserver().isAlive()) {
            host.getViewTreeObserver().removeOnGlobalLayoutListener(layout);
            host.getViewTreeObserver().removeOnPreDrawListener(prepare);
        }
        if (root!=null) root.setForceDarkAllowed(originalForceDark);
        drawScroll=headerScroll=sectionProgress=0;
        restHostShift=0;
        host=null; root=null; cameraBoundary=0; materials.clear();
    }
}
