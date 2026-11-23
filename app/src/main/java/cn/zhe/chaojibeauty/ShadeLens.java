package cn.zhe.chaojibeauty;

import android.content.Context;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.graphics.*;
import android.view.ViewTreeObserver;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;
import java.lang.reflect.Method;

/** The moving notification panel's background. Screen and panel coordinates only meet at sampling. */
final class ShadeLens implements ViewTreeObserver.OnPreDrawListener, WallpaperManager.OnColorsChangedListener {
    private final View host, backdrop;
    private final Method add;
    private final NotificationGlass cards;
    private final float radius, density;
    private final int[] origin = new int[2];
    private final Matrix toBackdrop = new Matrix();
    private final RectF bounds = new RectF(), lastBounds = new RectF();
    private final RuntimeShader lens = new RuntimeShader(PROGRAM);
    private boolean registered, attached, dirty = true;
    private float progress = -1f, reveal = -1f;
    private float paperScale = 1f, notificationVisibility = 1f;
    private float dimEnd, returnExtent = -1f;
    private int width, height;
    private final WallpaperManager wallpaperManager;
    private boolean observesColors, darkAppearance, brightPoster;
    private final Object source, lock;
    private final Method pending, image, bitmap, region, rotation, scale, remove, release;
    private Bitmap current;
    private boolean closed;
    private boolean sampling, exposeHome, slidingBackdrop;
    private int sampleX, sampleY;
    private float sampleScale;
    private int frames;

    ShadeLens(View host, View backdrop, float radius, NotificationGlass cards) throws ReflectiveOperationException {
        this.cards = cards;
        this.host = host;
        this.backdrop = backdrop;
        this.radius = radius;
        density = host.getResources().getDisplayMetrics().density;
        ClassLoader cl = host.getContext().getClassLoader();
        Class<?> drawable = cl.loadClass("com.oplus.posteffect.BlurDrawable");
        Class<?> param = cl.loadClass("com.oplus.posteffect.BlurParam");
        Object root = View.class.getMethod("getViewRootImpl").invoke(host);
        SurfaceControl surface = (SurfaceControl)root.getClass().getMethod("getSurfaceControl").invoke(root);
        source = drawable.getConstructor(Context.class, SurfaceControl.class)
                .newInstance(host.getContext().getApplicationContext(), surface);
        lock = drawable.getMethod("getDataLock").invoke(source);
        pending = drawable.getMethod("getPendingImageInfo");
        Class<?> info = pending.getReturnType();
        image = info.getMethod("getImage");
        bitmap = image.getReturnType().getMethod("get");
        region = info.getMethod("getCaptureRegion");
        rotation = info.getMethod("getRotation");
        scale = info.getMethod("getScale");
        remove = drawable.getMethod("removeBlurDrawable", boolean.class);
        release = drawable.getMethod("releasePendingBitmap");
        Object settings = drawable.getMethod("getBlurParams").invoke(source);
        param.getMethod("setBlurRadius", int.class).invoke(settings, 1);
        drawable.getMethod("setBlurParamsSync", param).invoke(source, settings);
        lens.setInputShader("scene", new LinearGradient(0,0,1,0,0,0,Shader.TileMode.CLAMP));
        add = drawable.getMethod("addBlurDrawable");
        register();
        wallpaperManager = host.getContext().getSystemService(WallpaperManager.class);
        lens.setFloatUniform("paperMultiply", .50f);
        lens.setFloatUniform("notificationVisibility", notificationVisibility);
        if (wallpaperManager != null) {
            try {
                wallpaperManager.addOnColorsChangedListener(this, new android.os.Handler(host.getContext().getMainLooper()));
                observesColors = true;
                refreshPosterTone();
            } catch (RuntimeException e) { Log.w("ChaojiBeauty","Wallpaper palette unavailable",e); }
        }
        host.getViewTreeObserver().addOnPreDrawListener(this);
    }

    void notificationVisibility(float amount) {
        if (amount == notificationVisibility || closed) return;
        notificationVisibility = amount;
        lens.setFloatUniform("notificationVisibility", amount);
        dirty = true;
        backdrop.invalidate();
    }

