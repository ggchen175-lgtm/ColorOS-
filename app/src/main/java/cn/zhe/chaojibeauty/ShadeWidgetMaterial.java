package cn.zhe.chaojibeauty;

import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Existing transparent artwork for the optional unlocked notification-page preview. */
final class ShadeWidgetMaterial {
    private final Set<View> clocks = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Class<?>, Method> alphaReaders = new HashMap<>();
    private final Map<Drawable, Integer> original = Collections.synchronizedMap(new WeakHashMap<>());
    private final ViewTreeObserver.OnGlobalLayoutListener layout = this::refresh;
    private boolean enabled;
    private final WidgetVibrancy vibrancy = new WidgetVibrancy();
    private final WidgetAccessoryLayout accessories;
    ShadeWidgetMaterial(WidgetAccessoryLayout accessories) { this.accessories=accessories; }
    private final Set<Class<?>> shadowClasses = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<View> shadows = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<View> plateHosts = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Drawable,ColorFilter> ambientFilters = new IdentityHashMap<>();
    private final ColorFilter transparent = new PorterDuffColorFilter(Color.TRANSPARENT,PorterDuff.Mode.SRC_IN);
    private final ViewTreeObserver.OnPreDrawListener beforeDraw = this::prepareFrame;
    private boolean prepareFrame() {
        accessories.updateMotion();
        for (View host:plateHosts) {
            Drawable front=host.getForeground(), body=bodyPlate(host);
            if ((front instanceof ColorDrawable && front.getColorFilter()!=transparent)
                    || (body!=null && body.getColorFilter()!=transparent)) {
                updatePlates();
                break;
            }
        }
        vibrancy.refreshColors();
        return true;
    }

    void setAmbient(boolean asleep) {
        accessories.setAmbient(asleep);
    }

    private Drawable bodyPlate(View plate) {
        if (!(plate instanceof ViewGroup)) return null;
        ViewGroup shell=(ViewGroup)plate;
        if (shell.getChildCount()!=1) return null;
        View body=shell.getChildAt(0);
        return body.getWidth()==shell.getWidth() && body.getHeight()==shell.getHeight()
                && body.getBackground() instanceof ColorDrawable ? body.getBackground() : null;
    }

    private void updatePlates() {
        Set<Drawable> active=Collections.newSetFromMap(new IdentityHashMap<>());
        if (enabled) for (View plate:plateHosts) {
            if (plate.getForeground() instanceof ColorDrawable) active.add(plate.getForeground());
            Drawable body=bodyPlate(plate);
            if (body!=null) active.add(body);
        }
        for (Drawable d:new ArrayList<>(ambientFilters.keySet())) if (!active.contains(d)) {
            ColorFilter saved=ambientFilters.remove(d);
            if (d.getColorFilter()==transparent) d.setColorFilter(saved);
        }
        for (Drawable d:active) if (d.getColorFilter()!=transparent) {
            ambientFilters.put(d,d.getColorFilter());
            d.setColorFilter(transparent);
        }
    }

