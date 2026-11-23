package cn.zhe.chaojibeauty;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.ComponentName;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.List;

/** Scales the original full-screen app task during the committed CoverSheet return. */
final class AppReturnSurface {
    private static final SparseArray<SurfaceControl> surfaces = new SparseArray<>();
    private static AppReturnSurface active;
    private final SurfaceControl surface;
    private final int id, width, height;
    private final ComponentName activity;
    private float applied = 1f;

    private AppReturnSurface(SurfaceControl surface, RunningTaskInfo task, View root) {
        this.surface = surface;
        id = task.taskId;
        activity = task.topActivity;
        width = root.getWidth();
        height = root.getHeight();
    }

    static void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> listener = loader.loadClass("com.android.wm.shell.fullscreen.FullscreenTaskListener");
        XposedBridge.hookMethod(listener.getDeclaredMethod("onTaskAppeared", RunningTaskInfo.class, SurfaceControl.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                synchronized (surfaces) {
                    surfaces.put(((RunningTaskInfo)p.args[0]).taskId, (SurfaceControl)p.args[1]);
                }
            }
        });
        XposedBridge.hookMethod(listener.getDeclaredMethod("onTaskVanished", RunningTaskInfo.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                int id = ((RunningTaskInfo)p.args[0]).taskId;
                synchronized (surfaces) {
                    if (active != null && active.id == id) active.restore();
                    surfaces.remove(id);
                }
            }
        });
        XposedBridge.hookMethod(listener.getDeclaredMethod("onTaskInfoChanged", RunningTaskInfo.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                RunningTaskInfo info = (RunningTaskInfo)p.args[0];
                synchronized (surfaces) {
                    if (active != null && active.id == info.taskId && !active.matches(info)) active.restore();
                }
            }
        });
    }

    private static RunningTaskInfo foreground(View panel) {
        List<RunningTaskInfo> tasks = panel.getContext().getSystemService(ActivityManager.class).getRunningTasks(1);
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    static AppReturnSurface find(View panel) {
        try {
            RunningTaskInfo task = foreground(panel);
            if (task == null || task.topActivity == null || panel.getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY) return null;
            synchronized (surfaces) {
                SurfaceControl surface = surfaces.get(task.taskId);
                if (surface == null || !surface.isValid()) return null;
                AppReturnSurface app = new AppReturnSurface(surface, task, panel.getRootView());
                return app.matches(task) ? app : null;
            }
        } catch (RuntimeException | LinkageError e) {
            Log.w("ChaojiBeauty", "Cannot identify full-screen app", e);
            return null;
        }
    }

    private boolean matches(RunningTaskInfo task) {
        if (task == null || task.taskId != id || !task.isVisible() || !activity.equals(task.topActivity)) return false;
        Point origin = (Point)XposedHelpers.getObjectField(task, "positionInParent");
        Object config = XposedHelpers.getObjectField(task, "configuration");
        Object window = XposedHelpers.getObjectField(config, "windowConfiguration");
        Rect bounds = (Rect)XposedHelpers.callMethod(window, "getBounds");
        return origin.x == 0 && origin.y == 0 && bounds.left == 0 && bounds.top == 0 &&
                bounds.width() == width && bounds.height() == height &&
                (int)XposedHelpers.callMethod(window, "getActivityType") == 1;
    }

    boolean begin(View panel) {
        synchronized (surfaces) {
            if (!matches(foreground(panel)) || surfaces.get(id) != surface || !surface.isValid()) return false;
            active = this;
            return true;
        }
    }

    void scale(float value) {
        synchronized (surfaces) {
            if (active != this || applied == value || !surface.isValid()) return;
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.setScale(surface, value, value);
                transaction.setPosition(surface, width * .5f * (1f - value), height * .5f * (1f - value));
                transaction.apply();
                applied = value;
            } catch (RuntimeException e) {
                Log.w("ChaojiBeauty", "App return transform unavailable", e);
            }
        }
    }

    void restore() {
        synchronized (surfaces) {
            if (active != this) return;
            scale(1f);
            active = null;
        }
    }
}
