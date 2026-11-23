package cn.zhe.chaojibeauty;

import android.content.res.Configuration;
import android.view.View;

/** Scope appearance to the material's owner. The wallpaper keeps its own poster appearance. */
final class GlassAppearance {
    static boolean dark(View owner) {
        return (owner.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    // Relative saturation of CoreMaterial dockDark / dockLight. The transmission
    // ratio is fitted to the exposed Dock between icons in the light/dark recording.
    static final String DOCK_TONE =
            "uniform float darkAppearance;" +
            "half3 dockTone(half3 c){half y=dot(c,half3(.2126,.7152,.0722));" +
            "return mix(c,(y+(c-y)*(1.6/1.8))*.84,darkAppearance);}";

    private GlassAppearance() {}
}
