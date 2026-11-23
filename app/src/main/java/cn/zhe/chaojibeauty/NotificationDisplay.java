package cn.zhe.chaojibeauty;

import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import static de.robv.android.xposed.XposedHelpers.*;

/** One display choice, with native keyguard and unlocked list hosts. */
final class NotificationDisplay {
    static final int COUNT = 0, STACK = 1, LIST = 2;
    private final NotificationHistory history;
    private final WidgetAccessoryLayout accessories;
    private int preferred = STACK, mode = STACK;
    private boolean enabled, shade, lockHistory, pinch, consumed, inside, tappedCount;
    private float downX, downY, initialSpan;
    private Object lockController;
    private ViewGroup list;
    private final Rect bounds = new Rect();
    private final WeakHashMap<LinearLayout, Boolean> cards = new WeakHashMap<>();
    private ViewGroup watchedList;
    private Object ambientState;

    boolean sectionLayout() {return historyInsetActive();}

    boolean ownsNativeFade(Object drawer) {
        return historyInsetActive() && drawer==getObjectField(list,"mOplusDispatchDrawHook");
    }

    private boolean historyInsetActive() {
        return enabled && reveal.active(list) && (shade || lockHistory || wasPlainLock);
    }

    private void refreshStackGeometry() {
        if (list == null) return;
        callMethod(list, "updateContentHeight");
        callMethod(list, "requestChildrenUpdate$1");
    }
    private ShadeEntry shadeEntry;
    private final android.view.ViewTreeObserver.OnPreDrawListener lockLayout = this::updateLockLayout;
    private boolean updateLockLayout() {
        if (!enabled || list==null) return true;
        boolean plainLock = !shade && getIntField(list,"mStatusBarState")==1 && lockState()==0;
        if (!shade && !lockHistory && plainLock!=wasPlainLock && !reveal.dragging()) {
            reveal.reset();
            initialize=plainLock && mode!=COUNT;
        }
        wasPlainLock=plainLock;
        syncCountVisibility();
        layoutCount(!shade && !lockHistory && (LiveTune.custom("countX") || LiveTune.custom("countY") || LiveTune.custom("countSize")));
        if(!shade && (plainLock || lockHistory) && keyguardStatus!=null){
            float size=LiveTune.size("statusSize");
            statusLayout.apply(keyguardStatus,LiveTune.dp(list,"statusX"),LiveTune.dp(list,"statusY"),size,size);
        }else statusLayout.clear();
        if (shade || plainLock || lockHistory) {
            reveal.attach(list,shade);
            if (initialize && !reveal.dragging()) {
                initialize=false;
                reveal.prepare(reveal.entryStop());
            } else reveal.refresh();
        }
        float visible = mode == COUNT ? 0 : reveal.dimVisibility();
        if(shadeEntry!=null){
            shadeEntry.notificationVisibility(visible);
            shadeEntry.clearBackdropBelow(NotificationViewport.layoutY(list,backdropOrigin)+history.foldEdge(list));
        }
        float expansion=lockHistory && !shade && !countSuppressed
                ? reveal.lockExpansion() : 0;
        if(lockHistory && !shade && reveal.landing()==NotificationReveal.OPEN && !reveal.dragging() && !reveal.settling()
                && !history.hasLockContent(list) && getIntField(list,"mStatusBarState")==1)reveal.prepare(NotificationReveal.PARKED);
        if(!expandedEmptyLock()){
            emptyReturnPending=false;
        }else if(!emptyReturnPending){
            emptyReturnPending=true;
            list.postOnAnimation(this::returnEmptyLock);
        }
        countVisible=!shade && lockHistory && reveal.lockCount() && history.compact.hasCount()
                && !countSuppressed;
        history.expandedKeyguard(list,expansion);
        return true;
    }
    private final NotificationReveal reveal;
    private View tappedGroup;
    private boolean nativeLockStream, emptyReturnPending, summaryTap, lockCountTap;

    private boolean screenOff, countSuppressed;
    private android.os.PowerManager power;

    void ambient(boolean off){
        screenOff=off;
        syncCountVisibility();
    }

    private void syncCountVisibility(){
        if(!enabled || list==null)return;
        boolean suppress=screenOff || !power.isInteractive() || getBooleanField(ambientState,"mDozing")
                || getFloatField(ambientState,"mDozeAmount")>.05f;
        if(countSuppressed==suppress)return;
        countSuppressed=suppress;
        if(suppress){countVisible=false;countDown=-1;}
        history.suppressCount(suppress);
        for(LinearLayout card:cards.keySet()){
            View label=card.findViewWithTag(LABEL);
            if(label!=null)label.setVisibility(suppress?View.INVISIBLE:View.VISIBLE);
        }
        list.invalidate();
    }

    private boolean expandedEmptyLock(){
        return enabled && lockHistory && !shade && !reveal.dragging() && list!=null
                && getIntField(list,"mStatusBarState")==2 && !history.hasLockContent(list);
    }

    private void returnEmptyLock(){
        if(emptyReturnPending && expandedEmptyLock() && lockController!=null && locked()){
            reveal.prepare(NotificationReveal.PARKED);
            callMethod(lockController,"gotoKeyguardState",true,"NotificationDisplay",0f,null);
        }
    }
    private final RestingLayout statusLayout=new RestingLayout();
    private View keyguardStatus;
    private final RestingLayout countLayout=new RestingLayout();
    private View countSlot;
    private void layoutCount(boolean active){
        View next=null;
        if(active && lockState()==1){
            for(LinearLayout card:cards.keySet()){
                if(card==null)continue;
                View label=card.findViewWithTag(LABEL);
                if(label==null || !label.isShown())continue;
                next=card;
                for(View v=card;v.getParent() instanceof View;v=(View)v.getParent()){
                    if(v.getClass().getSimpleName().equals("CapsuleItemView")){next=v;break;}
                }
                break;
            }
        }
        if(next!=countSlot){countLayout.clear();countSlot=next;}
        if(next!=null)countLayout.apply(next,LiveTune.dp(next,"countX"),LiveTune.dp(next,"countY"),LiveTune.size("countSize"),LiveTune.size("countSize"));
    }
    private final TunableFeedback feedback=new TunableFeedback();
    private boolean initialize, wasPlainLock, fromCount;
    private static final String LABEL = "ChaojiNotificationCount";

