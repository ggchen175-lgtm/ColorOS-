package cn.zhe.chaojibeauty;

import android.graphics.Rect;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import de.robv.android.xposed.*;
import java.lang.reflect.Method;
import java.util.*;

/** Keep ColorOS's signal and network models bound to their original views. */
final class NetworkSlot {
    private final Set<Row> rows = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<TextView, String> networkNames = new WeakHashMap<>();

    void install(ClassLoader loader) throws ReflectiveOperationException {
        String prefix = "com.oplus.systemui.statusbar.phone.signal.widget.OplusModernStatusBar";
        Class<?> mobile = loader.loadClass(prefix + "MobileView");
        Class<?> wifi = loader.loadClass(prefix + "WifiView");
        Set<Method> attachMethods = new HashSet<>();
        for (Class<?> owner : new Class<?>[]{mobile, wifi}) {
            attachMethods.add(XposedHelpers.findMethodBestMatch(owner, "onAttachedToWindow"));
        }
        for (Method method : attachMethods) XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                boolean isMobile = mobile.isInstance(p.thisObject);
                if (!isMobile && !wifi.isInstance(p.thisObject)) return;
                ViewGroup root = (ViewGroup)p.thisObject;
                for (Row row : rows) if (row.root == root) return;
                Row row = new Row(root, isMobile);
                rows.add(row);
                root.addOnAttachStateChangeListener(row);
                root.getViewTreeObserver().addOnPreDrawListener(row);
            }
        });
        // Some operator labels are spans with empty backing text. Read the same
        // per-SIM model used by ColorOS rather than guessing from connectivity.
        Class<?> binding = loader.loadClass("com.oplus.systemui.statusbar.pipeline.mobile.ui.view.OplusStatusBarMobileViewBinder$Os17Binding");
        Class<?> model = loader.loadClass("com.android.systemui.statusbar.pipeline.mobile.domain.model.OplusNetworkTypeTextModel");
        Method textView = binding.getMethod("getMobileTypeText");
        Method type = model.getMethod("getNetworkTypeText");
        Method name = type.getReturnType().getMethod("getName");
        XposedBridge.hookMethod(binding.getMethod("bindNetworkTypeText", model), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                TextView source = (TextView)textView.invoke(p.thisObject);
                Object value = p.args[0] == null ? null : type.invoke(p.args[0]);
                if (source != null) networkNames.put(source, value == null ? "" : (String)name.invoke(value));
            }
        });
    }

    void refresh() {
        for (Row row : new ArrayList<>(rows)) {
            row.update();
            row.root.invalidate();
        }
    }

    /** Suppress ink without changing the visibility used by the OEM binder. */
    private static final class Badge {
        final View view;
        final Rect hidden = new Rect();
        Rect original;
        boolean clipped;
        Badge(View view) { this.view = view; }
        void hide(boolean hide) {
            if (view == null || clipped == hide) return;
            if (hide) original = view.getClipBounds();
            view.setClipBounds(hide ? hidden : original);
            clipped = hide;
        }
    }

    private final class Row implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        final ViewGroup root;
        final List<Badge> arrows = new ArrayList<>();
        final Badge textBadge;
        final TextView nativeText;
        final LinearLayout mobileGroup;
        final int wifiSignalId;
        View wifiSignal;
        TextView replacement;
        int slotWidth;
        private String lastModel, lastText, displayName = "";

        Row(ViewGroup root, boolean mobile) {
            this.root = root;
            View text = find("mobile_type_text"), group = find("mobile_group");
            nativeText = mobile && text instanceof TextView ? (TextView)text : null;
            mobileGroup = mobile && group instanceof LinearLayout ? (LinearLayout)group : null;
            textBadge = new Badge(nativeText);
            for (String id : mobile ? new String[]{"data_inout","mobile_in","mobile_out"}
                                    : new String[]{"wifi_inout","wifi_in","wifi_out"}) {
                View view = find(id);
                if (view != null) arrows.add(new Badge(view));
            }
            wifiSignalId = id("wifi_signal");
            slotWidth = Math.round(23 * root.getResources().getDisplayMetrics().density);
        }
        int id(String name) { return root.getResources().getIdentifier(name, "id", IconModule.SYSTEM); }
        View find(String name) { int key = id(name); return key == 0 ? null : root.findViewById(key); }
        boolean wifiShown() {
            if ((wifiSignal == null || !wifiSignal.isAttachedToWindow()) && root.getParent() instanceof ViewGroup)
                wifiSignal = ((ViewGroup)root.getParent()).findViewById(wifiSignalId);
            if (wifiSignal == null || !wifiSignal.isShown() || wifiSignal.getWidth() <= 0) return false;
            slotWidth = wifiSignal.getWidth();
            return true;
        }
        void update() {
            for (Badge arrow : arrows) arrow.hide(IconModule.enabled);
            if (nativeText == null || mobileGroup == null) return;
            if (!IconModule.enabled) { restoreLabel(); return; }
            if (!root.isShown()) return;
            boolean wifi = wifiShown();
            String label = wifi ? "" : label();
            boolean show = !wifi && nativeText.isShown() && !label.isEmpty();
            textBadge.hide(wifi || show);
            if (!show) {
                if (replacement != null && replacement.getVisibility() != View.GONE)
                    replacement.setVisibility(View.GONE);
                return;
            }
            if (replacement == null) {
                replacement = new TextView(root.getContext());
                replacement.setGravity(Gravity.CENTER);
                replacement.setIncludeFontPadding(false);
                replacement.setSingleLine();
                replacement.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                replacement.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                replacement.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                mobileGroup.addView(replacement, new LinearLayout.LayoutParams(slotWidth, -1));
            }
            if (replacement.getLayoutParams().width != slotWidth) {
                ViewGroup.LayoutParams lp = replacement.getLayoutParams();
                lp.width = slotWidth;
                replacement.setLayoutParams(lp);
            }
            if (!label.contentEquals(replacement.getText())) replacement.setText(label);
            if (replacement.getCurrentTextColor() != nativeText.getCurrentTextColor())
                replacement.setTextColor(nativeText.getCurrentTextColor());
            if (replacement.getVisibility() != View.VISIBLE) replacement.setVisibility(View.VISIBLE);
        }
        String label() {
            String text = nativeText.getText().toString();
            String model = networkNames.get(nativeText);
            if (!Objects.equals(model, lastModel) || !text.equals(lastText)) {
                displayName = NetworkName.forSlot(model, text);
                lastModel = model;
                lastText = text;
            }
            return displayName;
        }
        void restoreLabel() {
            textBadge.hide(false);
            if (replacement != null) {
                mobileGroup.removeView(replacement);
                replacement = null;
            }
        }
        @Override public boolean onPreDraw() { update(); return true; }
        @Override public void onViewAttachedToWindow(View v) {}
        @Override public void onViewDetachedFromWindow(View v) {
            root.removeOnAttachStateChangeListener(this);
            if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnPreDrawListener(this);
            for (Badge arrow : arrows) arrow.hide(false);
            restoreLabel();
            rows.remove(this);
        }
    }
}
