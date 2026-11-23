package cn.zhe.chaojibeauty;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.lang.reflect.Field;
import java.util.Arrays;

/** The drawable installed in the launcher's native Dock background slot. */
final class DockGlass extends Drawable implements ViewTreeObserver.OnPreDrawListener,
        ViewTreeObserver.OnWindowFocusChangeListener {
    private Runnable removeVisibilityListener;
    final GradientDrawable original;
    private final View host, icons;
    private final RenderNode node = new RenderNode("Dock liquid glass");
    private final Matrix toScreen = new Matrix();
    private final Matrix screenToDock = new Matrix();
    private final Matrix sampledTransform = new Matrix();
    private final Matrix parentToScreen = new Matrix();
    private View workspace;
    private RenderNode workspaceNode;
    private boolean workspaceRecorded;
    private java.lang.reflect.Method nativeReturnRunning;
    private final RectF screen = new RectF();
    private int sourceTop, sourceWidth, windowHeight;
    private DockWallpaperStream stream;
    private final float density;
    private DockMaterial material;
    private int width, height;
    private boolean dark;
    private float weight = -1;
    private float radius = -1;
    private boolean closed, failed, shadeSampling;

    DockGlass(View host, View icons, GradientDrawable original) {
        this.host = host;
        this.icons = icons;
        this.original = original;
        density = host.getResources().getDisplayMetrics().density;
        setBounds(original.getBounds());
        host.getViewTreeObserver().addOnPreDrawListener(this);
        host.getViewTreeObserver().addOnWindowFocusChangeListener(this);
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            removeVisibilityListener = observeVisibility(host.getViewTreeObserver());
        }
        onPreDraw();
    }

    @Override public boolean onPreDraw() {
        if (nativeReturnRunning != null) {
            try {
                if (!Boolean.TRUE.equals(nativeReturnRunning.invoke(workspace))) setReturnWorkspace(null);
            } catch (ReflectiveOperationException e) {
                setReturnWorkspace(null);
            }
        }
        if (stream != null) {
            boolean visible = host.getWindowVisibility() == View.VISIBLE;
            stream.setActive(visible || shadeSampling);
            if (visible && host.isShown()) {
                mapDockToScreen();
                if (dark != GlassAppearance.dark(host) || !toScreen.equals(sampledTransform)
                        || (workspaceNode != null && !workspaceRecorded && workspaceNode.hasDisplayList()
                            && workspace.isShown())) host.invalidate();
            }
        }
        return true;
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        if (focused) host.postInvalidateOnAnimation();
    }

    void setReturnWorkspace(View source) {
        if (closed) return;
        nativeReturnRunning = null;
        if (workspace == source) return;
        workspace = source;
        shadeSampling = source != null;
        workspaceNode = null;
        workspaceRecorded = false;
        if (source != null && source.getParent() == host.getParent()) {
            try {
                workspaceNode = (RenderNode)de.robv.android.xposed.XposedHelpers.getObjectField(source,"mRenderNode");
            } catch (RuntimeException | LinkageError e) {
                Log.w("ChaojiBeauty","Unable to read launcher workspace render node",e);
            }
        }
        if (stream != null) stream.setActive(shadeSampling || host.getWindowVisibility() == View.VISIBLE);
        host.invalidate();
    }

    void followNativeReturn(View source, java.lang.reflect.Method running) {
        setReturnWorkspace(source);
        if (!closed) nativeReturnRunning = running;
    }

    // The workspace is drawn before the hotseat in the same DragLayer. Referencing
    // its live node picks up icon transforms on RenderThread, including those
    // which never invalidate a Java View during the native return animation.
    private void recordWorkspace(RecordingCanvas canvas) {
        if (workspaceNode == null || !workspaceNode.hasDisplayList() || !workspace.isShown()
                || !(host.getParent() instanceof ViewGroup) || host.getParent() != workspace.getParent()) return;
        ViewGroup parent = (ViewGroup)host.getParent();
        if (parent.indexOfChild(workspace) >= parent.indexOfChild(host)) return;
        parentToScreen.reset();
        parent.transformMatrixToGlobal(parentToScreen);
        canvas.concat(parentToScreen);
        canvas.translate(-parent.getScrollX(),-parent.getScrollY());
        canvas.drawRenderNode(workspaceNode);
        workspaceRecorded = true;
        IconModule.firstDraw("Dock live workspace refraction");
    }

    void boundsInHost(Rect result) {
        result.set(getBounds());
        result.offset(icons.getLeft()-host.getScrollX(), icons.getTop()-host.getScrollY());
    }

    private final Rect hostBounds = new Rect();
    private void mapDockToScreen() {
        // OplusHotseat.onDraw translates its own canvas by the child's layout position.
        // Unlock animations may move the child independently of the background.
        toScreen.reset();
        host.transformMatrixToGlobal(toScreen);
        boundsInHost(hostBounds);
        toScreen.preTranslate(hostBounds.left, hostBounds.top);
        screen.set(0,0,hostBounds.width(),hostBounds.height());
        toScreen.mapRect(screen);
    }

    @android.annotation.TargetApi(34)
    private Runnable observeVisibility(ViewTreeObserver observer) {
        ViewTreeObserver.OnWindowVisibilityChangeListener listener = visibility -> {
            if (stream != null) stream.setActive(visibility == View.VISIBLE || shadeSampling);
            host.invalidate();
        };
        observer.addOnWindowVisibilityChangeListener(listener);
        return () -> {
            if (observer.isAlive()) observer.removeOnWindowVisibilityChangeListener(listener);
        };
    }

    private void prepareMaterial() throws ReflectiveOperationException {
        Rect b = getBounds();
        boolean appearance = GlassAppearance.dark(host);
        if (material != null && appearance == dark && b.width() == width && b.height() == height
                && original.getCornerRadius() == radius) return;
        if (material == null) {
            material = new DockMaterial(host.getContext().getClassLoader());
            Object state = original.getConstantState();
            Field nativeWeight = state.getClass().getDeclaredField("mSmoothWeight");
            nativeWeight.setAccessible(true);
            weight = nativeWeight.getFloat(state);
            if (weight <= 0) throw new IllegalStateException("Dock has no G2 corner weight");
        }
        if (width != b.width() || height != b.height()) releaseStream();
        width = b.width();
        height = b.height();
        radius = original.getCornerRadius();
        node.setRenderEffect(material.resize(width, height, radius, weight, density, appearance));

        dark = appearance;
        float[] corners = new float[8];
        Arrays.fill(corners, Math.min(radius, Math.min(width, height) * .5f));
        Path silhouette = new Path();
        Path.class.getMethod("addSmoothRoundRect16", RectF.class, float[].class, Path.Direction.class,
                float.class).invoke(silhouette, new RectF(0,0,width,height), corners, Path.Direction.CW, weight);
        Outline outline = new Outline();
        outline.setPath(silhouette);
        node.setOutline(outline);
        node.setClipToOutline(true);
    }

    @Override public void draw(Canvas canvas) {
        if (closed || host.getWindowVisibility() != View.VISIBLE || getAlpha() == 0 || getBounds().isEmpty()) return;
        if (failed || !canvas.isHardwareAccelerated()) {
            original.draw(canvas);
            return;
        }
        try {
            prepareMaterial();
            Rect b = getBounds();
            mapDockToScreen();
            if (screen.width() < 1 || screen.height() < 1 || !toScreen.invert(screenToDock)) return;
            sampledTransform.set(toScreen);
            View root=host.getRootView();
            int rw=root.getWidth(), rh=root.getHeight();
            if (rw<=0 || rh<=0) return;
            if (stream!=null && (rw!=sourceWidth || rh!=windowHeight || (sourceTop>0 && screen.top<sourceTop)))
                releaseStream();
            if (stream == null) {
                sourceWidth=rw;
                windowHeight=rh;
                // Keep a stationary native-resolution wallpaper band across the whole Dock trajectory.
                // If the launcher lays the Dock out above the band, include the full window.
                sourceTop=Math.max(0,rh-3*height);
                if (screen.top<sourceTop) sourceTop=0;
                stream = new DockWallpaperStream(host);
                stream.open(rw,rh-sourceTop,0,sourceTop,1f,1f);
            }
            if (!stream.hasFrame()) {
                original.draw(canvas);
                return;
            }
            node.setPosition(b.left,b.top,b.right,b.bottom);
            node.setAlpha(getAlpha()/255f);
            RecordingCanvas recording = node.beginRecording();
            try {
                recording.concat(screenToDock);
                int save = recording.save();
                recording.translate(0,sourceTop);
                stream.record(recording);
                recording.restoreToCount(save);
                recordWorkspace(recording);
            }
            finally { node.endRecording(); }
            canvas.drawRenderNode(node);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
            original.draw(canvas);
        }
    }

    private void fail(Exception e) {
        failed = true;
        releaseStream();
        Log.w("ChaojiBeauty","Live wallpaper input unavailable for Dock",e);
    }

    @Override protected void onBoundsChange(Rect b) { original.setBounds(b); }
    @Override public boolean getPadding(Rect padding) { return original.getPadding(padding); }
    @Override public int getAlpha() { return original.getAlpha(); }
    @Override public void setAlpha(int alpha) { original.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { original.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    private void releaseStream() {
        node.discardDisplayList();
        if (stream != null) {
            stream.close();
            stream = null;
        }
    }

    void close() {
        closed = true;
        ViewTreeObserver observer = host.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnPreDrawListener(this);
            observer.removeOnWindowFocusChangeListener(this);
        }
        if (removeVisibilityListener != null) removeVisibilityListener.run();
        releaseStream();
    }
}
