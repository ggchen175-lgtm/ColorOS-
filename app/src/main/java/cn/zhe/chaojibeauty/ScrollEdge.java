package cn.zhe.chaojibeauty;

import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;

/** Filters only the strip of live scroll content passing under the status bar. */
final class ScrollEdge {
    private final RenderNode source = new RenderNode("Scroll content");
    private final RenderNode strip = new RenderNode("Status bar scroll edge");
    private int height, lastEdge, lastOrigin;
    private float spread;

    // Two separable passes. Work in premultiplied color so transparent glyph edges
    // fade without acquiring a black fringe.
    private static RuntimeShader gaussian(boolean vertical) {
        String axis = vertical ? "float2(0.,1.)" : "float2(1.,0.)";
        return new RuntimeShader("uniform shader content; uniform float height; uniform float spread; uniform float origin;"
                + "half4 main(float2 p){"
                + "float visible=smoothstep(0.,height,p.y+origin);"
                + "float sigma=max(.18,min(6.,spread*.85)*(1.-visible));"
                + "float ratio=exp(-.5/(sigma*sigma)), decay=ratio*ratio;"
                + "float weight=1.,total=1.; float4 c=content.eval(p);"
                // Sample consecutive pixels. Widening a sparse kernel makes thin
                // strokes reappear at each tap, especially against a dark background.
                + "for(int i=1;i<=18;++i){"
                + " weight*=ratio; if(weight<.003) break;"
                + " float2 d=float(i)*"+axis+";"
                + " c+=weight*(content.eval(p+d)+content.eval(p-d));"
                + " total+=2.*weight; ratio*=decay;"
                + "} c/=total;"
                + (vertical ? "return c*mix(.18,1.,visible);}" : "return c;}"));
    }

    Canvas begin(int width,int fullHeight,int edgeHeight,int origin,float sampleSpread) {
        int end=Math.min(fullHeight,edgeHeight-origin);
        if (lastEdge!=edgeHeight || lastOrigin!=origin || spread!=sampleSpread) {
            RuntimeShader horizontal=gaussian(false), vertical=gaussian(true);
            for (RuntimeShader shader : new RuntimeShader[]{horizontal,vertical}) {
                shader.setFloatUniform("height",(float)edgeHeight);
                shader.setFloatUniform("origin",(float)origin);
                shader.setFloatUniform("spread",sampleSpread);
            }
            strip.setRenderEffect(RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(vertical,"content"),
                    RenderEffect.createRuntimeShaderEffect(horizontal,"content")));
            lastEdge=edgeHeight;
            lastOrigin=origin;
            spread=sampleSpread;
        }
        height=end;
        source.setPosition(0,0,width,fullHeight);
        strip.setPosition(0,0,width,Math.min(fullHeight,end+2));
        return source.beginRecording();
    }

    void end(Canvas output) {
        source.endRecording();
        int saved=output.save();
        output.clipRect(0,height,source.getWidth(),source.getHeight());
        output.drawRenderNode(source);
        output.restoreToCount(saved);
        RecordingCanvas top=strip.beginRecording();
        top.drawRenderNode(source);
        strip.endRecording();
        saved=output.save();
        output.clipRect(0,0,source.getWidth(),height);
        output.drawRenderNode(strip);
        output.restoreToCount(saved);
    }

    void release() {
        source.discardDisplayList();
        strip.discardDisplayList();
    }
}
