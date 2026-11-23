package cn.zhe.chaojibeauty;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import java.util.ArrayList;
import java.util.Set;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.callStaticMethod;

/** The confirmed clear commits immediately. Only the outgoing presentation survives the rebuild. */
final class NotificationClearExit extends View {
    private static final class Face {
        final RenderNode node = new RenderNode("Departing notification");
        final boolean header;
        View original;
        float originalAlpha;
        final float center;
        final Rect source = new Rect(), target = new Rect();
        Face(boolean header, float center) { this.header=header; this.center=center; }
    }

    private final ViewGroup host;
    private final ArrayList<Face> faces = new ArrayList<>();
    private final float edge;
    private ValueAnimator animator;
    private float progress;
    private int rows;
    private Bitmap snapshot;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private NotificationClearExit(ViewGroup host,int edge) {
        super(host.getContext());
        this.host=host;
        this.edge=edge;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    static NotificationClearExit capture(ViewGroup list, View title, Set<View> ordinary, int edge) {
        ViewGroup parent=(ViewGroup)list.getParent();
        NotificationClearExit exit=new NotificationClearExit(parent,edge);
        if (!list.isHardwareAccelerated() || !ValueAnimator.areAnimatorsEnabled()) return exit;
        int width=list.getWidth(), stripHeight=0;
        for (int i=0;i<list.getChildCount();i++) {
            View v=list.getChildAt(i);
            boolean header=v==title;
            if ((!header && !ordinary.contains(v)) || !v.isShown() || v.getAlpha()<=0) continue;
            int h=header ? v.getHeight() : (int)callMethod(v,"getActualHeight");
            float y=v.getY()-list.getScrollY();
            if (width<=0 || h<=0 || y>=edge || y+h<=0) continue;
            Face f=new Face(header,y+h*.5f);
            // Include the group's rear glass faces drawn by the list itself.
            int height=h+Math.round(24*list.getResources().getDisplayMetrics().density);
            f.target.set(0,Math.round(y),width,Math.round(y)+height);
            f.source.set(0,stripHeight,width,stripHeight+height);
            f.node.setPosition(f.source.left,f.source.top,f.source.right,f.source.bottom);
            stripHeight+=height;
            RecordingCanvas c=f.node.beginRecording(width,height);
            try {
                c.translate(0,-y);
                callMethod(list,"drawChild",c,v,list.getDrawingTime());
            } finally { f.node.endRecording(); }
            f.original=v;
            f.originalAlpha=v.getTransitionAlpha();
            exit.faces.add(f);
            if (!header) exit.rows++;
        }
        if (stripHeight>0) {
            // drawChild records references to OEM render nodes. Freeze them once,
            // before the collection recycles or moves those same nodes.
            RenderNode strip=new RenderNode("Confirmed notification clear");
            strip.setPosition(0,0,width,stripHeight);
            RecordingCanvas c=strip.beginRecording();
            try { for (Face f : exit.faces) c.drawRenderNode(f.node); }
            finally { strip.endRecording(); }
            try {
                exit.snapshot=(Bitmap)callStaticMethod(HardwareRenderer.class,"createHardwareBitmap",strip,width,stripHeight);
            } finally {
                strip.discardDisplayList();
                for (Face f : exit.faces) f.node.discardDisplayList();
            }
        }
        int left=list.getLeft()+Math.round(list.getTranslationX());
        int top=list.getTop()+Math.round(list.getTranslationY());
        exit.layout(left,top,left+width,top+Math.max(list.getHeight(),edge));
        return exit;
    }

    boolean owns(View view) {
        if (snapshot==null) return false;
        for (Face f : faces) if (f.original==view) return true;
        return false;
    }

    void start() {
        if (snapshot==null || !host.isAttachedToWindow()) { finish(); return; }
        for (Face f : faces) f.original.setTransitionAlpha(0f);
        host.getOverlay().add(this);
        animator=ValueAnimator.ofFloat(0,1);
        animator.setDuration((long)LiveTune.get("clearMs",560,100,1600));
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            double t=(float)a.getAnimatedValue()*.56;
            // Unit mass damped spring with the firmware's default tension 145 and friction 22.
            progress=(float)(1-Math.exp(-11*t)*(Math.cos(Math.sqrt(24)*t)+11/Math.sqrt(24)*Math.sin(Math.sqrt(24)*t)));
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { finish(); }
        });
        animator.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (snapshot==null || !canvas.isHardwareAccelerated()) return;
        for (Face f : faces) {
            float p=f.header ? progress : Math.min(1,progress*(rows==1 ? 15f : 1.8f));
            if (p>=1) continue;
            float scale=1-(f.header ? .8f : .16f)*p;
            int save=canvas.save();
            // One positional progress keeps the section header between its two sections.
            canvas.translate(0,Math.max(0,edge-f.center)*progress);
            canvas.scale(scale,f.header ? scale : Math.min(scale,1-progress),getWidth()*.5f,f.center);
            ink.setAlpha(Math.round(255*(1-p)));
            canvas.drawBitmap(snapshot,f.source,f.target,ink);
            canvas.restoreToCount(save);
        }
    }

    void finish() {
        if (animator!=null) { animator.removeAllListeners(); animator.cancel(); animator=null; }
        host.getOverlay().remove(this);
        for (Face f : faces) f.node.discardDisplayList();
        for (Face f : faces) {
            if (f.original.getTransitionAlpha()==0f) f.original.setTransitionAlpha(f.originalAlpha);
        }
        snapshot=null;
        faces.clear();
    }
}
