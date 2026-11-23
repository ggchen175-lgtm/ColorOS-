package cn.zhe.chaojibeauty;

import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;

/** Shared dp dimensions. Insets and measured view bounds are already in layout pixels. */
final class NotificationViewport {
    private NotificationViewport() {}
    static final int HEADER_GAP=16, ACTION_GAP=14;
    static float pixels(View view,float dp){
        return android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP,dp,view.getResources().getDisplayMetrics());
    }
    static int dp(View view,float dp){return Math.round(pixels(view,dp));}
    static int layoutY(View view,int[] location){
        View v=view;
        int y=0;
        while(v.getParent() instanceof View){
            View parent=(View)v.getParent();
            y+=v.getTop()-parent.getScrollY();
            v=parent;
        }
        v.getLocationInWindow(location);
        return y+location[1];
    }
    static int statusBottom(View root){
        WindowInsets insets=root.getRootWindowInsets();
        if(insets==null)return 0;
        Insets safe=insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()|WindowInsets.Type.displayCutout());
        return safe.top;
    }
    static int unlockBandTopOnScreen(View window,int[] location){
        View root=window.getRootView();
        root.getLocationOnScreen(location);
        WindowInsets insets=root.getRootWindowInsets();
        int nav=insets==null?0:insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;
        return location[1]+root.getHeight()-Math.max(dp(root,64),nav);
    }
    static int navigationTop(View view,int[] location){
        View root=view.getRootView();
        root.getLocationInWindow(location);
        WindowInsets insets=root.getRootWindowInsets();
        int inset=insets==null?0:insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()|WindowInsets.Type.displayCutout()).bottom;
        return location[1]+root.getHeight()-inset;
    }
}