    private void refreshPosterTone() {
        WallpaperColors colors = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_LOCK);
        if (colors == null) colors = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
        // Resolve the CoverSheet full-layer endpoints against the poster's own
        // legibility, so changing the UI theme cannot darken the poster a second time.
        boolean lightPoster = colors != null && (colors.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0;
        brightPoster=lightPoster || colors!=null && Color.luminance(colors.getPrimaryColor().toArgb())>.44f;
        applyPosterTone();
        dirty = true;
        backdrop.invalidate();
    }

    private void applyPosterTone(){
        darkAppearance=GlassAppearance.dark(host);
        lens.setFloatUniform("paperMultiply",brightPoster?(darkAppearance?.52f:.56f):.50f);
        dirty=true;
    }

    @Override public void onColorsChanged(WallpaperColors colors, int which) {
        if (closed) return;
        try { refreshPosterTone(); }
        catch (RuntimeException e) { Log.w("ChaojiBeauty","Updated wallpaper palette unavailable",e); }
    }

    private void register() throws ReflectiveOperationException {
        if (!registered) {
            add.invoke(source);
            registered = true;
        }
    }

    private void suspend() throws ReflectiveOperationException {
        if (!registered) return;
        synchronized (lock) {
            cards.clearWindowFrame();
            remove.invoke(source,false);
            release.invoke(source);
            registered=false;
            sampling=false;
            current=null;
            lens.setFloatUniform("ready",0f);
            lens.setInputShader("scene",new LinearGradient(0,0,1,0,0,0,Shader.TileMode.CLAMP));
        }
    }

    void position(float fraction, float wallpaper, float scale, boolean exposeHome, boolean slidingBackdrop, float dimEnd, float returnExtent) {
        if (progress == fraction && reveal == wallpaper && paperScale == scale && this.exposeHome == exposeHome && this.slidingBackdrop == slidingBackdrop && this.dimEnd == dimEnd && this.returnExtent == returnExtent) return;
        this.returnExtent = returnExtent;
        this.dimEnd = dimEnd;
        this.exposeHome = exposeHome;
        this.slidingBackdrop = slidingBackdrop;
        progress = fraction;
        reveal = wallpaper;
        paperScale = scale;
        dirty = true;
        backdrop.invalidate();
    }

    @Override public boolean onPreDraw() {
        if (closed || progress < 0f) return true;
        if(darkAppearance!=GlassAppearance.dark(host))applyPosterTone();
        try {
            if (progress == 1f && reveal == 1f && paperScale == 1f) {
                suspend();
                applyMaterial();
                return true;
            }
            if (reveal < 1f || exposeHome) register();
            toBackdrop.reset();
            host.transformMatrixToGlobal(toBackdrop);
            backdrop.transformMatrixToLocal(toBackdrop);
            bounds.set(0,0,host.getWidth(),host.getHeight());
            toBackdrop.mapRect(bounds);
            if (!bounds.equals(lastBounds)) {
                lastBounds.set(bounds);
                lens.setFloatUniform("panelBounds", bounds.left, bounds.right, bounds.bottom,
                        bounds.width()/Math.max(1,host.getWidth()));
                dirty=true;
            }
            if (width != host.getWidth() || height != host.getHeight()) {
                width=host.getWidth();
                height=host.getHeight();
                lens.setFloatUniform("size",width,height);
                lens.setFloatUniform("shape",radius,Math.min(radius*.4f,9f*density),density);
                dirty=true;
            }
            synchronized (lock) {
                Object next = pending.invoke(source);
                Bitmap frame = next == null ? null : (Bitmap)bitmap.invoke(image.invoke(next));
                float ratio = frame == null ? 0f : (float)scale.invoke(next);
                boolean valid = ratio > 0 && frame != null && !frame.isRecycled()
                        && (int)rotation.invoke(next) == host.getDisplay().getRotation();
                if (valid != sampling) {
                    if (!valid) cards.clearWindowFrame();
                    sampling = valid;
                    dirty=true;
                    lens.setFloatUniform("ready", valid ? 1f : 0f);
                }
                if (valid) {
                    Rect crop = (Rect)region.invoke(next);
                    backdrop.getLocationOnScreen(origin);
                    int x = origin[0]-crop.left, y = origin[1]-crop.top;
                    boolean mappingChanged = ratio != sampleScale || x != sampleX || y != sampleY;
                    if (mappingChanged || frame != current || !cards.hasWindowFrame()) {
                        cards.setWindowFrame(frame,crop.left,crop.top,ratio);
                    }
                    if (mappingChanged) {
                        sampleX = x;
                        sampleY = y;
                        sampleScale = ratio;
                        dirty=true;
                        lens.setFloatUniform("sampleMap", x,y,ratio);
                    }
                    if (frame != current) {
                        BitmapShader texture = new BitmapShader(frame, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                        texture.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                        lens.setInputShader("scene",texture);
                        current=frame;
                        dirty=true;
                        if (++frames == 1) Log.i("ChaojiBeauty","Shade lens source " + frame.getWidth()+"x"+frame.getHeight()+" scale="+ratio+" crop="+crop);
                    }
                }
                applyMaterial();
            }
            // The OEM service can update the same imported buffer while the finger is still.
            if (registered) backdrop.postInvalidateOnAnimation();
        } catch (ReflectiveOperationException | RuntimeException e) {
            close();
            Log.w("ChaojiBeauty","Shade lens unavailable",e);
        }
        return true;
    }

    static RenderEffect wallpaperBlur(View view) {
        float radius = NotificationViewport.pixels(view,8);
        return RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP);
    }

