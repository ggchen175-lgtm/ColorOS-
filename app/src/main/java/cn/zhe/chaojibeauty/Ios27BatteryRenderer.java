// SPDX-License-Identifier: GPL-3.0-only
package cn.zhe.chaojibeauty;


import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

/** Battery outline, percentage and charging glyph drawn in one coordinate space. */
final class Ios27BatteryRenderer {
    static final float WIDTH=31.1666667f, HEIGHT=20f, BODY_WIDTH=28.3333333f, BODY_HEIGHT=15f;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body=new RectF(0,(HEIGHT-BODY_HEIGHT)/2,BODY_WIDTH,(HEIGHT+BODY_HEIGHT)/2);
    private final RectF pathBounds=new RectF();
    private final Path shape=new Path(), placedBolt=new Path(), percentageGlyph=new Path();
    private final RectF glyphInk=new RectF();
    private final Matrix matrix=new Matrix();
    private final PorterDuffXfermode cutout=new PorterDuffXfermode(PorterDuff.Mode.DST_OUT);
    private final Path bolt;
    private final Typeface regular, charging, chargingHundred;
    private int cachedLevel=-1;
    private boolean cachedCharge;
    private String cachedText;
    private float boltX=Float.NaN,boltY,boltWidth,boltHeight;

    Ios27BatteryRenderer() {
        bolt = new Path();
        bolt.moveTo(8, 0);
        bolt.lineTo(0, 11);
        bolt.lineTo(5, 11);
        bolt.lineTo(3, 20);
        bolt.lineTo(12, 8);
        bolt.lineTo(7, 8);
        bolt.close();
        bolt.computeBounds(pathBounds, true);
        regular = Typeface.create("sans-serif", Typeface.BOLD);
        charging = regular;
        chargingHundred = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        shape.addRoundRect(body, 4.1666667f, 4.1666667f, Path.Direction.CW);
    }

    void draw(Canvas canvas, Rect bounds, int level, boolean charge, boolean percent, int tint, int nativeFill, int alpha) {
        float scale=Math.min(bounds.width()/WIDTH,bounds.height()/HEIGHT);
        if(!(scale>0))return;
        int outer=canvas.save();
        try {
            canvas.translate(bounds.exactCenterX()-WIDTH*scale/2,bounds.exactCenterY()-HEIGHT*scale/2);
            canvas.scale(scale,scale);
            int layer=percent&&!charge?canvas.saveLayer(0,0,WIDTH,HEIGHT,null):canvas.save();
            paint.setXfermode(null);paint.setStyle(Paint.Style.FILL);
            int foreground=tint|0xff000000;
            int fill=charge?0xff34c759:((nativeFill&0xffffff)!=(tint&0xffffff)?nativeFill|0xff000000:foreground);
            paint.setColor(foreground);paint.setAlpha(Math.round(alpha*.25f));
            canvas.drawRoundRect(body,4.1666667f,4.1666667f,paint);
            int clipped=canvas.save();
            canvas.clipPath(shape);canvas.clipRect(body.left,body.top,body.width()*Math.max(0,Math.min(100,level))/100f,body.bottom);
            paint.setColor(fill);paint.setAlpha(alpha);canvas.drawRect(body,paint);canvas.restoreToCount(clipped);
            paint.setColor(foreground);paint.setAlpha(Math.round(alpha*.4f));
            canvas.drawRoundRect(BODY_WIDTH+1.1666667f,(HEIGHT-5f)/2,WIDTH,(HEIGHT+5f)/2,.8333333f,.8333333f,paint);
            if(percent) {
                if(cachedLevel!=level||cachedCharge!=charge) {
                    cachedText=Integer.toString(level);
                    paint.setTypeface(charge?(level==100?chargingHundred:charging):regular);
                    paint.setTextSize(charge?13f:13.6666667f);
                    paint.setTextAlign(Paint.Align.LEFT);
                    percentageGlyph.reset();
                    paint.getTextPath(cachedText,0,cachedText.length(),0,0,percentageGlyph);
                    percentageGlyph.computeBounds(glyphInk,true);
                    float boltWidth=charge?4.6666667f:0f,gap=charge?2f:0f;
                    float group=glyphInk.width()+gap+boltWidth;
                    float left=(BODY_WIDTH-group)/2;
                    matrix.reset();matrix.setTranslate(left-glyphInk.left,HEIGHT/2-glyphInk.centerY());
                    percentageGlyph.transform(matrix);
                    if(charge) {
                        placeBolt(left+glyphInk.width()+gap,(HEIGHT-7.3333333f)/2,boltWidth,7.3333333f);
                        percentageGlyph.addPath(placedBolt);
                    }
                    // Optional-font fallback is fitted as ONE composed contour;
                    // digits and bolt are never stretched independently.
                    if(group>BODY_WIDTH-3f) {
                        float fit=(BODY_WIDTH-3f)/group;
                        matrix.reset();matrix.setScale(fit,fit,BODY_WIDTH/2,HEIGHT/2);
                        percentageGlyph.transform(matrix);
                    }
                    cachedLevel=level;cachedCharge=charge;
                }
                paint.setColor(charge?Color.WHITE:Color.BLACK);paint.setAlpha(alpha);
                paint.setXfermode(charge?null:cutout);canvas.drawPath(percentageGlyph,paint);
            } else if(charge) {
                paint.setColor(Color.WHITE);paint.setAlpha(alpha);paint.setXfermode(null);
                drawBolt(canvas,(BODY_WIDTH-6f)/2,5f,6f,10f);
            }
            paint.setXfermode(null);canvas.restoreToCount(layer);
        } finally {paint.setXfermode(null);canvas.restoreToCount(outer);}
    }

    private void drawBolt(Canvas canvas,float x,float y,float width,float height) {
        placeBolt(x,y,width,height);canvas.drawPath(placedBolt,paint);
    }
    private void placeBolt(float x,float y,float width,float height) {
        if(x!=boltX||y!=boltY||width!=boltWidth||height!=boltHeight) {
            float scale=Math.min(width/pathBounds.width(),height/pathBounds.height());
            matrix.reset();matrix.setScale(scale,scale);
            matrix.postTranslate(x+(width-pathBounds.width()*scale)/2-pathBounds.left*scale,
                    y+(height-pathBounds.height()*scale)/2-pathBounds.top*scale);
            bolt.transform(matrix,placedBolt);
            boltX=x;boltY=y;boltWidth=width;boltHeight=height;
        }
    }
}
