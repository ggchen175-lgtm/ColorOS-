package cn.zhe.chaojibeauty;

import android.app.Presentation;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XposedHelpers;

/** Draws the selected wallpaper from its screen crop or its live service renderer. */
final class ShadeWallpaper extends Drawable implements ServiceConnection, AutoCloseable {
    private static final String TAG = "ChaojiBeauty";
    private static final String CONNECTION = "android.service.wallpaper.IWallpaperConnection";
    private static final String ENGINE = "android.service.wallpaper.IWallpaperEngine";
    private static final String SERVICE = "android.service.wallpaper.IWallpaperService";
    private final View host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private DockWallpaperStream mirror;
    private SurfaceTexture texture;
    private Surface output;
    private VirtualDisplay display;
    private Presentation window;
    private Object layer, service, description;
    private Method update, draw, layerPaint;
    private final Paint opacity = new Paint();
    private static final java.util.concurrent.Executor DISPLAY_WORKER = java.util.concurrent.Executors.newSingleThreadExecutor();
    private WallpaperInfo info;
    private IBinder token;
    private int which, width, height;
    private float offset;
    private boolean bound, closed, shown, pending, ready;
    private final Runnable frameListener;
    ShadeWallpaper(View host) { this(host,null); }
    ShadeWallpaper(View host,Runnable frameListener) { this.host=host; this.frameListener=frameListener; }

    private boolean opening, appOrigin;
    private Bitmap staticPoster;
    private final Rect posterBounds = new Rect();

    void openForApp() {
        appOrigin = true;
        open();
    }
    private boolean backdropSampled = true;

    boolean started() { return opening; }

    void sampleBackdrop(boolean sampled) {
        if (backdropSampled == sampled) return;
        backdropSampled = sampled;
        invalidateSelf();
    }

    void open() {
        if (opening || closed) return;
        opening = true;
        Context context = host.getContext();
        width = host.getRootView().getWidth();
        height = host.getRootView().getHeight();
        posterBounds.set(0, 0, width, height);
        DISPLAY_WORKER.execute(() -> selectWallpaper(context));
    }

