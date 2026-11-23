package cn.zhe.chaojibeauty;

import android.graphics.*;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Lock-screen accessories share their clock's foreground, with source luminance defining emphasis. */
final class WidgetVibrancy {
    private final Map<View, View> foreground = new IdentityHashMap<>();
    private final Map<View, Palette> hosts = new IdentityHashMap<>();
    private final RectF bounds = new RectF();
    private Constructor<?> constructor;
    private Method setTint;
    private static final String SAVE = "chaoji.widget.save";

    static final String PROGRAM =
            "layout(color) uniform half4 titleInk;" +
            "half4 main(half4 src, half4 dst) {" +
            "  if (src.a <= 0.) return dst;" +
            "  half level = dot(clamp(src.rgb/src.a,0.,1.),half3(.2126,.7152,.0722));" +
            "  half a = src.a * mix(.52,1.,level);" +
            "  return half4(titleInk.rgb*a,a) + dst*(1.-a);" +
            "}";

    void install() throws ReflectiveOperationException {
        Class<?> type = Class.forName("android.graphics.RuntimeXfermode");
        constructor = type.getConstructor(String.class);
        setTint = type.getMethod("setColorUniform", String.class, int.class);
        XposedBridge.hookMethod(ViewGroup.class.getDeclaredMethod("drawChild", Canvas.class, View.class, long.class),
                new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                View child = (View)p.args[1];
                Palette palette = hosts.get(foreground.get(child));
                if (palette == null || palette.paint == null) return;
                Canvas canvas = (Canvas)p.args[0];
                if (!canvas.isHardwareAccelerated()) return;
                bounds.set(0,0,child.getWidth(),child.getHeight());
                child.getMatrix().mapRect(bounds);
                bounds.offset(child.getLeft(),child.getTop());
                if (!bounds.isEmpty()) p.setObjectExtra(SAVE, canvas.saveLayer(bounds,palette.paint));
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!foreground.containsKey((View)p.args[1])) return;
                Integer save = (Integer)p.getObjectExtra(SAVE);
                if (save != null) ((Canvas)p.args[0]).restoreToCount(save);
            }
        });
    }

    void update(Map<View,View> current) {
        if (setTint == null) return;
        hosts.keySet().retainAll(current.values());
        for (View host : current.values()) hosts.computeIfAbsent(host, v -> new Palette());
        for (Map.Entry<View,Palette> entry : hosts.entrySet())
            entry.getValue().foreground = LockForeground.bind(entry.getKey());
        for (View view : foreground.keySet()) if (!current.containsKey(view)) invalidateParent(view);
        for (View view : current.keySet()) if (current.get(view) != foreground.get(view)) invalidateParent(view);
        foreground.clear();
        foreground.putAll(current);
        refreshColors();
    }

    void refreshColors() {
        for (Map.Entry<View,Palette> entry : hosts.entrySet()) {
            Palette palette = entry.getValue();
            int color = palette.foreground.color();
            if (palette.color == color) continue;
            try {
                Xfermode mode = (Xfermode)constructor.newInstance(PROGRAM);
                setTint.invoke(mode,"titleInk",color);
                Paint paint = new Paint();
                paint.setXfermode(mode);
                palette.paint = paint;
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.w("ChaojiBeauty","Widget title foreground unavailable",e);
            }
            palette.color = color;
            for (Map.Entry<View,View> glyph : foreground.entrySet())
                if (glyph.getValue() == entry.getKey()) invalidateParent(glyph.getKey());
        }
    }

    private static final class Palette {
        LockForeground foreground;
        int color;
        Paint paint;
    }

    private static void invalidateParent(View view) {
        if (view.getParent() instanceof View) ((View)view.getParent()).invalidate();
    }
}
