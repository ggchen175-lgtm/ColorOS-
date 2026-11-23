package cn.zhe.chaojibeauty;

import android.content.res.Resources;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.Set;
import java.util.Collections;
import de.robv.android.xposed.XposedHelpers;

/** Compact accessory layouts keep the provider's live labels, gauge and touch targets. */
final class WidgetAccessoryLayout {
    private final IdentityHashMap<View,Pose> original = new IdentityHashMap<>();
    private final Set<View> used = Collections.newSetFromMap(new IdentityHashMap<>());

    private final IdentityHashMap<View,Placement> placements=new IdentityHashMap<>();
    private boolean ambient, settling;
    private long wakeUntil;

    void begin() { used.clear(); placements.clear(); }

    void setAmbient(boolean next) {
        if (ambient==next) return;
        ambient=next;
        settling=true;
        wakeUntil=android.os.SystemClock.uptimeMillis()+800;
        updateMotion();
    }

    void updateMotion() {
        if (!ambient && !settling) return;
        boolean moving=false;
        for (Placement placement:placements.values()) {
            placement.apply();
            moving |= Math.abs(1f-placement.layer.getScaleY())>.0001f;
        }
        if (!ambient && !moving && android.os.SystemClock.uptimeMillis()>=wakeUntil) settling=false;
    }

    // The unlocked preview draws the item without its animated lock-screen parent.
    // Undo only the card's AOD compensation in that item's local coordinates.
    void previewTransform(View item, Matrix out) {
        out.reset();
        Placement p=placements.get(item);
        if (p==null) return;
        View card=p.card;
        if (card.getParent()!=item || card.getScaleX()==0f || card.getScaleY()==0f) return;
        float rx=p.sx/card.getScaleX(), ry=p.sy/card.getScaleY();
        float px=card.getLeft()-item.getScrollX()+card.getPivotX();
        float py=card.getTop()-item.getScrollY()+card.getPivotY();
        out.setScale(rx,ry);
        out.postTranslate((1f-rx)*px+p.x-rx*card.getTranslationX(),
                (1f-ry)*py+p.y-ry*card.getTranslationY());
    }

    // The vendor AOD animates a full-screen widget layer. Express its progress at
    // each individual card centre without cancelling the layer's burn-in translation.
    private static final class Placement {
        final View card,layer;
        final float x,y,sx,sy,cx,cy,anchorX,anchorY;
        float lastX=Float.NaN,lastY=Float.NaN;
        Placement(View card,View layer) {
            this.card=card;
            this.layer=layer;
            x=card.getTranslationX(); y=card.getTranslationY();
            sx=card.getScaleX(); sy=card.getScaleY();
            float px=card.getPivotX(),py=card.getPivotY();
            for (View v=card; v!=layer && v.getParent() instanceof View; v=(View)v.getParent()) {
                View parent=(View)v.getParent();
                px+=v.getLeft()+v.getTranslationX()-parent.getScrollX();
                py+=v.getTop()+v.getTranslationY()-parent.getScrollY();
            }
            cx=px; cy=py;
            // A wide weather card is visually anchored at the weather symbol.
            View symbol=card.getWidth()>1.5f*card.getHeight() ? ref(card,"ref_28") : null;
            RectF rect=new RectF();
            if (symbol!=null && symbol.isShown()) {
                rect.set(0,0,symbol.getWidth(),symbol.getHeight());
                for (View v=symbol; v!=card && v.getParent() instanceof View; v=(View)v.getParent()) {
                    View parent=(View)v.getParent();
                    v.getMatrix().mapRect(rect);
                    rect.offset(v.getLeft()-parent.getScrollX(),v.getTop()-parent.getScrollY());
                }
            }
            anchorX=rect.isEmpty()?0:sx*(rect.centerX()-card.getPivotX());
            anchorY=rect.isEmpty()?0:sy*(rect.centerY()-card.getPivotY());
        }
        void apply() {
            float nx=layer.getScaleX(),ny=layer.getScaleY();
            if (nx<=0 || ny<=0 || (lastX==nx && lastY==ny)) return;
            lastX=nx; lastY=ny;
            card.setTranslationX(x+(1f/nx-1f)*(cx-layer.getPivotX())+(1-componentScale(nx))*anchorX/nx);
            card.setTranslationY(y+(1f/ny-1f)*(cy-layer.getPivotY())+(1-componentScale(ny))*anchorY/ny);
            card.setScaleX(sx*componentScale(nx)/nx);
            card.setScaleY(sy*componentScale(ny)/ny);
        }
    }

