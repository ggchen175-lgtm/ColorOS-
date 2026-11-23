package cn.zhe.chaojibeauty;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.util.TypedValue;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Bottom slot shared by the stack's resting layout and its accessible expand control. */
final class LockStackSummary {
    private final NotificationGlass glass;
    private final NotificationHistory history;
    private final Rect hit=new Rect();
    private final int[] hostOrigin=new int[2], listOrigin=new int[2];
    private final RectF layerBounds=new RectF();
    private final Rect exposed=new Rect();
    private final Paint glassPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private static final Typeface PREVIEW_TYPE=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    private View second;
    private CharSequence previewTitle="", previewTime="", previewApp="";
    private Drawable previewIcon;
    private String overflow="";
    private int previewHeight, overflowHeight;
    View front;
    private boolean historicalPreview, thinTail;
    private int remaining, signature;
    private long revision=-1;
    Runnable open, restore;
    private TextView count;
    private int allCount;


    boolean hasCount(){return allCount>0;}
    boolean showsHistory(){return historicalPreview;}
    boolean countHit(float x,float y){return count!=null && count.isShown() && count.isClickable()
            && count.getGlobalVisibleRect(hit) && hit.contains((int)x,(int)y);}
    void clickCount(){if(count!=null && count.isClickable())count.performClick();}

