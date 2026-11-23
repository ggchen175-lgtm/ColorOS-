package cn.zhe.chaojibeauty;

import android.graphics.Rect;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import static de.robv.android.xposed.XposedHelpers.*;

/** Uses the header owned, positioned and animated by the native group container. */
final class GroupHeaderControls {
    private final NotificationGlass glass;
    private Class<?> stackType, stats, item, groupType;
    private final Set<View> containers = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean enabled;

    GroupHeaderControls(NotificationGlass glass) { this.glass = glass; }

    void install(ClassLoader loader) throws ReflectiveOperationException {
        String base = "com.android.systemui.statusbar.notification.";
        stackType = loader.loadClass(base + "stack.NotificationStackScrollLayout");
        groupType = loader.loadClass(base + "collection.GroupEntry");
        stats = loader.loadClass(base + "collection.notifcollection.DismissedByUserStats");
        item = loader.loadClass(base + "collection.EntryWithDismissStats");
        Class<?> container = loader.loadClass(base + "stack.NotificationChildrenContainer");
        Class<?> ext = loader.loadClass("com.oplus.systemui.notification.row.oplusgroup.OplusNotificationGroupExtImpl");
        XposedBridge.hookMethod(ext.getDeclaredMethod("measureOplusExpandHeader",container,int.class,int.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                View host = (View)p.args[0];
                containers.add(host);
                Object header = callMethod(p.thisObject,"getOplusExpandGroupHeaderView");
                if (header instanceof FrameLayout) bind(host,(FrameLayout)header);
            }
        });
    }

    void configure(boolean on) {
        if (enabled == on) return;
        enabled = on;
        for (View host : new ArrayList<>(containers)) host.requestLayout();
    }

    private void bind(View host, FrameLayout header) {
        int count = header.getChildCount();
        Bar bar = count > 0 && header.getChildAt(count-1) instanceof Bar ? (Bar)header.getChildAt(count-1) : null;
        if (bar != null) {
            if (!enabled) bar.remove();
            else if (!bar.prompt.isEmpty() && !getBooleanField(host,"mChildrenExpanded")) bar.cancel();
            return;
        }
        if (!enabled) return;
        TextView title = null;
        ImageView original = null;
        for (int i=0; i<count; i++) {
            View child = header.getChildAt(i);
            if (child instanceof TextView) title = (TextView)child;
            if (child instanceof ImageView) original = (ImageView)child;
        }
        if (title != null && original != null) new Bar(host,header,title,original).attach();
    }

    private final class Bar extends LinearLayout {
        final View host, original;
        final FrameLayout header;
        final TextView title;
        final int oldVisibility;
        final FrameLayout.LayoutParams originalTitleLayout;
        final float oldTextSize;
        final android.graphics.Typeface oldTypeface;
        final ImageView clear, less;
        final HistoryClearControl lessFace;
        final HistoryClearControl clearFace;
        final Map<Object,Long> prompt = new IdentityHashMap<>();
        final Runnable expire = this::cancel;
        final Rect visible = new Rect();

        Bar(View host, FrameLayout header, TextView title, View original) {
            super(header.getContext());
            this.host = host;
            this.header = header;
            this.title = title;
            this.original = original;
            originalTitleLayout = new FrameLayout.LayoutParams((FrameLayout.LayoutParams)title.getLayoutParams());
            oldTextSize = title.getTextSize();
            oldTypeface = title.getTypeface();
            oldVisibility = original.getVisibility();
            setGravity(Gravity.CENTER_VERTICAL);
            less = button();
            lessFace = new HistoryClearControl(less,glass,HistoryClearControl.Action.LESS);
            less.setOnClickListener(v -> {
                cancel();
                if (owner() != null) header.performClick();
            });
            clear = button();
            clearFace = new HistoryClearControl(clear,glass,HistoryClearControl.Action.GROUP);
            clearFace.pairWith(value -> {
                float progress = (float)value;
                lessFace.setGroupProgress(progress);
                int lessWidth = lessFace.groupWidth(1-progress);
                int total = lessFace.groupWidth(1) + clearFace.groupWidth(0);
                resize(less, lessWidth);
                resize(clear, total - lessWidth);
            });
            clear.setOnClickListener(v -> {
                try { press(); }
                catch (RuntimeException | LinkageError | XposedHelpers.InvocationTargetError e) {
                    cancel();
                    Log.w("ChaojiHistory","Group dismissal unavailable",e);
                }
            });
        }

        ImageView button() {
            ImageView v = new ImageView(getContext());
            addView(v,new LinearLayout.LayoutParams(-2,-2));
            v.setFocusable(true);
            return v;
        }

        private void resize(View button, int width) {
            ViewGroup.LayoutParams layout = button.getLayoutParams();
            if (layout.width == width) return;
            layout.width = width;
            button.setLayoutParams(layout);
        }

        void attach() {
            original.setVisibility(GONE);
            FrameLayout.LayoutParams label = new FrameLayout.LayoutParams(originalTitleLayout);
            label.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            label.topMargin = label.bottomMargin = 0;
            title.setLayoutParams(label);
            title.setTextSize(16);
            title.setTypeface(android.graphics.Typeface.DEFAULT);
            header.addView(this,new FrameLayout.LayoutParams(-2,-1,Gravity.END | Gravity.CENTER_VERTICAL));
            addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> margin(r-l));
        }

        void margin(int end) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams)title.getLayoutParams();
            if (p.getMarginEnd() != end) { p.setMarginEnd(end); title.setLayoutParams(p); }
        }

        void remove() {
            cancel();
            header.removeView(this);
            original.setVisibility(oldVisibility);
            title.setLayoutParams(originalTitleLayout);
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,oldTextSize);
            title.setTypeface(oldTypeface);
        }

        View owner() {
            if (!enabled || !isShown() || header.getAlpha() < .9f || !getGlobalVisibleRect(visible)
                    || host != header.getParent() || !getBooleanField(host,"mChildrenExpanded")) return null;
            View row = (View)getObjectField(host,"mContainingNotification");
            if (row == null || !(boolean)callMethod(row,"isGroupExpanded") || (boolean)callMethod(row,"shouldShowPublic")) return null;
            return row;
        }

        void press() {
            View row = owner();
            if (row == null) { cancel(); return; }
            View v = row;
            while (!stackType.isInstance(v) && v.getParent() instanceof View) v = (View)v.getParent();
            if (!stackType.isInstance(v)) { cancel(); return; }
            ViewGroup stack = (ViewGroup)v;
            Object summary = callMethod(row,"getEntry");
            Object group = callMethod(summary,"getParent");
            if (!groupType.isInstance(group) || callMethod(group,"getSummary") != summary) { cancel(); return; }
            List<?> children = (List<?>)callMethod(group,"getChildren");
            if (children.isEmpty()) { cancel(); return; }
            ArrayList<Object> entries = new ArrayList<>(children);
            entries.add(summary);
            Set<Object> eligible = NotificationHistory.eligible(stack,Collections.singletonMap(row,entries));
            Map<Object,Long> current = new IdentityHashMap<>();
            for (Object e : eligible) current.put(e,((StatusBarNotification)callMethod(e,"getSbn")).getPostTime());
            if (prompt.isEmpty()) {
                if (current.isEmpty()) { clear.announceForAccessibility("本组没有可清除的通知"); return; }
                prompt.putAll(current);
                clearFace.setExpanded(true);
                postDelayed(expire,2000);
                return;
            }
            boolean same = current.size() == prompt.size();
            for (Object e : current.keySet()) if (!current.get(e).equals(prompt.get(e))) same = false;
            cancel();
            if (same) NotificationHistory.dismiss(stack,eligible,stats,item);
        }

        void cancel() {
            prompt.clear();
            removeCallbacks(expire);
            if (clearFace != null) clearFace.setExpanded(false);
        }

        @Override protected void onDetachedFromWindow() { cancel(); super.onDetachedFromWindow(); }
        @Override protected void onVisibilityChanged(View v,int visibility) {
            super.onVisibilityChanged(v,visibility);
            if (visibility != VISIBLE && prompt != null && !prompt.isEmpty()) cancel();
        }
    }
}
