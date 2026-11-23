package cn.zhe.chaojibeauty;

import android.app.WallpaperManager;
import android.graphics.Rect;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

/** Transform the active image wallpaper buffer. The launcher's wallpaper mirror includes this child. */
final class HomeWallpaperMotion implements AutoCloseable {
    private static volatile WeakReference<WallpaperService.Engine> home = new WeakReference<>(null);
    private static Field content, transformParent;
    private final WallpaperService.Engine engine;
    private final SurfaceControl buffer, parent, motion;
    private final SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
    private final float halfWidth, halfHeight, zoomRange;
    private float applied = 1f;
    private boolean closed;

    static void install(ClassLoader loader) throws ReflectiveOperationException {
        if (android.os.Build.VERSION.SDK_INT < 34) return;
        Class<?> canvasEngine = loader.loadClass("com.android.systemui.wallpapers.ImageWallpaper$CanvasEngine");
        content = de.robv.android.xposed.XposedHelpers.findFieldIfExists(WallpaperService.Engine.class, "mBbqSurfaceControl");
        transformParent = de.robv.android.xposed.XposedHelpers.findFieldIfExists(WallpaperService.Engine.class, "mTransformSurfaceControl");
        if (content == null || transformParent == null) throw new NoSuchFieldException("Wallpaper buffer hierarchy");
        XposedBridge.hookAllMethods(WallpaperService.Engine.class, "updateSurface", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                WallpaperService.Engine engine = (WallpaperService.Engine)p.thisObject;
                if (android.os.Build.VERSION.SDK_INT >= 34 && !p.hasThrowable() && canvasEngine.isInstance(engine) && !engine.isPreview()
                        && engine.getDisplayContext().getDisplay().getDisplayId() == Display.DEFAULT_DISPLAY
                        && (engine.getWallpaperFlags() & WallpaperManager.FLAG_SYSTEM) != 0
                        && home.get() != engine) home = new WeakReference<>(engine);
            }
        });
    }

    static HomeWallpaperMotion forWindow(View panel) {
        WallpaperService.Engine engine = home.get();
        if (engine == null || panel.getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY) return null;
        try {
            SurfaceControl layer = (SurfaceControl)content.get(engine);
            SurfaceControl parent = (SurfaceControl)transformParent.get(engine);
            Rect size = engine.getSurfaceHolder().getSurfaceFrame();
            if (layer == null || !layer.isValid() || parent == null || !parent.isValid() || size.width() != panel.getRootView().getWidth()
                    || size.height() != panel.getRootView().getHeight()) return null;
            int id = panel.getResources().getIdentifier("config_wallpaperMaxScale", "dimen", "android");
            if (id == 0) return null;
            return new HomeWallpaperMotion(engine, layer, parent, size, panel.getResources().getFloat(id));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w("ChaojiBeauty","Home wallpaper buffer unavailable",e);
            return null;
        }
    }

    private HomeWallpaperMotion(WallpaperService.Engine engine, SurfaceControl layer, SurfaceControl parent, Rect size, float maxScale) throws ReflectiveOperationException {
        this.engine = engine;
        this.buffer = layer;
        this.parent = parent;
        Class<?> type = Class.forName("android.view.SurfaceControl$Builder");
        Object builder = type.getConstructor().newInstance();
        type.getMethod("setName", String.class).invoke(builder, "Chaoji home wallpaper gesture");
        type.getMethod("setContainerLayer").invoke(builder);
        type.getMethod("setParent", SurfaceControl.class).invoke(builder, parent);
        motion = (SurfaceControl)type.getMethod("build").invoke(builder);
        transaction.reparent(buffer, motion).setVisibility(motion, true).apply();
        halfWidth = size.width() * .5f;
        halfHeight = size.height() * .5f;
        zoomRange = Math.max(0f, maxScale - 1f);
    }

    void move(float progress) {
        if (closed || android.os.Build.VERSION.SDK_INT < 34) return;
        if (home.get() != engine || !buffer.isValid() || !parent.isValid()
                || (engine.getWallpaperFlags() & WallpaperManager.FLAG_SYSTEM) == 0) {
            close();
            return;
        }
        float scale = 1f + zoomRange * progress;
        if (scale != applied) setScale(scale);
    }

    private void setScale(float scale) {
        transaction.setScale(motion, scale, scale)
                .setPosition(motion, (1f-scale)*halfWidth, (1f-scale)*halfHeight).apply();
        applied = scale;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            if (buffer.isValid() && parent.isValid()) transaction.reparent(buffer, parent);
            transaction.reparent(motion, null).apply();
        } finally {
            motion.release();
            transaction.close();
        }
    }
}