    void install(ClassLoader loader) throws ReflectiveOperationException {
        try { vibrancy.install(); }
        catch (ReflectiveOperationException | RuntimeException e) {
            Log.w("ChaojiBeauty", "Widget foreground compositor unavailable", e);
        }
        Class<?> clock = loader.loadClass("com.oplus.systemui.keyguard.view.CustomOplusKeyguardStyleClock");
        XposedBridge.hookMethod(clock.getDeclaredMethod("onAttachedToWindow"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                View view = (View)p.thisObject;
                if (clocks.add(view) && enabled) observe(view, true);
            }
        });
        XposedBridge.hookMethod(clock.getDeclaredMethod("onDetachedFromWindow"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                View view = (View)p.thisObject;
                if (clocks.remove(view) && enabled) {
                    observe(view, false);
                    refresh();
                }
            }
        });
    }

    void configure(boolean on) {
        if (enabled == on) return;
        enabled = on;
        for (View clock : clocks) observe(clock, on);
        refresh();
    }

    private void observe(View clock, boolean on) {
        ViewTreeObserver observer = clock.getViewTreeObserver();
        if (!observer.isAlive()) return;
        if (on) {
            observer.addOnGlobalLayoutListener(layout);
            observer.addOnPreDrawListener(beforeDraw);
        } else {
            observer.removeOnGlobalLayoutListener(layout);
            observer.removeOnPreDrawListener(beforeDraw);
        }
    }

    private void refresh() {
        Set<Drawable> current = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<View,View> content = new IdentityHashMap<>();
        accessories.begin();
        plateHosts.clear();
        Set<View> oldShadows = Collections.newSetFromMap(new IdentityHashMap<>());
        oldShadows.addAll(shadows);
        shadows.clear();
        if (enabled) for (View clock : clocks) findCards(clock,clock,current,content);
        accessories.finish();
        updatePlates();
        for (View v : oldShadows) if (!shadows.contains(v)) v.invalidate();
        for (View v : shadows) if (!oldShadows.contains(v)) v.invalidate();
        vibrancy.update(content);
        ArrayList<Drawable> previous;
        synchronized (original) { previous = new ArrayList<>(original.keySet()); }
        for (Drawable d : previous) {
            if (current.contains(d)) continue;
            Integer requested = original.remove(d);
            if (requested != null) d.setAlpha(requested);
        }
        for (Drawable d : current) {
            if (original.containsKey(d)) continue;
            try {
                Method reader = alphaReader(d.getClass());
                if (reader == null) continue;
                int requested = (int)reader.invoke(d);
                original.put(d, requested);
                d.setAlpha(requested);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                Log.w("ChaojiBeauty", "Widget material unavailable", ex);
            }
        }
    }

    private void findCards(View view, View clock, Set<Drawable> current, Map<View,View> content) {
        if (view instanceof ViewGroup && view.getClass().getSimpleName().equals("CustomRecyclerView")
                && view.getParent() instanceof View && clock.getHeight()>clock.getWidth())
            accessories.arrangeRows((ViewGroup)view,clock);
        if (view.getClass().getName().equals("com.oplus.keyguard.style.widgets.container.view.WidgetShadowView")) {
            shadows.add(view);
            Class<?> cls = view.getClass();
            if (shadowClasses.add(cls)) try {
                XposedBridge.hookMethod(cls.getDeclaredMethod("draw",android.graphics.Canvas.class),new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (shadows.contains((View)p.thisObject)) p.setResult(null);
                    }
                });
            } catch (ReflectiveOperationException | RuntimeException e) { Log.w("ChaojiBeauty","Widget shadow unavailable",e); }
            return;
        }
        if (view.getClass().getName().equals("com.oplus.keyguard.style.widgets.container.view.InstantCardView")) {
            accessories.arrange((ViewGroup)view);
            Drawable plate = findPlate(view);
            if (plate != null) {
                current.add(plate);
                collectContent(view,clock,content);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i=0; i<group.getChildCount(); i++) findCards(group.getChildAt(i),clock,current,content);
        }
    }

    private Drawable findPlate(View view) {
        Drawable background = view.getBackground();
        if (background != null && background.getClass().getName().equals("com.oplus.posteffect.BlurDrawable")) {
            plateHosts.add(view);
            return background;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i=0; i<group.getChildCount(); i++) {
                Drawable found = findPlate(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void collectContent(View view, View clock, Map<View,View> content) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) collectContent(group.getChildAt(i),clock,content);
        } else if (view.getBackground() == null && !view.willNotDraw()) {
            content.put(view,clock);
        }
    }

    private Method alphaReader(Class<?> type) throws ReflectiveOperationException {
        if (alphaReaders.containsKey(type)) return alphaReaders.get(type);
        alphaReaders.put(type, null);
        Method read = type.getMethod("getPaintAlpha");
        Method write = type.getMethod("setAlpha", int.class);
        XposedBridge.hookMethod(write, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Drawable d = (Drawable)p.thisObject;
                int requested = (int)p.args[0];
                synchronized (original) {
                    if (!original.containsKey(d)) return;
                    original.put(d, requested);
                }
                p.args[0] = 0;
            }
        });
        alphaReaders.put(type, read);
        return read;
    }
}
