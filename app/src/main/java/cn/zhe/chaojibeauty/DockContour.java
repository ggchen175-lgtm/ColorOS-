package cn.zhe.chaojibeauty;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.HardwareRenderer;
import android.graphics.RenderNode;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;

/** Red stores inward distance in units of the lens band. Green stores native edge coverage. */
final class DockContour {
    static RenderNode record(GradientDrawable face) {
        Rect b = face.getBounds();
        RenderNode node = new RenderNode("Dock fill contour");
        node.setPosition(0,0,b.width()+2,b.height()+2);
        Canvas canvas = node.beginRecording();
        try {
            canvas.translate(1-b.left, 1-b.top);
            face.draw(canvas);
        } finally { node.endRecording(); }
        return node;
    }

    static Bitmap readContour(RenderNode node, float band) throws ReflectiveOperationException {
        try {
            Bitmap hardware = (Bitmap)HardwareRenderer.class
                    .getMethod("createHardwareBitmap", RenderNode.class, int.class, int.class)
                    .invoke(null, node, node.getWidth(), node.getHeight());
            if (hardware == null) return null;
            try { return distanceMap(hardware.copy(Bitmap.Config.ARGB_8888, true), band); }
            finally { hardware.recycle(); }
        } finally { node.discardDisplayList(); }
    }

    static Bitmap distanceMap(Bitmap stamp, float band) {
        int w = stamp.getWidth(), h = stamp.getHeight();
        int[] pixels = new int[w*h];
        stamp.getPixels(pixels,0,w,0,0,w,h);
        // A solid native fill can itself be translucent. Preserve only its geometric coverage.
        int body = pixels[(h/2)*w+w/2] >>> 24;
        if (body == 0) return null;
        float[] depth = new float[pixels.length];
        for (int i=0; i<pixels.length; i++) {
            int coverage = Math.min(255, ((pixels[i] >>> 24)*255 + body/2)/body);
            pixels[i] = coverage;
            depth[i] = coverage == 255 ? band+1 : coverage / 255f;
        }
        // The one-pixel transparent border supplies an outside sample even along flat sides.
        float diagonal = (float)Math.sqrt(2);
        for (int y=1; y<h-1; y++) for (int x=1; x<w-1; x++) {
            int i=y*w+x;
            float straight=Math.min(depth[i-1],depth[i-w])+1f;
            float slant=Math.min(depth[i-w-1],depth[i-w+1])+diagonal;
            depth[i]=Math.min(depth[i],Math.min(straight,slant));
        }
        for (int y=h-2; y>0; y--) for (int x=w-2; x>0; x--) {
            int i=y*w+x;
            float straight=Math.min(depth[i+1],depth[i+w])+1f;
            float slant=Math.min(depth[i+w-1],depth[i+w+1])+diagonal;
            depth[i]=Math.min(depth[i],Math.min(straight,slant));
        }
        for (int i=0; i<pixels.length; i++) {
            int d = Math.round(255*Math.min(1f,Math.max(0f,depth[i]-.5f)/band));
            pixels[i] = 0xff000000 | (d << 16) | (pixels[i] << 8);
        }
        stamp.setPixels(pixels,0,w,0,0,w,h);
        return stamp;
    }
}
