package cn.zhe.chaojibeauty;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import de.robv.android.xposed.*;

/** Follow the meter's external charge model when ColorOS draws its bolt separately. */
final class BatteryCharge {
    private final WeakHashMap<Drawable, Boolean> externalCharge = new WeakHashMap<>();
    private Method style, charge;
    private Field visible, icon;

    void install(ClassLoader loader, Class<?> horizontal) throws ReflectiveOperationException {
        String prefix = "com.oplus.systemui.statusbar.pipeline.battery.ui.";
        Class<?> meter = loader.loadClass(prefix + "view.StatBatteryMeterView");
        Class<?> model = loader.loadClass(prefix + "model.ChargeIcon");
        style = meter.getMethod("getBatteryStyleDrawable");
        charge = meter.getMethod("getBatteryCharge");
        visible = XposedHelpers.findField(model, "isVisible");
        icon = XposedHelpers.findField(model, "iconId");
        Class<?> binder=loader.loadClass(prefix+"binder.BatteryViewBinder");
        XposedBridge.hookMethod(binder.getDeclaredMethod("bind$updateChargingView",ImageView.class,meter,model),new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws IllegalAccessException,java.lang.reflect.InvocationTargetException {
                View bolt=(View)p.args[0];
                if (!IconModule.enabled || bolt==null) return;
                Object shape=style.invoke(p.args[1]);
                if (horizontal.isInstance(shape)) {
                    Drawable d=(Drawable)shape;
                    Object requested=p.args[2];
                    externalCharge.put(d,requested!=null && visible.getBoolean(requested) && icon.getInt(requested)>0);
                    bolt.setVisibility(View.GONE);
                    d.invalidateSelf();
                }
            }
        });
        XposedBridge.hookMethod(meter.getDeclaredMethod("onAttachedToWindow"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                View owner = (View)p.thisObject;
                int id = owner.getResources().getIdentifier("battery_charge_icon","id",IconModule.SYSTEM);
                View bolt = id == 0 ? null : owner.findViewById(id);
                if (bolt == null) return;
                Observer observer = new Observer(owner, bolt, horizontal);
                owner.addOnAttachStateChangeListener(observer);
                owner.getViewTreeObserver().addOnPreDrawListener(observer);
            }
        });
    }
    boolean active(Drawable drawable) { return Boolean.TRUE.equals(externalCharge.get(drawable)); }

    private final class Observer implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        final View owner, bolt;
        final Class<?> horizontal;
        Drawable last;
        boolean charging, hidden;
        Observer(View owner, View bolt, Class<?> horizontal) {
            this.owner = owner; this.bolt = bolt; this.horizontal = horizontal;
        }
        @Override public boolean onPreDraw() {
            try {
                Object nativeModel = charge.invoke(owner);
                Object shape = style.invoke(owner);
                Drawable current = horizontal.isInstance(shape) ? (Drawable)shape : null;
                boolean next = nativeModel != null && visible.getBoolean(nativeModel) && icon.getInt(nativeModel) > 0;
                if (current != last || next != charging) {
                    if (last != null) externalCharge.remove(last);
                    if (current != null) {
                        externalCharge.put(current,next);
                        current.invalidateSelf();
                    }
                    last = current; charging = next;
                }
                if (IconModule.enabled && current != null) {
                    if (bolt.getVisibility() != View.GONE) bolt.setVisibility(View.GONE);
                    hidden = true;
                } else restore();
            } catch (ReflectiveOperationException unavailable) { restore(); }
            return true;
        }
        void restore() {
            if (!hidden) return;
            bolt.setVisibility(charging ? View.VISIBLE : View.GONE);
            hidden = false;
        }
        @Override public void onViewAttachedToWindow(View v) {}
        @Override public void onViewDetachedFromWindow(View v) {
            owner.removeOnAttachStateChangeListener(this);
            if (owner.getViewTreeObserver().isAlive()) owner.getViewTreeObserver().removeOnPreDrawListener(this);
            if (last != null) externalCharge.remove(last);
            restore();
        }
    }
}
