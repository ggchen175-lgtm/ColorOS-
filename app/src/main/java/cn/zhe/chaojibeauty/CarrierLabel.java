package cn.zhe.chaojibeauty;

import android.content.res.Configuration;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Type metrics for the real keyguard service name, shared with the unlocked shade. */
final class CarrierLabel {
    private final Map<TextView, Original> labels = new WeakHashMap<>();
    private boolean enabled;

    void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = loader.loadClass("com.oplus.systemui.statusbar.widget.OplusStatCarrierText");
        XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                TextView label = (TextView)param.thisObject;
                int id = label.getResources().getIdentifier("keyguard_carrier_text","id","com.android.systemui");
                if (id == 0 || label.getId() != id || labels.containsKey(label)) return;
                Original original = new Original(label);
                labels.put(label,original);
                if (enabled) apply(label,original);
            }
        });
        XposedBridge.hookMethod(type.getDeclaredMethod("onConfigurationChanged",Configuration.class),
                new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                TextView label = (TextView)param.thisObject;
                Original original = labels.get(label);
                if (enabled && original != null) original.restore(label);
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                TextView label = (TextView)param.thisObject;
                if (!labels.containsKey(label)) return;
                Original original = new Original(label);
                labels.put(label,original);
                if (enabled) apply(label,original);
            }
        });
    }

    void configure(boolean on) {
        if (enabled == on) return;
        enabled = on;
        for (Map.Entry<TextView, Original> entry : labels.entrySet()) {
            if (on) apply(entry.getKey(),entry.getValue());
            else entry.getValue().restore(entry.getKey());
        }
    }

    private static void apply(TextView label, Original original) {
        // 16 pt on the 440 pt reference display. Retain the user's Android font scaling.
        float compactWidth = label.getResources().getConfiguration().smallestScreenWidthDp;
        float sp = compactWidth > 0 ? Math.min(16f,16f*compactWidth/440f) : 16f;
        Typeface face = original.face;
        label.setTypeface(Typeface.create(face,Math.max(500,face == null ? 400 : face.getWeight()),
                face != null && face.isItalic()));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP,sp);
    }

    private static final class Original {
        final Typeface face;
        final float size;
        Original(TextView label) { face=label.getTypeface(); size=label.getTextSize(); }
        void restore(TextView label) {
            label.setTypeface(face);
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);
        }
    }
}
