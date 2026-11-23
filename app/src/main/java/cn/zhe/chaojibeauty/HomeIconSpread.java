package cn.zhe.chaojibeauty;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import de.robv.android.xposed.XposedHelpers;

/** One channel per home-originated gesture. The workspace owns the actual icon transforms. */
final class HomeIconSpread {
    static final String ACTION = "cn.zhe.chaojibeauty.SHADE_SPREAD_HOME";
    private static final int END = -1;
    private static final int RETURN_DOCK = -2;
    private Messenger target;
    final IBinder owner = new Binder();
    private int position, dockPosition;
    private boolean returning, presented;

    HomeIconSpread(Context context) {
        Bundle session = new Bundle();
        session.putBinder("owner", owner);
        session.putParcelable("reply", new Messenger(new Handler(Looper.getMainLooper(), message -> {
            target = message.replyTo;
            if (returning && position == END) send(RETURN_DOCK);
            sendPosition();
            return true;
        })));
        context.sendBroadcast(new Intent(ACTION).setPackage("com.android.launcher")
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("sent", SystemClock.uptimeMillis()).putExtras(session));
    }

    void move(float progress, float dockProgress, boolean fullyPresented) {
        if (position == END || returning) return;
        int next = Float.floatToIntBits(progress);
        int nextDock = Float.floatToIntBits(dockProgress);
        if (position == next && dockPosition == nextDock && presented == fullyPresented) return;
        presented = fullyPresented;
        dockPosition = nextDock;
        position = next;
        sendPosition();
    }

    void close() {
        position = END;
        sendPosition();
    }

    void returnDock() {
        if (position == END || returning) return;
        returning = true;
        sendPosition();
    }

    private void sendPosition() {
        send(position == END ? END : returning ? RETURN_DOCK : position);
        if (position == END) target = null;
    }

    private void send(int command) {
        if (target == null) return;
        try {
            target.send(Message.obtain(null, command, dockPosition, presented ? 1 : 0));
        } catch (RemoteException disconnected) {
            target = null;
        }
    }

    static final class Binding {
        private final View workspace;
        private final ArrayList<Item> items = new ArrayList<>();
        private final android.graphics.Rect plate = new android.graphics.Rect();
        private View dock, pageIndicator;
        private float indicatorBaseY, indicatorTravel;
        private float appliedSpread, dockBaseY, dockFraction;
        private DockGlass dockGlass;
        private android.view.ViewTreeObserver dockObserver;
        private final android.view.ViewTreeObserver.OnPreDrawListener keepDockPosition = () -> {
            // Launcher may reset hotseat translation as an earlier unlock animation ends.
            placeDock(dockFraction);
            return true;
        };
        private float offscreenTravel;
        private boolean parked, awaitingNative;
        private final Runnable recover = this::reset;
        private int generation;
        private IBinder owner;
        private IBinder.DeathRecipient death;

        Binding(View workspace) { this.workspace = workspace; }

        boolean owns(IBinder token) { return owner != null && owner.equals(token); }

