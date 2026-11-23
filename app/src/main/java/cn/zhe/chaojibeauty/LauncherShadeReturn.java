package cn.zhe.chaojibeauty;

import android.animation.Animator;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class LauncherShadeReturn {
    private static final String ACTION = "cn.zhe.chaojibeauty.SHADE_RETURN_HOME";
    private static final String LAUNCHER = "com.android.launcher";

    static boolean homeVisible(Context context) {
        try {
            List<ActivityManager.RunningTaskInfo> tasks = context.getSystemService(ActivityManager.class).getRunningTasks(1);
            if (tasks.isEmpty()) return false;
            ComponentName top = tasks.get(0).topActivity;
            return new ComponentName(LAUNCHER, LAUNCHER + ".Launcher").equals(top);
        } catch (RuntimeException error) {
            Log.w("ChaojiBeauty", "Cannot identify foreground home", error);
            return false;
        }
    }

    static void request(Context context, android.os.IBinder owner, Runnable ready) {
        Intent intent = new Intent(ACTION).setPackage(LAUNCHER).addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        android.os.Bundle session = new android.os.Bundle();
        session.putBinder("owner", owner);
        intent.putExtras(session);
        intent.putExtra("sent", SystemClock.uptimeMillis());
        try {
            context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent result) { ready.run(); }
            }, null, 0, null, null);
        } catch (RuntimeException error) {
            Log.w("ChaojiBeauty", "Cannot request home animation", error);
            ready.run();
        }
    }

    static void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> workspace = loader.loadClass("com.android.launcher3.OplusWorkspace");
        Class<?> state = loader.loadClass("com.android.keyguardservice.c");
        Constructor<?> unlock = state.getConstructor(int.class, boolean.class, loader.loadClass(state.getName() + "$a"));
        Method animate = workspace.getDeclaredMethod("doUnlockAnim", state);
        animate.setAccessible(true);
        Method animating = workspace.getMethod("isDismissKeyguardAnimating");
        Method currentReturn = workspace.getMethod("getDismissKeyguardAnimSet");
        Method cancelReturn = workspace.getDeclaredMethod("tryCancelScreenUnLockAnimate", boolean.class);
        cancelReturn.setAccessible(true);
        java.lang.reflect.Field normal = loader.loadClass("com.android.launcher3.LauncherState").getField("NORMAL");
        XposedBridge.hookMethod(workspace.getDeclaredMethod("onAttachedToWindow"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                View host = (View)p.thisObject;
                if (XposedHelpers.getAdditionalInstanceField(host, ACTION) != null) return;
                HomeIconSpread.Binding spread = new HomeIconSpread.Binding(host);
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    private WeakReference<Animator> shadeReturn = new WeakReference<>(null);

                    @Override public void onReceive(Context context, Intent intent) {
                        long age = SystemClock.uptimeMillis() - intent.getLongExtra("sent", -1);
                        if (age < 0 || age > 700 || !host.isShown()
                                || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
                        try {
                            Object launcher = XposedHelpers.callMethod(host, "getLauncher");
                            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(launcher, "isFirstPageBound"))
                                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(launcher, "isInState", normal.get(null)))
                                    || Boolean.TRUE.equals(XposedHelpers.callMethod(launcher, "isAssistScreenShown"))) return;
                            boolean iconsBusy = (boolean)animating.invoke(host);
                            if (HomeIconSpread.ACTION.equals(intent.getAction())) {
                                Animator previous = shadeReturn.get();
                                if (previous != null && currentReturn.invoke(host) == previous) {
                                    // Release the native render animation and restore its icon and
                                    // hotseat transforms before the new spread captures their bases.
                                    cancelReturn.invoke(host, true);
                                    iconsBusy = (boolean)animating.invoke(host);
                                    Log.i("ChaojiBeauty", "Interrupted prior shade return for desktop departure");
                                }
                                shadeReturn.clear();
                                spread.begin(launcher, intent, !iconsBusy);
                                return;
                            }
                            if (!spread.owns(intent.getExtras().getBinder("owner"))) return;
                            if (iconsBusy || XposedHelpers.getBooleanField(launcher, "mIsIgnoreUnLockScreenAnimation")
                                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(launcher, "isResumed"))) {
                                spread.reset();
                                return;
                            }
                            View dock = (View)XposedHelpers.callMethod(launcher, "getHotseat");
                            // The native animator owns the workspace and hotseat for the entire return.
                            // Release the drag transform before it records either target's starting state.
                            spread.reset();
                            DockGlass glass = dock == null ? null : FoldDock.glass(dock);
                            animate.invoke(host, unlock.newInstance(1, true, null));
                            Animator started = (Animator)currentReturn.invoke(host);
                            shadeReturn = new WeakReference<>(started);
                            if (glass != null) glass.followNativeReturn(host, animating);
                            Log.i("ChaojiBeauty", "Shade returned to native home animator, active="
                                    + (started != null));
                        } catch (ReflectiveOperationException | android.os.RemoteException | RuntimeException | LinkageError error) {
                            Log.w("ChaojiBeauty", "Home animation unavailable", error);
                        }
                    }
                };
                IntentFilter actions = new IntentFilter(ACTION);
                actions.addAction(HomeIconSpread.ACTION);
                host.getContext().registerReceiver(receiver, actions, "android.permission.STATUS_BAR_SERVICE",
                        null, Context.RECEIVER_EXPORTED);
                XposedHelpers.setAdditionalInstanceField(host, ACTION, receiver);
                XposedHelpers.setAdditionalInstanceField(host, HomeIconSpread.ACTION, spread);
            }
        });
        XposedBridge.hookMethod(workspace.getDeclaredMethod("onDetachedFromWindow"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                View host = (View)p.thisObject;
                BroadcastReceiver receiver = (BroadcastReceiver)XposedHelpers.removeAdditionalInstanceField(host, ACTION);
                if (receiver != null) host.getContext().unregisterReceiver(receiver);
                HomeIconSpread.Binding spread = (HomeIconSpread.Binding)XposedHelpers.removeAdditionalInstanceField(host, HomeIconSpread.ACTION);
                if (spread != null) spread.reset();
            }
        });
    }
}
