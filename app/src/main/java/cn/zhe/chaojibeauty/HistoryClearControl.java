package cn.zhe.chaojibeauty;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/** A single native header action, from its closed glyph to its confirmed title. */
final class HistoryClearControl extends Drawable {
    enum Action { HISTORY, GROUP, LESS }
    private final Action action;
    private final String scope;
    private final ImageView view;
    private final NotificationGlass glass;
    private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF shape = new RectF();
    private final Rect hit = new Rect();
    private final float density;
    private final int collapsedWidth, expandedWidth;
    private boolean expanded;
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevron = new Path();
    private float progress, titleCenterOffset;
    private ValueAnimator animator;
    private java.util.function.DoubleConsumer resizePair;

    HistoryClearControl(ImageView view, NotificationGlass glass, TextView title) {
        this(view, glass, Action.HISTORY);
        // Both children belong to the native section header. Read their final layout
        // together so the glass follows the title's visible ink through header motion.
        ViewGroup content = (ViewGroup)view.getParent();
        Rect inkBounds = new Rect();
        content.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            CharSequence text = title.getText();
            title.getPaint().getTextBounds(text, 0, text.length(), inkBounds);
            if (inkBounds.isEmpty()) return;
            float center = title.getY() + title.getBaseline() - title.getScrollY() + inkBounds.exactCenterY();
            titleCenterOffset = center - view.getY() - view.getHeight() * .5f;
            invalidateSelf();
        });
    }

    HistoryClearControl(ImageView view, NotificationGlass glass, Action action) {
        this.action = action;
        scope = action == Action.HISTORY ? "通知中心" : "本组通知";
        this.view = view;
        this.glass = glass;
        density = view.getResources().getDisplayMetrics().density;
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(1.6f * density);
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);
        mask.setColor(Color.WHITE);
        ink.setTextAlign(Paint.Align.CENTER);
        ink.setTextSize(11.5f * view.getResources().getDisplayMetrics().scaledDensity);
        collapsedWidth = Math.round(44 * density);
        expandedWidth = collapsedWidth + Math.round(action != Action.HISTORY ? ink.measureText("更少内容") + 7*density : ink.measureText("清除"));
        view.setImageDrawable(null);
        view.setPadding(0,0,0,0);
        view.setContentDescription(action == Action.LESS ? "更少内容" : "清除" + scope);
        view.setBackground(this);
        if (action == Action.HISTORY && view.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams) {
            android.widget.FrameLayout.LayoutParams layout = (android.widget.FrameLayout.LayoutParams)view.getLayoutParams();
            layout.topMargin = layout.bottomMargin = 0;
            layout.setMarginEnd(0);
            // The section content uses the same outer bounds as a notification row.
            // Its native end padding is for an inset icon, not the edge of a glass button.
            if (view.getParent() instanceof ViewGroup) {
                ViewGroup content = (ViewGroup)view.getParent();
                content.setPaddingRelative(content.getPaddingStart(), content.getPaddingTop(), 0, content.getPaddingBottom());
            }
            layout.gravity = android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL;
        }
        setWidth(action == Action.LESS ? expandedWidth : collapsedWidth);
    }

    private void setWidth(int width) {
        ViewGroup.LayoutParams p = view.getLayoutParams();
        p.width = width;
        p.height = collapsedWidth;
        view.setLayoutParams(p);
    }

    void pairWith(java.util.function.DoubleConsumer resize) { resizePair = resize; }

    void setGroupProgress(float value) {
        progress = value;
        invalidateSelf();
    }

    int groupWidth(float visibleFraction) {
        return collapsedWidth + Math.round((expandedWidth-collapsedWidth)*visibleFraction);
    }

    void show(boolean visible) {
        view.setVisibility(visible ? ImageView.VISIBLE : ImageView.INVISIBLE);
    }

    boolean owns(Object candidate) { return candidate == view; }

    boolean contains(float x, float y) {
        return view.isShown() && view.getGlobalVisibleRect(hit) && hit.contains((int)x, (int)y);
    }

    void setExpanded(boolean value) {
        if (expanded == value) return;
        expanded = value;
        if (animator != null) animator.cancel();
        if (value && resizePair == null) setWidth(expandedWidth);
        view.setContentDescription((value ? "确认清除" : "清除") + scope);
        if (value) view.announceForAccessibility("再点清除，清除" + scope);
        animator = ValueAnimator.ofFloat(progress, value ? 1f : 0f);
        animator.setDuration(250);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float)a.getAnimatedValue();
            if (resizePair != null) resizePair.accept(progress);
            invalidateSelf();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (a == animator && !expanded && resizePair == null) setWidth(collapsedWidth);
            }
        });
        animator.start();
    }

    @Override public void draw(Canvas canvas) {
        Rect box = getBounds();
        float diameter = Math.min(28*density, box.height()), radius = diameter/2;
        float cy = box.exactCenterY() + titleCenterOffset;
        float fraction = action == Action.LESS ? 1-progress : progress;
        float width = diameter + fraction*(expandedWidth-collapsedWidth);
        // Pin the visible glass to the logical end of its hit target in both states.
        boolean rtl = view.getLayoutDirection() == android.view.View.LAYOUT_DIRECTION_RTL;
        float left = rtl ? box.left : box.right - width;
        float right = left + width;
        shape.set(left, cy-radius, right, cy+radius);
        NotificationMaterial.Palette palette=glass.palette(view);
        boolean refracted=glass.paintHeader(view,canvas,shape,radius,mask);
        if(refracted) {
            IconModule.firstDraw(action == Action.LESS ? "group collapse glass" :
                    action == Action.GROUP ? "group clear glass" : "history clear glass");
        }
        ink.setColor(palette.face);
        if(refracted)ink.setAlpha(ink.getAlpha()/2);
        canvas.drawRoundRect(shape,radius,radius,ink);
        int foreground=palette.ink;
        glyphPaint.setColor(foreground);
        ink.setColor(foreground);
        if (action == Action.LESS) {
            float center = shape.left + radius;
            chevron.rewind();
            chevron.moveTo(center - 5 * density, cy + 2.5f * density);
            chevron.lineTo(center, cy - 2.5f * density);
            chevron.lineTo(center + 5 * density, cy + 2.5f * density);
            canvas.drawPath(chevron, glyphPaint);
            if (fraction > .55f) {
                ink.setAlpha(Math.round(255*(fraction-.55f)/.45f));
                canvas.drawText("更少内容",shape.left + diameter/2 + 10*density + ink.measureText("更少内容")/2,
                        cy-(ink.ascent()+ink.descent())/2,ink);
            }
            return;
        }
        if (progress < 1) {
            float r = 5*density;
            float center = rtl ? left + radius : right - radius;
            glyphPaint.setAlpha(Math.round(255 * (1 - progress)));
            canvas.drawLine(center - r, cy - r, center + r, cy + r, glyphPaint);
            canvas.drawLine(center - r, cy + r, center + r, cy - r, glyphPaint);
        }
        if (progress > 0) {
            ink.setAlpha(Math.round(255*progress));
            canvas.drawText("清除",shape.centerX(),cy-(ink.ascent()+ink.descent())/2,ink);
        }
    }
    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
