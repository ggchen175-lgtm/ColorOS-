package cn.zhe.chaojibeauty;

import android.graphics.*;

/** Only the native silhouette is retained. All transmitted color comes from the live GPU input. */
final class ContainerOptics {
    private final RuntimeShader shader;
    private final BitmapShader shape;
    private final float band, density;
    private final int width,height;
    ContainerOptics(Bitmap stamp, float density) {
        this.density=density;
        width=stamp.getWidth()-2;height=stamp.getHeight()-2;
        band=Math.min(18f*density,(stamp.getHeight()-2)*.34f);
        Bitmap distances=DockContour.distanceMap(stamp,band);
        if(distances==null) throw new IllegalArgumentException("Empty native material");
        shape=new BitmapShader(distances,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        shape.setFilterMode(BitmapShader.FILTER_MODE_NEAREST);
        shader=new RuntimeShader(PROGRAM);
        configure(shader);
        shader.setFloatUniform("size",width,height);
    }
    private void configure(RuntimeShader shader) {
        shader.setInputBuffer("shape",shape);
        shader.setFloatUniform("optics",band,.86f*band,1.3f*density);
    }
    RenderEffect effect(boolean dark) { return effect(dark,false); }
    RenderEffect effect(boolean dark,boolean highlightCurves) {
        shader.setFloatUniform("curveLighting",highlightCurves ? 1f : 0f);
        shader.setFloatUniform("darkAppearance",dark ? 1f : 0f);
        return RenderEffect.createRuntimeShaderEffect(shader,"scene");
    }
    // Draw the material edge in the owner's local display list. HWUI can crop a
    // backdrop filter to the visible surface when a page crosses the window edge.
    Paint surfacePaint() {
        RuntimeShader surface=new RuntimeShader(SHAPE+
                "half4 main(float2 p){float a=shape.eval(floor(p)+1.5).g;"+
                "float d=depth(p);float2 n=normal(p);float rim=1.-smoothstep(0.,optics.z,d);"+
                "float shine=.025+rim*(.12+.65*pow(abs(dot(n,float2(-.6,-.8))),2.2));"+
                "return half4(half3(.98,.99,1.)*shine,shine)*a;}");
        configure(surface);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(surface);
        return paint;
    }
    private RuntimeShader transmission;
    RenderEffect transmission(boolean dark,int left,int top) {
        if (transmission==null) {
            transmission=new RuntimeShader(GlassAppearance.DOCK_TONE+SHAPE+
                "uniform shader scene;uniform float2 crop;uniform float2 size;"+
                "half4 main(float2 p){float2 q=p+crop;float t=clamp(1.-depth(q)/optics.x,0.,1.);"+
                "float shift=optics.y*(1.-sqrt(max(0.,1.-t*t)));"+
                "half4 c=scene.eval(clamp(q-normal(q)*shift,float2(.5),size-.5)-crop);"+
                "return half4(dockTone(c.rgb),c.a);}");
            configure(transmission);
            transmission.setFloatUniform("size",width,height);
        }
        RuntimeShader glass=transmission;
        glass.setFloatUniform("darkAppearance",dark?1f:0f);
        glass.setFloatUniform("crop",left,top);
        return RenderEffect.createRuntimeShaderEffect(glass,"scene");
    }
    private static final String SHAPE="uniform shader shape;uniform float3 optics;"+
        "float depth(float2 p){return shape.eval(p+1.).r*optics.x;}"+
        "float2 normal(float2 p){float2 n=float2(depth(p-float2(1,0))-depth(p+float2(1,0)),"+
        "depth(p-float2(0,1))-depth(p+float2(0,1)));return n/max(.00001,length(n));}";
    private static final String PROGRAM=
        GlassAppearance.DOCK_TONE + "uniform float curveLighting; uniform shader scene; uniform shader shape; uniform float3 optics; uniform float2 size;"+
        "float depth(float2 p){return shape.eval(p+1.).r*optics.x;}"+
        "half4 main(float2 p){float a=shape.eval(floor(p)+1.5).g;if(a<=0.)return half4(0.);"+
        "float d=depth(p);float2 n=float2(depth(p-float2(1,0))-depth(p+float2(1,0)),"+
        "depth(p-float2(0,1))-depth(p+float2(0,1)));n/=max(.00001,length(n));"+
        "float t=clamp(1.-d/optics.x,0.,1.);float shift=optics.y*(1.-sqrt(max(0.,1.-t*t)));"+
        "half4 sampleColor=scene.eval(clamp(p-n*shift,float2(.5),size-.5));"+
        "float light=pow(abs(dot(n,float2(-.6,-.8))),2.2);"+
        "float rim=1.-smoothstep(0.,optics.z,d);half shine=.025+rim*(.12+.65*light);"+
        "float curve=curveLighting*abs(2.*n.x*n.y);"+
        "shine+=curve*(.32*(1.-smoothstep(0.,optics.z*1.8,d))+.055*t*t);"+
        "half3 tint=half3(.98,.99,1.);return half4(mix(dockTone(sampleColor.rgb),tint,shine),"+
        "mix(sampleColor.a,1.,shine))*a;}";
}
