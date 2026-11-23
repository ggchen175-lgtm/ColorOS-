package cn.zhe.chaojibeauty;

import android.content.Context;
import android.app.Presentation;
import android.graphics.RecordingCanvas;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Method;
import de.robv.android.xposed.XposedHelpers;

/** A private GPU stream of the wallpaper subtree. No launcher icons or app windows are mirrored. */
final class DockWallpaperStream implements AutoCloseable {
    static final Object WALLPAPER_WINDOW = new Object();
    private final View host;
    private final String name;
    private final Runnable onFrame;
    private SurfaceTexture texture;
    private Surface output;
    private VirtualDisplay display;
    private SurfaceControl root, mirror;
    private SurfaceControl.Transaction transaction;
    private Presentation presentation;
    private Object layer;
    private Method updateTexture, drawTexture;
    private boolean pending, ready, active = true;
    private float scaleX = Float.NaN, scaleY = Float.NaN;
    private int left = Integer.MIN_VALUE, top = Integer.MIN_VALUE;

    DockWallpaperStream(View host) { this(host, "Chaoji Dock", host::postInvalidateOnAnimation); }

    DockWallpaperStream(View host, String name, Runnable onFrame) {
        this.host = host;
        this.name = name;
        this.onFrame = onFrame;
    }

    void open(int width, int height, int x, int y, float sx, float sy) throws ReflectiveOperationException {
        open(width,height,x,y,sx,sy,false);
    }

    void open(int width, int height, int x, int y, float sx, float sy, boolean useDisplayWindow) throws ReflectiveOperationException {
        Context context = host.getContext();
        Object wm = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null);
        mirror = (SurfaceControl)Class.forName("android.view.IWindowManager")
                .getMethod("mirrorWallpaperSurface", int.class).invoke(wm, host.getDisplay().getDisplayId());
        if (mirror == null || !mirror.isValid()) throw new IllegalStateException("Wallpaper mirror unavailable");

        Object renderer = XposedHelpers.callMethod(host, "getThreadedRenderer");
        layer = XposedHelpers.callMethod(renderer, "createTextureLayer");
        Class<?> layerClass = layer.getClass();
        texture = new SurfaceTexture(false);
        texture.setDefaultBufferSize(width, height);
        layerClass.getMethod("setSurfaceTexture", SurfaceTexture.class).invoke(layer, texture);
        layerClass.getMethod("prepare", int.class, int.class, boolean.class).invoke(layer, width, height, false);
        updateTexture = layerClass.getMethod("updateSurfaceTexture");
        drawTexture = RecordingCanvas.class.getMethod("drawTextureLayer", layerClass);
        texture.setOnFrameAvailableListener(frame -> {
            if (frame == texture && active && !pending) {
                pending = true;
                onFrame.run();
            }
        }, new Handler(Looper.getMainLooper()));
        output = new Surface(texture);
        display = context.getSystemService(DisplayManager.class).createVirtualDisplay(name + " wallpaper",
                width, height, context.getResources().getDisplayMetrics().densityDpi, output,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        if (display == null) throw new IllegalStateException("Private wallpaper output unavailable");
        transaction = new SurfaceControl.Transaction();
        if (useDisplayWindow) {
            // The assistant owns this private display, but has no launcher-level composition root.
            // Parent the wallpaper to a real surface inside its own window on that display.
            presentation = new Presentation(context, display.getDisplay());
            presentation.getWindow().getDecorView().setTag(WALLPAPER_WINDOW);
            presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            presentation.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            SurfaceView target = new SurfaceView(presentation.getContext());
            target.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder h) {
                    if (transaction != null && mirror != null)
                        transaction.reparent(mirror,target.getSurfaceControl()).setLayer(mirror,1)
                                .setVisibility(mirror,true).apply();
                }
                @Override public void surfaceChanged(SurfaceHolder h,int format,int w,int hgt) {}
                @Override public void surfaceDestroyed(SurfaceHolder h) {
                    if (transaction != null && mirror != null) transaction.reparent(mirror,null).apply();
                }
            });
            presentation.setContentView(target);
            presentation.show();
            move(x,y,sx,sy);
            return;
        }
        Class<?> builderClass = Class.forName("android.view.SurfaceControl$Builder");
        Object builder = builderClass.getConstructor().newInstance();
        builderClass.getMethod("setName", String.class).invoke(builder, name + " private source");
        builderClass.getMethod("setContainerLayer").invoke(builder);
        root = (SurfaceControl)builderClass.getMethod("build").invoke(builder);
        int stack = (Integer)Display.class.getMethod("getLayerStack").invoke(display.getDisplay());
        transaction.getClass().getMethod("setLayerStack", SurfaceControl.class, int.class)
                .invoke(transaction, root, stack);
        transaction.reparent(mirror, root).setLayer(root, Integer.MAX_VALUE)
                .setVisibility(root, true).setVisibility(mirror, true);
        move(x, y, sx, sy);
    }

    void move(int x, int y, float sx, float sy) {
        if (left == x && top == y && sx == scaleX && sy == scaleY) return;
        scaleX = sx;
        scaleY = sy;
        left = x;
        top = y;
        transaction.setScale(mirror, sx, sy).setPosition(mirror, -x * sx, -y * sy).apply();
    }

    void setActive(boolean next) {
        if (active == next || display == null) return;
        active = next;
        // Suspending the producer does not destroy the TextureLayer's acquired buffer.
        // Keep it drawable until the producer signals its next frame.
        display.setSurface(active ? output : null);
    }

    // The assistant may keep its display connection across a quick reverse swipe,
    // but must wait for a newly delivered wallpaper buffer after resuming.
    void pauseUntilFreshFrame() {
        setActive(false);
        pending=false;
        ready=false;
    }

    boolean hasFrame() { return pending || ready; }

    void record(RecordingCanvas canvas) throws ReflectiveOperationException {
        if (pending) {
            updateTexture.invoke(layer);
            pending = false;
            ready = true;
        }
        drawTexture.invoke(canvas, layer);
    }

    @Override public void close() {
        if (texture != null) texture.setOnFrameAvailableListener(null);
        if (presentation != null) { presentation.dismiss(); presentation=null; }
        if (transaction != null) {
            if (mirror != null) transaction.setVisibility(mirror, false).reparent(mirror, null);
            if (root != null) transaction.setVisibility(root, false);
            transaction.apply();
            transaction.close();
            transaction=null;
        }
        if (display != null) display.release();
        if (mirror != null) mirror.release();
        if (root != null) root.release();
        if (output != null) output.release();
        if (layer != null) {
            try { ((AutoCloseable)layer).close(); }
            catch (Exception e) { android.util.Log.w("ChaojiBeauty", "Closing Dock texture", e); }
        }
        if (texture != null) texture.release();
        texture = null;
    }
}
