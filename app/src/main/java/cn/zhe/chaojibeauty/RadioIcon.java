package cn.zhe.chaojibeauty;

import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;

/** Keep the native slot and connectivity updates. Only its ink changes. */
final class RadioIcon extends DrawableWrapper {
    private final Drawable style;
    private final float scale;
    private final Rect ink = new Rect();

    RadioIcon(Drawable original, Drawable replacement, float scale) {
        super(original);
        this.scale = scale;
        style = replacement.mutate();
        style.setTint(Color.WHITE);
        style.setAlpha(original.getAlpha());
        style.setColorFilter(original.getColorFilter());
    }
    @Override public void draw(Canvas canvas) {
        if (!IconModule.enabled) { super.draw(canvas); return; }
        Rect b = getBounds();
        float width = style.getIntrinsicWidth(), height = style.getIntrinsicHeight();
        if (width <= 0 || height <= 0) { super.draw(canvas); return; }
        float fit = Math.min(b.width()/width, b.height()/height) * scale;
        int w = Math.round(width*fit), h = Math.round(height*fit);
        int x = b.centerX()-w/2, y = b.centerY()-h/2;
        ink.set(x,y,x+w,y+h);
        style.setBounds(ink);
        style.setState(getState());
        style.draw(canvas);
        IconModule.firstDraw(style instanceof BitmapDrawable && scale == 1f ? "wifi" : "signal");
    }
    @Override public void setTintList(ColorStateList tint) {
        super.setTintList(tint);
        if (style != null) style.setTintList(tint);
    }
    @Override public void setTintBlendMode(BlendMode mode) {
        super.setTintBlendMode(mode);
        if (style != null) style.setTintBlendMode(mode);
    }
    @Override public void setAlpha(int alpha) {
        super.setAlpha(alpha);
        if (style != null) style.setAlpha(alpha);
    }
    @Override public void setColorFilter(ColorFilter filter) {
        super.setColorFilter(filter);
        if (style != null) style.setColorFilter(filter);
    }
    @Override public ConstantState getConstantState() { return null; }
}
