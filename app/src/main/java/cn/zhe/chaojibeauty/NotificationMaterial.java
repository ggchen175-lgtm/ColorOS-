package cn.zhe.chaojibeauty;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;

/** Draws a notification face from the shade backdrop in one GPU pass. */
final class NotificationMaterial {
    enum Palette {
        DARK(0x80506174,0x6b405063,0x48acbdd1,0xfff4f7fb,0xffd5deea,0xfff5f8fc,0x74000000),
        LIGHT(0x6b8399b3,0x597286a2,0x55e5f1ff,0xfffbfdff,0xffe1e9f3,0xfffafcff,0x55000000),
        BRIGHT(0x5bc7d3e0,0x55adbfd2,0x7bffffff,0xff192b3d,0xff293e52,0xfffafcff,0x8d000000),
        BRIGHT_DARK(0x9c36495f,0x88303f53,0x66d7e5f5,0xfffafcff,0xffe3eaf5,0xfffafcff,0x96000000);

        final int face, rear, rim, ink, secondaryInk, panelInk, dim;

        int edgeContrast(){
            return android.graphics.Color.luminance(ink)>.45f?0xaa142235:0x9af5faff;
        }

        Palette(int face,int rear,int rim,int ink,int secondaryInk,int panelInk,int dim){
            this.face=face;
            this.rear=rear;
            this.rim=rim;
            this.ink=ink;
            this.secondaryInk=secondaryInk;
            this.panelInk=panelInk;
            this.dim=dim;
        }
    }

    static Palette palette(boolean dark,boolean brightWallpaper){
        if(brightWallpaper)return dark?Palette.BRIGHT_DARK:Palette.BRIGHT;
        return dark?Palette.DARK:Palette.LIGHT;
    }

    private final RuntimeShader shader = new RuntimeShader(OPTICS);

    RenderEffect resize(int width, int height, float density, int layer) {
        return resize(width,height,density,layer,false,false);
    }

    RenderEffect resize(int width, int height, float density, int layer, boolean liveWindow, boolean dark) {
        return resize(width,height,density,layer,liveWindow,dark,false,false,false,false);
    }

    RenderEffect resize(int width,int height,float density,int layer,boolean liveWindow,boolean dark,
            boolean button,boolean selected,boolean keyguard,boolean frosted) {
        float band=Math.min((button?14f:10f)*density,Math.min(width,height)*.275f);
        shader.setFloatUniform("frosted",frosted?1f:0f);
        shader.setFloatUniform("selectedGlass",selected?1f:0f);
        float depth = 1f / (1f + 2f * layer);
        shader.setFloatUniform("cardSize",width,height);
        shader.setFloatUniform("edgeOptics",band,(button?.82f:.36f)*band,density,depth);
        shader.setFloatUniform("darkAppearance",dark ? 1f : 0f);
        shader.setFloatUniform("liveWindow",liveWindow ? 1f : 0f);
        shader.setFloatUniform("keyguardGlass",keyguard?1f:0f);
        return RenderEffect.createRuntimeShaderEffect(shader,"inputs");
    }

    // The original drawable supplies final coverage on the destination pixel grid.
    // The shader's mask texture only locates the bevel and never clips the result.
    static void composite(android.graphics.Canvas canvas, android.graphics.RenderNode mask,
            android.graphics.RenderNode fill, int width, int height) {
        int saved=canvas.saveLayer(0,0,width,height,null);
        try {
            canvas.drawRenderNode(mask);
            canvas.drawRenderNode(fill);
        } finally { canvas.restoreToCount(saved); }
    }

    static void useSourceIn(android.graphics.RenderNode fill) {
        android.graphics.Paint paint=new android.graphics.Paint();
        paint.setBlendMode(android.graphics.BlendMode.SRC_IN);
        fill.setUseCompositingLayer(true,paint);
    }