    static float componentScale(float nativeScale) {
        // PLK110 moves from 1 to .90. Use the same progress for a .97 endpoint.
        return 1f-.03f*Math.max(0f,Math.min(1f,(1f-nativeScale)/.10f));
    }

    void arrangeRows(ViewGroup grid, View host) {
        int width=host.getWidth();
        if (grid.getWidth()==0 || width==0) return;
        TreeMap<Integer,ArrayList<View>> rows=new TreeMap<>();
        for (int i=0;i<grid.getChildCount();i++) {
            View item=grid.getChildAt(i);
            if (item.getVisibility()!=View.VISIBLE || !item.getClass().getSimpleName().equals("WidgetItemContainer")) continue;
            View card=LockStylePreview.findType(item,"InstantCardView");
            if (card==null || card.getParent()!=item || card.getVisibility()!=View.VISIBLE
                    || card.getWidth()==0 || card.getHeight()==0) continue;
            rows.computeIfAbsent(item.getTop(),unused -> new ArrayList<>()).add(card);
        }
        // CoverSheetKit portrait table matching the 440 pt reference: 35 outer, 8 inner, 22 between frames.
        float pt=width/440f, gap=38*pt, unit=(width-2*43*pt-3*gap)/4;
        View layer=null;
        int gridLeft=0;
        for (View v=grid; v!=host && v.getParent() instanceof View; v=(View)v.getParent()) {
            gridLeft+=v.getLeft()-((View)v.getParent()).getScrollX();
            if (v.getClass().getSimpleName().equals("WidgetPluginContainer")) layer=v;
        }
        android.graphics.Rect extent=LiveTune.active()?new android.graphics.Rect():null;
        int[] livePoint=extent==null?null:new int[2];
        for (ArrayList<View> row : rows.values()) {
            row.sort(Comparator.comparingInt(card -> ((View)card.getParent()).getLeft()));
            int columns=0;
            for (View card : row) columns+=span(card);
            if (columns>4) continue;
            boolean rtl=grid.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
            if (rtl) Collections.reverse(row);
            float rowWidth=columns*unit+(columns-1)*gap;
            float x=(width-rowWidth)*.5f;
            boolean bookends=span(row.get(0))==2 && (row.size()==1 || row.size()==2 && span(row.get(1))==1);
            if (bookends) x=43*pt;
            for (int i=0;i<row.size();i++) {
                View card=row.get(i),item=(View)card.getParent();
                float slot=span(card)*unit+(span(card)-1)*gap;
                if (bookends && i==1) x=width-43*pt-slot;
                float scale=Math.min(slot/card.getWidth(),unit/card.getHeight())*LiveTune.get("widgets",1,.6f,1.35f);
                Pose saved=remember(card);
                card.setTranslationX(saved.x);
                card.setScaleX(saved.sx*scale);
                card.setScaleY(saved.sy*scale);
                card.setTranslationY(saved.y-card.getPivotY()*(1-card.getScaleY())+LiveTune.dp(host,"widgetY"));
                // Leading-align wide artwork and center circular artwork inside its allocated slot.
                float center=x+(span(card)==2 ? card.getWidth()*scale : slot)*.5f;
                if (rtl) center=width-center;
                center+=LiveTune.dp(host,"widgetX");
                Pose cell=remember(item);
                item.setTranslationX(cell.x+center-(gridLeft+item.getLeft()-grid.getScrollX()
                        +card.getLeft()+card.getWidth()*.5f));
                if(extent!=null){
                    card.getLocationOnScreen(livePoint);
                    extent.union(livePoint[0],livePoint[1],livePoint[0]+Math.round(card.getWidth()*scale),
                            livePoint[1]+Math.round(card.getHeight()*scale));
                }
                if (layer!=null) {
                    Placement placement=new Placement(card,layer);
                    placements.put(item,placement);
                    if (ambient || settling) placement.apply();
                }
                x+=slot+gap;
            }
        }
        if(extent!=null)LiveTune.widget(extent);
    }

