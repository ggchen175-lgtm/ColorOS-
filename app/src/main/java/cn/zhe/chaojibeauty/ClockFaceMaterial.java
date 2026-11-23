package cn.zhe.chaojibeauty;

import android.graphics.*;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;

/** One native glyph mask and one live wallpaper region, in the same local coordinate space. */
final class ClockFaceMaterial {
    private final RenderNode node = new RenderNode("Clock glyph material");
    private final RenderNode backdrop = new RenderNode("Clock wallpaper");
    private final RenderNode bevel = new RenderNode("Clock bevel mask");
    private final RuntimeShader shader;
    private final Rect area = new Rect();
    private final Rect local = new Rect();
    private int lastWidth, lastHeight;
    private WeakReference<View> widget, owner;
    static View firstCard(View host) {
        return findCard(LockStylePreview.findType(host,"WidgetPluginContainer"),true);
    }

    static View findCard(View v,boolean visibleOnly) {
        if (!(v instanceof ViewGroup) || (visibleOnly && v.getVisibility()!=View.VISIBLE)) return null;
        if (v.getClass().getSimpleName().equals("InstantCardView") && v.getWidth()>0 && v.getHeight()>0) return v;
        ViewGroup group=(ViewGroup)v;
        for (int i=0;i<group.getChildCount();i++) {
            View card=findCard(group.getChildAt(i),visibleOnly);
            if (card!=null) return card;
        }
        return null;
    }

    void anchor(View card, View host) {
        foreground = LockForeground.bind(host);
        widget=new WeakReference<>(card);
        owner=new WeakReference<>(host);
    }

    private int cardTop(View piece) {
        View card=widget==null ? null : widget.get();
        if (card==null || !card.isShown()) {
            card=firstCard(owner==null ? null : owner.get());
            if (card!=null) widget=new WeakReference<>(card);
        }
        if (card==null || piece.getRootView()!=card.getRootView()) return 0;
        View host=owner.get();
        return layoutTop(card,host)-layoutTop(piece,host);
    }

    // SystemUI animates the clock and accessory hosts independently on wake.
    // Keep glyph layout in the settled host coordinates and let each host apply its animation once.
    private static int layoutTop(View view, View host) {
        float top=0;
        for (View v=view; v!=host && v.getParent() instanceof View; v=(View)v.getParent()) {
            top+=v.getTop()-((View)v.getParent()).getScrollY();
            if (v.getClass().getSimpleName().equals("WidgetPluginContainer")) top+=v.getTranslationY();
        }
        return Math.round(top);
    }

    private final boolean subtitle;
    private final ClockTypography typography = new ClockTypography();
    private float lastDensity;
    private LockForeground foreground;
    private int lastInk,lastGray;
    private float lastGlass=-1f;
    private boolean lockLayout,lastDark;

    void anchorLock(View card,View host) {
        lockLayout=true;
        anchor(card,host);
    }
    static int portraitWidgetTop(int width) {
        return Math.round(width*(615f/1280f+13f/440f));
    }
    private int glyphAnchor(View piece) {
        View host=owner==null ? null : owner.get();
        if (host!=null && host.getHeight()>host.getWidth() && piece.getHeight()>piece.getWidth()) {
            return portraitWidgetTop(host.getWidth())-layoutTop(piece,host)+Math.round(LiveTune.get("clockShift",0,-80,80)*host.getResources().getDisplayMetrics().density);
        }
        return cardTop(piece);
    }
    

