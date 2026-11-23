package cn.zhe.chaojibeauty;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class FoldDock implements IXposedHookLoadPackage {
    private static final String LAUNCHER = "com.android.launcher";
    private static final String KEY = "cn.zhe.chaojibeauty.foldDock";
    private Method setNativeBackground;
    private Field dockBackground, shortcuts;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!LAUNCHER.equals(p.packageName) || !LAUNCHER.equals(p.processName)) return;
        try { LauncherShadeReturn.install(p.classLoader); }
        catch (ReflectiveOperationException | RuntimeException e) { Log.w("ChaojiBeauty","Launcher unlock animation unavailable",e); }
        try {
            Class<?> hotseat = p.classLoader.loadClass("com.android.launcher3.OplusHotseat");
            setNativeBackground = hotseat.getDeclaredMethod("setDockerBackground");
            dockBackground = XposedHelpers.findField(hotseat, "mDockerBackgroundDrawable");
            shortcuts = XposedHelpers.findField(hotseat, "mShortcutsAndWidgets");
            Method attach = hotseat.getDeclaredMethod("onAttachedToWindow");
            Method layout = hotseat.getDeclaredMethod("onLayout", boolean.class, int.class, int.class, int.class, int.class);
            XposedBridge.hookMethod(setNativeBackground, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                    Binding b = (Binding)XposedHelpers.getAdditionalInstanceField(p.thisObject, KEY);
                    if (b != null) b.syncMaterial();
                }
            });
            XposedBridge.hookMethod(attach, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View host = (View)p.thisObject;
                    if (XposedHelpers.getAdditionalInstanceField(host, KEY) == null) new Binding(host).start();
                }
            });
            XposedBridge.hookMethod(layout, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Binding binding = (Binding)XposedHelpers.getAdditionalInstanceField(p.thisObject, KEY);
                    if (binding != null && binding.enabled) binding.update();
                }
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w("ChaojiBeauty", "Launcher Fold Dock interface unavailable", e);
        }
    }

    static DockGlass glass(View host) {
        Binding binding = (Binding)XposedHelpers.getAdditionalInstanceField(host, KEY);
        return binding == null ? null : binding.glass;
    }

    private final class Binding extends ContentObserver implements View.OnAttachStateChangeListener {
        final View host;
        final ContentResolver resolver;
        final Runnable retry = this::read;
        boolean enabled, useGlass;
        int retries, height;
        DockGlass glass;
        Drawable owned, previousChildBackground;

        Binding(View host) {
            super(new Handler(Looper.getMainLooper()));
            this.host = host;
            resolver = host.getContext().getContentResolver();
        }

        void start() {
            try {
                resolver.registerContentObserver(FeatureProvider.DOCK, false, this);
            } catch (RuntimeException e) {
                Log.w("ChaojiBeauty", "Fold Dock observer unavailable", e);
                return;
            }
            XposedHelpers.setAdditionalInstanceField(host, KEY, this);
            host.addOnAttachStateChangeListener(this);
            read();
        }

        @Override public void onChange(boolean selfChange) { retries = 0; read(); }

        void read() {
            host.removeCallbacks(retry);
            try (Cursor state = resolver.query(FeatureProvider.DOCK, null, null, null, null)) {
                if (state != null && state.moveToFirst()) {
                    int column = state.getColumnIndex("glass");
                    useGlass = column >= 0 && state.getInt(column) == 1;
                    enabled = state.getInt(0) == 1 || useGlass;
                    host.invalidate();
                    retries = 0;
                    update();
                    return;
                }
            } catch (RuntimeException e) {
                if (retries == 0) Log.w("ChaojiBeauty", "Fold Dock setting unavailable", e);
            }
            if (++retries <= 4) host.postDelayed(retry, 1000L * retries);
        }

        void update() {
            try {
                ViewGroup icons = (ViewGroup)shortcuts.get(host);
                if (icons == null) return;
                syncMaterial();
                Drawable current = nativeFace();
                if (owned != current) { owned = null; previousChildBackground = null; }
                if (!enabled || icons.getChildCount() == 0) {
                    if (owned != null) {
                        releaseMaterial();
                        owned.setCallback(null);
                        dockBackground.set(host, null);
                        if (icons.getBackground() == null) icons.setBackground(previousChildBackground);
                        owned = null;
                        previousChildBackground = null;
                        host.invalidate();
                    }
                } else if (host.getHeight() > 0 && (current == null || (owned != null && height != host.getHeight()))) {
                    if (owned == null) previousChildBackground = icons.getBackground();
                    setNativeBackground.invoke(host);
                    owned = nativeFace();
                    height = host.getHeight();
                    if (owned != null) {
                        host.invalidate();
                        IconModule.firstDraw("launcher Fold Dock");
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                enabled = false;
                Log.w("ChaojiBeauty", "Fold Dock refresh failed", e);
            }
        }

        private Drawable nativeFace() throws IllegalAccessException {
            Drawable face = (Drawable)dockBackground.get(host);
            return face instanceof DockGlass ? ((DockGlass)face).original : face;
        }

        private void releaseMaterial() throws IllegalAccessException {
            if (glass == null) return;
            if (dockBackground.get(host) == glass) {
                glass.original.setCallback(glass.getCallback());
                dockBackground.set(host,glass.original);
            }
            glass.setCallback(null);
            glass.close();
            glass = null;
        }

        private void syncMaterial() throws IllegalAccessException {
            Drawable current = (Drawable)dockBackground.get(host);
            if (glass != null && (!useGlass || current != glass)) releaseMaterial();
            if (!useGlass || glass != null || !(current instanceof GradientDrawable)) return;
            View icons = (View)shortcuts.get(host);
            if (icons == null) return;
            glass = new DockGlass(host, icons, (GradientDrawable)current);
            glass.setCallback(current.getCallback());
            current.setCallback(null);
            dockBackground.set(host,glass);
            host.invalidate();
        }

        @Override public void onViewAttachedToWindow(View v) {}
        @Override public void onViewDetachedFromWindow(View v) {
            host.removeCallbacks(retry);
            resolver.unregisterContentObserver(this);
            host.removeOnAttachStateChangeListener(this);
            useGlass = false;
            enabled = false;
            update();
            XposedHelpers.removeAdditionalInstanceField(host, KEY);
        }
    }
}
