package cn.zhe.chaojibeauty;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Replaces the native notification fill at its draw call. Click, mask and content passes stay native. */
final class NotificationGlass {
    private final Map<View, Face[]> cards = new WeakHashMap<>();
    private final Set<View> headers = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<View> waitingHeaders = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<View> menuButtons = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<View> glassActivityRows = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<View, Float> activityLights = new WeakHashMap<>();
    private final Set<Method> hookedDraws = new HashSet<>();
    private Class<?> rowType, customRowType, groupType, listType;
    private Method bgView, ordinary, groupPath, tempDrawable;
    private boolean enabled, installed;
    private long retryAfter;
    private NotificationMaterial material;
    private android.app.WallpaperManager wallpaperColors;
    private boolean brightWallpaper;

    private void readWallpaperContrast(){
        android.app.WallpaperColors colors=wallpaperColors.getWallpaperColors(android.app.WallpaperManager.FLAG_LOCK);
        if(colors==null)colors=wallpaperColors.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
        brightWallpaper=colors!=null && ((colors.getColorHints() & android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT)!=0
                || Color.luminance(colors.getPrimaryColor().toArgb())>.44f);
    }

    NotificationMaterial.Palette palette(View owner){
        if(wallpaperColors==null){
            wallpaperColors=owner.getContext().getSystemService(android.app.WallpaperManager.class);
            if(wallpaperColors!=null){
                readWallpaperContrast();
                wallpaperColors.addOnColorsChangedListener((colors,which)->{
                    readWallpaperContrast();
                    invalidateCards();
                },new android.os.Handler(Looper.getMainLooper()));
            }
        }
        return NotificationMaterial.palette(GlassAppearance.dark(owner.getRootView()),brightWallpaper);
    }
    private View sourceRoot, backdrop;
    private View enteringRoot, shadeBackdrop;
    private ShadeWallpaper lockPaper;
    private int lockPaperAlpha;
    private View expandedLockHost;
    private ShadeWallpaper expandedLockPaper;
    private long expandedPaperRetry;

    void expandedKeyguard(View host,float progress){
        View root=progress>0 && host!=null?host.getRootView():null;
        if(expandedLockHost!=root){
            expandedLockHost=root;
            closeExpandedPaper();
            invalidateCards();
        }
        if(!enabled || root==null || !root.isAttachedToWindow())return;
        // The keyguard status view can detach when history takes over. Keep the
        // notification material's wallpaper input available for that interval.
        if(lockPaper!=null && lockPaper.hasFrame()){
            closeExpandedPaper();
            return;
        }
        if(expandedLockPaper!=null && expandedLockPaper.unavailable()){
            closeExpandedPaper();
            expandedPaperRetry=android.os.SystemClock.uptimeMillis()+5000;
        }
        if(expandedLockPaper==null && lockPaper!=null && !lockPaper.unavailable())return;
        if(expandedLockPaper==null && root.getWidth()>0 && root.getHeight()>0
                && android.os.SystemClock.uptimeMillis()>=expandedPaperRetry){
            expandedLockPaper=new ShadeWallpaper(root,this::invalidateCards);
            expandedLockPaper.setBounds(0,0,root.getWidth(),root.getHeight());
            expandedLockPaper.open();
        }
    }

    private void closeExpandedPaper(){
        ShadeWallpaper previous=expandedLockPaper;
        expandedLockPaper=null;
        if(previous!=null)previous.close();
    }
    boolean isEnabled() { return enabled; }
    void lockWallpaperFrame(ShadeWallpaper paper,float glassAmount) {
        lockPaper = paper;
        lockPaperAlpha = Math.round(255f*glassAmount);
        if (enabled && shadeBackdrop==null && enteringRoot==null) {
            invalidateCards();
            if (lockList!=null) lockList.invalidate();
        }
    }
    private View lockList;
    void setShadeBackdrop(View view) {
        shadeBackdrop = view;
        shadeNode = null;
        for (Face[] faces : cards.values()) {
            if (faces != null) for (Face face : faces) if (face != null) face.movingShade = false;
        }
        invalidateCards();
    }
    private RenderNode shadeNode;
    private final RenderNode windowFrame = new RenderNode("Live notification underlay");
    private final Paint windowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Field renderNode, boundsExtension, blendMode;
    private Method resolveMeta, metaEffective;
    private ViewTreeObserver observer;
    private final ViewTreeObserver.OnPreDrawListener beforeFrame=this::updatePositions;
    private final Matrix global=new Matrix(), inverse=new Matrix(), parentToScreen=new Matrix();
    private boolean recordingMask;
    private final RectF bounds = new RectF();
    private Scope current;