    private static final String INPUT =
            "uniform shader atlas; uniform float width,height,dp;" +
            "float mask(float2 p){if(p.x<0.||p.x>=width)return 0.;return atlas.eval(p).a;}" +
            "half3 paper(float2 p){return atlas.eval(float2(width+clamp(p.x,.5,width-.5),clamp(p.y,.5,height-.5))).rgb;}" +
            "float lum(half3 c){return dot(c,half3(.2126,.7152,.0722));}";
    private static final String TIME =
            "uniform float glassAmount,darkAppearance;layout(color) uniform half4 grayInk;" +
            "float smoothMask(float2 p){return atlas.eval(float2(2.*width+clamp(p.x,.5,width-.5),clamp(p.y,.5,height-.5))).a;}" +
            "half4 main(float2 p){float a=mask(p);if(a<=0.)return half4(0);" +
            "float2 dx=float2(dp,0),dy=dx.yx;" +
            "float2 n=float2(smoothMask(p+dx)-smoothMask(p-dx),smoothMask(p+dy)-smoothMask(p-dy));" +
            "half3 bg=paper(p+6.*dp*n);half3 body=mix(bg,half3(.92,.96,1.),mix(.66,.62,darkAppearance));" +
            "float key=max(0.,dot(n,float2(.46,.89)));float fill=max(0.,dot(n,float2(-.8,-.6)));" +
            "float2 rim=float2(mask(p+.45*dx)-mask(p-.45*dx),mask(p+.45*dy)-mask(p-.45*dy));" +
            "float glint=max(0.,dot(rim,float2(.46,.89)))*(.18+.25*lum(bg));" +
            "body=mix(body,half3(1),clamp(.72*key+.24*fill+glint,0.,1.));return half4(mix(grayInk.rgb,body,glassAmount)*a,a);}";
    private static final String DATE =
            "layout(color) uniform half4 foregroundInk;" +
            "half4 main(float2 p){float a=mask(p);float s=.35*mask(p-float2(0,dp*.65))*(1.-a);" +
            "return half4(foregroundInk.rgb*a,a+s);}";

    ClockFaceMaterial(boolean date) {
        subtitle = date;
        shader = new RuntimeShader(INPUT + (date ? DATE : TIME));
    }

