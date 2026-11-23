package cn.zhe.chaojibeauty;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.Scroller;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Moves the home page with the assistant overlay's native displacement. */
final class AssistantSlide {
    private volatile boolean enabled;
    private View movingPage;
    private View desktop;
    private Rect originalClip;
    private final Rect visibleDesktop = new Rect();
    private float originalX;
    private static final Interpolator PAGE_SCROLL = t -> {
        float rest = 1f-t;
        return 1f-rest*rest*rest*rest;
    };
    private int pageDuration, minFling, minSnap;

    void install(ClassLoader loader, boolean assistant) {
        if (assistant) {
            installSnap(loader);
            return;
        }
        XposedHelpers.findAndHookMethod("com.android.launcher3.Workspace",loader,"setOverlayTranslation",float.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!enabled) return;
                        float offset = (float)p.args[0];
                        if (offset==0) { restore(); return; }
                        ViewGroup workspace = (ViewGroup)p.thisObject;
                        int index = (int) XposedHelpers.callMethod(workspace,"getCurrentPage");
                        View page = workspace.getChildAt(index);
                        if (page==null) { restore(); return; }
                        if (page!=movingPage) {
                            restore();
                            Object launcher = XposedHelpers.getObjectField(workspace,"mLauncher");
                            desktop = (View)XposedHelpers.callMethod(launcher,"getDragLayer");
                            originalClip = desktop.getClipBounds();
                            movingPage=page;
                            originalX=page.getTranslationX();
                        }
                        page.setTranslationX(originalX+offset);
                        // The overlay window is transparent. Reveal the fixed hotseat and indicator
                        // only on the home side of the seam, as each page crosses the viewport.
                        int w=desktop.getWidth(), h=desktop.getHeight();
                        int x=Math.round(Math.max(-w,Math.min(w,offset)));
                        visibleDesktop.set(Math.max(0,x),0,w+Math.min(0,x),h);
                        if (originalClip!=null && !visibleDesktop.intersect(originalClip)) visibleDesktop.setEmpty();
                        desktop.setClipBounds(visibleDesktop);
                    }
                });
    }

    private void installSnap(ClassLoader loader) {
        // Keep card contents at their native positions inside the horizontally scrolling window.
        XposedHelpers.findAndHookMethod("dt0.h",loader,"g",new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (enabled) p.setResult(false);
            }
        });
        // The progressive overlay jumps the view to its end and animates a separate surface.
        // Use the assistant's scrolling-view path so dragging and settling move the same page.
        XposedHelpers.findAndHookMethod("kk0.t",loader,"w",new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (enabled) p.setResult(true);
            }
        });
        XposedHelpers.findAndHookMethod("kk0.a0",loader,"e",new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Scroller scroller=(Scroller)p.getResult();
                if (scroller==null) return;
                Object saved=XposedHelpers.getAdditionalInstanceField(scroller,"cjbPageInterpolator");
                if (enabled && saved==null) {
                    Interpolator current=(Interpolator)XposedHelpers.getObjectField(scroller,"mInterpolator");
                    XposedHelpers.setAdditionalInstanceField(scroller,"cjbPageInterpolator",current);
                    XposedHelpers.setObjectField(scroller,"mInterpolator",PAGE_SCROLL);
                } else if (!enabled && saved!=null) {
                    XposedHelpers.setObjectField(scroller,"mInterpolator",saved);
                    XposedHelpers.removeAdditionalInstanceField(scroller,"cjbPageInterpolator");
                }
            }
        });
        XposedHelpers.findAndHookMethod("com.oplus.assistantscreen.window.l1",loader,"a",int.class,int.class,int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!enabled) return;
                        if (pageDuration==0) {
                            Context c=(Context)XposedHelpers.getObjectField(p.thisObject,"a");
                            if (!readPageResources(c)) return;
                        }
                        int speed=Math.abs((int)p.args[0]), width=(int)p.args[2];
                        if (width<=0) return;
                        if (speed<minFling) { p.setResult(pageDuration); return; }
                        float part=Math.min(1f,Math.abs((int)p.args[1])/(float)width);
                        float distance=(width/2)*(1f+(float)Math.sin((part-.5f)*.4712389167638204));
                        p.setResult(Math.round(3500f*distance/Math.max(speed,minSnap)));
                    }
                });
    }

    private boolean readPageResources(Context context) {
        String pkg="com.android.launcher";
        try {
            Resources r=context.getPackageManager().getResourcesForApplication(pkg);
            minFling=r.getDimensionPixelSize(r.getIdentifier("min_fling_velocity","dimen",pkg));
            minSnap=r.getDimensionPixelSize(r.getIdentifier("min_page_snap_velocity","dimen",pkg));
            pageDuration=r.getInteger(r.getIdentifier("config_pageSnapAnimationDuration","integer",pkg));
            return pageDuration>0;
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) { return false; }
    }

    void setEnabled(boolean value) {
        enabled=value;
        if (!value) restore();
    }

    private void restore() {
        if (movingPage==null) return;
        movingPage.setTranslationX(originalX);
        desktop.setClipBounds(originalClip);
        desktop=null;
        originalClip=null;
        movingPage=null;
    }
}