    private static final class Face {
        final RenderNode node = new RenderNode("Notification liquid glass");
        final RenderNode mask = new RenderNode("Original notification fill");
        Face() { NotificationMaterial.useSourceIn(node); }
        int width, height;
        int sampleLeft, sampleTop;
        boolean liveWindow, dark, movingShade, selected;
        final Matrix sampledAt = new Matrix();
        boolean keyguard, frosted;
        RenderNode softBackdrop;
        float density;
        int blurMargin;
    }
    private static final class Scope {
        View view;
        Drawable base, blur, tint, immersive;
        boolean replaced;
    }

    void install(ClassLoader cl) throws ReflectiveOperationException {
        renderNode=View.class.getDeclaredField("mRenderNode");
        renderNode.setAccessible(true);
        String prefix = "com.android.systemui.statusbar.notification.";
        rowType = cl.loadClass(prefix + "row.ExpandableNotificationRow");
        listType = cl.loadClass(prefix + "stack.NotificationStackScrollLayout");
        groupType = cl.loadClass("com.oplus.systemui.statusbar.notification.view.GroupOverlayContainerView");
        Class<?> ext = cl.loadClass("com.oplus.systemui.statusbar.notification.row.NotificationBackgroundViewExtImp");
        Class<?> bg = cl.loadClass(prefix + "row.NotificationBackgroundView");
        Class<?> proxy = cl.loadClass("com.oplusos.systemui.common.blurability.ViewBlurProxy");
        Class<?> auto = cl.loadClass("com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable");
        resolveMeta=cl.loadClass("com.oplus.systemui.notification.row.NotificationMenuRowProviderKt")
                .getMethod("tryGetMetaBallBlurDrawable",Drawable.class);
        metaEffective=resolveMeta.getReturnType().getMethod("isMetaBallEffective");
        boundsExtension=resolveMeta.getReturnType().getField("boundsExtension");
        blendMode=resolveMeta.getReturnType().getField("blendMode");
        bgView = ext.getMethod("getBgView");
        ordinary = ext.getMethod("noColorized");
        try {
            tempDrawable = ext.getMethod("getTempDrawable");
            customRowType = cl.loadClass("com.oplus.systemui.statusbar.notification.customcard.OplusCustomRow");
        } catch (ReflectiveOperationException e) {
            Log.w("ChaojiBeauty","Live activity background unavailable",e);
        }
        if (customRowType!=null) {
            try { installActivityLights(); }
            catch (ReflectiveOperationException e) { Log.w("ChaojiBeauty","Activity template lights unavailable",e); }
        }
        groupPath=groupType.getSuperclass().getDeclaredMethod("getRoundClipPath");
        groupPath.setAccessible(true);
        XC_MethodHook register = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                View card = (View)p.thisObject;
                // ColorOS pre-inflates rows on a worker. Register them on the UI thread
                // that iterates the same weak map in onPreDraw.
                if (Looper.myLooper() == Looper.getMainLooper()) cards.putIfAbsent(card, null);
                else card.post(() -> cards.putIfAbsent(card, null));
            }
        };
        XposedBridge.hookAllConstructors(bg, register);
        XposedBridge.hookAllConstructors(groupType, register);
        Class<?> menu=cl.loadClass("com.oplus.systemui.notification.row.NotificationMenuRowExtImpl");
        XposedBridge.hookMethod(menu.getMethod("getMenuItemBackground",
                android.content.res.Resources.class,View.class),new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                if(!(p.getResult() instanceof Drawable)) return;
                View v=(View)p.args[1];
                Drawable drawable=(Drawable)p.getResult();
                if (Looper.myLooper() == Looper.getMainLooper()) registerMenu(v,drawable);
                else v.post(() -> {
                    try { registerMenu(v,drawable); }
                    catch (ReflectiveOperationException e) { Log.w("ChaojiBeauty","Menu glass unavailable",e); }
                });
            }
        });
        XposedBridge.hookMethod(ext.getMethod("draw",Canvas.class,Drawable.class),new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                if (!enabled || Looper.myLooper()!=Looper.getMainLooper()) return;
                p.setObjectExtra("outer",current);
                p.setObjectExtra("entered",Boolean.TRUE);
                current=null;
                View v=(View)bgView.invoke(p.thisObject);
                // The native panoramic overlay is a separate, progress-driven draw
                // above this fill. Keep the same underlying face throughout wake.
                boolean custom=customRowType!=null && customRowType.isInstance(v.getParent());
                if (!custom && !(rowType.isInstance(v.getParent()) && (boolean)ordinary.invoke(p.thisObject))) return;
                Scope s=new Scope();
                s.view=v; s.base=(Drawable)p.args[1];
                watchDraw(s.base);
                if (custom) {
                    s.immersive=(Drawable)tempDrawable.invoke(p.thisObject);
                    watchDraw(s.immersive);
                }
                current=s;
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if(p.getObjectExtra("entered")==null) return;
                Scope drawn=current;
                current=(Scope)p.getObjectExtra("outer");
                if(drawn!=null && customRowType!=null && customRowType.isInstance(drawn.view.getParent())) {
                    View row=(View)drawn.view.getParent();
                    boolean changed=drawn.replaced ? glassActivityRows.add(row) : glassActivityRows.remove(row);
                    if(changed) updateActivityLights(row,drawn.replaced);
                }
            }
        });
        XposedBridge.hookMethod(proxy.getMethod("getBlurDrawable",Drawable.class),selectDrawable(false));
        Method tint=ext.getDeclaredMethod("getMaterialColorDrawable");
        XposedBridge.hookMethod(tint,selectDrawable(true));
        XposedBridge.hookMethod(auto.getMethod("draw",Canvas.class),new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                Drawable d=(Drawable)p.thisObject;
                if(Looper.myLooper()!=Looper.getMainLooper() || recordingMask || !enabled || !groupType.isInstance(d.getCallback())) return;
                View v=(View)d.getCallback();
                bounds.set(d.getBounds());
                Path clip=(Path)groupPath.invoke(v);
                if(paint(v,(Canvas)p.args[0],bounds,d,clip,0,null,0)) {
                    IconModule.firstDraw("glass group");
                    p.setResult(null);
                }
            }
        });
        try { installLockScale(cl); }
        catch (ReflectiveOperationException e) { Log.w("ChaojiBeauty","Panoramic notification scale unavailable",e); }
        installed=true;
    }

    private void installActivityLights() throws ReflectiveOperationException {
        XposedBridge.hookMethod(customRowType.getDeclaredMethod("onLayout",boolean.class,int.class,
                int.class,int.class,int.class),new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                View row=(View)p.thisObject;
                if (enabled && glassActivityRows.contains(row)) updateActivityLights(row,true);
            }
        });
    }

    private final View.OnAttachStateChangeListener lightAttachment=new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) {}
        @Override public void onViewDetachedFromWindow(View v) { restoreLight(v); }
    };

    private void restoreLight(View v) {
        Float original=activityLights.remove(v);
        if (original!=null) {
            if (v.getTransitionAlpha()==0f) v.setTransitionAlpha(original);
            v.removeOnAttachStateChangeListener(lightAttachment);
        }
    }

    private void updateActivityLights(View v, boolean hasGlass) {
        // These two plugin views are full-card light passes. Album art, transport
        // controls and parcel content are siblings, with their own native animations.
        String type=v.getClass().getName();
        if (type.equals("com.oplus.systemui.plugins.shared.template.page.view.PageBackgroundView")
                || type.equals("com.oplus.systemui.plugins.shared.template.component.media.view.MediaBackGroundView")) {
            if (hasGlass) {
                if (!activityLights.containsKey(v)) {
                    activityLights.put(v,v.getTransitionAlpha());
                    v.addOnAttachStateChangeListener(lightAttachment);
                }
                if (v.getTransitionAlpha()!=0f) v.setTransitionAlpha(0f);
            } else restoreLight(v);
        } else if (v instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)v;
            for (int i=0;i<group.getChildCount();i++) updateActivityLights(group.getChildAt(i),hasGlass);
        }
    }

    private void installLockScale(ClassLoader cl) throws ReflectiveOperationException {
        String controller="com.oplus.systemui.keyguard.anim.OplusPanelKeyguardAnimControllerImpl";
        Class<?> scene=cl.loadClass(controller+"$PanelSceneAnim");
        Class<?> panoramic=cl.loadClass(controller+"$PanoramicScreenAnim");
        Class<?> fromApp=cl.loadClass(controller+"$PanoramicScreenOffAnimFromUnlock");
        Field scale=cl.loadClass(controller).getDeclaredField("_notificationAnimScale");
        Field wakeOwner=panoramic.getDeclaredField("this$0"), sleepOwner=fromApp.getDeclaredField("this$0");
        scale.setAccessible(true);
        wakeOwner.setAccessible(true);
        sleepOwner.setAccessible(true);
        Method animate=scene.getDeclaredMethod("anim",cl.loadClass("kotlinx.coroutines.flow.MutableStateFlow"),
                float.class,float.class,int.class,int.class,android.animation.TimeInterpolator.class);
        XposedBridge.hookMethod(animate,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws IllegalAccessException {
                if (!enabled) return;
                Field owner=panoramic.isInstance(p.thisObject) ? wakeOwner : fromApp.isInstance(p.thisObject) ? sleepOwner : null;
                if (owner!=null && p.args[0]==scale.get(owner.get(p.thisObject)) && (float)p.args[2]==.90f)
                    p.args[2]=WidgetAccessoryLayout.componentScale(.90f);
            }
        });
    }

    private XC_MethodHook selectDrawable(boolean tint) {
        return new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                if (Looper.myLooper()!=Looper.getMainLooper() || recordingMask || current==null || !(p.getResult() instanceof Drawable)) return;
                Drawable d=(Drawable)p.getResult();
                if (tint) current.tint=d; else current.blur=d;
                watchDraw(d);
            }
        };
    }

    private void registerMenu(View v, Drawable drawable) throws ReflectiveOperationException {
        menuButtons.add(v);
        cards.putIfAbsent(v,null);
        watchDraw(drawable);
    }

    private void watchDraw(Drawable drawable) throws ReflectiveOperationException {
        if (drawable==null) return;
        Method draw=drawable.getClass().getMethod("draw",Canvas.class);
        if (!hookedDraws.add(draw)) return;
        XposedBridge.hookMethod(draw,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                Scope s=current;
                if(Looper.myLooper()!=Looper.getMainLooper() || recordingMask || !enabled) return;
                Drawable d=(Drawable)p.thisObject;
                if(d.getCallback() instanceof View){
                    View owner=(View)d.getCallback();
                    if(menuButtons.contains(owner) && owner.getBackground()==d){
                        bounds.set(d.getBounds());
                        if(paint(owner,(Canvas)p.args[0],bounds,d,null,0,null,0)){
                            p.setResult(null);
                            IconModule.firstDraw("glass swipe menu");
                        }
                        return;
                    }
                }
                if(s==null) return;
                if(d!=s.base && d!=s.blur && d!=s.tint && d!=s.immersive) return;
                if(s.replaced) { p.setResult(null); return; }
                bounds.set(d.getBounds());
                if(paint(s.view,(Canvas)p.args[0],bounds,d,null,0,null,0)){
                    s.replaced=true;
                    p.setResult(null);
                    IconModule.firstDraw(customRowType!=null && customRowType.isInstance(s.view.getParent())
                            ? "glass live activity" : "glass notification");
                }
            }
        });
    }

    void configure(boolean on) {
        if (!installed || enabled==on) return;
        enabled=on;
        retryAfter=0;
        if(!on){closeSource();closeExpandedPaper();expandedLockHost=null;}
        invalidateCards();
        if (!on) {
            glassActivityRows.clear();
            for (View v:new ArrayList<>(activityLights.keySet())) restoreLight(v);
        }
    }
    private static void refreshHeader(View v) {
        Drawable background = v.getBackground();
        if (background instanceof HistoryClearControl) background.invalidateSelf();
    }

    private void invalidateCards(){
        for (View v : headers) if (v.isAttachedToWindow()) refreshHeader(v);
        for(View v:cards.keySet()) if(v!=null && v.isAttachedToWindow() && v.isShown()) v.invalidate();
    }
    void enterWindow(View root) {
        enteringRoot = root;
        if (root==null) clearWindowFrame();
        invalidateCards();
    }

    boolean hasWindowFrame() { return windowFrame.hasDisplayList(); }

    void setWindowFrame(Bitmap frame, int left, int top, float scale) {
        if (!enabled || enteringRoot==null) return;
        int width=Math.round(frame.getWidth()/scale), height=Math.round(frame.getHeight()/scale);
        windowFrame.setPosition(left,top,left+width,top+height);
        RecordingCanvas c=windowFrame.beginRecording();
        try {
            c.drawBitmap(frame,null,new Rect(0,0,width,height),windowPaint);
        } finally { windowFrame.endRecording(); }
    }

    void clearWindowFrame() { windowFrame.discardDisplayList(); }

    private boolean inShade(View v){
        if(listType.isInstance(v))return true;
        for(ViewParent p=v.getParent();p!=null;p=p.getParent()) if(listType.isInstance(p)) return true;
        return false;
    }

    boolean paintHeader(View v, Canvas canvas, RectF rect, float radius, Paint mask) {
        headers.add(v);
        boolean drawn = paintRear(v,canvas,rect,radius,mask,0);
        if (drawn || !enabled) waitingHeaders.remove(v);
        else waitingHeaders.add(v);
        return drawn;
    }

    private final Set<View> quickActions = Collections.newSetFromMap(new WeakHashMap<>());

    boolean paintQuickAction(View host, Canvas canvas, RectF rect, Paint mask) {
        quickActions.add(host);
        return paint(host,canvas,rect,null,null,rect.width()*.5f,mask,0);
    }

    void drawStackLayer(View host,Canvas canvas,RectF rect,float radius,Paint mask,int layer){
        mask.setStyle(Paint.Style.FILL);
        mask.setColor(android.graphics.Color.WHITE);
        if(!paintRear(host,canvas,rect,radius,mask,layer)){
            mask.setColor(GlassAppearance.dark(host.getRootView())?0x1f6b7a8f:0x1fadbdd1);
            canvas.drawRoundRect(rect,radius,radius,mask);
        }
    }

    boolean paintRear(View v, Canvas canvas, RectF rect, float radius, Paint original, int layer) {
        return paint(v,canvas,rect,null,null,radius,original,layer);
    }

    private boolean paint(View v, Canvas canvas, RectF rect, Drawable original, Path nativeClip,
            float rearRadius, Paint rearPaint, int layer) {
        if(!enabled || !canvas.isHardwareAccelerated() || rect.isEmpty() || v.getDisplay()==null || (!quickActions.contains(v) && !inShade(v))) return false;
        try {
            if(!ensureSource(v)) return false;
            RenderNode background=(RenderNode)renderNode.get(backdrop);
            boolean lockScreen=(expandedLockHost==v.getRootView() || enteringRoot==null && shadeBackdrop==null)
                    && v.getContext().getSystemService(android.app.KeyguardManager.class).isKeyguardLocked();
            if(!lockScreen && (background==null || !background.hasDisplayList())) return false;
            // ColorOS expands its inner drawable beyond its wrapper while forming a liquid join.
            // Record that full contour for both the mask and the optics.
            if(original!=null && nativeClip==null){
                Object meta=resolveMeta.invoke(null,original);
                if(meta!=null && meta!=original && (boolean)metaEffective.invoke(meta)){
                    int extension=boundsExtension.getInt(meta);
                    if(blendMode.getInt(meta)==0) rect.inset(-extension,0);
                    else if(blendMode.getInt(meta)==1) rect.inset(0,-extension);
                }
            }
            int x=(int)Math.floor(rect.left), y=(int)Math.floor(rect.top);
            int w=(int)Math.ceil(rect.right)-x, h=(int)Math.ceil(rect.bottom)-y;
            if(w<1 || h<1) return false;
            Face[] faces=cards.get(v);
            if(faces==null) {faces=new Face[3];cards.put(v,faces);}
            Face f=faces[layer];
            if(f==null) faces[layer]=f=new Face();
            boolean liveWindow = lockScreen || (enteringRoot != null && sourceRoot == enteringRoot);
            boolean dark = GlassAppearance.dark(v.getRootView());
            boolean button=quickActions.contains(v);
            boolean selected=button && v.isActivated();
            boolean frosted=!button && !headers.contains(v);
            float density=v.getResources().getDisplayMetrics().density;
            if(f.frosted!=frosted || f.density!=density || f.dark!=dark || f.width!=w || f.height!=h || liveWindow!=f.liveWindow || selected!=f.selected || f.keyguard!=lockScreen){
                if(frosted && (f.softBackdrop==null || density!=f.density)){
                    if(f.softBackdrop==null)f.softBackdrop=new RenderNode("Notification frosted backdrop");
                    float radius=NotificationViewport.pixels(v,14);
                    f.softBackdrop.setRenderEffect(RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP));
                    f.blurMargin=(int)Math.ceil(2*radius);
                }
                f.frosted=frosted;
                f.density=density;
                f.selected=selected;
                f.node.setRenderEffect(material.resize(w,h,density,layer,liveWindow,dark,button,selected,lockScreen,frosted));
                f.dark=dark;
                f.keyguard=lockScreen;
                f.liveWindow=liveWindow;
                f.width=w; f.height=h;
            }
            global.reset();
            v.transformMatrixToGlobal(global);
            if(!global.invert(inverse)) return false;
            f.sampledAt.set(global);
            // The atlas contains the shade backdrop, its native mask and optionally the live application.
            f.mask.setPosition(0,0,w,h);
            RecordingCanvas nativeCanvas=f.mask.beginRecording();
            try {
                nativeCanvas.translate(-x,-y);
                // The view applies its outline at composition, including menu transitions.
                if(original!=null){
                    // ColorOS crossfades two fills inside one custom row. The detached
                    // immersive drawable supplies only its contour for our single face.
                    boolean immersive=current!=null && original==current.immersive && original.getCallback()==null;
                    int alpha=immersive ? original.getAlpha() : 255;
                    recordingMask=true;
                    try {
                        if(alpha!=255) original.setAlpha(255);
                        original.draw(nativeCanvas);
                    } finally {
                        if(alpha!=255) original.setAlpha(alpha);
                        recordingMask=false;
                    }
                } else {
                    nativeCanvas.drawRoundRect(rect,rearRadius,rearRadius,rearPaint);
                }
            } finally { f.mask.endRecording(); }
            f.sampleLeft = x;
            f.sampleTop = y;
            f.movingShade = liveWindow && backdrop == shadeBackdrop && nativeClip == null && !lockScreen;
            if (f.movingShade) shadeNode = background;
            recordInputs(f, background, nativeClip, lockScreen);
            int save=canvas.save();
            try {
                canvas.translate(x,y);
                canvas.clipRect(0,0,w,h);
                NotificationMaterial.composite(canvas,f.mask,f.node,w,h);
            } finally { canvas.restoreToCount(save); }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e){
            closeSource();
            retryAfter=SystemClock.uptimeMillis()+5000;
            Log.w("ChaojiBeauty","Notification shade material unavailable",e);
            return false;
        }
    }

    private void recordInputs(Face f, RenderNode background, Path nativeClip, boolean lockScreen) {
        int x = f.sampleLeft, y = f.sampleTop, w = f.width, h = f.height;
        boolean liveWindow = f.liveWindow;
        f.node.setPosition(0,0,w*(liveWindow ? 3 : 2),h);
        RecordingCanvas c=f.node.beginRecording();
        try {
            c.save();
            c.clipRect(0,0,w,h);
            if(f.frosted){
                int pad=f.blurMargin;
                f.softBackdrop.setPosition(-pad,-pad,w+pad,h+pad);
                RecordingCanvas paper=f.softBackdrop.beginRecording();
                try{
                    paper.translate(pad,pad);
                    drawBackdrop(paper,f,background,lockScreen);
                }finally{f.softBackdrop.endRecording();}
                c.drawRenderNode(f.softBackdrop);
            }else drawBackdrop(c,f,background,lockScreen);
            c.restore();
            if (liveWindow && !lockScreen) {
                c.save();
                c.clipRect(2*w,0,3*w,h);
                c.translate(2*w-x,-y);
                c.concat(inverse);
                c.drawRenderNode(windowFrame);
                c.restore();
            }
            c.clipRect(w,0,w*2,h);
            c.translate(w,0);
            if(nativeClip!=null){
                c.translate(-x,-y);
                c.clipPath(nativeClip);
                c.translate(x,y);
            }
            c.drawRenderNode(f.mask);
        } finally { f.node.endRecording(); }
    }

    private void drawBackdrop(RecordingCanvas c,Face f,RenderNode background,boolean lockScreen){
        c.translate(-f.sampleLeft,-f.sampleTop);
        c.concat(inverse);
        if (lockScreen) {
            if(lockPaperAlpha>0 && lockPaper!=null && lockPaper.hasFrame()){
                if(lockPaperAlpha<255)c.saveLayerAlpha(null,lockPaperAlpha);
                lockPaper.drawFrame(c);
                if(lockPaperAlpha<255)c.restore();
            }else if(expandedLockPaper!=null && expandedLockPaper.hasFrame()){
                expandedLockPaper.drawFrame(c);
            }
        } else {
            if(f.frosted && f.liveWindow && windowFrame.hasDisplayList())c.drawRenderNode(windowFrame);
            parentToScreen.reset();
            View parent=(View)backdrop.getParent();
            parent.transformMatrixToGlobal(parentToScreen);
            parentToScreen.preTranslate(-parent.getScrollX(),-parent.getScrollY());
            c.concat(parentToScreen);
            c.drawRenderNode(background);
        }
    }

    private boolean ensureSource(View v) throws ReflectiveOperationException {
        if(SystemClock.uptimeMillis()<retryAfter) return false;
        View root=v.getRootView();
        if (shadeBackdrop != null && shadeBackdrop.getRootView() == root && sourceRoot == root)
            backdrop = shadeBackdrop;
        if(sourceRoot!=root || backdrop==null || !backdrop.isAttachedToWindow()){
            closeSource();
            int id=root.getResources().getIdentifier("scrim_behind","id","com.android.systemui");
            View scrim=shadeBackdrop != null && shadeBackdrop.getRootView()==root ? shadeBackdrop : id==0 ? null : root.findViewById(id);
            if(scrim==null || !(scrim.getParent() instanceof View)) return false;
            if(material==null) material=new NotificationMaterial();
            sourceRoot=root;
            backdrop=scrim;
            if(listType.isInstance(v))lockList=v;
            for (ViewParent parent=v.getParent();parent!=null;parent=parent.getParent()) {
                if (listType.isInstance(parent)) { lockList=(View)parent; break; }
            }
            observer=root.getViewTreeObserver();
            observer.addOnPreDrawListener(beforeFrame);
        }
        return backdrop.getVisibility()==View.VISIBLE || (shadeBackdrop==null && enteringRoot==null);
    }

    private boolean updatePositions() {
        if(!sourceRoot.isAttachedToWindow()){closeSource();return true;}
        if (backdrop != null && backdrop.isShown() && !waitingHeaders.isEmpty()) {
            for (Iterator<View> it = waitingHeaders.iterator(); it.hasNext();) {
                View v = it.next();
                if (!v.isAttachedToWindow()) it.remove();
                else if (v.isShown()) { it.remove(); refreshHeader(v); }
            }
        }
        for(Map.Entry<View,Face[]> entry:cards.entrySet()){
            View v=entry.getKey();
            Face[] faces=entry.getValue();
            if(v==null || faces==null || !v.isShown()) continue;
            global.reset();
            v.transformMatrixToGlobal(global);
            boolean dark = GlassAppearance.dark(v.getRootView());
            for(int i=0;i<faces.length;i++){
                Face f=faces[i];
                if (f == null || !f.node.hasDisplayList() || (f.dark == dark && global.equals(f.sampledAt))) continue;
                // Only the sample coordinates changed. Native shape or content invalidations
                // still take the normal draw path and refresh the mask there.
                if (f.movingShade && f.dark == dark && enteringRoot == sourceRoot && shadeNode != null
                        && backdrop == shadeBackdrop && backdrop.getParent() instanceof View
                        && global.invert(inverse)) {
                    recordInputs(f, shadeNode, null, false);
                    f.sampledAt.set(global);
                    continue;
                }
                if(i==0 || listType.isInstance(v)) {
                    if (v.getBackground() instanceof HistoryClearControl) refreshHeader(v);
                    else v.invalidate();
                }
                else {
                    for(ViewParent p=v.getParent();p!=null;p=p.getParent()) {
                        if(listType.isInstance(p)){((View)p).invalidate();break;}
                    }
                }
            }
        }
        return true;
    }

    private void closeSource(){
        if(observer!=null && observer.isAlive()) observer.removeOnPreDrawListener(beforeFrame);
        observer=null;
        sourceRoot=null;
        lockList=null;
        backdrop=null;
        for(Face[] faces:cards.values()) if(faces!=null) for(Face f:faces) if(f!=null) {
            f.node.discardDisplayList(); f.mask.discardDisplayList();
        }
    }
}