    private void selectWallpaper(Context context) {
        try {
            WallpaperManager manager = context.getSystemService(WallpaperManager.class);
            int selectedWhich = manager.getWallpaperId(WallpaperManager.FLAG_LOCK) > 0
                    ? WallpaperManager.FLAG_LOCK : WallpaperManager.FLAG_SYSTEM;
            Object selected = WallpaperManager.class.getMethod("getWallpaperInstance", int.class).invoke(manager, selectedWhich);
            Object selectedDescription = selected.getClass().getMethod("getDescription").invoke(selected);
            WallpaperInfo selectedInfo = (WallpaperInfo) selected.getClass().getMethod("getInfo").invoke(selected);
            ComponentName component = (ComponentName)selectedDescription.getClass().getMethod("getComponent").invoke(selectedDescription);
            if (component == null && selectedInfo != null) component = selectedInfo.getComponent();
            if (component == null) {
                int id = context.getResources().getIdentifier("image_wallpaper_component", "string", "android");
                if (id != 0) component = ComponentName.unflattenFromString(context.getString(id));
            }
            if (component == null) throw new IllegalStateException("Selected wallpaper has no renderer");
            boolean mirrorOnly = !component.getPackageName().equals(context.getPackageName()) &&
                    context.checkSelfPermission("android.permission.BIND_WALLPAPER") != android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (mirrorOnly && selectedWhich == WallpaperManager.FLAG_LOCK &&
                    manager.getWallpaperId(selectedWhich) != manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM))
                throw new IllegalStateException("Separate lock wallpaper is not available to mirror");
            // ImageWallpaper's preview window has its own entrance fade. Use the same
            // system image directly if its selected crop already matches this viewport.
            if (appOrigin && "com.android.systemui.wallpapers.ImageWallpaper".equals(component.getClassName())
                    && openStaticCrop(manager, selectedWhich)) return;
            ComponentName selectedService = component;
            main.post(() -> {
                if (closed) return;
                which = selectedWhich;
                description = selectedDescription;
                info = selectedInfo;
                openSource(context, selectedService, mirrorOnly);
            });
        } catch (ReflectiveOperationException | RuntimeException e) { main.post(() -> fail(e)); }
    }

    private boolean openStaticCrop(WallpaperManager manager, int which) {
        try {
            Bitmap photo = (Bitmap)WallpaperManager.class.getMethod("getBitmapAsUser",
                    int.class, boolean.class, int.class, boolean.class)
                    .invoke(manager, -2, false, which, true);
            if (photo == null || photo.isRecycled() ||
                    Math.abs((long) photo.getWidth() * height - (long) photo.getHeight() * width) > Math.max(width, height))
                return false;
            main.post(() -> {
                if (closed) return;
                staticPoster = photo;
                invalidateFrames();
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return false;
        }
    }

    private void openSource(Context context, ComponentName selectedService, boolean mirrorOnly) {
        try {
            if (mirrorOnly) {
                mirror = new DockWallpaperStream(host, "Chaoji shade", this::invalidateFrames);
                mirror.open(width, height, 0, 0, 1f, 1f);
                return;
            }
            Object renderer = XposedHelpers.callMethod(host, "getThreadedRenderer");
            layer = XposedHelpers.callMethod(renderer, "createTextureLayer");
            Class<?> type = layer.getClass();
            texture = new SurfaceTexture(false);
            texture.setDefaultBufferSize(width, height);
            type.getMethod("setSurfaceTexture", SurfaceTexture.class).invoke(layer, texture);
            type.getMethod("prepare", int.class, int.class, boolean.class).invoke(layer, width, height, false);
            layerPaint = type.getMethod("setLayerPaint", Paint.class);
            layerPaint.invoke(layer,opacity);
            update = type.getMethod("updateSurfaceTexture");
            draw = RecordingCanvas.class.getMethod("drawTextureLayer", type);
            texture.setOnFrameAvailableListener(frame -> {
                if (closed) return;
                pending = true;
                if (opacity.getAlpha() == 0) {
                    try { update.invoke(layer); pending = false; ready = true; }
                    catch (ReflectiveOperationException e) { fail(e); }
                } else if (shown) invalidateFrames();
            }, main);
            Surface sink = new Surface(texture);
            DISPLAY_WORKER.execute(() -> {
                try {
                    VirtualDisplay created = context.getSystemService(DisplayManager.class).createVirtualDisplay(
                            "Chaoji shade wallpaper", width, height, context.getResources().getDisplayMetrics().densityDpi,
                            sink, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
                    if (created == null) throw new IllegalStateException("Wallpaper preview display unavailable");
                    main.post(() -> {
                        if (closed) { created.release(); sink.release(); return; }
                        display = created;
                        output = sink;
                        showPreview(context, selectedService);
                    });
                } catch (RuntimeException e) { sink.release(); main.post(() -> fail(e)); }
            });
        } catch (ReflectiveOperationException | RuntimeException e) { fail(e); }
    }

    private void showPreview(Context context, ComponentName selectedService) {
        try {
            window = new Presentation(context, display.getDisplay(), android.R.style.Theme_Material_NoActionBar_Fullscreen);
            Window w = window.getWindow();
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            window.show();
            w.getDecorView().post(() -> {
                if (closed) return;
                try {
                    token = w.getDecorView().getWindowToken();
                    if (token == null) throw new IllegalStateException("Wallpaper preview window not attached");
                    bound = context.bindService(new Intent(WallpaperService.SERVICE_INTERFACE).setComponent(selectedService),
                            this, Context.BIND_AUTO_CREATE);
                    if (!bound) throw new IllegalStateException("Could not bind " + selectedService);
                } catch (RuntimeException e) { fail(e); }
            });
        } catch (RuntimeException e) { fail(e); }
    }

    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        if (closed) return;
        try {
            service = asInterface(SERVICE, binder);
            Class<?> connection = Class.forName(CONNECTION);
            PreviewCallbacks callbacks = new PreviewCallbacks(connection);
            Class.forName(SERVICE).getMethod("attach", connection, IBinder.class, int.class, boolean.class,
                    int.class, int.class, Rect.class, int.class, int.class, WallpaperInfo.class, description.getClass())
                    .invoke(service, callbacks.local, token, WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA,
                            true, width, height, new Rect(), display.getDisplay().getDisplayId(), which, info, description);
        } catch (ReflectiveOperationException | RuntimeException e) { fail(e); }
    }

    @Override public void onServiceDisconnected(ComponentName name) { if (!closed) fail(new IllegalStateException("Disconnected " + name)); }

    private static Object asInterface(String name, IBinder binder) throws ReflectiveOperationException {
        return Class.forName(name + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    /** The platform uses synchronous binder callbacks for remote engines and direct calls for ImageWallpaper. */
    private final class PreviewCallbacks extends Binder {
        final IInterface local;
        final int attachCode, shownCode, setCode;
        PreviewCallbacks(Class<?> type) throws ReflectiveOperationException {
            Class<?> stub = Class.forName(CONNECTION + "$Stub");
            attachCode = XposedHelpers.getStaticIntField(stub, "TRANSACTION_attachEngine");
            shownCode = XposedHelpers.getStaticIntField(stub, "TRANSACTION_engineShown");
            setCode = XposedHelpers.getStaticIntField(stub, "TRANSACTION_setWallpaper");
            local = (IInterface) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p,m,args) -> {
                switch (m.getName()) {
                    case "asBinder": return this;
                    case "attachEngine": attach(args[0]); break;
                    case "engineShown": didShow(); break;
                    case "hashCode": return System.identityHashCode(p);
                    case "equals": return p == args[0];
                    case "toString": return "Shade wallpaper connection";
                }
                return null;
            });
            attachInterface(local, CONNECTION);
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) return super.onTransact(code,data,reply,flags);
            data.enforceInterface(CONNECTION);
            try {
                if (code == attachCode) attach(asInterface(ENGINE, data.readStrongBinder()));
                else if (code == shownCode) didShow();
            } catch (ReflectiveOperationException e) { main.post(() -> fail(e)); }
            if (reply != null) { reply.writeNoException(); if (code == setCode) reply.writeInt(0); }
            return true;
        }
        private void attach(Object engine) {
            main.post(() -> {
                if (closed) return;
                try { Class.forName(ENGINE).getMethod("setVisibility",boolean.class).invoke(engine,true); }
                catch (ReflectiveOperationException e) { fail(e); }
            });
        }
        private void didShow() { main.post(() -> { if (!closed) { shown = true; invalidateFrames(); } }); }
    }

    private View clockView;
    void setClockView(View view) { clockView=view; }
    private void invalidateFrames() {
        if (opacity.getAlpha() == 0) return;
        invalidateSelf();
        if (clockView!=null) clockView.postInvalidateOnAnimation();
        if (!closed && frameListener!=null) frameListener.run();
    }
    boolean unavailable() { return closed; }
    boolean hasFrame() {
        if (closed) return false;
        if (staticPoster != null) return !staticPoster.isRecycled();
        return mirror != null ? mirror.hasFrame() : shown && (pending || ready);
    }
    void move(float y) { if (offset != y) { offset = y; invalidateSelf(); } }
    @Override public void draw(Canvas c) {
        // Keep the full input bounds even before the wallpaper service delivers its first frame.
        c.drawColor(android.graphics.Color.TRANSPARENT);
        if (backdropSampled) drawFrame(c);
    }

    void drawFrame(Canvas c) {
        if (!(c instanceof RecordingCanvas) || !hasFrame() || opacity.getAlpha() == 0) return;
        int save = c.save();
        try {
            if (pending) { update.invoke(layer); pending = false; ready = true; }
            c.translate(0, offset);
            c.clipRect(getBounds());
            if (staticPoster != null) {
                c.drawBitmap(staticPoster, null, posterBounds, opacity);
            } else if (mirror != null) {
                if (opacity.getAlpha() < 255) c.saveLayerAlpha(0,0,width,height,opacity.getAlpha());
                mirror.record((RecordingCanvas)c);
            } else draw.invoke(c, layer);
        } catch (ReflectiveOperationException | RuntimeException e) { main.post(() -> fail(e)); }
        finally { c.restoreToCount(save); }
    }
    @Override public void setAlpha(int alpha) {
        if (opacity.getAlpha() == alpha) return;
        opacity.setAlpha(alpha);
        if (layerPaint != null) {
            try { layerPaint.invoke(layer,opacity); }
            catch (ReflectiveOperationException e) { fail(e); }
        }
        invalidateSelf();
    }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    private void fail(Exception e) { if (!closed) { Log.w(TAG,"Live shade wallpaper unavailable",e); close(); } }
    @Override public void close() {
        if (closed) return;
        closed = true;
        clockView=null;
        staticPoster=null;
        shown = pending = ready = false;
        if (mirror != null) mirror.close();
        if (service != null) {
            try { Class.forName(SERVICE).getMethod("detach",IBinder.class).invoke(service,token); }
            catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG,"Detach wallpaper preview",e); }
        }
        if (bound) host.getContext().unbindService(this);
        if (window != null && window.isShowing()) window.dismiss();
        if (texture != null) texture.setOnFrameAvailableListener(null);
        if (display != null) display.release();
        if (output != null) output.release();
        if (layer != null) try { ((AutoCloseable)layer).close(); } catch (Exception e) { Log.w(TAG,"Release wallpaper texture",e); }
        if (texture != null) texture.release();
        invalidateFrames();
    }
}
