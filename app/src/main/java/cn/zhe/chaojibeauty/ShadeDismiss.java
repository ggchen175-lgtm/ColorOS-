package cn.zhe.chaojibeauty;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import java.util.function.BooleanSupplier;

/** Interactive dismissal from the empty strip below the notification controls. */
final class ShadeDismiss {
    private final View panel;
    private final View[] layers;
    private final float[] origins;
    private final Runnable onCommit, collapse;
    private final BooleanSupplier wallpaperReady;
    private final float[] opacity;
    private boolean fixedBackdrop, fading, committed;
    // CSCoverSheetDismissGestureSettings, physical home-indicator device (24A437).
    private static final float EDGE_HEIGHT = 33.75f;
    private static final float EXTENDED_HEIGHT = 64f;
    private static final float EXTENDED_INSET_X = 16f;
    private final int slop;
    private final ShadePullRelease samples = new ShadePullRelease();
    private final int[] rootOrigin = new int[2];
    private boolean candidate, dragging;
    private float downX;
    private long downTime = -1;
    private ValueAnimator settle;
    private float down, offset, initialOffset;
    private final java.util.function.Consumer<Float> position;

    ShadeDismiss(View panel, View behind, View notifications, View wallpaper, BooleanSupplier wallpaperReady, java.util.function.Consumer<Float> position, Runnable onCommit, Runnable collapse) {
        this.panel=panel;
        this.collapse=collapse;
        this.onCommit=onCommit;
        this.position=position;
        this.wallpaperReady=wallpaperReady;
        layers=new View[]{panel,behind,notifications,wallpaper};
        origins=new float[layers.length];
        opacity=new float[layers.length];
        ViewConfiguration config=ViewConfiguration.get(panel.getContext());
        slop=config.getScaledTouchSlop();
    }

    boolean startsAtBottom(float rawX, float rawY) {
        View root = panel.getRootView();
        root.getLocationOnScreen(rootOrigin);
        float fromBottom = rootOrigin[1] + root.getHeight() - rawY;
        float x = rawX - rootOrigin[0];
        float density = panel.getResources().getDisplayMetrics().density;
        fromBottom-=LiveTune.get("gestureLift",0,0,180)*density;
        float width=root.getWidth()*LiveTune.get("gestureW",1,.15f,1);
        float left=(root.getWidth()-width)*.5f+LiveTune.dp(root,"gestureX");
        if(x<left || x>=left+width || fromBottom<0)return false;
        if (fromBottom <= LiveTune.get("gestureEdge",EDGE_HEIGHT,6,108) * density) return true;
        float inset = EXTENDED_INSET_X * density;
        return fromBottom <= EXTENDED_HEIGHT * density && x >= inset && x < root.getWidth() - inset;
    }

    void startAt(float y) { offset = initialOffset = y; }

    void beginTouch(float x, float y, long time) {
        if (settle != null || committed) return;
        downTime = time;
        candidate = startsAtBottom(x, y);
        dragging = false;
        if (!candidate) return;
        initialOffset = offset;
        fixedBackdrop=wallpaperReady.getAsBoolean();
        for(int i=0;i<layers.length;i++) if(layers[i]!=null) {
            origins[i]=layers[i].getTranslationY();
            opacity[i]=layers[i].getAlpha();
        }
        down=y;
        downX=x;
        samples.begin(time, down);
    }

    boolean touch(MotionEvent e, Runnable onClaim) {
        int action=e.getActionMasked();
        if (settle!=null || committed) return true;
        if (!candidate || e.getEventTime()<downTime || action==MotionEvent.ACTION_DOWN) return false;
        for (int i=0; i<e.getHistorySize(); i++)
            samples.sample(e.getHistoricalEventTime(i),e.getHistoricalY(i)+e.getRawY()-e.getY());
        samples.sample(e.getEventTime(),e.getRawY());
        if (!dragging) {
            float up = down - e.getRawY(), side = Math.abs(e.getRawX()-downX);
            if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) && up > slop && up > side) {
                onClaim.run();
                dragging = true;
            }
            else {
                if (action != MotionEvent.ACTION_MOVE || side > slop || up < -slop) candidate = false;
                return false;
            }
        }
        if (action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_UP) {
            move(Math.max(-panel.getHeight(),Math.min(0,initialOffset+e.getRawY()-down)));
        }
        if (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_POINTER_DOWN) {
            boolean finish=action==MotionEvent.ACTION_UP && down-e.getRawY()>slop
                    && !ShadePullRelease.opens(e.getRawY()-rootOrigin[1], panel.getRootView().getHeight(),
                            samples.velocity(), panel.getResources().getDisplayMetrics().density);
            candidate = dragging = false;
            animate(finish);
        }
        return true;
    }

    private void move(float y) {
        if (offset == y) return;
        offset=y;
        if (position!=null) {
            position.accept(1f + y/Math.max(1,panel.getHeight()));
            return;
        }
        int moving=fixedBackdrop ? 1 : layers.length;
        for(int i=0;i<moving;i++) if(layers[i]!=null) layers[i].setTranslationY(origins[i]+y);
    }

    private void animate(boolean finish) {
        committed=finish;
        if (finish) onCommit.run();
        float target=finish ? -panel.getHeight() : 0;
        settle=ValueAnimator.ofFloat(offset,target);
        settle.setInterpolator(new DecelerateInterpolator());
        settle.setDuration(Math.round(LiveTune.get("pageTime",1,.35f,2.5f)*(80+180*Math.abs(target-offset)/Math.max(1,panel.getHeight()))));
        settle.addUpdateListener(a->move((float)a.getAnimatedValue()));
        settle.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                settle=null;
                if(finish && position==null && fixedBackdrop) fadeWallpaper();
                else if(finish) collapse.run();
                else if(position!=null) position.accept(1f);
            }
        });
        settle.start();
    }

    private void fadeWallpaper() {
        fading=true;
        settle=ValueAnimator.ofFloat(1f,0f);
        settle.setStartDelay(55);
        settle.setDuration(300);
        settle.addUpdateListener(a->{
            float alpha=(float)a.getAnimatedValue();
            for (int i=1;i<layers.length;i++) if(layers[i]!=null) layers[i].setAlpha(opacity[i]*alpha);
        });
        settle.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { settle=null; collapse.run(); }
        });
        settle.start();
    }

    boolean interacting() { return dragging || settle!=null || committed; }

    void release() {
        if(settle!=null) {
            settle.removeAllListeners();
            settle.cancel();
            settle=null;
        }
        candidate = dragging = false;
        if(offset!=0 && position==null) move(0);
        if(fading) for(int i=1;i<layers.length;i++) if(layers[i]!=null) layers[i].setAlpha(opacity[i]);
        fading=false;
    }
}