    private void layoutCount(ViewGroup list){
        View left=history.tuneButton(0),right=history.tuneButton(1);
        View anchor=left!=null?left:right;
        FrameLayout parent=anchor!=null && anchor.getParent() instanceof FrameLayout?(FrameLayout)anchor.getParent():null;
        if(parent==null && list.getRootView() instanceof FrameLayout)parent=(FrameLayout)list.getRootView();
        if(count!=null && count.getParent()!=parent)removeCount();
        if(parent==null || allCount==0){
            if(count!=null)count.setVisibility(View.INVISIBLE);
            return;
        }
        if(count==null){
            count=NotificationDisplay.label(list.getContext());
            count.setPadding(NotificationViewport.dp(count,10),0,NotificationViewport.dp(count,10),0);
            count.setSingleLine();
            count.setIncludeFontPadding(false);
            count.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,14);
            count.setShadowLayer(NotificationViewport.pixels(list,2),0,NotificationViewport.pixels(list,1),0xa0000000);
            count.setVisibility(View.INVISIBLE);
            count.setOnClickListener(v->{if(restore!=null)restore.run();});
            parent.addView(count,new FrameLayout.LayoutParams(-2,NotificationViewport.dp(count,44),
                    Gravity.CENTER_HORIZONTAL|Gravity.BOTTOM));
        }
        NotificationDisplay.count(count,allCount);
        int cy=history.shortcutCenterInWindow();
        int height=NotificationViewport.dp(list,48);
        int parentTop=NotificationViewport.layoutY(parent,hostOrigin);
        if(cy<0)cy=NotificationViewport.navigationTop(list,listOrigin)-height/2;
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)count.getLayoutParams();
        int margin=parentTop+parent.getHeight()-parent.getPaddingBottom()-cy-height/2;
        if(anchor!=null && anchor.getParent()==parent && anchor.getHeight()>0){
            int center=anchor.getTop()+anchor.getHeight()/2;
            if(left!=null && right!=null && left.getParent()==parent && right.getParent()==parent)
                center=(left.getTop()+left.getHeight()/2+right.getTop()+right.getHeight()/2)/2;
            margin=parent.getHeight()-parent.getPaddingBottom()-center-height/2;
        }
        int offsetX=0;
        if(left!=null && right!=null && left.getParent()==parent && right.getParent()==parent){
            int midpoint=(left.getLeft()+left.getWidth()/2+right.getLeft()+right.getWidth()/2)/2;
            offsetX=midpoint-(parent.getWidth()+parent.getPaddingLeft()-parent.getPaddingRight())/2;
        }
        int width=Math.min(NotificationViewport.dp(list,168),parent.getWidth()-parent.getPaddingLeft()-parent.getPaddingRight());
        if(left!=null && right!=null && left.getParent()==parent && right.getParent()==parent){
            View start=left.getLeft()<right.getLeft()?left:right;
            View end=start==left?right:left;
            width=Math.min(width,Math.max(0,end.getLeft()-start.getRight()-2*NotificationViewport.dp(list,8)));
        }
        if(width<=0)width=ViewGroup.LayoutParams.WRAP_CONTENT;
        if(lp.bottomMargin!=margin || lp.leftMargin!=offsetX || lp.height!=height || lp.width!=width){
            lp.bottomMargin=margin;lp.leftMargin=offsetX;lp.height=height;lp.width=width;count.setLayoutParams(lp);
        }
    }
    private void removeCount(){
        if(count!=null && count.getParent() instanceof ViewGroup)((ViewGroup)count.getParent()).removeView(count);
        count=null;
    }

    LockStackSummary(NotificationGlass glass,NotificationHistory history){this.glass=glass;this.history=history;}

    int height(){return previewHeight+overflowHeight;}

    boolean hit(View list,float x,float y){
        if(exposed.isEmpty())return false;
        list.getLocationOnScreen(listOrigin);
        return exposed.contains((int)x-listOrigin[0],(int)y-listOrigin[1]);
    }

    void click(){if(open!=null)open.run();}

    void measure(ViewGroup list,int available){
        historicalPreview=false;
        allCount=history.ordinaryCount();
        View first=null, archive=null;
        for(int i=0;i<list.getChildCount();i++){
            View row=list.getChildAt(i);
            if(row.getVisibility()==View.GONE || !history.ordinaryRow(row))continue;
            if(first==null && eligible(row))first=row;
            if(archive==null && history.historicalRow(row))archive=row;
        }
        if(first==null && history.onlyHistory(list)){
            first=archive;
            historicalPreview=first!=null;
            available=Math.min(available,NotificationViewport.dp(list,142));
        }
        front=first;
        thinTail=front!=null && (historicalPreview?(allCount==2 || history.totalCount(front)==2):history.incomingCount()==2);
        second=history.groupPreview(front);
        remaining=second==null?0:Math.max(0,history.totalCount(front)-2);
        int hash=31*System.identityHashCode(front)+System.identityHashCode(second);
        if(front!=null)
            available=Math.max(0,available-(int)de.robv.android.xposed.XposedHelpers.callMethod(front,"getIntrinsicHeight"));
        previewHeight=thinTail?Math.min(Math.round(NotificationDeck.tail(list,1)),available):second==null?0:Math.min(NotificationViewport.dp(list,32),
                Math.max(0,available-NotificationViewport.dp(list,remaining>0?24:0)));
        overflowHeight=thinTail || remaining==0?0:Math.min(NotificationViewport.dp(list,26),available-previewHeight);
        if(signature==hash && revision==history.stackRevision())return;
        signature=hash;
        revision=history.stackRevision();
        previewApp=second==null?"":history.stackSource(second);
        previewTitle=second==null?"":history.stackPreviewTitle(second);
        previewTime=second==null?"":history.stackPreviewTime(second);
        previewIcon=second==null?null:history.stackPreviewIcon(second);
        if(remaining>0)overflow="+"+remaining+"个，来自"+history.stackSource(front);
    }

    private boolean eligible(View row){
        return history.ordinaryRow(row) && !history.historicalRow(row) && history.stackCount(row)>0;
    }

    void draw(ViewGroup list,Canvas canvas,float unfold,float sink,int edge,boolean settled){
        exposed.setEmpty();
        if(front==null || previewHeight==0 || front.getVisibility()!=View.VISIBLE || unfold>=.24f)return;
        float bottom=front.getY()-list.getScrollY()+(int)de.robv.android.xposed.XposedHelpers.callMethod(front,"getActualHeight");
        if(bottom>=edge)return;
        float step=NotificationDeck.rearInset(front,1);
        float left=front.getX()-list.getScrollX()+step;
        NotificationMaterial.Palette palette=glass.palette(list);
        float right=left+front.getWidth()-2*step;
        float secondProgress=Math.min(1,unfold/.24f);
        float thirdProgress=Math.min(1,unfold/.20f);
        float secondEnd=bottom+previewHeight*(1-.13f*secondProgress);
        drawLayer(list,canvas,left,right,bottom,secondEnd,1-secondProgress,false,palette);
        if(overflowHeight>0){
            float thirdEnd=secondEnd+overflowHeight*(1-.18f*thirdProgress);
            drawLayer(list,canvas,left+step,right-step,secondEnd,thirdEnd,1-thirdProgress,true,palette);
        }
        if(settled && sink==0){
            exposed.set(Math.round(left),Math.round(bottom),Math.round(right),
                    Math.min(edge,Math.round(secondEnd+overflowHeight)));
        }
    }

    private void drawLayer(View list,Canvas canvas,float left,float right,float top,float bottom,float opacity,
            boolean summary,NotificationMaterial.Palette palette){
        if(opacity<=0 || right<=left || bottom<=top)return;
        int layer=summary?2:1;
        float radius=((Number)de.robv.android.xposed.XposedHelpers.callMethod(front,"getBottomCornerRadius")).floatValue();
        float height=((Number)de.robv.android.xposed.XposedHelpers.callMethod(front,"getActualHeight")).floatValue();
        int save=canvas.saveLayerAlpha(left,top,right,bottom,Math.round(255*opacity));
        canvas.clipRect(left,top,right,bottom);
        layerBounds.set(left,bottom-height,right,bottom);
        int material=canvas.saveLayerAlpha(left,top,right,bottom,Math.round(255*NotificationDeck.rearOpacity(layer)));
        glass.drawStackLayer(list,canvas,layerBounds,radius,glassPaint,layer);
        canvas.restoreToCount(material);
        if(thinTail){
            canvas.restoreToCount(save);
            return;
        }
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,summary?12:13,
                list.getResources().getDisplayMetrics()));
        textPaint.setTypeface(summary?Typeface.DEFAULT:PREVIEW_TYPE);
        textPaint.setShadowLayer(NotificationViewport.pixels(list,1.4f),0,
                NotificationViewport.pixels(list,.5f),palette.edgeContrast());
        float center=(top+bottom)/2;
        float baseline=center-(textPaint.ascent()+textPaint.descent())/2;
        float padding=NotificationViewport.pixels(list,12);
        if(summary){
            textPaint.setColor(palette.secondaryInk);
            textPaint.setTextAlign(Paint.Align.CENTER);
            CharSequence shortLabel=TextUtils.ellipsize(overflow,textPaint,Math.max(0,right-left-padding*2),TextUtils.TruncateAt.END);
            canvas.drawText(shortLabel,0,shortLabel.length(),(left+right)/2,baseline,textPaint);
        }else{
            float titleLeft=left+padding;
            if(previewIcon!=null){
                int iconSize=NotificationViewport.dp(list,17);
                int x=Math.round(titleLeft),y=Math.round(center-iconSize/2f);
                previewIcon.setBounds(x,y,x+iconSize,y+iconSize);
                previewIcon.draw(canvas);
                titleLeft+=iconSize+NotificationViewport.pixels(list,8);
            }
            textPaint.setTextAlign(Paint.Align.RIGHT);
            textPaint.setColor(palette.secondaryInk);
            float timeWidth=Math.min(textPaint.measureText(previewTime,0,previewTime.length()),(right-left)/3);
            CharSequence time=TextUtils.ellipsize(previewTime,textPaint,timeWidth,TextUtils.TruncateAt.END);
            canvas.drawText(time,0,time.length(),right-padding,baseline,textPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(palette.ink);
            float titleWidth=Math.max(0,right-padding*2-timeWidth-titleLeft);
            CharSequence preview=history.canShowStackDetails(second)?previewTitle:previewApp;
            CharSequence title=TextUtils.ellipsize(preview,textPaint,titleWidth,TextUtils.TruncateAt.END);
            canvas.drawText(title,0,title.length(),titleLeft,baseline,textPaint);
        }
        canvas.restoreToCount(save);
    }

    private boolean countSuppressed;
    void suppressCount(boolean suppress){
        countSuppressed=suppress;
        if(suppress && count!=null){
            count.setVisibility(View.INVISIBLE);
            count.setClickable(false);
            count.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }
    void present(ViewGroup list,float progress){
        if(countSuppressed)return;
        if(list!=null && (progress>0 || count!=null))layoutCount(list);
        if(count!=null){
            float alpha=Math.max(0,Math.min(1,(progress-.65f)/.35f));
            count.setVisibility(allCount>0 && alpha>0?View.VISIBLE:View.INVISIBLE);
            count.setAlpha(alpha);count.setClickable(progress>=1);
            count.setImportantForAccessibility(progress>=1?View.IMPORTANT_FOR_ACCESSIBILITY_YES:View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }

    void detach(){
        removeCount();
        exposed.setEmpty();
        allCount=0;
        front=second=null;
        historicalPreview=false;
        previewIcon=null;
        remaining=previewHeight=overflowHeight=signature=0;
        revision=-1;
    }
}
