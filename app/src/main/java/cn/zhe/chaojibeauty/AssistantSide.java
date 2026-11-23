package cn.zhe.chaojibeauty;

import android.view.View;
import android.view.MotionEvent;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Selects the physical edge of the existing assistant window independently of UI language. */
final class AssistantSide {
    private volatile boolean right;
    private WeakReference<View> workspace = new WeakReference<>(null);
    private WeakReference<Object> window = new WeakReference<>(null);
    private WeakReference<View> indicator = new WeakReference<>(null);
    private boolean indicatorReversed;
    private Class<?> profile;
    private final ThreadLocal<Boolean> windowDirection = new ThreadLocal<>();

    void install(ClassLoader loader, boolean assistant) {
        if (assistant) {
            installWindow(loader);
            return;
        }
        Class<?> home = XposedHelpers.findClass("com.android.launcher3.Workspace",loader);
        Class<?> oplus = XposedHelpers.findClass("com.android.launcher3.OplusWorkspace",loader);
        XC_MethodHook useRightEdge = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (right && !XposedHelpers.getBooleanField(p.thisObject,"mIsRtl")) {
                    p.setObjectExtra("restoreDirection",Boolean.TRUE);
                    XposedHelpers.setBooleanField(p.thisObject,"mIsRtl",true);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.getObjectExtra("restoreDirection")!=null)
                    XposedHelpers.setBooleanField(p.thisObject,"mIsRtl",false);
            }
        };
        XposedHelpers.findAndHookMethod(oplus,"overScroll",int.class,int.class,int.class,useRightEdge);
        XposedHelpers.findAndHookMethod(home,"onOverlayScrollChanged",float.class,useRightEdge);
        // The overlay client only delivers release velocity from its logical first page.
        // Translate that single eligibility query while retaining the workspace's real index.
        ThreadLocal<View> releasingPage=new ThreadLocal<>();
        XposedHelpers.findAndHookMethod("z9.c",loader,"onScrollInteractionEnd",float.class,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                View page=workspace.get();
                if (right && page!=null) releasingPage.set(page);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) { releasingPage.remove(); }
        });
        XposedHelpers.findAndHookMethod("com.android.launcher3.PagedView",loader,"getCurrentPage",new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (right && releasingPage.get()==p.thisObject
                        && (int)p.getResult()==((android.view.ViewGroup)p.thisObject).getChildCount()-1)
                    p.setResult(0);
            }
        });
        XposedBridge.hookAllMethods(home,"setLauncherOverlay",new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                placeEdge((View)p.thisObject,false);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                View view=(View)p.thisObject;
                workspace=new WeakReference<>(view);
                placeEdge(view,right);
            }
        });
        installIndicator(loader);
    }

    private void installIndicator(ClassLoader loader) {
        Class<?> type=XposedHelpers.findClass("com.android.launcher.pageindicators.OplusPageIndicator",loader);
        XposedHelpers.findAndHookMethod(type,"setShowAssistScreenPoint",boolean.class,new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                View v=(View)p.thisObject;
                if ((boolean)XposedHelpers.callMethod(v,"isFolderHost")) return;
                if (v!=indicator.get()) {
                    indicator=new WeakReference<>(v);
                    indicatorReversed=false;
                }
                updateIndicator(v);
            }
        });
        XposedHelpers.findAndHookMethod(type,"modifyActivePageValueForSwitchMode",int.class,new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!indicatorReversed || indicator.get()!=p.thisObject) return;
                int slot=(int)p.getResult();
                int count=XposedHelpers.getIntField(p.thisObject,"mNumPages");
                if (slot>0 && slot<count) p.setResult(count-slot);
            }
        });
        XposedHelpers.findAndHookMethod(type,"setCurrentActiveMarker",new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!indicatorReversed || indicator.get()!=p.thisObject) return;
                int active=XposedHelpers.getIntField(p.thisObject,"mActivePage");
                int count=XposedHelpers.getIntField(p.thisObject,"mNumPages");
                if (count<=0) return;
                // Reapply the displayed page after a press using a workspace page index.
                XposedHelpers.callMethod(p.thisObject,"setActiveMarker",active==0 ? -1 : count-1-active);
                p.setResult(null);
            }
        });
        XposedHelpers.findAndHookMethod(type,"getSwitchTargetPage",MotionEvent.class,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!indicatorReversed || indicator.get()!=p.thisObject) return;
                // Hit testing consumes a physical slot; the rightmost slot is the assistant.
                View v=(View)p.thisObject;
                XposedHelpers.setBooleanField(v,"mShowAssistScreenPoint",false);
                int slot;
                try { slot=(int)XposedHelpers.callMethod(v,"getPageIndex",p.args[0]); }
                finally { XposedHelpers.setBooleanField(v,"mShowAssistScreenPoint",true); }
                int count=XposedHelpers.getIntField(v,"mNumPages");
                int active=XposedHelpers.getIntField(v,"mActivePage");
                p.setResult(slot>=0 && slot<count-1 && slot!=count-1-active ? slot : -1);
            }
        });
    }

    private void updateIndicator(View v) {
        boolean reverse=right && XposedHelpers.getBooleanField(v,"mShowAssistScreenPoint")
                && v.getResources().getConfiguration().getLayoutDirection()==View.LAYOUT_DIRECTION_LTR;
        if (indicatorReversed==reverse) return;
        indicatorReversed=reverse;
        XposedHelpers.setBooleanField(v,"mIsRtl",reverse ||
                v.getResources().getConfiguration().getLayoutDirection()==View.LAYOUT_DIRECTION_RTL);
        View page=workspace.get();
        if (page!=null && XposedHelpers.getIntField(v,"mNumPages")>0)
            XposedHelpers.callMethod(v,"setActiveMarker",XposedHelpers.callMethod(page,"getCurrentPage"));
        v.invalidate();
    }

    private static void placeEdge(View view, boolean forceRight) {
        Object overlay=XposedHelpers.getObjectField(view,"mOverlayEdgeEffect");
        if (overlay==null) return;
        boolean onRight=forceRight || XposedHelpers.getBooleanField(view,"mIsRtl");
        String target=onRight ? "mEdgeGlowRight" : "mEdgeGlowLeft";
        String opposite=onRight ? "mEdgeGlowLeft" : "mEdgeGlowRight";
        if (XposedHelpers.getObjectField(view,opposite)==overlay) {
            Object normalEdge=XposedHelpers.getObjectField(view,target);
            XposedHelpers.setObjectField(view,target,overlay);
            XposedHelpers.setObjectField(view,opposite,normalEdge);
        }
    }

    private void installWindow(ClassLoader loader) {
        String pkg="com.oplus.assistantscreen.window.";
        profile=XposedHelpers.findClass(pkg+"k1",loader);
        Class<?> host=XposedHelpers.findClass(pkg+"AssistantScreenWindow",loader);
        XC_MethodHook directionScope=new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.setObjectExtra("previousDirection",windowDirection.get());
                windowDirection.set(Boolean.TRUE);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.getObjectExtra("previousDirection")==null) windowDirection.remove();
            }
        };
        XposedBridge.hookAllMethods(profile,"c",directionScope);
        XposedBridge.hookAllMethods(host,"j",directionScope);
        XposedBridge.hookAllMethods(XposedHelpers.findClass("kk0.m",loader),"c",directionScope);
        XposedHelpers.findAndHookMethod("com.coloros.common.utils.e0",loader,"s",new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (right && Boolean.TRUE.equals(windowDirection.get())) p.setResult(true);
            }
        });
        XposedBridge.hookAllMethods(host,"k",new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                window=new WeakReference<>(p.thisObject);
            }
        });
    }

    void setRight(boolean value) {
        if (right==value) return;
        right=value;
        View page=workspace.get();
        if (page!=null) placeEdge(page,value);
        View pager=indicator.get();
        if (pager!=null) updateIndicator(pager);
        Object host=window.get();
        if (host==null) return;
        Object params=XposedHelpers.callMethod(host,"e");
        if (((WindowManager.LayoutParams)params).x==0) return;
        Object state=XposedHelpers.getStaticObjectField(profile,"a");
        XposedHelpers.callMethod(state,"c",XposedHelpers.callMethod(host,"d"));
        XposedHelpers.callMethod(host,"j");
        View root=(View)XposedHelpers.getObjectField(host,"i");
        if (root.isAttachedToWindow()) {
            WindowManager wm=(WindowManager)XposedHelpers.callMethod(host,"f");
            wm.updateViewLayout(root,(WindowManager.LayoutParams)params);
        }
    }
}