    private static final String OPTICS =
            "uniform shader inputs; uniform float2 cardSize; uniform float4 edgeOptics; uniform float liveWindow,darkAppearance,selectedGlass,keyguardGlass,frosted;" +
            "float mask(float2 p) {" +
            "  if(any(lessThan(p,float2(0.))) || any(greaterThanEqual(p,cardSize)))return 0.;" +
            "  return inputs.eval(float2(cardSize.x,0.)+p).a;" +
            "}" +
            // Locate both sides of a short chord on the actual drawable's alpha contour.
            "float crossing(float2 p,float2 n,float lo,float hi,float threshold) {" +
            "  for(int i=0;i<6;i++){float m=.5*(lo+hi);" +
            "    if(mask(p+n*m)>threshold)lo=m;else hi=m;}" +
            "  return .5*(lo+hi);" +
            "}" +
            "half3 scene(float2 p){return inputs.eval(clamp(p,float2(.5),cardSize-.5)).rgb;}" +
            "half4 main(float2 position) {" +
            "  if(position.x>=cardSize.x)return half4(0);" +
            "  float reach=edgeOptics.x+1.; float2 dx=float2(reach,0.),dy=dx.yx;" +
            "  float2 g=float2(mask(position-dx)-mask(position+dx),mask(position-dy)-mask(position+dy));" +
            "  float magnitude=length(g); float2 n=float2(0.); float d=-reach;" +
            "  if(magnitude>.02) {" +
            "    n=g/magnitude;" +
            "    float threshold=.5*max(mask(position),mask(position-n*2.));" +
            "    d=-crossing(position,n,0.,reach*1.42,threshold);" +
            "    if(-d<edgeOptics.z*5.){" +
            "      float2 t=float2(-n.y,n.x); float r=max(2.,1.6*edgeOptics.z);" +
            "      float2 edge=position-n*d;" +
            "      float a=crossing(edge-t*r,n,-2.*r,2.*r,threshold);" +
            "      float b=crossing(edge+t*r,n,-2.*r,2.*r,threshold);" +
            "      float2 normal=normalize(n-t*((b-a)/ (2.*r)));" +
            "      d*=dot(n,normal); n=normal;" +
            "    }" +
            "  }" +
            "  float bevel=smoothstep(-edgeOptics.x,0.,d);" +
            // A rear edge is another glass surface in front of the same window.
            // Preserve the wallpaper profile when the window is no longer visible.
            "  float depth=edgeOptics.w,windowWeight=0.;" +
            "  if(liveWindow>.5 && bevel>0.){" +
            "    half4 w=inputs.eval(float2(2.*cardSize.x,0.)+clamp(position,float2(.5),cardSize-.5));" +
            "    windowWeight=w.a*smoothstep(0.,.35,1.-inputs.eval(position).a);" +
            "    depth=mix(depth,.82+.18*depth,windowWeight);}" +
            "  float2 sampleAt=clamp(position-n*edgeOptics.y*bevel*bevel*depth," +
            "                        float2(.5),cardSize-.5);" +
            "  half4 sampleColor=inputs.eval(sampleAt);" +
            "  half3 color=mix(sampleColor.rgb,sampleColor.rgb/max(.001,sampleColor.a),liveWindow);" +
            "  float luma=clamp(dot(color,half3(.2126,.7152,.0722)),0.,1.);" +
            "  float inward=max(-d,0.)/edgeOptics.z;" +
            // 24A437 platformContentGlass colorMatrix, rows in RGB order.
            "  if(frosted>.5){" +
            "    half3 rgb=sampleColor.rgb/max(.001,sampleColor.a);" +
            "    color=clamp(half3(dot(rgb,half3(.921,-.265,-.027))," +
            "                      dot(rgb,half3(-.079,.735,-.027))," +
            "                      dot(rgb,half3(-.079,-.265,.973)))+.235,0.,1.);" +
            "  }else{" +
            "    float tone=darkAppearance*smoothstep(.16,.72,luma);" +
            "    color=mix(color,luma+(color-luma)*(1.4/2.4),tone);}" +
            "  half3 base=color;" +
            "  float glint=0., rimShadow=0.;" +
            "  if(frosted<.5)color=mix(color,half3(.69,.75,.85),mix(.18,.045,luma));" +
            "  if(inward<4. && magnitude>.02){" +
            // Convex shoulder between the vertical rim and the flat face.
            "    float tilt=1.-smoothstep(0.,2.1,inward);" +
            "    float3 normal=float3(n*tilt,sqrt(max(0.,1.-tilt*tilt)));" +
            "    float key=max(dot(normal,normalize(float3(-.13,-.72,.68))),0.);" +
            "    float fill=max(dot(normal,normalize(float3(.08,.72,.68))),0.);" +
            "    float spec=pow(max(key,fill),6.)*smoothstep(0.,.46,tilt);" +
            "    float shadow=(1.-smoothstep(.18,1.1,inward))*(1.-.6*spec);" +
            "    glint=spec; rimShadow=shadow;" +
            "    color=mix(color,base*.58,shadow*.90);" +
            // Incident scene color is sampled separately from the refracted pixel.
            "    half3 incident=scene(position-n*(9.*edgeOptics.z));" +
            "    half3 radiance=half3(.31)+incident*.65;" +
            "    color+=(half3(1.)-color)*radiance*spec*(1.+.36*(1.-edgeOptics.w));" +
            "  }" +
            // The compositor supplies the transmitted light when the scene is in another window.
            // Only reflectance, tint and edge shadow belong in our premultiplied output.
            // Keyguard already composites the wallpaper. The pass contributes only
            // a small fraction of the displaced sample and the glass rim.
            "  if(frosted>.5){" +
            "    half3 tint=mix(half3(.68,.74,.82),half3(.42,.48,.56),darkAppearance);" +
            "    float veil=.05+.07*smoothstep(.34,.88,luma);" +
            "    half3 body=mix(color,tint,veil*(1.-.65*bevel));" +
            // The filtered wallpaper supplies transmitted light. Limit the contribution
            // of the sharp original underneath the card, even on bright poster details.
            "    float coverage=mix(.86,.94,bevel)*sampleColor.a;" +
            "    float substrate=veil+.04*rimShadow;" +
            "    float rim=.36*glint;" +
            "    half4 fallback=half4(mix(tint*substrate,half3(1.),rim),mix(substrate,1.,rim));" +
            "    return mix(fallback,half4(body,1.),coverage);}" +
            "  if(keyguardGlass>.5){" +
            "    float refract=(.045+.29*bevel*bevel)*sampleColor.a;" +
            "    float tint=.07+.08*rimShadow; float shine=.36*glint;" +
            "    half3 veil=mix(half3(.66,.72,.81),half3(.47,.52,.62),darkAppearance);" +
            "    half3 ink=(color*refract+veil*tint)*(1.-shine)+half3(shine);" +
            "    return half4(ink,(refract+tint)*(1.-shine)+shine);" +
            "  }" +
            "  float reflection=.46*glint; float absorption=.105+.17*rimShadow;" +
            "  half3 reflected=half3(reflection+mix(.50,.47,darkAppearance)*.105);" +
            "  half4 transmitted=half4(reflected, reflection+absorption);" +
            "  half4 incident=inputs.eval(position); float pass=1.-transmitted.a;" +
            // SystemUI already composites the scrim over the live desktop. Add only the
            // difference produced by the glass. Drawing the scrim again crushes icon colors.
            "  half3 surface=mix(transmitted.rgb,color,sampleColor.a)-pass*incident.rgb;" +
            "  half4 result=half4(color,1.);" +
            "  if(liveWindow>.5 && incident.a<.999)result=half4(clamp(surface,0.,transmitted.a),transmitted.a);" +
            "  if(liveWindow>.5 && bevel>0.){" +
            "    half4 window=inputs.eval(float2(2.*cardSize.x,0.)+sampleAt);" +
            "    half3 refracted=sampleColor.rgb+window.rgb*(1.-sampleColor.a);" +
            "    half3 edge=refracted*(1.-transmitted.a)+transmitted.rgb;" +
            "    result=mix(result,half4(edge,1.),windowWeight*smoothstep(0.,.7,bevel));}" +
            // Selected quick actions brighten transmitted detail without painting an opaque face.
            "  result.rgb+=selectedGlass*.23*(result.a-result.rgb);" +
            "  return result;" +
            "}";
}
