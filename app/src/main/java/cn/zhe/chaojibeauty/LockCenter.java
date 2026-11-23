package cn.zhe.chaojibeauty;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.os.Binder;
import android.os.IBinder;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Presentation for the unlocked notification page of the ColorOS split shade. */
final class LockCenter {
    private final NotificationGlass cardGlass;
    private final NotificationHistory history;
    final NotificationDisplay display;
    private final ShadeQuickActions quickActions;
    final ShadeWidgetMaterial previewWidgets;

    private final WidgetAccessoryLayout accessories;
    LockCenter(NotificationGlass cardGlass,LockClockMaterial lockClock,WidgetAccessoryLayout accessories) {
        this.accessories=accessories;
        previewWidgets=new ShadeWidgetMaterial(accessories);
        this.cardGlass = cardGlass;
        scenes = new LockStylePreview.Scenes(lockClock);
        quickActions = new ShadeQuickActions(cardGlass,lockClock);
        history = new NotificationHistory(cardGlass,quickActions);
        display = new NotificationDisplay(history,accessories,lockClock);
    }
    private Field panelField, notifyFraction;
    private Method keyguardShowing, qsArea;
    private boolean enabled, leftPull;
    private ViewGroup panel, holder;
    private View scrim, date, time;
    private LockStylePreview heading;
    private int oldHeight, dateVisibility, timeVisibility;
    private float fraction;
    private ShadeWallpaper paper;
    private View wallpaperView;
    private StatusHeader statusHeader;
    private View list, tilt;
    private Method getScroll;
    private boolean holdEntryLayout;
    private Method intrinsicPadding, setIntrinsicPadding, updateTopPadding;
    private final ViewTreeObserver.OnPreDrawListener entryScale = () -> {
        if (holdEntryLayout && list != null) {
            if (list.getScaleX()!=1f) list.setScaleX(1f);
            if (list.getScaleY()!=1f) list.setScaleY(1f);
        }
        return true;
    };
    private Field stackExtension;
    private Object activeExtension;
    private float firstRowRebound;
    private int tiltVisibility;
    private boolean fullyOpen;
    private boolean widgetActions;
    private Object openController;

    void setWidgetActions(boolean enabled) {
        widgetActions=enabled;
        previewWidgets.configure(enabled && panel!=null);
    }
    private final LockStylePreview.Scenes scenes;
    private Object fadeDrawer;
    private Field nativeDrawer;
    private ShadeDismiss dismiss;
    private final ShadeNavigation navigation = new ShadeNavigation();
    private ShadeEntry entry;
    private boolean trackingEntry;
    private final HashSet<View> entryRows = new HashSet<>();
    private float touchStart, touchY, resumedProgress;
    private long windowDownTime = -1;
    private float windowDownX, windowDownY;
    private long lastWindowDown = -1, edgeDown = -1;
    private float edgeX, edgeY;
    private boolean edgeStarted, edgeClaimed;
    private volatile boolean watchOpeningEdge;
    private final ShadePullRelease pull = new ShadePullRelease();
    private Boolean openOnRelease;
    private Field panelController;
    private Method instantCollapse;
    private Object quickSettingsPager;