    // Layout coordinates in the clock host, excluding whole-page transitions,
    // overscroll and AOD scaling. An empty editor slot contributes no height.
    int headerBottom(View host, View layer,int inkBottom) {
        if (host==null || host.getWidth()<=0 || host.getHeight()<=0) return 0;
        int widgetBottom=layer==null?0:occupiedBottom(layer,host,0);
        int bottom=Math.max(inkBottom,widgetBottom);
        return bottom>0?bottom+NotificationViewport.dp(host,NotificationViewport.HEADER_GAP):0;
    }

    private int occupiedBottom(View view,View host,int top) {
        if (view.getVisibility()!=View.VISIBLE || !(view instanceof ViewGroup)) return 0;
        if (view.getClass().getSimpleName().equals("InstantCardView")) {
            Placement p=placements.get(view.getParent());
            float scale=p==null?view.getScaleY():p.sy;
            float y=p==null?view.getTranslationY():p.y;
            return Math.round(top+y+view.getPivotY()+(view.getHeight()-view.getPivotY())*scale);
        }
        if (view.getClass().getSimpleName().equals("WidgetPluginContainer")) {
            for (View v=view;v!=host && v.getParent() instanceof View;v=(View)v.getParent())
                top+=v.getTop()-((View)v.getParent()).getScrollY();
        }
        ViewGroup group=(ViewGroup)view;
        int bottom=0;
        for (int i=0;i<group.getChildCount();i++) {
            View child=group.getChildAt(i);
            bottom=Math.max(bottom,occupiedBottom(child,host,top+child.getTop()-group.getScrollY()));
        }
        return bottom;
    }

    private static int span(View card) { return card.getWidth()>card.getHeight()*1.45f ? 2 : 1; }

    void arrange(ViewGroup card) {
        if (card.getWidth() <= 0 || card.getHeight() <= 0) return;
        int type=0;
        try {
            Object grid = XposedHelpers.getObjectField(card,"gridData");
            Object data = grid==null ? null : XposedHelpers.callMethod(grid,"getCardContent");
            if (data!=null) type = (int)XposedHelpers.callMethod(data,"getCardType");
        } catch (RuntimeException | LinkageError unsupported) { /* Fit the provider's own layout. */ }
        if (type == 201805 && card.getWidth()<1.4f*card.getHeight()) {
            View icon = ref(card,"ref_15"), time = ref(card,"ref_16");
            if (icon != null && time != null) {
                position(card,icon,.50f,.32f,1f);
                position(card,time,.50f,.66f,1f);
                return;
            }
        } else if (type == 100787 && card.getWidth()<1.4f*card.getHeight()) {
            View gauge = ref(card,"ref_5"), count = ref(card,"ref_6"), walk = ref(card,"ref_7");
            if (gauge != null && count != null && walk != null) {
                position(card,gauge,.5f,.50f,.96f);
                float diameter = Math.min(gauge.getWidth()*gauge.getScaleX(),gauge.getHeight()*gauge.getScaleY());
                float countScale = stepCountScale(diameter,count.getWidth()*remember(count).sx);
                position(card,count,.5f,.46f,countScale);
                position(card,walk,.5f,.73f,.76f);
                return;
            }
        } else if (type == 100801 && card.getWidth()>1.5f*card.getHeight()) {
            View labels=ref(card,"ref_12"), glyph=ref(card,"ref_28");
            if (labels!=null && glyph!=null && glyph.getParent()==labels.getParent()
                    && glyph.getParent() instanceof ViewGroup) {
                ViewGroup content=(ViewGroup)glyph.getParent();
                // The weather provider clips its inner row. Align that row before moving its children.
                position(card,content,content.getWidth()*.5f/card.getWidth(),.5f,1f);
                float iconWidth=glyph.getWidth()*.91f;
                if (content.getWidth()>0) {
                    position(content,glyph,iconWidth*.5f/content.getWidth(),.50f,.91f);
                    position(content,labels,(iconWidth+.025f*content.getWidth()+labels.getWidth()*.5f)/content.getWidth(),.50f,.97f);
                }
                return;
            }
        }
        fitProviderContent(card);
    }