    // Use the glyph-bearing children to avoid allocating a screen-sized clock render target.
    private void measure(View root, View view) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() == 0f) return;
        if (view instanceof ViewGroup && ((ViewGroup)view).getChildCount() > 0) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) measure(root, group.getChildAt(i));
        } else {
            local.set(0,0,view.getWidth(),view.getHeight());
            if (root != view) ((ViewGroup)root).offsetDescendantRectToMyCoords(view,local);
            area.union(local);
        }
    }

    private int inkBottom, inkWidth, inkHeight;

    private void rememberInk(View piece) {
        inkBottom=area.bottom;
        inkWidth=piece.getWidth();
        inkHeight=piece.getHeight();
    }

    int contentBottom(View piece,View host) {
        if (piece==null || inkWidth!=piece.getWidth() || inkHeight!=piece.getHeight() || inkBottom<=0) return 0;
        return layoutTop(piece,host)+inkBottom;
    }

    private float mountX, mountY;

    boolean mountChanged(View mount) {
        float x=mount.getScaleX(), y=mount.getScaleY();
        if (x==mountX && y==mountY) return false;
        mountX=x;
        mountY=y;
        return true;
    }

    // CoverSheet 24A437 uses targetScale 0.97 on its AOD clock component.
    boolean drawForeground(Canvas out,View glyph,View wrapper,boolean ambient) {
        area.setEmpty();
        measure(glyph,glyph);
        if (area.isEmpty() || !typography.layout(glyph,subtitle,area,glyphAnchor(glyph))) return false;
        rememberInk(glyph);
        if (ambient) transformMount(out,glyph,wrapper);
        typography.draw(out,subtitle ? (foreground==null ? Color.WHITE : foreground.color()) : grayInk());
        return true;
    }

    private int grayInk() { return foreground==null ? 0xffb4b4b4 : foreground.clockGray(); }

    private void transformMount(Canvas out,View glyph,View wrapper) {
        float sx=wrapper.getScaleX(), sy=wrapper.getScaleY();
        if (sx<=0 || sy<=0 || (sx==1f && sy==1f)) return;
        out.scale(1f/sx,1f/sy,wrapper.getPivotX()-glyph.getLeft(),wrapper.getPivotY()-glyph.getTop());
        out.scale(WidgetAccessoryLayout.componentScale(sx),WidgetAccessoryLayout.componentScale(sy),
                area.exactCenterX(),area.exactCenterY());
    }

    boolean draw(Canvas out,View glyph,float screenX,float screenY,float posterY,ShadeWallpaper wallpaper,
            boolean transition,float glassAmount) throws ReflectiveOperationException {
        if (!(out instanceof RecordingCanvas)) return false;
        area.setEmpty();
        measure(glyph,glyph);
        if (area.isEmpty()) return false;
        float density = glyph.getResources().getDisplayMetrics().density;
        boolean typeset=typography.layout(glyph,subtitle,area,glyphAnchor(glyph));
        rememberInk(glyph);
        LiveTune.ink(subtitle,screenX,screenY,area);
        if (transition && glyph.getParent() instanceof View) transformMount(out,glyph,(View)glyph.getParent());
        int pad = (int)Math.ceil((subtitle?7:18)*density);
        area.inset(-pad,-pad);
        if (!area.intersect(0,0,glyph.getWidth(),glyph.getHeight())) return false;
        // The shade header is translated from above the physical screen. Its host
        // invalidates as it moves, so an offscreen atlas can be recorded on first exposure.
        if (wallpaper != null && !lockLayout && screenY + area.bottom <= 0f) return true;
        int w = area.width(), h = area.height();
        int ink = subtitle && foreground != null ? foreground.color() : Color.WHITE;
        int gray=subtitle ? 0 : grayInk();
        View clockHost=owner==null?null:owner.get();
        boolean dark=GlassAppearance.dark((clockHost!=null?clockHost:glyph).getRootView());
        boolean opticsChanged=!subtitle && (lastGray!=gray || lastGlass!=glassAmount || lastDark!=dark);
        boolean geometryChanged=ink!=lastInk || w!=lastWidth || h!=lastHeight || density!=lastDensity;
        if (opticsChanged) {
            shader.setFloatUniform("darkAppearance",dark?1f:0f);
            lastDark=dark;
            shader.setColorUniform("grayInk",gray);
            shader.setFloatUniform("glassAmount",glassAmount);
            lastGray=gray;
            lastGlass=glassAmount;
        }
        if (geometryChanged) {
            if (subtitle) shader.setColorUniform("foregroundInk",ink);
            lastInk=ink;
            shader.setFloatUniform("width",w);
            shader.setFloatUniform("dp",density);
            shader.setFloatUniform("height",h);
            if (!subtitle) {
                backdrop.setRenderEffect(RenderEffect.createBlurEffect(8*density,8*density,Shader.TileMode.CLAMP));
                bevel.setRenderEffect(RenderEffect.createBlurEffect(1.8f*density,1.8f*density,Shader.TileMode.DECAL));
            }
            lastWidth=w; lastHeight=h; lastDensity=density;
        }
        // Each RenderEffect snapshots the shader builder's uniforms.
        if (geometryChanged || opticsChanged)
            node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader,"atlas"));
        node.setPosition(0,0,w*(subtitle?1:3),h);
        RecordingCanvas c = node.beginRecording();
        try {
            c.save();
            c.clipRect(0,0,w,h);
            c.translate(-area.left,-area.top);
            if (typeset) typography.draw(c); else glyph.draw(c);
            c.restore();
            if (!subtitle) {
                backdrop.setPosition(w,0,2*w,h);
                RecordingCanvas bg=backdrop.beginRecording();
                try {
                    // Use the current poster hue until the first wallpaper buffer is ready.
                    bg.drawColor(foreground==null?0xff526880:foreground.paperColor());
                    bg.translate(-area.left-screenX,-area.top-posterY);
                    if (wallpaper!=null && wallpaper.hasFrame()) wallpaper.drawFrame(bg);
                } finally { backdrop.endRecording(); }
                c.drawRenderNode(backdrop);
                bevel.setPosition(2*w,0,3*w,h);
                RecordingCanvas mask=bevel.beginRecording();
                try {
                    mask.translate(-area.left,-area.top);
                    if (typeset) typography.draw(mask); else glyph.draw(mask);
                } finally { bevel.endRecording(); }
                c.drawRenderNode(bevel);
            }
        } finally { node.endRecording(); }
        int save=out.save();
        out.translate(area.left,area.top);
        out.clipRect(0,0,w,h);
        ((RecordingCanvas)out).drawRenderNode(node);
        out.restoreToCount(save);
        return true;
    }
}