        void begin(Object launcher, Intent intent, boolean captureIcons) throws RemoteException {
            Bundle extras = intent.getExtras();
            IBinder token = extras.getBinder("owner");
            Messenger reply = extras.getParcelable("reply", Messenger.class);
            if (token == null || reply == null) return;
            reset();
            int current = generation;
            Object page = XposedHelpers.callMethod(workspace, "getPageAt", XposedHelpers.callMethod(workspace, "getCurrentPage"));
            if (page == null) return;
            ViewGroup group = (ViewGroup)XposedHelpers.callMethod(page, "getShortcutsAndWidgets");
            View root = workspace.getRootView();
            int[] xy = new int[2];
            root.getLocationOnScreen(xy);
            int rootBottom = xy[1] + root.getHeight();
            float cx = xy[0] + root.getWidth() * .5f, cy = xy[1] + root.getHeight() * .5f;
            if (captureIcons && group != null) {
                for (int i = 0; i < group.getChildCount(); i++)
                    capture(group.getChildAt(i), xy, cx, cy, root.getWidth(), root.getHeight());
            }
            dock = (View)XposedHelpers.callMethod(launcher, "getHotseat");
            dockGlass = captureIcons && dock != null ? FoldDock.glass(dock) : null;
            if (dockGlass != null) {
                dockGlass.boundsInHost(plate);
                dockBaseY = dock.getTranslationY();
                dock.getLocationOnScreen(xy);
                offscreenTravel = Math.max(0, rootBottom - xy[1] - plate.top + 1);
            } else dock = null;
            pageIndicator = captureIcons ? (View)XposedHelpers.callMethod(workspace, "getPageIndicator") : null;
            if (pageIndicator != null && pageIndicator.isShown()) {
                indicatorBaseY = pageIndicator.getTranslationY();
                pageIndicator.getLocationOnScreen(xy);
                indicatorTravel = Math.max(0, rootBottom - xy[1] + 1);
            } else pageIndicator = null;
            if (dock != null || pageIndicator != null) {
                dockFraction = 0f;
                dockObserver = workspace.getViewTreeObserver();
                dockObserver.addOnPreDrawListener(keepDockPosition);
            }
            death = () -> workspace.post(() -> { if (generation == current) reset(); });
            token.linkToDeath(death, 0);
            owner = token;
            Message result = Message.obtain();
            result.replyTo = new Messenger(new Handler(Looper.getMainLooper(), message -> {
                if (generation != current) return true;
                if (message.what == END) { if (!awaitingNative) reset(); }
                else if (message.what == RETURN_DOCK) {
                    awaitingNative = true;
                    // If the ordered launch request is dropped, restore the hotseat.
                    workspace.removeCallbacks(recover);
                    workspace.postDelayed(recover, 750);
                }
                else {
                    float progress = Float.intBitsToFloat(message.what);
                    if (!(progress >= 0f && progress <= 1f)) return true;
                    if (progress != appliedSpread) {
                        for (Item item : items) item.apply(progress);
                        appliedSpread = progress;
                    }
                    float dockProgress = Float.intBitsToFloat(message.arg1);
                    if (dockProgress >= 0f && dockProgress <= 1f) {
                        if (dockGlass != null && dockProgress < dockFraction) dockGlass.setReturnWorkspace(workspace);
                        if (dockGlass != null && dockProgress == 1f) dockGlass.setReturnWorkspace(null);
                        parked = message.arg2 != 0;
                        placeDock(dockProgress);
                    }
                }
                return true;
            }));
            reply.send(result);
        }

        private void capture(View child, int[] xy, float cx, float cy, int width, int height) {
            if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0) return;
            child.getLocationOnScreen(xy);
            items.add(new Item(child, xy[0] + child.getWidth() * .5f - cx,
                    xy[1] + child.getHeight() * .5f - cy, width, height));
        }

        private void placeDock(float fraction) {
            dockFraction = fraction;
            float travel = parked ? 1f : fraction;
            if (pageIndicator != null) {
                float y = indicatorBaseY + indicatorTravel * travel;
                if (pageIndicator.getTranslationY() != y) pageIndicator.setTranslationY(y);
            }
            if (dock == null) return;
            float y = dockBaseY + offscreenTravel * travel;
            if (dock.getTranslationY() == y) return;
            dock.setTranslationY(y);
            dock.invalidate();
        }

        void reset() {
            workspace.removeCallbacks(recover);
            awaitingNative = false;
            parked = false;
            placeDock(0f);
            releaseDock();
            endGesture();
        }

        private void releaseDock() {
            if (dockGlass != null) dockGlass.setReturnWorkspace(null);
            if (dockObserver != null && dockObserver.isAlive())
                dockObserver.removeOnPreDrawListener(keepDockPosition);
            dockObserver = null;
            dock = null;
            pageIndicator = null;
            dockGlass = null;
            offscreenTravel = 0f;
        }

        private void endGesture() {
            generation++;
            if (owner != null && owner.isBinderAlive()) owner.unlinkToDeath(death, 0);
            owner = null;
            if (appliedSpread != 0f) for (Item item : items) item.apply(0f);
            appliedSpread = 0f;
            items.clear();
        }
    }

    private static final class Item {
        final View view;
        final float dx, dy, x, y, sx, sy, extraSpread;
        Item(View view, float dx, float dy, int width, int height) {
            this.view = view;
            this.dx = dx;
            this.dy = dx == 0f && dy == 0f ? -1f : dy;
            x = view.getTranslationX();
            y = view.getTranslationY();
            sx = view.getScaleX();
            sy = view.getScaleY();
            // The final icon is twice its original size. A fixed radial scale
            // leaves central icons parked behind the sheet on a slow wallpaper load.
            float exitX = dx == 0f ? Float.POSITIVE_INFINITY
                    : (width * .5f + view.getWidth() * Math.abs(sx)) / Math.abs(dx);
            float exitY = this.dy == 0f ? Float.POSITIVE_INFINITY
                    : (height * .5f + view.getHeight() * Math.abs(sy)) / Math.abs(this.dy);
            extraSpread = Math.max(0f, Math.min(exitX, exitY) - 1f - 1.6f);
        }
        void apply(float progress) {
            float spread = progress * (1.6f + progress * extraSpread);
            float size = 1f + progress;
            view.setTranslationX(x + dx * spread);
            view.setTranslationY(y + dy * spread);
            view.setScaleX(sx * size);
            view.setScaleY(sy * size);
        }
    }
}