    NotificationDisplay(NotificationHistory history, WidgetAccessoryLayout accessories, LockClockMaterial clock) {
        this.accessories=accessories;
        this.history = history;
        reveal = new NotificationReveal(history,clock);
        reveal.scrollReady=this::requestListScroll;
        history.revealCount = this::openCount;
        history.compact.restore=this::openCount;
        history.compact.open = () -> {
            if(!historyInsetActive() || history.compact.height()==0)return;
            if(history.ownsAppGroup(history.compact.front))history.openAppGroup(history.compact.front);
            settle(NotificationReveal.PREVIEW);
        };
        history.parkClearedRows = () -> {
            if(historyInsetActive() && !reveal.dragging())settle(NotificationReveal.PARKED);
        };
    }

    void setDeck(NotificationDeck deck){reveal.deck=deck;}

    int groupSizeLimit(View row){
        return historyInsetActive() ? history.renderedGroupSize(row) : Integer.MAX_VALUE;
    }

    boolean ownsAppGroup(View row){return historyInsetActive() && history.ownsAppGroup(row);}

    int appGroupSize(View row){return history.totalCount(row);}

    boolean hasRoomForRear(View row,float depth){return !historyInsetActive() || reveal.hasRoomForRear(row,depth);}

    boolean hidesStackRear(View row){return historyInsetActive() && reveal.hidesRear(row);}

    boolean drawingMovingRow(ViewGroup host, View row) {
        return enabled && list==host && reveal.drawingMovingRow(host,row);
    }

    static TextView label(Context context) {
        TextView v = new TextView(context);
        v.setTag(LABEL);
        v.setTextColor(Color.WHITE);
        v.setTextSize(14);
        v.setIncludeFontPadding(false);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(NotificationViewport.dp(v,44));
        v.setFocusable(true);
        return v;
    }