    void install(ClassLoader loader) throws ReflectiveOperationException {
        scenes.install(loader);
        Class<?> stack = loader.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
        nativeDrawer=stack.getDeclaredField("mOplusDispatchDrawHook");
        nativeDrawer.setAccessible(true);
        Class<?> fade=loader.loadClass("com.oplus.systemui.statusbar.notification.stack.NotificationStackScrollLayoutControllerExtImpl$TopFadeDrawer");
        XposedBridge.hookMethod(declared(fade,"onDispatchDraw",Canvas.class),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if ((p.thisObject==fadeDrawer && panel!=null) || display.ownsNativeFade(p.thisObject)) p.setResult(false);
            }
        });
        Class<?> algorithm=loader.loadClass("com.oplus.systemui.statusbar.notification.stack.OplusStackScrollAlgorithmExImpl");
        Class<?> ambient=loader.loadClass("com.android.systemui.statusbar.notification.stack.AmbientState");
        XposedBridge.hookMethod(declared(algorithm,"calculateNotificationStackDrawStart",ViewGroup.class,ambient),new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                if (panel==null || p.args[0]!=list) return;
                int dy=(int)getScroll.invoke(list);
                if (dy>0) p.setResult(Math.min((float)p.getResult(),0f));
            }
        });
        getScroll = declared(stack,"getOwnScrollY");
        // The sheet translates the entire list. Keep its local row and shelf
        // coordinates at their expanded endpoints while that translation runs.
        XposedBridge.hookMethod(declared(stack,"setStackTranslation",float.class),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (holdEntryLayout && p.thisObject==list) p.args[0]=0f;
            }
        });
        XposedBridge.hookMethod(declared(stack,"updateInterpolatedStackHeight",float.class,float.class),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (holdEntryLayout && p.thisObject==list) p.args[1]=1f;
            }
        });
        intrinsicPadding = declared(stack,"getIntrinsicPadding");
        setIntrinsicPadding = declared(stack,"setIntrinsicPadding",int.class);
        updateTopPadding = declared(stack,"updateTopPadding",float.class,boolean.class);
        XposedBridge.hookMethod(updateTopPadding,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                if (!holdEntryLayout || p.thisObject!=list) return;
                int padding=(int)intrinsicPadding.invoke(list);
                if (padding>0) {
                    p.args[0]=(float)padding;
                    p.args[1]=false;
                }
            }
        });
        Class<?> rowAnimation = loader.loadClass("com.oplus.systemui.panelanimation.notification.OplusNotificationRowShowAnimationExImpl");
        XposedBridge.hookMethod(declared(rowAnimation,"getViewNegativeExpandOffset",View.class),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if ((holdEntryLayout || display.sectionLayout()) && ((View)p.args[0]).getParent()==list) p.setResult(0f);
            }
        });
        Class<?> spacing = loader.loadClass("com.oplus.systemui.panelanimation.LevelPositionAlgorithmImpl");
        XposedBridge.hookMethod(declared(spacing,"getPaddingWithIndex",int.class,int.class,View.class),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                View row=(View)p.args[2];
                if ((holdEntryLayout || display.sectionLayout()) && (int)p.args[1]==2 && row!=null && row.getParent()==list) p.setResult(0f);
            }
        });
        stackExtension = stack.getDeclaredField("mExt");
        stackExtension.setAccessible(true);
        Class<?> extension = loader.loadClass("com.oplus.systemui.statusbar.notification.stack.NotificationStackScrollLayoutExtImpl");
        XposedBridge.hookMethod(declared(extension,"getLayeringReboundTrans",int.class,int.class),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if ((holdEntryLayout || display.sectionLayout()) && p.thisObject==activeExtension) p.setResult(0f);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                if (p.thisObject != activeExtension || (int)p.args[0] != 0 || heading == null) return;
                float rebound = (float)p.getResult();
                if (rebound != firstRowRebound) {
                    firstRowRebound = rebound;
                    syncScroll();
                }
            }
        });
        XposedBridge.hookMethod(declared(stack,"setOwnScrollY",int.class,boolean.class),new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                if (heading!=null && p.thisObject==list) syncScroll();
            }
        });
        Class<?> blur = loader.loadClass("com.oplus.systemui.notification.blur.OplusNotificationTiltShiftBlurController");
        Field blurContainer = blur.getDeclaredField("container");
        blurContainer.setAccessible(true);
        XposedBridge.hookMethod(declared(blur,"setOwnScrollY",int.class),new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws IllegalAccessException {
                if (tilt!=null && blurContainer.get(p.thisObject)==tilt) {
                    tiltVisibility = tilt.getVisibility();
                    tilt.setVisibility(View.INVISIBLE);
                }
            }
        });
        Class<?> pager = loader.loadClass("com.oplus.systemui.separate.OplusPanelViewPagerController");
        panelController=pager.getDeclaredField("notificationPanelViewController");
        panelController.setAccessible(true);
        instantCollapse=declared(panelController.getType(),"instantCollapse");
        XposedBridge.hookMethod(declared(loader.loadClass("com.oplus.systemui.separate.OplusSeparateNotificationAndQSExImpl"),
                "onBackPressed"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (entry != null && fullyOpen && quickSettingsPager == null
                        && !panel.getContext().getSystemService(KeyguardManager.class).isKeyguardLocked()) p.setResult(true);
            }
        });
        XposedBridge.hookMethod(declared(panelController.getType(), "fling", float.class, String.class,
                float.class, boolean.class, boolean.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.thisObject != openController || openOnRelease == null || fullyOpen ||
                        !("endMotionEvent 1".equals(p.args[1]) || "endMotionEvent 3".equals(p.args[1]))) return;
                p.args[3] = openOnRelease;
                p.args[0] = openOnRelease ? Math.max(0f,pull.velocity()) : Math.min(0f,pull.velocity());
            }
        });
        XposedBridge.hookMethod(declared(panelController.getType(), "onTrackingStopped", String.class, boolean.class),
                new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.thisObject == openController && openOnRelease != null && "endMotionEvent".equals(p.args[0]))
                    p.args[1] = openOnRelease;
            }
        });
        XposedBridge.hookMethod(declared(pager, "notifyNTEndMotionEvent", float.class,boolean.class),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws IllegalAccessException {
                if (openOnRelease != null && panelController.get(p.thisObject) == openController)
                    p.args[1] = openOnRelease;
            }
            @Override protected void afterHookedMethod(MethodHookParam p) throws IllegalAccessException {
                if (panelController.get(p.thisObject) == openController) openOnRelease = null;
            }
        });
        Method fling = declared(panelController.getType(), "flingToHeight", float.class, boolean.class,
                float.class, float.class, boolean.class, String.class);
        XposedBridge.hookMethod(fling, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (entry != null && !fullyOpen && p.thisObject == openController)
                    entry.flingDecision((boolean)p.args[1], (float)p.args[0]);
            }
        });
        Class<?> window=loader.loadClass("com.android.systemui.shade.NotificationShadeWindowView");
        quickActions.install(window);
        Class<?> touch=loader.loadClass("com.android.systemui.shade.NotificationPanelViewController$TouchHandler");
        // ColorOS tests its native notification drag area against the full NSSL.
        // Our viewport ends above the shortcuts, even when the native NSSL extends below them.
        Class<?> lockDrag = de.robv.android.xposed.XposedHelpers.findClassIfExists(
                "com.oplus.systemui.notification.lockscreen.stack.LockscreenNotificationDragHelperExImpl",loader);
        if(lockDrag!=null)XposedBridge.hookAllMethods(lockDrag,"onInterceptTouchEvent",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                if(p.args.length!=1 || !(p.args[0] instanceof MotionEvent))return;
                MotionEvent event=(MotionEvent)p.args[0];
                if(event.getActionMasked()==MotionEvent.ACTION_MOVE && display.systemOwnsLockStream())p.setResult(false);
            }
        });
        XC_MethodHook entryTouch = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (display.systemOwnsLockStream()) return;
                if (entry != null && fullyOpen && !trackingEntry && quickSettingsPager == null) {
                    p.setResult(false);
                    return;
                }
                trackEntry((MotionEvent)p.args[0]);
            }
        };
        XposedBridge.hookMethod(declared(touch,"onTouchEvent",MotionEvent.class),entryTouch);
        XposedBridge.hookMethod(declared(touch,"onInterceptTouchEvent",MotionEvent.class),entryTouch);
        XposedBridge.hookMethod(declared(window,"dispatchTouchEvent",MotionEvent.class),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                MotionEvent event=(MotionEvent)p.args[0];
                if(display.nativeLockGesture((View)p.thisObject,event)){
                    if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
                        lastWindowDown=event.getEventTime();windowDownTime=-1;
                        edgeDown=-1;edgeStarted=edgeClaimed=false;
                    }
                    return;
                }
                if (edgeClaimed && event.getEventTime() >= edgeDown) {
                    p.setResult(true);
                    return;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    // The window can receive the next DOWN before the status
                    // bar forwards the previous pull's UP to its controller.
                    windowDownTime = event.getEventTime();
                    lastWindowDown = windowDownTime;
                    windowDownX = event.getRawX();
                    windowDownY = event.getRawY();
                }
                if (display.touch((View)p.thisObject, event, fullyOpen && panel != null, () -> {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    try { XposedBridge.invokeOriginalMethod(p.method,p.thisObject,new Object[]{cancel}); }
                    catch (Throwable e) { Log.w("ChaojiBeauty", "Cancel notification gesture", e); }
                    finally { cancel.recycle(); }
                })) {
                    p.setResult(true);
                    return;
                }
                if (quickActions.touch((View)p.thisObject,event,
                        fullyOpen && fraction>=.999f && panel!=null && panel.getRootView()==p.thisObject)) {
                    windowDownTime=-1;
                    p.setResult(true);
                    return;
                }
                if (widgetActions && fullyOpen && fraction>=.999f && heading!=null && panel.getRootView()==p.thisObject &&
                        heading.isWidgetTap(event) && !panel.getContext().getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                    LockStylePreview tapped=heading;
                    Object controller=openController;
                    MotionEvent cancel=MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    try { XposedBridge.invokeOriginalMethod(p.method,p.thisObject,new Object[]{cancel}); }
                    finally { cancel.recycle(); }
                    if (tapped.activateWidget(event) && controller!=null && panel!=null) instantCollapse.invoke(controller);
                    p.setResult(true);
                    return;
                }
                if (event.getActionMasked()==MotionEvent.ACTION_DOWN) history.onDown(event.getRawX(), event.getRawY());
                if (fullyOpen && event.getActionMasked()==MotionEvent.ACTION_DOWN) holdEntryLayout=false;
                trackEntry(event);
                if (dismiss != null && panel.getRootView() == p.thisObject &&
                        (fullyOpen || (entry != null && entry.canReverseOpening()))) {
                    if (windowDownTime >= 0 && event.getEventTime() >= windowDownTime) {
                        dismiss.beginTouch(windowDownX, windowDownY, windowDownTime);
                        windowDownTime = -1;
                    }
                    if (dismiss.touch(event, () -> {
                        watchOpeningEdge = false;
                        reverseOpening();
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        try { XposedBridge.invokeOriginalMethod(p.method,p.thisObject,new Object[]{cancel}); }
                        catch (Throwable failure) { Log.w("ChaojiBeauty","Cannot cancel child touch",failure); }
                        finally { cancel.recycle(); }
                    })) p.setResult(true);
                }
                if ((event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                        && event.getEventTime() >= windowDownTime) windowDownTime = -1;
            }
            @Override protected void afterHookedMethod(MethodHookParam p){
                display.finishLockDispatch((MotionEvent)p.args[0]);
            }
        });
        panelField = pager.getDeclaredField("notificationPanelView");
        panelField.setAccessible(true);
        notifyFraction = pager.getField("currentNotifyFraction");
        keyguardShowing = declared(pager, "isKeyGuardShowing");
        Method rightArea = declared(pager, "isRightArea", float.class);
        qsArea = declared(pager, "getDownOnQsArea");
        Method begin = declared(pager, "initExpanded", MotionEvent.class);
        Method progress = declared(pager, "updateNotifyExpandFraction", float.class, boolean.class);
        Method qs = declared(pager, "updateQSExpandFraction", float.class, boolean.class, boolean.class, float.class);
        Method screenOff = declared(pager, "onScreenTurnedOff");
        Method detach = declared(pager, "onViewDetached");
        // Separate QS has its own tile pager. Reject only the outer panel's
        // cross-page gestures before it starts forwarding events to notifications.
        Field qsFraction = pager.getDeclaredField("currentQsFraction");
        qsFraction.setAccessible(true);
        Method screenWidth = declared(pager, "getScreenWidth");
        XposedBridge.hookMethod(rightArea, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                if (!enabled || notifyFraction.getFloat(p.thisObject) > 0f || qsFraction.getFloat(p.thisObject) > 0f
                        || (boolean)keyguardShowing.invoke(p.thisObject)) return;
                View shade = (View)panelField.get(p.thisObject);
                if (shade == null) return;
                Configuration config = shade.getResources().getConfiguration();
                if (config.smallestScreenWidthDp >= 600) return;
                int width = (int)screenWidth.invoke(p.thisObject);
                if (width <= 0) return;
                float controlsWidth = width * (config.orientation == Configuration.ORIENTATION_LANDSCAPE ? .172f : .25f);
                float x = (float)p.args[0];
                // ColorOS asks for the physical right side and maps it to QS
                // according to layout direction. Equality belongs to notifications.
                p.setResult(config.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                        ? x >= controlsWidth : x > width - controlsWidth);
            }
        });
        XC_MethodHook keepQuickSettings = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                if (enabled && qsFraction.getFloat(p.thisObject) > 0f
                        && notifyFraction.getFloat(p.thisObject) == 0f
                        && !(boolean)keyguardShowing.invoke(p.thisObject)) {
                    // Repeated pulls during the release animation also belong to QS.
                    p.setResult(p.method.equals(qsArea));
                }
            }
        };
        XposedBridge.hookMethod(qsArea, keepQuickSettings);
        XposedBridge.hookMethod(declared(pager, "isInScrollXHotZone", android.graphics.PointF.class), keepQuickSettings);
        XposedBridge.hookMethod(declared(pager, "isInScrollYHotZone", android.graphics.PointF.class), keepQuickSettings);
        XposedBridge.hookMethod(begin, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                if (!enabled) return;
                MotionEvent event = (MotionEvent)p.args[0];
                if (notifyFraction.getFloat(p.thisObject) > 0f) {
                    resumePullOnDown(event);
                    return;
                }
                leftPull = !(boolean)qsArea.invoke(p.thisObject) && !(boolean)keyguardShowing.invoke(p.thisObject);
                if (!leftPull) trackingEntry = false;
                if (leftPull && (!trackingEntry || event.getActionMasked()==MotionEvent.ACTION_DOWN)) {
                    beginPull(event, 0f);
                }
            }
        });
        XposedBridge.hookMethod(progress, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                if (!enabled) return;
                if (!leftPull) {
                    // Commands and notification keys expand the same panel without initExpanded.
                    if ((float)p.args[0] <= 0f || qsFraction.getFloat(p.thisObject) > 0f
                            || (boolean)keyguardShowing.invoke(p.thisObject)) return;
                    leftPull = true;
                }
                fraction = Math.min(1f, (float)p.args[0]);
                if (fraction <= 0f) {
                    if (panel != null) {
                        close();
                    }
                    return;
                }
                if ((boolean)keyguardShowing.invoke(p.thisObject)) { close(); return; }
                ViewGroup view = (ViewGroup)panelField.get(p.thisObject);
                if (panel == null && view != null) open(view,panelController.get(p.thisObject));
                if (fraction >= 1 && entry==null) fullyOpen=true;
                positionPage();
            }
        });
        XC_MethodHook reset = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (quickSettingsPager == p.thisObject) quickSettingsPager = null;
                close();
            }
        };
        XposedBridge.hookMethod(screenOff, reset);
        XposedBridge.hookMethod(detach, reset);
        XposedBridge.hookMethod(qs, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if ((float)p.args[0] > 0f) quickSettingsPager = p.thisObject;
                else if (quickSettingsPager == p.thisObject) quickSettingsPager = null;
                if (leftPull && (float)p.args[0] > 0f) close();
            }
        });
        XC_MethodHook keepOpenControls = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                Object owner = quickSettingsPager;
                if (enabled && owner != null && qsFraction.getFloat(owner) > 0f
                        && !(boolean)keyguardShowing.invoke(owner)) p.setResult(null);
            }
        };
        XposedBridge.hookMethod(declared(loader.loadClass("com.android.systemui.shade.ShadeControllerImpl"),
                "expandToNotifications"), keepOpenControls);
        XposedBridge.hookMethod(declared(loader.loadClass("com.android.systemui.statusbar.phone.CentralSurfacesCommandQueueCallbacks"),
                "toggleNotificationsPanel"), keepOpenControls);
        installControlsRelease(loader);
        try { installOpeningEdge(loader); }
        catch (ReflectiveOperationException e) { Log.w("ChaojiBeauty","Early shade return unavailable",e); }
        try { installEntryRowAnimation(loader,rowAnimation,ambient); }
        catch (ReflectiveOperationException e) { Log.w("ChaojiBeauty","Row entry animation hook unavailable",e); }
    }

    private void reverseOpening() {
        if (fullyOpen) return;
        dismiss.startAt(entry.takeOpeningOffset());
        fullyOpen = true;
        holdEntryLayout = false;
    }

    private void installOpeningEdge(ClassLoader loader) throws ReflectiveOperationException {
        String handler = "com.android.systemui.navigationbar.gestural.DisplayBackGestureHandlerImpl";
        Class<?> listener = loader.loadClass(handler + "$inputEventReceiver$1");
        Field owner = listener.getDeclaredField("this$0");
        owner.setAccessible(true);
        Field display = loader.loadClass(handler).getField("displayId");
        Field compat = loader.loadClass(handler).getDeclaredField("inputMonitorCompat");
        compat.setAccessible(true);
        Field monitor = compat.getType().getField("mInputMonitor");
        Method pilfer = monitor.getType().getMethod("pilferPointers");
        android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        XposedBridge.hookMethod(declared(listener,"onInputEvent",android.view.InputEvent.class),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws IllegalAccessException {
                if ((!watchOpeningEdge && !LockCenter.this.display.countVisible) || !(p.args[0] instanceof MotionEvent)) return;
                MotionEvent event = (MotionEvent)p.args[0];
                if (!event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)) return;
                if(!watchOpeningEdge && event.getActionMasked()==MotionEvent.ACTION_MOVE)return;
                Object receiver = owner.get(p.thisObject);
                int displayId = display.getInt(receiver);
                if (ui.getLooper().isCurrentThread()) {
                    openingEdge(event, displayId, receiver, compat, monitor, pilfer);
                } else {
                    MotionEvent copy = MotionEvent.obtain(event);
                    ui.post(() -> {
                        try { openingEdge(copy, displayId, receiver, compat, monitor, pilfer); }
                        finally { copy.recycle(); }
                    });
                }
            }
        });
    }

    private void openingEdge(MotionEvent event, int displayId, Object receiver, Field compat, Field monitor, Method pilfer) {
        if(display.countVisible)display.observeCountRelease(event,displayId,lastWindowDown);
        if (!watchOpeningEdge || panel == null || panel.getDisplay() == null
                || panel.getDisplay().getDisplayId() != displayId || dismiss == null || entry == null) return;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if(display.startsNativeLockGesture(panel.getRootView(),event)){
                edgeDown=-1;edgeStarted=edgeClaimed=false;
                return;
            }
            edgeDown = !fullyOpen && (trackingEntry || entry.canReverseOpening())
                    && dismiss.startsAtBottom(event.getRawX(),event.getRawY()) ? event.getEventTime() : -1;
            edgeX = event.getRawX();
            edgeY = event.getRawY();
            edgeStarted = edgeClaimed = false;
            return;
        }
        if (edgeDown < 0 || event.getEventTime() < edgeDown) return;
        // If the window received this DOWN, its normal dispatch owns the whole stream.
        if (!edgeClaimed && Math.abs(lastWindowDown - edgeDown) <= 2
                && Math.abs(windowDownY-edgeY) < 2 && Math.abs(windowDownX-edgeX) < 2) {
            edgeDown = -1;
            if (fullyOpen) watchOpeningEdge = false;
            return;
        }
        if (fullyOpen || entry.canReverseOpening()) {
            if (!edgeStarted) {
                dismiss.beginTouch(edgeX, edgeY, edgeDown);
                edgeStarted = true;
            }
            dismiss.touch(event, () -> {
                reverseOpening();
                edgeClaimed = true;
                // Cancel the original desktop touch only after the upward direction is accepted.
                if (action == MotionEvent.ACTION_MOVE) try {
                    pilfer.invoke(monitor.get(compat.get(receiver)));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    Log.w("ChaojiBeauty","Cannot claim interrupted shade gesture",e);
                }
            });
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            edgeDown = -1;
            edgeClaimed = false;
            if (fullyOpen) watchOpeningEdge = false;
        }
    }

    private void installControlsRelease(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> root = loader.loadClass("com.oplus.systemui.plugins.qs.OplusQSRootViewComponent");
        Field down = root.getDeclaredField("touchDownPoint");
        down.setAccessible(true);
        Field slop = root.getField("touchSlop");
        Field closedOnDown = root.getField("panelClosedOnDown");
        // Scope the decision to the release currently being evaluated. The native
        // handler still finishes tracking, clears velocity and settles its tiles.
        XC_MethodHook horizontalRelease = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                Object controls = p.args[0];
                if (!enabled || quickSettingsPager == null || (boolean)keyguardShowing.invoke(quickSettingsPager)
                        || closedOnDown.getBoolean(controls)) return;
                android.graphics.PointF start = (android.graphics.PointF)down.get(controls);
                float dx = Math.abs((float)p.args[2] - start.x);
                float dy = Math.abs((float)p.args[3] - start.y);
                if (dx > slop.getInt(controls) && dx > 1.5f * dy) horizontalReleaseOwner = controls;
            }
            @Override protected void afterHookedMethod(MethodHookParam p) { horizontalReleaseOwner = null; }
        };
        XposedBridge.hookMethod(declared(root, "access$endMotionEvent",root,MotionEvent.class,float.class,float.class), horizontalRelease);
        XC_MethodHook keepExpanded = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.thisObject == horizontalReleaseOwner) p.setResult(true);
            }
        };
        XposedBridge.hookMethod(declared(root,"shouldExpand",float.class,float.class),keepExpanded);
        XposedBridge.hookMethod(declared(root,"shouldExpand",float.class,float.class,float.class,boolean.class),keepExpanded);
    }

    private Object horizontalReleaseOwner;

    void installHistory(ClassLoader loader) throws ReflectiveOperationException {
        history.install(loader);
        display.install(loader);
    }

    private void installEntryRowAnimation(ClassLoader loader, Class<?> animation, Class<?> ambient)
            throws ReflectiveOperationException {
        String base = "com.android.systemui.statusbar.notification.";
        Class<?> expandable = loader.loadClass(base + "row.ExpandableView");
        Class<?> notification = loader.loadClass(base + "row.ExpandableNotificationRow");
        Method clear = declared(animation,"clearShowAnimationState",expandable);
        Method children = declared(notification,"getAttachedChildren");
        Method pinned = declared(notification,"isPinned");
        Method managerCancel = declared(loader.loadClass("com.oplus.systemui.shade.NotificationPanelSeparateAnimationManagerImpl"),
                "cancelAllAnimation",View.class);
        Method update = declared(animation,"updateViewAlphaAndScale",expandable,loader.loadClass(base + "stack.ViewState"),
                ambient,loader.loadClass(base + "stack.StackScrollAlgorithm$StackScrollAlgorithmState"),ViewGroup.class);
        XposedBridge.hookMethod(update,new XC_MethodHook() {
            private int spacing, first, shown;
            private boolean resolved;
            @Override protected void beforeHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                if (!holdEntryLayout || p.args[4] != list) return;
                View row = (View)p.args[0];
                boolean isNotification = notification.isInstance(row);
                if (isNotification ? (boolean)pinned.invoke(row) : !history.ownsHeader(row)) return;
                if (!resolved) {
                    android.content.res.Resources resources = row.getResources();
                    spacing = resources.getIdentifier("tag_notification_view_spacing","id","com.android.systemui");
                    first = resources.getIdentifier("tag_notification_first_show","id","com.android.systemui");
                    shown = resources.getIdentifier("tag_notification_last_should_show","id","com.android.systemui");
                    resolved = true;
                }
                if (spacing==0 || first==0 || shown==0) return;
                if (entryRows.add(row)) {
                    // Delayed OEM callbacks look up springs on the row. Cancel and
                    // clear those tags before the whole sheet starts carrying it.
                    managerCancel.invoke(null,row);
                    clear.invoke(p.thisObject,row);
                    row.setTag(spacing,0f);
                    row.setTag(first,Boolean.FALSE);
                    row.setTag(shown,Boolean.TRUE);
                    List<?> attached = isNotification ? (List<?>)children.invoke(row) : null;
                    if (attached != null) for (Object child : attached) {
                        managerCancel.invoke(null,child);
                        clear.invoke(p.thisObject,child);
                    }
                }
                p.setResult(null);
            }
        });
    }

    private static Method declared(Class<?> owner, String name, Class<?>... types) throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    void configure(boolean on) {
        enabled = on;
        history.enabled=on;
        quickActions.configure(on);
        scenes.configure(on);
        if (!on) {
            close();
            history.forgetObservations();
        }
    }

    void refreshClock() {
        scenes.invalidateGlyphs();
        if (heading!=null) heading.invalidate();
    }

    void ambient(boolean off){display.ambient(off);}
    void screenOn(){display.screenOn();}

    void userPresent() {
        scenes.releaseLockSource();
        if (enabled) history.userPresent();
    }

    static View find(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", "com.android.systemui");
        return id == 0 ? null : root.findViewById(id);
    }


    private void syncScroll() throws ReflectiveOperationException {
        if(display.sectionLayout())return;
        int scroll=(int)getScroll.invoke(list);
        heading.sectionProgress(0,scroll-firstRowRebound);
    }

    private void beginPull(MotionEvent event, float initialProgress) {
        watchOpeningEdge = true;
        resumedProgress = initialProgress;
        trackingEntry = true;
        touchStart = event.getActionMasked()==MotionEvent.ACTION_DOWN ? event.getRawY() : 0f;
        touchY = event.getRawY();
        openOnRelease = null;
        pull.begin(event.getEventTime(),touchY);
    }

    private void resumePullOnDown(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN || trackingEntry || !leftPull || entry == null) return;
        float start = entry.resumeClosingPull();
        if (start >= 0f) beginPull(event, start);
    }

    private float fingerProgress() {
        float travel = panel.getHeight() - 1f;
        if (resumedProgress == 0f) travel -= touchStart;
        return resumedProgress + (touchY-touchStart)/Math.max(1f,travel);
    }

    private void trackEntry(MotionEvent event) {
        resumePullOnDown(event);
        if (!trackingEntry || !leftPull) return;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            touchY = event.getRawY();
            for (int i=0; i<event.getHistorySize(); i++)
                pull.sample(event.getHistoricalEventTime(i),event.getHistoricalY(i)+touchY-event.getY());
            pull.sample(event.getEventTime(),touchY);
            if (entry != null) {
                float progress = fingerProgress();
                if (action == MotionEvent.ACTION_MOVE && wouldOpenOnRelease()) {
                    entry.projectedOpen(progress);
                    if (!paper.started()) paper.open();
                }
                entry.followFinger(progress);
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            trackingEntry = false;
            if (entry != null) {
                openOnRelease = action == MotionEvent.ACTION_UP && wouldOpenOnRelease();
                entry.release(fraction, !openOnRelease);
            }
        }
    }

    private boolean wouldOpenOnRelease() {
        return ShadePullRelease.opens(resumedProgress * panel.getHeight() + touchY - touchStart, panel.getHeight(), pull.velocity(),
                panel.getResources().getDisplayMetrics().density);
    }

    private void positionPage() {
        if (heading==null) return;
        if (entry != null) {
            if (fullyOpen) {
                if (dismiss==null || !dismiss.interacting()) entry.move(fraction);
            } else if (trackingEntry) entry.followFinger(fingerProgress());
            else entry.nativeProgress(fraction);
            return;
        }
        float y = fullyOpen ? (fraction-1f)*panel.getHeight() : 0;
        heading.setTranslationY(y);
        if (paper!=null) paper.move(y);
    }

    private void open(ViewGroup view,Object controller) throws ReflectiveOperationException {
        View header = find(view, "settings_button_container");
        View behind = find(view.getRootView(), "scrim_behind");
        View clock = find(view, "qs_footer_clock"), day = find(view, "oplus_date");
        if (!(header instanceof ViewGroup) || behind == null || !(behind.getParent() instanceof ViewGroup) || clock == null || day == null) return;
        Context context = view.getContext();
        KeyguardManager guard = context.getSystemService(KeyguardManager.class);
        if (guard == null || guard.isKeyguardLocked()) { close(); return; }
        statusHeader = StatusHeader.attach(view);
        if (statusHeader == null) { close(); return; }
        panel = view;
        previewWidgets.configure(widgetActions);
        scrim = behind;
        ViewGroup shade = (ViewGroup)behind.getParent();
        wallpaperView = new View(context);
        wallpaperView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        paper = new ShadeWallpaper(behind);
        wallpaperView.setBackground(paper);
        wallpaperView.setRenderEffect(ShadeLens.wallpaperBlur(wallpaperView));
        wallpaperView.setForeground(new android.graphics.drawable.ColorDrawable(0x23000000));
        shade.addView(wallpaperView,shade.indexOfChild(behind)+1,new ViewGroup.LayoutParams(-1,-1));
        cardGlass.setShadeBackdrop(wallpaperView);
        holder = (ViewGroup)header;
        date = day;
        time = clock;
        dateVisibility = date.getVisibility();
        timeVisibility = time.getVisibility();
        oldHeight = holder.getLayoutParams().height;
        heading = new LockStylePreview(view, find(view.getRootView(), "keyguard_style_clock"), scenes, statusHeader,paper,accessories);
        paper.setClockView(heading);
        openController=controller;
        list=find(view,"notification_stack_scroller");
        if (list!=null) {
            fadeDrawer=nativeDrawer.get(list);
            activeExtension=stackExtension.get(list);
        }
        tilt=find(view,"tilt_shift_blur_container");
        if (tilt!=null) {
            tiltVisibility=tilt.getVisibility();
            tilt.setVisibility(View.INVISIBLE);
        }
        View content = find(view, "notification_container_parent");
        int slot = view.indexOfChild(content);
        view.addView(heading, Math.max(0, slot), new ViewGroup.LayoutParams(-1, -1));
        boolean fromHome=LauncherShadeReturn.homeVisible(context);
        entry = new ShadeEntry(view,heading,behind,find(view.getRootView(),"scrim_notifications"),cardGlass,paper,wallpaperView,fromHome,()->{
            fullyOpen=true;
            if (edgeDown < 0) watchOpeningEdge = false;
            positionPage();
        });
        if (entry.available()) {
            holdEntryLayout=true;
            if (list!=null) list.getViewTreeObserver().addOnPreDrawListener(entryScale);
            cardGlass.enterWindow(view.getRootView());
            if (!trackingEntry) entry.release(0f, false);
        } else {
            entry = null;
            paper.open();
        }
        dismiss=new ShadeDismiss(view,behind,find(view.getRootView(),"scrim_notifications"),wallpaperView,()->paper!=null && paper.hasFrame(),entry==null ? null : entry::dismissTo,()->{
            if (entry != null && !guard.isKeyguardLocked() && (!fromHome || LauncherShadeReturn.homeVisible(context)))
                entry.commitReturn();
        },()->{
            Runnable collapse=()->{
                try { instantCollapse.invoke(controller); }
                catch (ReflectiveOperationException failure) { if (dismiss!=null) dismiss.release(); Log.w("ChaojiBeauty","Shade dismissal failed",failure); }
            };
            if (entry != null) entry.finishReturn(collapse);
            else collapse.run();
        });
        updateHeaderSpace(statusHeader.originalMargin+statusHeader.column.getHeight()-holder.getHeight());
        panel.getViewTreeObserver().addOnGlobalLayoutListener(contentLayout);
        date.setVisibility(View.INVISIBLE);
        time.setVisibility(View.INVISIBLE);
        if (list instanceof ViewGroup) {
            display.enterShade((ViewGroup)list,entry);
            syncScroll();
            history.open((ViewGroup)list, find(view, "clear_all"), () -> fullyOpen && fraction >= .999f, heading);
        }
        quickActions.open(view,()->{
            try { instantCollapse.invoke(controller); }
            catch (ReflectiveOperationException e) { Log.w("ChaojiBeauty","Camera shade dismissal failed",e); }
        });
        navigation.acquire(context);
        IconModule.firstDraw("lock center header");
    }

    private final ViewTreeObserver.OnGlobalLayoutListener contentLayout=()->{
        if (heading==null || holder==null) return;
        heading.contentLayoutChanged();
        try {
            updateHeaderSpace(statusHeader.column.getHeight()-holder.getHeight());
        } catch (ReflectiveOperationException e) { Log.w("ChaojiBeauty","Cannot update notification header",e); }
    };

    private void updateHeaderSpace(int otherChrome) throws ReflectiveOperationException {
        int desired=Math.max(0,heading.headerHeight(otherChrome));
        ViewGroup.LayoutParams size=holder.getLayoutParams();
        if (size.height==desired) return;
        int delta=desired-holder.getHeight();
        size.height=desired;
        holder.setLayoutParams(size);
        if (holdEntryLayout && list!=null && holder.getHeight()>0) {
            int current=(int)intrinsicPadding.invoke(list);
            if (current>0) {
                int padding=Math.max(0,current+delta);
                setIntrinsicPadding.invoke(list,padding);
                updateTopPadding.invoke(list,(float)padding,false);
            }
        }
    }

    private void close() {
        previewWidgets.configure(false);
        watchOpeningEdge = edgeStarted = edgeClaimed = false;
        edgeDown = lastWindowDown = -1;
        windowDownTime = -1;
        navigation.release();
        leftPull = false;
        trackingEntry = false;
        openOnRelease = null;
        holdEntryLayout=false;
        entryRows.clear();
        if (panel == null) return;
        if (panel.getViewTreeObserver().isAlive()) panel.getViewTreeObserver().removeOnGlobalLayoutListener(contentLayout);
        quickActions.close();
        history.close();
        display.leaveShade();
        if (entry != null) { entry.restore(); entry=null; }
        cardGlass.enterWindow(null);
        if(dismiss!=null) { dismiss.release(); dismiss=null; }
        fullyOpen=false;
        if (tilt!=null) tilt.setVisibility(tiltVisibility);
        if (list!=null && list.getViewTreeObserver().isAlive())
            list.getViewTreeObserver().removeOnPreDrawListener(entryScale);
        list=tilt=null;
        activeExtension=null;
        firstRowRebound=0;
        fadeDrawer=null;
        cardGlass.setShadeBackdrop(null);
        if (wallpaperView != null) {
            wallpaperView.setBackground(null);
            if (wallpaperView.getParent() instanceof ViewGroup) ((ViewGroup)wallpaperView.getParent()).removeView(wallpaperView);
            wallpaperView=null;
        }
        if (paper != null) paper.close();
        statusHeader.restore();
        statusHeader = null;
        heading.finish();
        panel.removeView(heading);
        ViewGroup.LayoutParams size = holder.getLayoutParams();
        size.height = oldHeight;
        holder.setLayoutParams(size);
        date.setVisibility(dateVisibility);
        time.setVisibility(timeVisibility);
        heading = null;
        openController=null;
        panel = holder = null;
        scrim = date = time = null;
        paper = null;
    }

    /** Keep the actual carrier/radio widgets bound while the split-shade animator swaps its copies. */
    /** Keep Quickstep from claiming the bottom swipe while this shade owns dismissal. */
    private static final class ShadeNavigation {
        private Object service;
        private Method disable;
        private IBinder token;
        private int user;
        private String owner;

        void acquire(Context context) {
            if (token != null) return;
            int mode = context.getResources().getIdentifier("config_navBarInteractionMode", "integer", "android");
            if (mode == 0 || context.getResources().getInteger(mode) != 2) return;
            try {
                Object manager = context.getSystemService(Class.forName("android.app.StatusBarManager"));
                if (manager == null) return;
                Method getService = declared(manager.getClass(), "getService");
                service = getService.invoke(manager);
                if (service == null) return;
                disable = Class.forName("com.android.internal.statusbar.IStatusBarService")
                        .getMethod("disableForUser", int.class, IBinder.class, String.class, int.class);
                int home = manager.getClass().getField("DISABLE_HOME").getInt(null);
                int recents = manager.getClass().getField("DISABLE_RECENT").getInt(null);
                user = (int) ActivityManager.class.getMethod("getCurrentUser").invoke(null);
                owner = context.getPackageName();
                token = new Binder();
                disable.invoke(service, home | recents, token, owner, user);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.w("ChaojiBeauty", "Cannot reserve notification shade navigation", e);
                release();
            }
        }

        void release() {
            if (token == null) return;
            try {
                disable.invoke(service, 0, token, owner, user);
                token = null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.w("ChaojiBeauty", "Cannot release notification shade navigation", e);
            }
        }
    }

    /** Keep the actual carrier/radio widgets bound while the split-shade animator swaps its copies. */
    static final class StatusHeader implements ViewTreeObserver.OnPreDrawListener {
        final ViewGroup column;
        final View buttons, row, bell;
        final int originalMargin, originalBellVisibility, originalBottomPadding;
        final View[] suppressed;
        final Rect[] nativeClip;
        final Rect empty = new Rect();
        final View carrier, radios, panel, lockCarrier;

        static StatusHeader attach(View panel) {
            View column = find(panel, "simple_qs_container");
            View row = find(panel, "header_container"), buttons = find(panel, "button_container_parent");
            View bell = find(panel, "settings_button");
            if (!(column instanceof LinearLayout) || row == null || buttons == null || bell == null
                    || row.getParent() != column || buttons.getParent() != column
                    || ((ViewGroup)column).getChildCount() != 2 || ((ViewGroup)column).getChildAt(0) != buttons
                    || !(column.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return null;
            return new StatusHeader((ViewGroup)column, buttons, row, bell, panel);
        }

        StatusHeader(ViewGroup column, View buttons, View row, View bell, View panel) {
            this.column = column;
            this.buttons = buttons;
            this.row = row;
            this.bell = bell;
            this.panel = panel;
            lockCarrier = find(panel.getRootView(),"keyguard_carrier_text");
            originalBellVisibility = bell.getVisibility();
            originalBottomPadding = column.getPaddingBottom();
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams)column.getLayoutParams();
            originalMargin = params.topMargin;
            // Move the original top space below the two rows, preserving the first notification's position.
            params.topMargin = 0;
            column.setLayoutParams(params);
            column.setPaddingRelative(column.getPaddingStart(), column.getPaddingTop(), column.getPaddingEnd(),
                    originalBottomPadding + originalMargin);
            column.bringChildToFront(buttons);
            bell.setVisibility(View.INVISIBLE);
            carrier = find(panel,"qs_clock_container");
            radios = find(panel,"quick_qs_status_icons");
            suppressed = new View[]{row, find(panel,"oplus_fake_carrier_container"),
                    find(panel,"simple_qs_fake_statusbar_icons_container")};
            nativeClip = new Rect[suppressed.length];
            for (int i=0;i<suppressed.length;i++) {
                View v=suppressed[i];
                if (v!=null) { nativeClip[i]=v.getClipBounds(); v.setClipBounds(empty); }
            }
            column.getViewTreeObserver().addOnPreDrawListener(this);
        }

        @Override public boolean onPreDraw() {
            if (bell.getVisibility() == View.VISIBLE) bell.setVisibility(View.INVISIBLE);
            return true;
        }

        int centerY() {
            int center = row.getHeight()/2;
            if (lockCarrier!=null && lockCarrier.getHeight()>0) {
                center = layoutOffset(lockCarrier,false)+lockCarrier.getHeight()/2;
            }
            return center;
        }

        int statusBottomInWindow(int[] origin){
            panel.getLocationInWindow(origin);
            int height=Math.max(lockCarrier==null?0:lockCarrier.getHeight(),radios==null?0:radios.getHeight());
            return origin[1]+Math.round(LiveTune.dp(panel,"statusY")+
                    (centerY()+height/2f)*LiveTune.size("statusSize"));
        }

        void draw(Canvas c) {
            int center = centerY();
            View label = IconModule.enabled && lockCarrier instanceof android.widget.TextView
                    && lockCarrier.getWidth()>0 && ((android.widget.TextView)lockCarrier).length()>0
                    ? lockCarrier : carrier;
            int save=c.save();
            float scale=LiveTune.size("statusSize");
            c.translate(LiveTune.dp(panel,"statusX"),LiveTune.dp(panel,"statusY"));
            c.scale(scale,scale,panel.getWidth()*.5f,0);
            if(LiveTune.active()){
                int left=label==null?0:layoutOffset(label==carrier && lockCarrier!=null?lockCarrier:label,true);
                int right=radios==null?panel.getWidth():layoutOffset(radios,true)+radios.getWidth();
                int height=Math.max(label==null?0:label.getHeight(),radios==null?0:radios.getHeight());
                LiveTune.statusInk(panel,left,center-height*.5f,right,center+height*.5f,scale);
            }
            drawAtTop(c,label,center);
            drawAtTop(c,radios,center);
            c.restoreToCount(save);
        }

        private int layoutOffset(View item,boolean x) {
            int value=0;
            for (View v=item;v!=panel && v.getParent() instanceof View;v=(View)v.getParent())
                value+=x ? v.getLeft() : v.getTop();
            return value;
        }

        private void drawAtTop(Canvas c, View item, int center) {
            if (item==null || item.getWidth()==0) return;
            int x=layoutOffset(item==carrier && lockCarrier!=null ? lockCarrier : item,true);
            int save=c.save();
            c.translate(x,center-item.getHeight()*.5f);
            item.draw(c);
            c.restoreToCount(save);
        }

        void restore() {
            column.getViewTreeObserver().removeOnPreDrawListener(this);
            for (int i=0;i<suppressed.length;i++) {
                View v=suppressed[i];
                if (v!=null && empty.equals(v.getClipBounds())) v.setClipBounds(nativeClip[i]);
            }
            column.bringChildToFront(row);
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams)column.getLayoutParams();
            params.topMargin = originalMargin;
            column.setLayoutParams(params);
            column.setPaddingRelative(column.getPaddingStart(), column.getPaddingTop(), column.getPaddingEnd(), originalBottomPadding);
            bell.setVisibility(originalBellVisibility);
        }
    }

}
