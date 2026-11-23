package cn.zhe.chaojibeauty;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import de.robv.android.xposed.XposedHelpers;

/** One resolved foreground for the poster date and its accessories. Owned by the clock host. */
final class LockForeground implements WallpaperManager.OnColorsChangedListener, View.OnAttachStateChangeListener {
    private static final String KEY = "chaoji.lockForeground";
    private final WallpaperManager wallpaper;
    private final View host;
    private TextView title;
    private int wallpaperInk = Color.WHITE;
    private int ambientGray = 180;
    private int paperColor=0xff526880;
    int paperColor(){return paperColor;}

    int clockGray() { return Color.rgb(ambientGray,ambientGray,ambientGray); }
    private boolean listening, dark;

    static LockForeground bind(View host) {
        if (host == null) return null;
        LockForeground result = (LockForeground) XposedHelpers.getAdditionalInstanceField(host, KEY);
        if (result == null) {
            result = new LockForeground(host);
            XposedHelpers.setAdditionalInstanceField(host, KEY, result);
        }
        result.title = LockStylePreview.titleInkSource(host);
        return result;
    }

    private LockForeground(View host) {
        this.host = host;
        wallpaper = host.getContext().getSystemService(WallpaperManager.class);
        host.addOnAttachStateChangeListener(this);
        if (host.isAttachedToWindow()) onViewAttachedToWindow(host);
    }

    int color() {
        boolean currentDark = GlassAppearance.dark(host.getRootView());
        if (dark != currentDark) {
            dark = currentDark;
            title = LockStylePreview.titleInkSource(host);
            if (wallpaper != null) {
                try { readWallpaper(); }
                catch (RuntimeException e) { Log.w("ChaojiBeauty","Appearance palette unavailable",e); }
            }
        }
        if (title == null) return wallpaperInk;
        int c = title.getCurrentTextColor();
        int max = Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c)));
        int min = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c)));
        if (max-min <= 6 && min >= 88 && max <= 164) return wallpaperInk;
        return c | 0xff000000;
    }

    private void readWallpaper() {
        WallpaperColors colors = wallpaper.getWallpaperColors(WallpaperManager.FLAG_LOCK);
        if (colors == null) colors = wallpaper.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
        if (colors == null) { wallpaperInk = Color.WHITE; ambientGray=180; return; }
        int primary = colors.getPrimaryColor().toArgb();
        paperColor=primary;
        boolean dark = (colors.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0;
        // Keep the hue of the wallpaper within a legible light or dark foreground.
        int r = Color.red(primary), g = Color.green(primary), b = Color.blue(primary);
        // Start with the luminance of the awake glass body, then remove its hue and glints.
        float luma=.2126f*r+.7152f*g+.0722f*b;
        ambientGray=Math.round(.90f*(.34f*luma+.66f*244));
        int anchor = dark ? Math.min(r, Math.min(g,b)) : Math.max(r, Math.max(g,b));
        int base = dark ? 22 : 255;
        wallpaperInk = Color.rgb(base+Math.round(.28f*(r-anchor)), base+Math.round(.28f*(g-anchor)),
                base+Math.round(.28f*(b-anchor)));
    }

    @Override public void onViewAttachedToWindow(View v) {
        if (wallpaper == null || listening) return;
        try {
            wallpaper.addOnColorsChangedListener(this, new Handler(Looper.getMainLooper()));
            listening = true;
            readWallpaper();
        } catch (RuntimeException e) { Log.w("ChaojiBeauty","Lock foreground unavailable",e); }
    }
    @Override public void onViewDetachedFromWindow(View v) {
        if (listening) wallpaper.removeOnColorsChangedListener(this);
        listening = false;
        title = null;
        XposedHelpers.removeAdditionalInstanceField(host, KEY);
        host.removeOnAttachStateChangeListener(this);
    }
    @Override public void onColorsChanged(WallpaperColors colors, int which) {
        if (!listening) return;
        try { readWallpaper(); host.postInvalidateOnAnimation(); }
        catch (RuntimeException e) { Log.w("ChaojiBeauty","Wallpaper foreground unavailable",e); }
    }
}