    static float stepCountScale(float diameter,float measuredWidth) {
        // The native gauge asset includes transparent margins. Reserve its stroke and
        // breathing room on both sides of the number, including localized separators.
        return measuredWidth>0 && diameter>0 ? Math.min(1f,diameter*.59f/measuredWidth) : 1f;
    }

    // Other providers keep their own order and actions. Fit their visible content
    // as one unit, so the renderer and Android hit testing use the same transform.
    private void fitProviderContent(ViewGroup card) {
        if (card.getChildCount()!=1) return;
        View root=card.getChildAt(0);
        RectF visual=new RectF();
        contentBounds(root,0,0,visual);
        if (visual.isEmpty() || root.getWidth()<=0 || root.getHeight()<=0) return;
        float w=card.getWidth(), h=card.getHeight();
        boolean wide=w>h*1.45f;
        float scale=Math.min(1f,Math.min(w*(wide?.91f:.85f)/visual.width(),h*.87f/visual.height()));
        float cx=wide ? .045f*w+visual.width()*scale*.5f : w*.5f;
        float tx=cx-(root.getLeft()+root.getPivotX()+(visual.centerX()-root.getPivotX())*scale);
        float ty=h*.5f-(root.getTop()+root.getPivotY()+(visual.centerY()-root.getPivotY())*scale);
        remember(root);
        root.setScaleX(scale); root.setScaleY(scale);
        root.setTranslationX(tx); root.setTranslationY(ty);
    }

    private static void contentBounds(View v,float l,float t,RectF out) {
        if (v.getVisibility()!=View.VISIBLE || v.getAlpha()==0) return;
        if (v instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)v;
            for (int i=0;i<group.getChildCount();i++) {
                View child=group.getChildAt(i);
                contentBounds(child,l+child.getLeft()-group.getScrollX(),t+child.getTop()-group.getScrollY(),out);
            }
        } else if (!v.willNotDraw() && v.getWidth()>0 && v.getHeight()>0) {
            out.union(l,t,l+v.getWidth(),t+v.getHeight());
        }
    }

    private Pose remember(View view) {
        used.add(view);
        Pose saved = original.get(view);
        if (saved == null) { saved = new Pose(view); original.put(view,saved); }
        return saved;
    }

    private void position(ViewGroup card, View view, float x, float y, float scale) {
        if (view.getWidth() == 0 || view.getHeight() == 0) return;
        Pose saved = remember(view);
        if (view instanceof TextView) {
            TextView label = (TextView)view;
            if (label.getGravity() != Gravity.CENTER) label.setGravity(Gravity.CENTER);
        }
        // Accumulate untransformed layout offsets so repeated passes cannot drift.
        int left = 0, top = 0;
        for (View v=view; v!=card && v.getParent() instanceof View; v=(View)v.getParent()) {
            left += v.getLeft(); top += v.getTop();
        }
        view.setScaleX(saved.sx*scale);
        view.setScaleY(saved.sy*scale);
        view.setTranslationX(card.getWidth()*x - left - view.getWidth()*.5f);
        view.setTranslationY(card.getHeight()*y - top - view.getHeight()*.5f);
    }

    private static View ref(View view,String name) {
        if (view.getId() != View.NO_ID) {
            try { if (name.equals(view.getResources().getResourceEntryName(view.getId()))) return view; }
            catch (Resources.NotFoundException ignored) { /* Generated card view ID. */ }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) {
                View match=ref(group.getChildAt(i),name);
                if (match!=null) return match;
            }
        }
        return null;
    }

    void finish() {
        for (View view : new ArrayList<>(original.keySet())) if (!used.contains(view)) original.remove(view).restore(view);
    }

    private static final class Pose {
        final float x,y,sx,sy;
        final int gravity;
        Pose(View v) {
            x=v.getTranslationX(); y=v.getTranslationY(); sx=v.getScaleX(); sy=v.getScaleY();
            gravity=v instanceof TextView ? ((TextView)v).getGravity() : 0;
        }
        void restore(View v) {
            v.setTranslationX(x); v.setTranslationY(y); v.setScaleX(sx); v.setScaleY(sy);
            if (v instanceof TextView) ((TextView)v).setGravity(gravity);
        }
    }
}