    private RenderEffect backdropBlur, fadedBackdropBlur;
    private int clearEdge, fadeBottom=-1, fadeSize=-1;
    private final RuntimeShader blurPortion=new RuntimeShader(
            "uniform shader sceneInk; uniform float2 edge;"
            +"half4 main(float2 p){return sceneInk.eval(p)*(1.-smoothstep(edge.x,edge.y,p.y));}");
    private final RuntimeShader clearPortion=new RuntimeShader(
            "uniform shader sceneInk; uniform float2 edge;"
            +"half4 main(float2 p){return sceneInk.eval(p)*smoothstep(edge.x,edge.y,p.y);}");

    void clearBackdropBelow(int windowEdge){
        int edge=windowEdge-NotificationViewport.layoutY(backdrop,origin);
        if(edge==clearEdge)return;
        clearEdge=edge;
        dirty=true;
        backdrop.invalidate();
    }

    private RenderEffect wallpaperFilter(){
        if(backdropBlur==null)backdropBlur=wallpaperBlur(backdrop);
        if(clearEdge<=0)return backdropBlur;
        int fade=Math.min(clearEdge,NotificationViewport.dp(backdrop,42));
        if(clearEdge!=fadeBottom || fade!=fadeSize){
            fadeBottom=clearEdge;
            fadeSize=fade;
            blurPortion.setFloatUniform("edge",clearEdge-fade,clearEdge);
            clearPortion.setFloatUniform("edge",clearEdge-fade,clearEdge);
            RenderEffect blurred=RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(blurPortion,"sceneInk"),backdropBlur);
            RenderEffect clear=RenderEffect.createRuntimeShaderEffect(clearPortion,"sceneInk");
            fadedBackdropBlur=RenderEffect.createBlendModeEffect(clear,blurred,BlendMode.PLUS);
        }
        return fadedBackdropBlur;
    }
    private void applyMaterial() {
        if (attached && !dirty) return;
        lens.setFloatUniform("progress",progress);
        lens.setFloatUniform("reveal",reveal);
        lens.setFloatUniform("paperScale",paperScale);
        lens.setFloatUniform("exposeHome",exposeHome ? 1f : 0f);
        lens.setFloatUniform("dimEnd",dimEnd);
        lens.setFloatUniform("slidingBackdrop",slidingBackdrop ? 1f : 0f);
        lens.setFloatUniform("returnExtent",returnExtent);
        backdrop.setRenderEffect(RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(lens,"wallpaper"),wallpaperFilter()));
        dirty=false;
        attached=true;
    }

    void close() {
        if (closed) return;
        closed=true;
        if (observesColors) wallpaperManager.removeOnColorsChangedListener(this);
        if (host.getViewTreeObserver().isAlive()) host.getViewTreeObserver().removeOnPreDrawListener(this);
        if (attached) backdrop.setRenderEffect(null);
        try { suspend(); }
        catch (ReflectiveOperationException e) { Log.w("ChaojiBeauty","Shade lens release",e); }
        Log.i("ChaojiBeauty","Shade lens released, source changes="+frames);
    }

    // Screen-space input throughout. Only the SDF moves; the foreground and wallpaper stay registered.
    private static final String PROGRAM =
        "uniform shader scene, wallpaper; uniform float2 size; uniform float3 shape, sampleMap;" +
        "uniform float progress,reveal,ready,paperMultiply,notificationVisibility,exposeHome,slidingBackdrop,paperScale,dimEnd,returnExtent; uniform float4 panelBounds;" +
        "float revealAt(float2 p){if(slidingBackdrop<.5 || reveal==0. || reveal==1.)return reveal;" +
        "return clamp(size.y*reveal-p.y+.5,0.,1.);}" +
        "half4 paperAt(float2 p){float cover=revealAt(p);if(cover==0.)return half4(0);if(paperScale==1.)return wallpaper.eval(p)*cover;" +
        "float2 q=(p-size*.5)/paperScale+size*.5;" +
        "if(q.x<0.||q.y<0.||q.x>=size.x||q.y>=size.y)return half4(0);" +
        "return wallpaper.eval(q)*cover;}" +
        "half4 sourceAt(float2 p){if(exposeHome>.5 && ready>.5)return scene.eval((p+sampleMap.xy)*sampleMap.z);half4 w=paperAt(p);" +
        "return w+scene.eval((p+sampleMap.xy)*sampleMap.z)*(ready*(1.-w.a));}" +
        // A new wallpaper buffer may still be translucent at the panel endpoint.
        // Composite it over the same scrim on both sides of the last moving frame.
        "float tone(float2 p){float lower=smoothstep(0.,max(1.,size.y*.5),p.y);" +
        // The reference poster gains about 1.62x in its lower unobstructed area.
        "return mix(paperMultiply,min(1.,1.62*paperMultiply),lower*(1.-notificationVisibility));}" +
        "half4 appPoster(half4 w,float cover,float m){return half4(w.rgb*m,w.a+(cover-w.a)*(1.-m));}" +
        "half4 main(float2 p){float m=tone(p);" +
        "if(reveal==1. && progress==1. && paperScale==1.){half4 w=wallpaper.eval(p);return exposeHome>.5?half4(w.rgb*m,w.a):appPoster(w,1.,m);}" +
        "float r=shape.x*panelBounds.w,band=shape.y*panelBounds.w,dp=shape.z;float bottom=panelBounds.z;" +
        "float pullDim=slidingBackdrop*.32*smoothstep(0.,dimEnd,progress);" +
        "half4 outside=half4(0,0,0,pullDim);" +
        "if(returnExtent>0. && reveal>0.){half4 poster=appPoster(paperAt(p),revealAt(p),m)*clamp(size.y*returnExtent-p.y+.5,0.,1.);" +
        "outside=poster+outside*(1.-poster.a);}" +
        "if(p.y>=bottom+.5)return outside;" +
        "float cx=(panelBounds.x+panelBounds.y)*.5;" +
        "float2 axis=float2(abs(p.x-cx)-(panelBounds.y-panelBounds.x)*.5+r,p.y-bottom+r);" +
        "float2 v=max(axis,0.);float d=length(v)+min(max(axis.x,axis.y),0.)-r;" +
        "if(d>=.5)return outside;" +
        "half4 w=paperAt(p);" +
        "float active=smoothstep(0.,8.*dp,size.y-bottom);" +
        "float cover=revealAt(p);float dim=mix(.32,1.-m,cover);" +
        "half4 under=half4(w.rgb*(1.-dim),w.a+(1.-w.a)*dim);" +
        "float t=clamp(1.+d/band,0.,1.);" +
        "float shade=.28*(1.-cover);" +
        "half4 face=half4(under.rgb*(1.-shade),under.a+(1.-under.a)*shade);" +
        "if(d<=-band)return face;" +
        "float2 n=length(v)>.001?v/length(v):(axis.x>axis.y?float2(1,0):float2(0,1));n.x*=sign(p.x-cx);" +
        "float2 q=p-n*band*.62*(1.-sqrt(max(0.,1.-t*t)));float2 split=n*dp*.65*t*t*t;" +
        "half4 sampleG=sourceAt(q);half4 optical=half4(sourceAt(q+split).r,sampleG.g,sourceAt(q-split).b,sampleG.a);" +
        "half4 reflected=sourceAt(p+n*band*.35); optical=mix(optical,reflected,.16*t*t*t);" +
        "float weight=smoothstep(0.,.72,t)*active;" +
        "face.rgb=mix(face.rgb,optical.rgb*(1.-dim)*(1.-shade)+face.rgb*(1.-optical.a),weight);" +
        "face.a+=weight*optical.a*(1.-face.a);" +
        "return mix(face,outside,smoothstep(-.5,.5,d));}";
}