    static void count(TextView label, int count) {
        String text = "• " + count + "个通知";
        if (!text.contentEquals(label.getText())) {
            android.text.SpannableString ink=new android.text.SpannableString(text);
            ink.setSpan(new CountDot(label),0,2,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setText(ink);
            label.setContentDescription(count + "个通知");
        }
    }

    private static final class CountDot extends android.text.style.ReplacementSpan {
        private final float radius, gap;
        CountDot(View view){
            radius=NotificationViewport.pixels(view,4.5f);
            gap=NotificationViewport.pixels(view,5);
        }
        @Override public int getSize(android.graphics.Paint paint,CharSequence text,int start,int end,
                android.graphics.Paint.FontMetricsInt metrics){
            return Math.round(radius*2+gap);
        }
        @Override public void draw(Canvas canvas,CharSequence text,int start,int end,float x,int top,int y,int bottom,
                android.graphics.Paint paint){
            float center=y+(paint.ascent()+paint.descent())/2;
            canvas.drawCircle(x+radius,center,radius,paint);
        }
    }

    void install(ClassLoader loader) throws ReflectiveOperationException {
        String base = "com.oplus.systemui.notification.lockscreen.";
        Class<?> manager = loader.loadClass(base + "statemanager.LockScreenNotificationExtStateManager");
        XposedBridge.hookAllConstructors(manager, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { lockController = p.thisObject; }
        });
        XposedBridge.hookAllMethods(manager, "getDefaultExtState", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (enabled && (int)p.getResult() != 2) p.setResult(preferred == COUNT ? 1 : 0);
            }
        });
        XposedBridge.hookAllMethods(manager, "onEnterKeyguard", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                lockController = p.thisObject;
                if (enabled && list != null) list.post(() -> {
                    if (!shade && locked() && lockState() != 2) {
                        select(preferred, false);
                        setLockPresentation(preferred == COUNT ? 1 : 0);
                    }
                });
            }
        });
        // The ordinary count and list share one poster. ColorOS normally replaces
        // the entire clock/widget scene with its small variant when rows appear.
        Class<?> clock = loader.loadClass("com.oplus.systemui.keyguard.clockstyle.KeyguardStyleClockControllerImpl");
        XposedBridge.hookMethod(clock.getDeclaredMethod("deliverPluginClockState",int.class,String.class,int.class,boolean.class,boolean.class),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!enabled || (int)p.args[0] != 2 || (int)p.args[2] != 0) return;
                int state = lockState();
                if (state != 0 && state != 1 && state != 3 && state != 10) return;
                p.args[2] = 1;
                if ("clockSizeChangeWrapper".equals(p.args[1])) p.args[3] = false;
            }
        });
        Class<?> card = loader.loadClass(base + "notification.CapsuleNotificationCardView");
        XposedBridge.hookAllMethods(card, "bind", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                LinearLayout v = (LinearLayout)p.thisObject;
                cards.put(v, true);
                bindCapsule(v);
            }
        });
        XC_MethodHook countInk = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!enabled) return;
                View node = (View)p.thisObject;
                while (node.getParent() instanceof View) {
                    node = (View)node.getParent();
                    if (!node.getClass().getSimpleName().equals("CapsuleItemView")) continue;
                    ViewGroup content = (ViewGroup)callMethod(node,"getContentContainer");
                    View count = content.findViewWithTag(LABEL);
                    if (count != null && count.isShown()) p.setResult(null);
                    break;
                }
            }
        };
        for (String type : new String[]{"capsule.CapsuleBackgroundView","capsule.stroke.StrokeView"})
            XposedBridge.hookAllMethods(loader.loadClass(base + type),"onDraw",countInk);
        Class<?> decider = loader.loadClass(base + "statemanager.LockScreenNotificationExtStateDecider");
        XposedBridge.hookAllMethods(decider,"onNsslSlideToDown",new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                int from = (int)p.args[0];
                if (enabled && (from == 0 || from == 3)) p.setResult(1);
            }
        });
        Class<?> host = loader.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
        XC_MethodHook ambientChanged=new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(p.thisObject==ambientState)syncCountVisibility();
            }
        };
        Class<?> ambient=loader.loadClass("com.android.systemui.statusbar.notification.stack.AmbientState");
        for(String method:new String[]{"setDozeAmount","setDozing"})XposedBridge.hookAllMethods(ambient,method,ambientChanged);
        Class<?> algorithm = loader.loadClass("com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm");
        XposedBridge.hookAllMethods(host,"updateChildren$1",new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if(historyInsetActive() && p.thisObject==list) reveal.measure();
            }
        });
        XposedBridge.hookAllMethods(algorithm,"getGapHeightForChild",new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(!historyInsetActive() || p.thisObject!=getObjectField(list,"mStackScrollAlgorithm"))return;
                float gap=(float)p.getResult();
                float adjustment=reveal.rowGap((View)p.args[3],gap);
                if(adjustment!=0)p.setResult(gap+adjustment);
            }
        });
        Class<?> edges=loader.loadClass("com.oplus.systemui.statusbar.notification.stack.OplusStackScrollAlgorithmExImpl");
        XposedBridge.hookAllMethods(edges,"calculateNotificationStackDrawStart",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(historyInsetActive() && p.args[0]==list)p.setResult((float)reveal.viewportTop());
            }
        });
        XposedBridge.hookAllMethods(edges,"afterResetViewStates",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if(historyInsetActive() && p.args[0]==list)
                    reveal.rollUnder((java.util.List<?>)getObjectField(p.args[1],"visibleChildren"));
            }
        });
        XC_MethodHook sectionState=new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                if(historyInsetActive() && p.thisObject==list)reveal.applySectionState();
            }
        };
        XposedBridge.hookAllMethods(host,"applyCurrentState$2",sectionState);
        XposedBridge.hookAllMethods(host,"startAnimationToState$1",sectionState);
        XposedBridge.hookAllMethods(host,"getScrollRange",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(historyInsetActive() && p.thisObject==list)p.setResult(reveal.scrollRange((int)p.getResult()));
            }
        });
        installMetrics(host,loader);
        installLiveCard(loader);
        XposedBridge.hookAllMethods(host,"getChildAtPosition",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(historyInsetActive() && p.thisObject==list && p.getResult() instanceof View
                        && (reveal.foldedRow((View)p.getResult()) || p.args.length==4 &&
                        !reveal.acceptsPoint((View)p.getResult(),(float)p.args[0],(float)p.args[3])))p.setResult(null);
            }
        });
        // Both shelf placement and actual row positions use this leading space minus native scrollY.
        XposedBridge.hookAllMethods(algorithm, "getScrimTopPaddingOrZero", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (historyInsetActive() && p.args[0] == ambientState)
                    p.setResult(reveal.leadingInset((float)callMethod(ambientState,"getStackY")));
            }
        });
        // The host computes its content height through a separate, integer-valued padding accessor.
        XposedBridge.hookAllMethods(host, "getScrimTopPaddingOrZero", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (historyInsetActive() && p.thisObject == list)
                    p.setResult(Math.round(reveal.leadingInset((float)callMethod(ambientState,"getStackY"))));
            }
        });
        java.lang.reflect.Method drawChild=host.getDeclaredMethod("drawChild",Canvas.class,View.class,long.class);
        installNativeSummaryControl(loader);
        // Leave the linear positions already computed by StackScrollAlgorithm intact.
        // The OEM pass otherwise pulls off-screen rows back over the section header.
        Class<?> stackManager=de.robv.android.xposed.XposedHelpers.findClassIfExists(
                "com.oplus.systemui.notification.lockscreen.stack.algorithm.StackAlgorithmManager",loader);
        if(stackManager!=null){
            XC_MethodHook linearViewport=new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(!historyInsetActive())return;
                    Object ext=callMethod(getObjectField(p.thisObject,"nsslExt"),"get");
                    if(callMethod(ext,"getView")==list)
                        p.setResult(((java.lang.reflect.Method)p.method).getReturnType()==boolean.class?Boolean.FALSE:null);
                }
            };
            for(String pass:new String[]{"applyDynamic","applyStatic","applySinkingDynamicLayout","applySinkingStaticLayout"})
                XposedBridge.hookAllMethods(stackManager,pass,linearViewport);
        }
        XposedBridge.hookMethod(host.getDeclaredMethod("dispatchDraw",Canvas.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (enabled && p.thisObject == list) {
                    Canvas c=(Canvas)p.args[0];
                    int save=reveal.beginViewport(c);
                    if(save!=0){
                        p.setObjectExtra("viewportDestination",c);
                        c=reveal.contentCanvas(c);
                        p.args[0]=c;
                        reveal.clipViewport(c);
                        reveal.drawCompact(c);
                        p.setObjectExtra("sectionViewport",save);
                    }
                    LiveTune.geometry(list,history,reveal,shade,lockHistory);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                Object save=p.getObjectExtra("sectionViewport");
                if(save==null)return;
                Canvas canvas=(Canvas)p.args[0];
                Canvas destination=(Canvas)p.getObjectExtra("viewportDestination");
                p.args[0]=destination;
                reveal.finishViewport(destination,(int)save,destination!=canvas);
            }
        });
        XposedBridge.hookMethod(drawChild,
                new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!enabled || p.thisObject != list) return;
                Canvas canvas = (Canvas)p.args[0];
                View row=(View)p.args[1];
                Boolean ordered=reveal.drawRollGroup(canvas,row,(long)p.args[2]);
                if(ordered!=null){p.setResult(ordered);return;}
                int save = reveal.beginRowDraw(list,canvas,row);
                if (save < 0) p.setResult(false);
                else if (save > 0) p.setObjectExtra("ChaojiRowCanvas", save);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object save = p.getObjectExtra("ChaojiRowCanvas");
                if (save != null) ((Canvas)p.args[0]).restoreToCount((int)save);
            }
        });
        XposedBridge.hookAllMethods(host,"setStatusBarState",new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.setObjectExtra("previousStatus",getIntField(p.thisObject,"mStatusBarState"));
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                watch((ViewGroup)p.thisObject);
                int previous=(int)p.getObjectExtra("previousStatus"),next=getIntField(list,"mStatusBarState");
                if(enabled && previous==2 && next==1 && feedback.recent())feedback.switched(list.getRootView(),false);
                if (enabled && previous!=2 && upwardLockIntent && next==2) {
                    feedback.switched(list.getRootView(),true);
                    upwardLockIntent=false;
                }
                list.post(() -> {
                    syncLockedList();
                    if(next==2 && !reveal.dragging() && !reveal.settling())
                        restoreRestingKeyguard(reveal.landing());
                });
            }
        });
    }

    private boolean ownsNativeSummary(Object controller){
        if(!historyInsetActive())return false;
        Object author=callMethod(controller,"getAuthorView");
        return author instanceof View && ((View)author).getRootView()==list.getRootView();
    }

    private void installNativeSummaryControl(ClassLoader loader) throws ClassNotFoundException {
        Class<?> overlay=loader.loadClass("com.oplus.systemui.notification.row.overlay.OplusNotificationOverlayControllerImpl");
        XposedBridge.hookAllMethods(overlay,"setNotificationOverlayInfo",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                if(!ownsNativeSummary(p.thisObject))return;
                p.args[0]=null;
                p.args[1]=0L;
                p.args[2]=false;
            }
        });
        XposedBridge.hookAllMethods(overlay,"setOverlayAlpha",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                if(ownsNativeSummary(p.thisObject))p.args[0]=0f;
            }
        });
    }

    private void installLiveCard(ClassLoader loader) throws ClassNotFoundException {
        Class<?> type=loader.loadClass("com.oplus.systemui.statusbar.notification.customcard.OplusCustomRow");
        WeakHashMap<View,Float> contentScale=new WeakHashMap<>();
        XposedBridge.hookAllMethods(type,"onMeasure",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                ViewGroup card=(ViewGroup)p.thisObject;
                View content=(View)callMethod(card,"getContentLayout");
                if(content==null || content.getParent()!=card)return;
                Float previous=contentScale.remove(content);
                if(previous!=null){content.setScaleX(1);content.setScaleY(1);content.resetPivot();}
                if(!historyInsetActive() || card.getParent()!=list)return;
                
                int height=content.getMeasuredHeight(),width=content.getMeasuredWidth();
                if(height<=0 || width<=0)return;
                int limit=reveal.liveHeightLimit(card);
                if(limit<=0)return;
                float scale=Math.min(1,limit/(float)height);
                if(scale<1){
                    // Measure the entire RemoteViews content at its logical width:
                    // Android maps both painting and touch through the same transform.
                    content.measure(View.MeasureSpec.makeMeasureSpec(Math.round(width/scale),View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                    content.setPivotX(0);content.setPivotY(0);
                    content.setScaleX(scale);content.setScaleY(scale);contentScale.put(content,scale);
                }
                // Keep the translucent host and the RemoteViews face on the same bottom edge.
                int face=Math.min(limit,Math.round(scale*content.getMeasuredHeight()));
                callMethod(card,"setMeasuredDimension",card.getMeasuredWidth(),face);
            }
        });
        XposedBridge.hookAllMethods(type,"getIntrinsicHeight",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                View card=(View)p.thisObject;
                if(historyInsetActive() && card.getParent()==list && card.getMeasuredHeight()>0)
                    p.setResult(card.getMeasuredHeight());
            }
        });
    }

    private void installMetrics(Class<?> host,ClassLoader loader) throws ClassNotFoundException {
        // Scope OEM measurement values to a traversal. Other SystemUI surfaces
        // share this stack instance and immediately recover their own dimensions.
        XC_MethodHook metrics=new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                if(!historyInsetActive() || list!=p.thisObject)return;
                if("onMeasure".equals(p.method.getName()))reveal.measureActivitySlots();
                Object algo=getObjectField(list,"mStackScrollAlgorithm");
                
                p.setObjectExtra("nativeMetrics",new float[]{getIntField(list,"mSidePaddings"),
                        getIntField(list,"mPaddingBetweenElements"),getFloatField(algo,"mPaddingBetweenElements")});
                setIntField(list,"mSidePaddings",NotificationViewport.dp(list,15));
                setIntField(list,"mPaddingBetweenElements",NotificationViewport.dp(list,7));
                setFloatField(algo,"mPaddingBetweenElements",NotificationViewport.dp(list,7));
            }
            @Override protected void afterHookedMethod(MethodHookParam p){
                float[] saved=(float[])p.getObjectExtra("nativeMetrics");
                if(saved==null)return;
                setIntField(p.thisObject,"mSidePaddings",(int)saved[0]);
                setIntField(p.thisObject,"mPaddingBetweenElements",(int)saved[1]);
                setFloatField(getObjectField(p.thisObject,"mStackScrollAlgorithm"),"mPaddingBetweenElements",saved[2]);
            }
        };
        for(String name:new String[]{"onMeasure","onLayout","updateChildren$1","updateContentHeight","getPositionInLinearLayout$1"})
            XposedBridge.hookAllMethods(host,name,metrics);
        Class<?> row=loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow");
        Class<?> contentLayout=loader.loadClass("com.android.systemui.statusbar.notification.row.NotificationContentView");
        XC_MethodHook compactPadding=new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                View layout=(View)p.thisObject;
                if(!historyInsetActive() || !(layout.getParent() instanceof View))return;
                View owner=(View)layout.getParent();
                if(owner.getParent()!=list || !history.ordinaryRow(owner))return;
                View face=(View)callMethod(layout,"getContractedChild");
                if(face==null)return;
                int id=face.getResources().getIdentifier("notification_headerless_view_column","id","android");
                View column=id==0?null:face.findViewById(id);
                if(column==null || !(column.getLayoutParams() instanceof ViewGroup.MarginLayoutParams))return;
                ViewGroup.MarginLayoutParams lp=(ViewGroup.MarginLayoutParams)column.getLayoutParams();
                int lo=NotificationViewport.dp(layout,10),hi=NotificationViewport.dp(layout,12);
                if(lp.topMargin>=lo && lp.topMargin<=hi && lp.bottomMargin>=lo && lp.bottomMargin<=hi)return;
                p.setObjectExtra("column",lp);
                p.setObjectExtra("margins",new int[]{lp.topMargin,lp.bottomMargin});
                lp.topMargin=lp.bottomMargin=NotificationViewport.dp(layout,11);
            }
            @Override protected void afterHookedMethod(MethodHookParam p){
                int[] old=(int[])p.getObjectExtra("margins");
                if(old==null)return;
                ViewGroup.MarginLayoutParams lp=(ViewGroup.MarginLayoutParams)p.getObjectExtra("column");
                lp.topMargin=old[0];lp.bottomMargin=old[1];
            }
        };
        XposedBridge.hookAllMethods(contentLayout,"onMeasure",compactPadding);
        XposedBridge.hookAllMethods(contentLayout,"onLayout",compactPadding);
        XC_MethodHook fitActivity=new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                fitActivityFace((ViewGroup)p.thisObject);
            }
        };
        XposedBridge.hookAllMethods(contentLayout,"onLayout",fitActivity);
        XposedBridge.hookAllMethods(contentLayout,"setContentHeight",fitActivity);
        Class<?> outline=loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableOutlineView");
        for(String name:new String[]{"getTopCornerRadius","getBottomCornerRadius","getMaxRadius"})
            XposedBridge.hookAllMethods(outline,name,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){
                    View face=(View)p.thisObject;
                    if(historyInsetActive() && face.getParent()==list && (history.isCard(face) || history.isLiveContainer(face))
                            && (float)p.getResult()>0)p.setResult(NotificationViewport.pixels(face,22));
                }
            });
        XposedBridge.hookAllMethods(row,"getIntrinsicHeight",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                View face=(View)p.thisObject;
                if(!historyInsetActive() || face.getParent()!=list || p.hasThrowable()
                        || (boolean)callMethod(face,"isExpanded",false) || (boolean)callMethod(face,"isSummaryWithChildren")
                        || (boolean)callMethod(face,"isHeadsUpState"))return;
                boolean ordinary=history.ordinaryRow(face);
                
                int h=(int)p.getResult();
                if(h<=0)return;
                // Custom RemoteViews can carry controls. Respect their measured content.
                View contracted=(View)callMethod(callMethod(face,"getShowingLayout"),"getContractedChild");
                int content=contracted==null?0:contracted.getMeasuredHeight();
                int limit=ordinary?NotificationViewport.dp(face,68):reveal.liveHeightLimit(face);
                int natural=Math.max(content,Math.max(NotificationViewport.dp(face,ordinary?58:96),Math.min(h,limit)));
                p.setResult(ordinary || limit<=0?natural:Math.min(limit,natural));
            }
        });
    }

    private final WeakHashMap<View,Boolean> fittedActivityFaces=new WeakHashMap<>();

    private void fitActivityFace(ViewGroup layout){
        if(!(layout.getParent() instanceof View))return;
        View row=(View)layout.getParent();
        boolean active=historyInsetActive() && row.getParent()==list && history.isLiveActivity(row);
        if(!active && fittedActivityFaces.isEmpty())return;
        int room=active?(int)callMethod(row,"getActualHeight"):0;
        if(active && room<=0)room=reveal.liveHeightLimit(row);
        View current=active?(View)callMethod(layout,"getViewForVisibleType",callMethod(layout,"getVisibleType")):null;
        for(int i=0;i<layout.getChildCount();i++){
            View face=layout.getChildAt(i);
            if(face==current && room>0 && face.getHeight()>room){
                float scale=room/(float)face.getHeight();
                face.setPivotX(0);face.setPivotY(0);face.setScaleX(scale);face.setScaleY(scale);
                fittedActivityFaces.put(face,true);
            }else if(fittedActivityFaces.remove(face)!=null){
                face.setScaleX(1);face.setScaleY(1);face.resetPivot();
            }
        }
    }

    private void watch(ViewGroup host) {
        if (watchedList==host) return;
        if (watchedList!=null && watchedList.getViewTreeObserver().isAlive())
            watchedList.getViewTreeObserver().removeOnPreDrawListener(lockLayout);
        reveal.reset();
        statusLayout.clear();countLayout.clear();countSlot=null;
        history.expandedKeyguard(null,0);
        watchedList=list=host;
        history.watchCollection(host);
        ambientState=getObjectField(host,"mAmbientState");
        power=host.getContext().getSystemService(android.os.PowerManager.class);
        keyguardStatus=LockCenter.find(host.getRootView(),"keyguard_header");
        LiveTune.bind(host,() -> {
            if(list==null)return;
            refreshStackGeometry();
            reveal.retune();
            View root=list.getRootView();
            invalidateTree(root);
            root.requestLayout();
            list.postDelayed(list::invalidate,125);
            if(shadeEntry!=null)shadeEntry.retune();
        },() -> {
            if(enabled && list!=null && list.isShown())LiveTune.geometry(list,history,reveal,shade,lockHistory);
        });
        wasPlainLock=false;
        initialize=true;
        list.getViewTreeObserver().addOnPreDrawListener(lockLayout);
    }

    private static void invalidateTree(View view) {
        view.invalidate();
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)invalidateTree(group.getChildAt(i));
        }
    }

    private void syncLockedList() {
        if (list == null || shade) return;
        int state=getIntField(list,"mStatusBarState");
        boolean open = enabled && locked() && (state==2 || state==1 && mode!=COUNT);
        if (open && !lockHistory) {
            View clear = LockCenter.find(list.getRootView(), "clear_all");
            if (clear == null) return;
            history.setStyle(mode == COUNT ? STACK : mode, preferred == COUNT);
            history.open(list,clear,() -> lockHistory,null);
            lockHistory = true;
            reveal.attach(list,false);
            refreshStackGeometry();
        } else if (!open && lockHistory) {
            history.close();
            reveal.reset();
            lockHistory = false;
            refreshStackGeometry();
        }
    }

    void screenOn(){
        if(!enabled || shade || !lockHistory || !locked() || list==null)return;
        if(reveal.landing()!=reveal.entryStop() || getIntField(list,"mStatusBarState")==2){
            callMethod(list,"resetScrollPosition");
            int entry=reveal.entryStop();
            restoreRestingKeyguard(entry);
            reveal.prepare(entry);
            history.resetExpandedHistoryGroups();
        }
    }

    void configure(boolean on, int value) {
        int style = Math.max(COUNT,Math.min(LIST,value));
        if (enabled == on && preferred == style) return;
        reveal.reset();
        initialize=on;
        countVisible=false;countDown=-1;
        enabled = on;
        if (!on) { history.expandedKeyguard(null,0);statusLayout.clear();countLayout.clear();countSlot=null; if (shadeEntry != null) shadeEntry.notificationVisibility(1); }
        preferred = style;
        select(style,true);
        for (LinearLayout v : new java.util.ArrayList<>(cards.keySet())) if (v != null) bindCapsule(v);
        syncLockedList();
    }

    void enterShade(ViewGroup host, ShadeEntry entry) {
        countVisible=false;countDown=-1;
        shadeEntry=entry;
        watch(host);
        if (lockHistory) { history.close(); lockHistory = false; }
        reveal.reset();
        initialize=true;
        shade = true;
        reveal.attach(list,true);
        select(preferred==COUNT?STACK:preferred,false);
        refreshStackGeometry();
    }
    void leaveShade() {
        shadeEntry=null;
        reveal.reset();
        initialize=false;
        shade = false;
        select(preferred,false);
        refreshStackGeometry();
        if (list != null) list.post(this::syncLockedList);
    }

    private boolean locked() {
        return list != null && list.getContext().getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    private int lockState() { return lockController == null ? -1 : (int)callMethod(lockController,"getExtState"); }

    private void setLockPresentation(int state) {
        if (lockController != null && getBooleanField(lockController,"isKeyguardState") && lockState() != state && lockState() != 2)
            callMethod(lockController,"goToExtState",true,state,"NotificationDisplay",101,null);
    }

    private void select(int style, boolean changeLock) {
        mode = style;
        history.setStyle(style, preferred == COUNT);
        if (!enabled || !changeLock || shade || !locked() || lockController == null) return;
        int state = lockState();
        if (state == 2 || !getBooleanField(lockController,"isKeyguardState")) return;
        if (style == LIST && state != 10 && history.hasLockContent(list)) {
            callMethod(lockController,"tryGotoShadeLockState",true,"NotificationDisplay",0f,null,null);
        } else if (style != LIST) setLockPresentation(style == COUNT ? 1 : 0);
    }

    private void bindCapsule(LinearLayout card) {
        Object data = getObjectField(card,"currentData");
        Object icon = data == null ? null : callMethod(data,"getIconData");
        int number = data == null ? 0 : (int)callMethod(data,"getNotifCount");
        boolean ordinary = enabled && number > 0 && (icon == null || !getBooleanField(icon,"isMediaPlayer"));
        View content = (View)callMethod(card,"getContentLayout");
        TextView text = card.findViewWithTag(LABEL);
        if (!ordinary) {
            if (text != null) { card.removeView(text); content.setVisibility(View.VISIBLE); }
            return;
        }
        // The system owns this slot and swaps it for media or a live activity.
        if (text == null) {
            text = label(card.getContext());
            card.addView(text,new LinearLayout.LayoutParams(-1,-1));
            text.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewDetachedFromWindow(View v) { v.animate().cancel(); }
                @Override public void onViewAttachedToWindow(View v) { animateCount(v); }
            });
            if (text.isAttachedToWindow()) animateCount(text);
            text.setOnClickListener(v -> openCount());
        }
        Object controller = getObjectField(card,"controller");
        Object aggregate = controller == null ? null : getObjectField(controller,"lastAggregateData");
        if (aggregate != null) number = Math.max(number,(int)callMethod(aggregate,"getNotifCount"));
        content.setVisibility(View.GONE);
        text.setVisibility(countSuppressed?View.INVISIBLE:View.VISIBLE);
        count(text,number);
    }

    private static void animateCount(View view) {
        view.animate().cancel();
        view.setScaleX(.25f);
        view.setScaleY(.25f);
        view.setAlpha(0f);
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start();
    }

    private void unfoldCount() {
        int style=preferred==LIST ? LIST : STACK;
        select(style,false);
        if (!shade) setLockPresentation(0);
        initialize=false;
        reveal.attach(list,shade);
        reveal.prepare(NotificationReveal.HIDDEN);
    }

    private void openCount() {
        if(!enabled || countSuppressed || list==null)return;
        if(reveal.lockCount()){
            settle(NotificationReveal.PARKED);
            return;
        }
        unfoldCount();
        list.postOnAnimation(() -> {
            if (list==null || !enabled || (!shade && !locked())) return;
            wasPlainLock=!shade && lockState()==0;
            initialize=false;
            settle(NotificationReveal.PARKED);
        });
    }

    private void restoreRestingKeyguard(int stop){
        if(stop!=NotificationReveal.OPEN && mode==STACK && enabled && !shade && lockHistory
                && locked() && lockController!=null && getIntField(list,"mStatusBarState")==2)
            callMethod(lockController,"gotoKeyguardState",true,"NotificationDisplay",0f,null);
    }

    private void requestListScroll(){
        if(!shade && lockHistory && locked() && lockController!=null && getIntField(list,"mStatusBarState")==1)
            callMethod(lockController,"tryGotoShadeLockState",true,"NotificationDisplay",0f,null,null);
    }

    private void settle(int stop) {
        boolean changed=reveal.changesStop(stop);
        restoreRestingKeyguard(stop);
        reveal.settle(stop,() -> {
            if(changed && list!=null)feedback.switched(list,false);
            if(stop==NotificationReveal.OPEN)requestListScroll();
            restoreRestingKeyguard(stop);
            if(stop==NotificationReveal.PARKED || stop==NotificationReveal.HIDDEN)history.resetExpandedHistoryGroups();
        });
    }

    // ColorOS may handle some touch streams before NotificationShadeWindowView.
    // Only handle the release of an unclaimed upward swipe on the focused count page.
    volatile boolean countVisible;
    private long countDown=-1;
    private float countX,countY;

    private boolean countArea(View window,float y){
        list.getLocationOnScreen(stackOrigin);
        int bottom=stackOrigin[1]+history.foldEdge(list);
        window.getLocationOnScreen(stackOrigin);
        View bouncer=LockCenter.find(window,"keyguard_bouncer");
        return (bouncer==null || !bouncer.isShown()) && y>=stackOrigin[1]+NotificationViewport.statusBottom(window) && y<bottom;
    }

    void observeCountRelease(MotionEvent e,int id,long windowDown){
        if(!enabled || countSuppressed || shade || list==null || !reveal.lockCount() || !list.hasWindowFocus()
                || list.getDisplay()==null || list.getDisplay().getDisplayId()!=id)return;
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            countX=e.getRawX();countY=e.getRawY();
            if(startsNativeLockGesture(list.getRootView(),e)){countDown=-1;return;}
            countDown=(countArea(list.getRootView(),countY) || history.compact.countHit(countX,countY))?e.getDownTime():-1;
        }else if(action==MotionEvent.ACTION_UP){
            float lift=countY-e.getRawY();
            int slop=ViewConfiguration.get(list.getContext()).getScaledTouchSlop();
            boolean unclaimed=countDown==e.getDownTime() && Math.abs(windowDown-countDown)>2;
            boolean tap=Math.hypot(countX-e.getRawX(),lift)<=slop && history.compact.countHit(countX,countY);
            boolean upward=lift>2*slop && lift>1.4f*Math.abs(countX-e.getRawX());
            if(unclaimed && locked()){
                if(tap)openCount();
                else if(upward)settle(lift>reveal.countOpenDistance()
                        ? NotificationReveal.OPEN:NotificationReveal.PARKED);
            }
            countDown=-1;
        }else if(action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_POINTER_DOWN)countDown=-1;
    }

    // Decide at DOWN: entering the list during the swipe never transfers ownership.
    boolean startsNativeLockGesture(View window,MotionEvent event){
        if(!enabled || shade || list==null || list.getRootView()!=window || !locked())return false;
        float x=event.getRawX(),y=event.getRawY();
        if(y>=NotificationViewport.unlockBandTopOnScreen(window,stackOrigin))return true;
        if(history.compact.countHit(x,y))return false;
        for(int i=0;i<2;i++){
            View button=history.tuneButton(i);
            if(button!=null && button.isShown() && button.getGlobalVisibleRect(bounds)
                    && bounds.contains((int)x,(int)y))return false;
        }
        if(lockState()==1){
            for(LinearLayout card:cards.keySet()){
                TextView label=card.findViewWithTag(LABEL);
                if(label!=null && label.isShown() && label.getGlobalVisibleRect(bounds)
                        && bounds.contains((int)x,(int)y))return false;
            }
        }
        if(!history.hasLockContent(list))return true;
        list.getLocationOnScreen(stackOrigin);
        return y>=stackOrigin[1]+history.foldEdge(list);
    }
    boolean systemOwnsLockStream(){
        return nativeLockStream;
    }
    boolean nativeLockGesture(View window,MotionEvent event){
        // ColorOS may reinject a home-bar DOWN with a different downTime from its MOVE.
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
            nativeLockStream=startsNativeLockGesture(window,event);
            // Expanded history uses SHADE_LOCKED for native list scrolling. Restore
            // KEYGUARD before it receives DOWN so its first swipe can unlock.
            if(nativeLockStream && lockHistory && lockController!=null && getIntField(list,"mStatusBarState")==2)
                callMethod(lockController,"gotoKeyguardState",true,"NotificationDisplay",0f,null);
        }
        return nativeLockStream;
    }
    void finishLockDispatch(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL)nativeLockStream=false;
    }

    boolean touch(View window, MotionEvent e, boolean openShade, Runnable cancel) {
        if (nativeLockStream || !enabled || list == null || list.getRootView() != window) return false;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            consumed = pinch = tappedCount = false;
            gestureDownTime=e.getDownTime();
            downX = e.getRawX(); downY = e.getRawY();
            boolean keyguard = locked();
            inside = ((shade && openShade) || keyguard) && list.getGlobalVisibleRect(bounds)
                    && bounds.contains((int)downX,(int)downY);
            fromCount = (shade && mode==COUNT) || (keyguard && !shade && lockState()==1);
            if ((shade && openShade || lockHistory) && (reveal.presented() || mode==COUNT)) {
                list.getLocationOnScreen(stackOrigin);
                int top=stackOrigin[1]-Math.round(list.getTranslationY())+(int)callMethod(list,"getIntrinsicPadding");
                int bottom=stackOrigin[1]-Math.round(list.getTranslationY())+list.getHeight();
                inside=downY>=history.contentTouchTop(top) && downY<bottom || history.footerHit(downX,downY);
            }
            if (inside && !reveal.presented() && history.style() != COUNT) {
                inside = downY >= bounds.top + NotificationViewport.statusBottom(list);
            }
            tappedGroup=historyInsetActive()?appGroupAt(downX,downY):null;
            summaryTap=(openShade || keyguard) && history.compact.hit(list,downX,downY);
            lockCountTap=(openShade || keyguard) && history.compact.countHit(downX,downY);
            boolean countArea=false;
            if((openShade || keyguard) && reveal.lockCount()){
                countArea=countArea(window,downY);
                inside=countArea || lockCountTap;
            }
            if(historyInsetActive() && (reveal.landing()==NotificationReveal.OPEN
                    || reveal.landing()==NotificationReveal.PREVIEW)){
                list.getLocationOnScreen(stackOrigin);
                float y=downY-stackOrigin[1];
                inside=y>=reveal.viewportTop() && y<history.foldEdge(list)
                        && downX>=stackOrigin[0] && downX<stackOrigin[0]+list.getWidth();
            }
            if(summaryTap || lockCountTap)inside=true;
            if(inside)reveal.stopFling();
            boolean nativeCountAvailable=false;
            if (keyguard && !shade && lockState() == 1) {
                for (LinearLayout card : cards.keySet()) {
                    TextView text = card.findViewWithTag(LABEL);
                    if(text==null || !text.isShown() || !text.getGlobalVisibleRect(bounds))continue;
                    nativeCountAvailable=true;
                    if (bounds.contains((int)downX,(int)downY)) { tappedCount = true; inside = true;  break; }
                }
            }
            if(nativeCountAvailable && blankLockContent(window,downX,downY)){inside=true;}
            feedback.begin(downX,downY);
            upwardLockIntent=false;
            lockGesture=keyguard && !shade && getIntField(list,"mStatusBarState")==1;
            lockBlank=lockGesture && lockController!=null && blankLockContent(window,downX,downY);
            contentGesture=(shade && openShade || lockHistory) && inside;
            rowGesture=(keyguard || shade && openShade) && hitsOrdinaryRow(downX,downY);
            liveTouch=false;
            if(LiveTune.active() && (openShade || lockHistory || lockBlank || rowGesture || tappedCount)){
                View bouncer=LockCenter.find(window,"keyguard_bouncer");
                liveTouch=bouncer==null || !bouncer.isShown();
            }
            if(liveTouch)LiveTune.touch(e);
            return false;
        }
        if(liveTouch)LiveTune.touch(e);
        if(lockCountTap){
            if(action==MotionEvent.ACTION_UP && history.compact.countHit(e.getRawX(),e.getRawY())){
                lockCountTap=false;cancel.run();history.compact.clickCount();return true;
            }
            if(action!=MotionEvent.ACTION_MOVE || Math.hypot(e.getRawX()-downX,e.getRawY()-downY)
                    >ViewConfiguration.get(window.getContext()).getScaledTouchSlop())lockCountTap=false;
        }
        if(tappedGroup!=null){
            int slop=ViewConfiguration.get(window.getContext()).getScaledTouchSlop();
            if(action==MotionEvent.ACTION_UP && tappedGroup==appGroupAt(e.getRawX(),e.getRawY())
                    && Math.hypot(e.getRawX()-downX,e.getRawY()-downY)<=slop
                    && e.getEventTime()-e.getDownTime()<ViewConfiguration.getLongPressTimeout()){
                View target=tappedGroup;tappedGroup=null;cancel.run();
                history.openAppGroup(target);
                if(reveal.landing()==NotificationReveal.PARKED)settle(NotificationReveal.PREVIEW);
                return true;
            }
            if(action!=MotionEvent.ACTION_MOVE || Math.hypot(e.getRawX()-downX,e.getRawY()-downY)>slop)tappedGroup=null;
        }
        if(summaryTap){
            if(action==MotionEvent.ACTION_UP && history.compact.hit(list,e.getRawX(),e.getRawY())){
                summaryTap=false;cancel.run();history.compact.click();return true;
            }
            if(action!=MotionEvent.ACTION_MOVE || Math.hypot(e.getRawX()-downX,e.getRawY()-downY)
                    >ViewConfiguration.get(window.getContext()).getScaledTouchSlop())summaryTap=false;
        }
        if (action==MotionEvent.ACTION_MOVE && e.getPointerCount()==1 && !pinch && !consumed) {
            float dy=e.getRawY()-downY, dx=e.getRawX()-downX;
            int threshold=ViewConfiguration.get(window.getContext()).getScaledTouchSlop()*2;
            if ((rowGesture || contentGesture || lockBlank) && Math.abs(dy)>Math.abs(dx)*1.4f) {
                list.getLocationOnScreen(stackOrigin);
                int listY=stackOrigin[1];
                feedback.move(window,e.getRawX(),e.getRawY(),rowGesture,contentGesture,lockBlank,listY+history.foldEdge(list));
            }
            if (Math.abs(dy)>Math.abs(dx)*1.4f && Math.abs(dy)>threshold) {
                if (lockGesture && (inside || lockBlank)) upwardLockIntent=dy<0;
                if (dy<0 && !fromCount && lockBlank && !lockHistory && locked() && getIntField(list,"mStatusBarState")==1) {
                    cancel.run();
                    consumed=true;
                    callMethod(lockController,"tryGotoShadeLockState",true,"NotificationDisplay",0f,null,null);
                    return true;
                }
            }
        }
        if (action==MotionEvent.ACTION_CANCEL) upwardLockIntent=false;
        if (action==MotionEvent.ACTION_UP && upwardLockIntent) {
            long gesture=e.getDownTime();
            list.postDelayed(() -> { if (gesture==gestureDownTime) upwardLockIntent=false; },450);
        }
        if (reveal.dragging()) {
            if (action==MotionEvent.ACTION_MOVE){
                reveal.move(e);
                if(e.getRawY()>downY && reveal.revealingHeader())restoreRestingKeyguard(NotificationReveal.PREVIEW);
            }
            else if (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
                int stop=reveal.end(e);
                settle(stop);
            }
            return true;
        }
        if (consumed) return true;
        if (!inside) return false;
        if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 2) {
            initialSpan = span(e); pinch = true; tappedCount = false;
            cancel.run();
            return true;
        }
        if (pinch && action == MotionEvent.ACTION_MOVE && e.getPointerCount() == 2 && initialSpan > 0) {
            float scale = span(e) / initialSpan;
            if (scale < .78f || scale > 1.27f) {
                int current = !shade && locked() && lockState() == 1 ? COUNT : history.style();
                int next = Math.max(COUNT,Math.min(LIST,current + (scale > 1 ? 1 : -1)));
                consumed = true;
                if (next != current) {
                    android.content.ContentValues changes = new android.content.ContentValues();
                    changes.put("display_style", next);
                    window.getContext().getContentResolver().update(FeatureProvider.DISPLAY, changes, null, null);
                    reveal.reset();
                    initialize=true;
                    preferred = next;
                    select(next,true);
                }
                return true;
            }
        }
        if (pinch) return true;
        float dy = e.getRawY()-downY;
        boolean countHit=tappedCount;
        if (action == MotionEvent.ACTION_MOVE && tappedCount &&
                Math.hypot(e.getRawX()-downX,dy) > ViewConfiguration.get(window.getContext()).getScaledTouchSlop()) tappedCount = false;
        if (action == MotionEvent.ACTION_UP && tappedCount) {
            cancel.run(); consumed = true; openCount(); return true;
        }
        boolean canDrag = shade ? openShade : (lockHistory || lockState()==0 || fromCount && (countHit || lockBlank));
        if (canDrag && action==MotionEvent.ACTION_MOVE
                && Math.abs(dy)>ViewConfiguration.get(window.getContext()).getScaledTouchSlop()
                && Math.abs(dy)>Math.abs(e.getRawX()-downX)
                && (fromCount ? dy<0 : reveal.canBegin())) {
            cancel.run();
            if (fromCount) unfoldCount();
            reveal.attach(list,shade);
            reveal.begin(e,downY);
            reveal.move(e);
            if(dy>0 && reveal.revealingHeader())restoreRestingKeyguard(NotificationReveal.PREVIEW);
            return true;
        }
        return false;
    }

    private final int[] backdropOrigin=new int[2];
    private final int[] stackOrigin=new int[2];
    private boolean lockBlank, lockGesture, contentGesture, rowGesture, upwardLockIntent;
    private long gestureDownTime;
    private boolean liveTouch;

    private View appGroupAt(float x,float y){
        list.getLocationOnScreen(stackOrigin);
        int listX=stackOrigin[0];
        float localY=y-stackOrigin[1];
        if(localY<reveal.viewportTop() || localY>=history.foldEdge(list))return null;
        for(int i=0;i<list.getChildCount();i++){
            View row=list.getChildAt(i);
            if(!history.ownsAppGroup(row) || reveal.foldedRow(row) || !row.isShown() || row.getAlpha()<=0)continue;
            if(!row.getGlobalVisibleRect(bounds))continue;
            row.getLocationOnScreen(stackOrigin);
            bounds.bottom=Math.min(bounds.bottom,stackOrigin[1]+(int)callMethod(row,"getActualHeight"));
            if(bounds.contains((int)x,(int)y) && reveal.acceptsPoint(row,x-listX,localY))return row;
        }
        return null;
    }

    private boolean hitsOrdinaryRow(float x,float y) {
        for (int i=0;i<list.getChildCount();i++) {
            View row=list.getChildAt(i);
            if (!row.isShown() || row.getAlpha()<=0 || !history.ordinaryRow(row)) continue;
            if (row.getGlobalVisibleRect(bounds)) {
                row.getLocationOnScreen(stackOrigin);
                bounds.bottom=Math.min(bounds.bottom,stackOrigin[1]+LiveTune.scaledHeight(row,(int)callMethod(row,"getActualHeight"),false));
                if (bounds.contains((int)x,(int)y)) return true;
            }
        }
        return false;
    }

    private boolean blankLockContent(View window,float x,float y) {
        View bouncer=LockCenter.find(window,"keyguard_bouncer");
        if (bouncer!=null && bouncer.isShown()) return false;
        list.getLocationOnScreen(stackOrigin);
        int bottom=stackOrigin[1]+history.foldEdge(list);
        View widgets=LockStylePreview.findType(window,"WidgetPluginContainer");
        int contentBottom=accessories.headerBottom(window,widgets,0);
        window.getLocationOnScreen(stackOrigin);
        int top=stackOrigin[1]+(contentBottom>0 ? contentBottom : window.getRootWindowInsets()==null ? 0 :
                window.getRootWindowInsets().getInsets(android.view.WindowInsets.Type.statusBars()).top);
        if (y<=top || y>=bottom) return false;
        for (int i=0;i<list.getChildCount();i++) {
            View row=list.getChildAt(i);
            if (!row.isShown() || row.getAlpha()<=0 || !row.getClass().getSimpleName().contains("NotificationRow")) continue;
            if (row.getGlobalVisibleRect(bounds)) {
                bounds.bottom=Math.min(bounds.bottom,bounds.top+LiveTune.scaledHeight(row,(int)callMethod(row,"getActualHeight"),false));
                if (bounds.contains((int)x,(int)y)) return false;
            }
        }
        return true;
    }

    private static float span(MotionEvent e) { return (float)Math.hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0)); }
}
