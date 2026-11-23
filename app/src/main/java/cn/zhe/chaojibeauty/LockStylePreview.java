package cn.zhe.chaojibeauty;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.os.Bundle;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;

/** Presents the native clock and widget artwork inside the unlocked notification page. */
final class LockStylePreview extends ViewGroup {
    static final class Scenes {
        private static final String COMMAND = "onClockStateChanged";
        private final Map<Object, Bundle> latest = new WeakHashMap<>();
        private final Map<Object, Integer> lastSize = new WeakHashMap<>();

        private boolean enabled, queued, prepared;
        private Method command;
        private Object previewPlugin;
        private final LockClockMaterial lockMaterial;

        Scenes(LockClockMaterial material) { lockMaterial = material; }

        void install(ClassLoader loader) {
            try {
                lockMaterial.install(loader);
                command = loader.loadClass("com.oplus.keyguard.plugin.KeyguardPlugin")
                        .getMethod("sendCommand", String.class, Bundle.class);
                XposedBridge.hookMethod(command, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!COMMAND.equals(p.args[0])) return;
                        Bundle b = (Bundle)p.args[1];
                        if (b == null || !b.containsKey("uiState")) return;
                        synchronized (Scenes.this) {
                            latest.put(p.thisObject, new Bundle(b));
                            queuePreparation();
                            if (b.getInt("uiState") == 2) lastSize.put(p.thisObject, b.getInt("clockSize", 1));
                            if (previewPlugin == p.thisObject && b.getInt("uiState") != 1) previewPlugin = null;
                        }
                    }

                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (p.hasThrowable() || !COMMAND.equals(p.args[0])) return;
                        Bundle b = (Bundle)p.args[1];
                        synchronized (Scenes.this) {
                            if (previewPlugin == p.thisObject && b != null && b.getInt("uiState", -1) == 1)
                                send(p.thisObject, lockAppearance(p.thisObject, b));
                        }
                    }
                });
            } catch (ReflectiveOperationException ex) { Log.w("ChaojiBeauty","Clock scene unavailable",ex); }
        }

        synchronized void configure(boolean on) {
            enabled = on;
            lockMaterial.configure(on);
            queuePreparation();
        }

        void invalidateGlyphs() { lockMaterial.invalidateGlyphs(); }
        void releaseLockSource() { lockMaterial.release(); }
        private synchronized void queuePreparation() {
            if (!enabled || prepared || queued || latest.isEmpty()) return;
            queued = true;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> android.os.Looper.myQueue().addIdleHandler(() -> {
                prepareClock();
                return false;
            }));
        }

        private synchronized void prepareClock() {
            queued = false;
            if (!enabled || prepared) return;
            try {
                for (Object plugin : latest.keySet()) {
                    Object clock = get(plugin, "getClockPlugin");
                    if (clock == null) continue;
                    View host = (View)clock.getClass().getMethod("getView",int.class).invoke(clock,0);
                    View root = findType(host,"ClockViewRoot");
                    if (root == null) continue;
                    RenderEffect[] effects = clockEffects(root);
                    if (effects[0] == null && effects[1] == null) continue;
                    prepared = true;
                    new Thread(() -> prepareEffects(effects), "Prepare shade clock").start();
                    break;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) { /* Optional for other clock themes. */ }
        }

        // Compile the plugin's optical effects without sampling the screen or retaining any picture.
        private static void prepareEffects(RenderEffect[] effects) {
            RenderNode output = new RenderNode("Clock material preparation");
            output.setPosition(0,0,64,32);
            RenderNode[] layers = new RenderNode[effects.length];
            try {
                RecordingCanvas canvas = output.beginRecording();
                for (int i=0; i<effects.length; i++) {
                    if (effects[i]==null) continue;
                    RenderNode layer = layers[i] = new RenderNode("Clock effect");
                    layer.setPosition(i*32,0,(i+1)*32,32);
                    layer.beginRecording().drawColor(0xff71849c);
                    layer.endRecording();
                    layer.setRenderEffect(effects[i]);
                    canvas.drawRenderNode(layer);
                }
                output.endRecording();
                android.graphics.Bitmap sample = (android.graphics.Bitmap)android.graphics.HardwareRenderer.class
                        .getMethod("createHardwareBitmap",RenderNode.class,int.class,int.class).invoke(null,output,64,32);
                if (sample!=null) sample.recycle();
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.w("ChaojiBeauty","Clock material preparation unavailable",e);
            } finally {
                output.discardDisplayList();
                for (RenderNode layer : layers) if (layer!=null) layer.discardDisplayList();
            }
        }

        synchronized Bundle show(Object plugin) {
            Bundle old = latest.get(plugin);
            if (old == null || old.getInt("uiState") != 1) return null;
            if (!send(plugin,lockAppearance(plugin,old))) return null;
            previewPlugin = plugin;
            return old;
        }

        private Bundle lockAppearance(Object plugin, Bundle source) {
            Bundle visual = new Bundle(source);
            visual.putInt("uiState", 2);
            visual.putInt("clockSize", lastSize.containsKey(plugin) ? lastSize.get(plugin) : 1);
            visual.putBoolean("isAnim", false);
            return visual;
        }

        synchronized void restore(Object plugin, Bundle old) {
            // A screen-off or a genuine keyguard transition takes precedence over our preview.
            if (old != null && previewPlugin == plugin) {
                previewPlugin = null;
                Bundle current = latest.get(plugin);
                if (current != null) send(plugin, new Bundle(current));
            }
        }

        private boolean send(Object plugin, Bundle visual) {
            visual.putBoolean("isAnim", false);
            try {
                Object clock = get(plugin, "getClockPlugin");
                if (clock == null) return false;
                clock.getClass().getMethod("sendCommand", String.class, Bundle.class).invoke(clock, COMMAND, visual);
                return true;
            }
            catch (ReflectiveOperationException ex) { Log.w("ChaojiBeauty","Clock scene update failed",ex); return false; }
        }
    }

    private final Scenes scenes;
    private Object stylePlugin;
    private Bundle originalScene;
    private final View source;
    private final ViewGroup panel;
    private final LockCenter.StatusHeader status;
    private final ArrayList<View> pieces = new ArrayList<>();
    private final RenderNode timeLayer = new RenderNode("Lock style time");
    private final RenderNode dateLayer = new RenderNode("Lock style date");
    private View timePiece, datePiece;
    private final ShadeWallpaper wallpaper;
    private final int[] clockOrigin = new int[2];
    private ClockFaceMaterial timeMaterial,dateMaterial;
    private long nextScan, minute = -1;
    private float statusOffset;

    private final ScrollEdge scrollEdge = new ScrollEdge();
    private final android.graphics.drawable.Drawable pinnedStatus = new android.graphics.drawable.Drawable() {
        private final android.graphics.Paint shade = new android.graphics.Paint(android.graphics.Paint.DITHER_FLAG);
        private int gradientHeight;

        @Override public void draw(Canvas c) {
            int saved=c.save();
            c.translate(getX(),getY()+statusOffset);
            int height=edgeHeight();
            if (gradientHeight!=height) {
                // The 24A437 phone status-bar ramp has 31 evenly spaced stops,
                // going from transparent at the bottom to 5% black at the top.
                int[] stops=new int[31];
                for (int i=0;i<stops.length;i++) {
                    float t=1f-i/30f;
                    stops[i]=Math.round(255f*.05f*t*t*(3f-2f*t)) << 24;
                }
                shade.setShader(new android.graphics.LinearGradient(0,0,0,height,stops,null,
                        android.graphics.Shader.TileMode.CLAMP));
                gradientHeight=height;
            }
            shade.setAlpha(Math.round(255f*Math.max(sectionProgress,Math.min(1f,headerScroll/Math.max(1,height)))));
            c.drawRect(0,0,panel.getWidth(),height,shade);
            status.draw(c);
            c.restoreToCount(saved);
        }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    };

    private float sectionProgress, headerScroll;
    void sectionProgress(float value,float offset) {
        if (sectionProgress != value || headerScroll != offset) {
            boolean previous=hasScrolledContent();
            sectionProgress = value;
            headerScroll = offset;
            syncStatusLayer(previous);
            invalidate();
        }
    }
    boolean hasScrolledContent() { return headerScroll > 0 || sectionProgress > 0; }
    float edgeSpread() { return 2f * getResources().getDisplayMetrics().density; }
    int edgeHeight() {
        return NotificationViewport.statusBottom(panel);
    }

    void pinStatusDuringEntry(float y) {
        if (statusOffset != y) { statusOffset = y; invalidate(); }
    }

    private void syncStatusLayer(boolean wasScrolled){
        if (wasScrolled != hasScrolledContent()) {
            if (hasScrolledContent()) {
                pinnedStatus.setBounds(0,0,panel.getWidth(),panel.getHeight());
                panel.getOverlay().add(pinnedStatus);
            } else panel.getOverlay().remove(pinnedStatus);
        }
    }
    private Object clockPlugin;
    private Method readClock, setTime;
    private View clockRoot;

    private final WidgetAccessoryLayout accessories;
    private final Matrix widgetTransform=new Matrix(), widgetInverse=new Matrix();
    private final float[] tapPoint=new float[2];
    LockStylePreview(ViewGroup panel, View source, Scenes scenes, LockCenter.StatusHeader status, ShadeWallpaper wallpaper, WidgetAccessoryLayout accessories) {
        super(panel.getContext());
        this.accessories=accessories;
        this.panel = panel;
        this.source = source;
        this.scenes = scenes;
        this.status = status;
        this.wallpaper=wallpaper;
        try {
            timeMaterial=new ClockFaceMaterial(false);
            dateMaterial=new ClockFaceMaterial(true);
        } catch (RuntimeException e) { Log.w("ChaojiBeauty","Shade clock material unavailable",e); }
        setWillNotDraw(false);
        if (source != null) {
            try {
                clockPlugin = get(source, "getClockPlugin");
                readClock = clockPlugin.getClass().getMethod("getView", int.class);
                setTime = clockPlugin.getClass().getMethod("setTime", long.class);
                stylePlugin=get(source,"getKeyguardPlugin");
                originalScene=scenes.show(stylePlugin);
            } catch (ReflectiveOperationException e) { Log.w("ChaojiBeauty","Lock clock unavailable",e); }
        }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        scan();
    }

    void finish() {
        sectionProgress(0,0);
        panel.getOverlay().remove(pinnedStatus);
        scrollEdge.release();
        scenes.restore(stylePlugin,originalScene);
        timeLayer.discardDisplayList();
        dateLayer.discardDisplayList();
    }

    private static Object get(Object obj, String name) throws ReflectiveOperationException {
        return obj == null ? null : obj.getClass().getMethod(name).invoke(obj);
    }

    private static Object field(Object obj, String name) throws ReflectiveOperationException {
        if (obj == null) return null;
        for (Class<?> type = obj.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) { /* Look in the base controller. */ }
        }
        throw new NoSuchFieldException(name);
    }

    static RenderEffect[] clockEffects(View root) {
        RenderEffect digits = null, date = null;
        try {
            Object color = field(root,"colorController");
            Object glass = field(field(color,"mixColorController"),"glassEffectHelper");
            Map<?,?> effects = (Map<?,?>)field(glass,"effectMap");
            if (effects != null) {
                digits = (RenderEffect)get(effects.get(get(root,"getClockTimeViewMount")),"getRenderEffect");
                date = (RenderEffect)get(effects.get(get(root,"getDateMessageViewMount")),"getRenderEffect");
            }
        } catch (ReflectiveOperationException ignored) { /* Solid-color clock themes draw directly. */ }
        return new RenderEffect[]{digits, date};
    }

    static View findType(View v, String type) {
        if (v == null || v.getClass().getSimpleName().equals(type)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup)v;
            for (int i=0; i<parent.getChildCount(); i++) {
                View match = findType(parent.getChildAt(i), type);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static Object activeClock(View root) throws ReflectiveOperationException {
        Object container = get(root, "getClockContainer");
        Object single = get(container, "getBaselineSingleClockView");
        try {
            Map<?,?> groups = (Map<?,?>)get(root, "getRenderedViewStateByGroup");
            Map<?,?> views = (Map<?,?>)get(container,"getSceneViewMap");
            if (groups != null && views != null) for (Map.Entry<?,?> group : groups.entrySet()) {
                if (!"KEYGUARD".equals(String.valueOf(group.getKey()))) continue;
                Object entry = views.get(get(group.getValue(), "getScene"));
                Object active = get(entry, "getSingleClockView");
                if (active != null) single = active;
                break;
            }
        } catch (ReflectiveOperationException ignored) { /* Use the plugin's baseline clock. */ }
        return single;
    }

    static android.widget.TextView titleInkSource(View host) {
        try {
            Object plugin = get(host, "getClockPlugin");
            if (plugin == null) return null;
            View tree = (View)plugin.getClass().getMethod("getView", int.class).invoke(plugin, 0);
            View root = findType(tree, "ClockViewRoot");
            // The date glyph is a mask when the clock plugin composites a glass material.
            if (root == null || clockEffects(root)[1] != null) return null;
            Object single = activeClock(root);
            View date = (View)get(single, "getDateMessageView");
            if (date != null) {
                Object label = field(date, "dateTextView");
                if (label instanceof android.widget.TextView) return (android.widget.TextView)label;
            }
        } catch (ReflectiveOperationException | RuntimeException unsupported) { /* Other clock themes. */ }
        return null;
    }

    void contentLayoutChanged() {
        nextScan=0;
        invalidate();
    }

    private void scan() {
        pieces.clear();
        if (readClock != null) {
            try {
                clockRoot = (View)readClock.invoke(clockPlugin, 0);
                View root = findType(clockRoot, "ClockViewRoot");
                Object single = activeClock(root);
                timePiece = (View)get(single, "getClockTimeView");
                datePiece = (View)get(single, "getDateMessageView");
                add(timePiece);
                add(datePiece);
                RenderEffect[] effects = clockEffects(root);
                timeLayer.setRenderEffect(effects[0]);
                dateLayer.setRenderEffect(effects[1]);
            } catch (ReflectiveOperationException ignored) { /* Other clock plugins may expose only the root. */ }
            if (pieces.isEmpty()) add(clockRoot);
        }
        collectWidgets(findType(source,"WidgetPluginContainer"), true);
        View card=ClockFaceMaterial.firstCard(source);
        if (timeMaterial!=null) timeMaterial.anchor(card,source);
        if (dateMaterial!=null) dateMaterial.anchor(card,source);
        nextScan = SystemClock.uptimeMillis() + 2000;
    }

    private void add(View v) { if (v != null && v.getWidth()>0 && v.getHeight()>0) pieces.add(v); }

    private void collectWidgets(View v, boolean root) {
        if (v == null || (!root && v.getVisibility()!=VISIBLE)) return;
        if (v.getClass().getSimpleName().equals("WidgetItemContainer")) {
            View card = findType(v, "InstantCardView");
            if (card == null || card.getVisibility()!=VISIBLE) return;
            float x = offset(v,true), y = offset(v,false);
            if (x < panel.getWidth() && x + v.getWidth()>0 && y>=0 && y<panel.getHeight()) add(v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup parent=(ViewGroup)v;
            for (int i=0;i<parent.getChildCount();i++) collectWidgets(parent.getChildAt(i),false);
        }
    }


    int statusBottomInWindow(int[] origin){return status.statusBottomInWindow(origin);}

    int headerHeight(int otherChrome) {
        if (timePiece!=null) {
            int ink=timeMaterial==null?0:timeMaterial.contentBottom(timePiece,source);
            if (dateMaterial!=null) ink=Math.max(ink,dateMaterial.contentBottom(datePiece,source));
            int boundary=accessories.headerBottom(source,findType(source,"WidgetPluginContainer"),ink);
            if (boundary>0) return boundary-otherChrome;
        }
        float bottom=0;
        // The digital time and date containers span the entire display even for a small clock.
        for (View v : pieces) if (v.getClass().getSimpleName().equals("WidgetItemContainer")) {
            bottom = Math.max(bottom, offset(v,false)+v.getHeight());
        }
        if (bottom==0) for (View v : pieces) {
            if (!v.getClass().getSimpleName().equals("ClockTimeView")) continue;
            try {
                View hour = (View)get(v,"getTimeHourFirstTextView");
                if (hour != null) bottom = offset(hour,false) + hour.getHeight();
            } catch (ReflectiveOperationException ignored) { /* Use the compact header below. */ }
        }
        if (bottom==0) bottom=NotificationViewport.statusBottom(panel);
        return Math.round(bottom)+NotificationViewport.dp(this,NotificationViewport.HEADER_GAP)-otherChrome;
    }

    private float offset(View child, boolean horizontal) {
        boolean clock=child==timePiece || child==datePiece;
        float value = 0;
        for (View v = child; v != source && v != clockRoot && v.getParent() instanceof View; v = (View)v.getParent()) {
            View p = (View)v.getParent();
            value += horizontal ? v.getX() - p.getScrollX() : (clock?v.getTop():v.getY()) - p.getScrollY();
        }
        return value;
    }

    private final int[] screen = new int[2];
    private final android.graphics.RectF widgetBounds = new android.graphics.RectF();
    private View tapTarget;
    private float downX,downY;

    private View widgetAt(float rawX,float rawY) {
        getLocationOnScreen(screen);
        float x=rawX-screen[0], y=rawY-screen[1];
        if (y < status.row.getHeight()) return null;
        for (View v : pieces) {
            if (!"WidgetItemContainer".equals(v.getClass().getSimpleName())) continue;
            View card=findType(v,"InstantCardView");
            if (card==null || !card.isAttachedToWindow() || card.getVisibility()!=VISIBLE) continue;
            widgetBounds.set(0,0,card.getWidth(),card.getHeight());
            card.getMatrix().mapRect(widgetBounds);
            widgetBounds.offset(offset(card,true)-offset(v,true)-card.getTranslationX(),
                    offset(card,false)-offset(v,false)-card.getTranslationY());
            accessories.previewTransform(v,widgetTransform);
            widgetTransform.mapRect(widgetBounds);
            widgetBounds.offset(offset(v,true),offset(v,false)-headerScroll);
            if (widgetBounds.contains(x,y)) return v;
        }
        return null;
    }

    boolean isWidgetTap(android.view.MotionEvent e) {
        if (hasScrolledContent()) { tapTarget=null; return false; }
        int action=e.getActionMasked();
        float x=e.getRawX(), y=e.getRawY();
        if (action==android.view.MotionEvent.ACTION_DOWN) {
            tapTarget=widgetAt(x,y);
            downX=x; downY=y;
            return false;
        }
        if (tapTarget==null) return false;
        int slop=android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
        if (e.getPointerCount()!=1 || action==android.view.MotionEvent.ACTION_CANCEL ||
                Math.hypot(x-downX,y-downY)>slop || e.getEventTime()-e.getDownTime()>=android.view.ViewConfiguration.getLongPressTimeout()) {
            tapTarget=null;
            return false;
        }
        if (action!=android.view.MotionEvent.ACTION_UP) return false;
        if (widgetAt(x,y)==tapTarget) return true;
        tapTarget=null;
        return false;
    }

    boolean activateWidget(android.view.MotionEvent up) {
        View target=tapTarget;
        tapTarget=null;
        if (target==null || !target.isAttachedToWindow()) return false;
        getLocationOnScreen(screen);
        float x=up.getRawX()-screen[0]-offset(target,true);
        float y=up.getRawY()-screen[1]+headerScroll-offset(target,false);
        accessories.previewTransform(target,widgetTransform);
        if (!widgetTransform.invert(widgetInverse)) return false;
        tapPoint[0]=x; tapPoint[1]=y;
        widgetInverse.mapPoints(tapPoint);
        x=tapPoint[0]; y=tapPoint[1];
        android.view.MotionEvent down=android.view.MotionEvent.obtain(up.getDownTime(),up.getDownTime(),
                android.view.MotionEvent.ACTION_DOWN,x,y,0);
        android.view.MotionEvent release=android.view.MotionEvent.obtain(up);
        release.setLocation(x,y);
        down.setSource(up.getSource());
        try {
            boolean accepted=target.dispatchTouchEvent(down);
            boolean handled=target.dispatchTouchEvent(release);
            return accepted && handled;
        } finally { down.recycle(); release.recycle(); }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // The plugin owns layout and the views remain attached to it.
    }

    @Override protected void onDraw(Canvas c) {
        if (SystemClock.uptimeMillis() >= nextScan) scan();
        long now = System.currentTimeMillis();
        if (setTime != null && now / 60000L != minute) {
            minute = now / 60000L;
            try { setTime.invoke(clockPlugin, now); }
            catch (ReflectiveOperationException failure) { setTime = null; }
        }
        Canvas output = c;
        boolean filter = hasScrolledContent() && c.isHardwareAccelerated();
        if (filter) c = scrollEdge.begin(getWidth(),getHeight(),edgeHeight(),0,edgeSpread());
        int save = sectionProgress == 0 ? c.save()
                : c.saveLayerAlpha(0,0,getWidth(),getHeight(),Math.round(255*(1-sectionProgress)));
        c.translate(0,-headerScroll);
        for (View piece : pieces) {
            RenderNode layer = piece == timePiece ? timeLayer : piece == datePiece ? dateLayer : null;
            ClockFaceMaterial material = piece == timePiece ? timeMaterial : piece == datePiece ? dateMaterial : null;
            if (material!=null) {
                float x=offset(piece,true), y=offset(piece,false);
                getLocationOnScreen(clockOrigin);
                int local=c.save();
                c.translate(x,y);
                try {
                    if (material.draw(c,piece,clockOrigin[0]+x,clockOrigin[1]+y-headerScroll,clockOrigin[1]+y,wallpaper,false,1f)) continue;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    Log.w("ChaojiBeauty","Shade clock rendering unavailable",e);
                    if (piece==timePiece) timeMaterial=null; else dateMaterial=null;
                } finally { c.restoreToCount(local); }
            }
            if (layer != null && c instanceof RecordingCanvas) {
                layer.setPosition(0, 0, getWidth(), getHeight());
                RecordingCanvas target = layer.beginRecording();
                try { drawPiece(target, piece); } finally { layer.endRecording(); }
                ((RecordingCanvas)c).drawRenderNode(layer);
            } else drawPiece(c, piece);
        }
        c.restoreToCount(save);
        if (filter) scrollEdge.end(output);
        if (hasScrolledContent()) pinnedStatus.invalidateSelf();
        else {
            int statusSave = output.save();
            output.translate(0, statusOffset);
            status.draw(output);
            output.restoreToCount(statusSave);
        }
        postInvalidateDelayed(Math.min(1000L, 60000L - now % 60000L));
    }

    private void drawPiece(Canvas c, View piece) {
        int saved = c.save();
        c.translate(offset(piece,true),offset(piece,false));
        if ("WidgetItemContainer".equals(piece.getClass().getSimpleName())) {
            accessories.previewTransform(piece,widgetTransform);
            c.concat(widgetTransform);
            View card=findType(piece,"InstantCardView");
            if (card!=null && card.getParent()==piece) {
                // AOD can move the source card beyond its lock-screen cell. Draw
                // the live card in the preview, before the cell clips its contents.
                c.translate(-piece.getScrollX(),-piece.getScrollY());
                drawChild(c,card,getDrawingTime());
                c.restoreToCount(saved);
                return;
            }
        }
        c.translate(-piece.getX(),-piece.getY());
        drawChild(c,piece,getDrawingTime());
        c.restoreToCount(saved);
    }
}

