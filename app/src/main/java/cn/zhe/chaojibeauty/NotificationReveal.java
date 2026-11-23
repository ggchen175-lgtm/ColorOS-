package cn.zhe.chaojibeauty;

import android.animation.ValueAnimator;
import android.graphics.*;
import android.view.*;
import androidx.dynamicanimation.animation.FlingAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getBooleanField;
import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.setBooleanField;

/** Native stack leading inset and section spacing share one interactive fraction. */
final class NotificationReveal {
    static final int OPEN=0, PARKED=1, HIDDEN=2, PREVIEW=3;
    private final NotificationHistory history;
    private final LockClockMaterial clock;
    private final int[] origin=new int[2];
    private ViewGroup list;
    private boolean shade, dragging, waitingForNew, stackRequested;
    private int resting=PARKED, beforeDrag, top, bottom, header, dock;
    private final java.util.ArrayList<View> activities=new java.util.ArrayList<>();
    private int activitiesHeight, activitySlots;
    private static final float HISTORY_REVEAL_START=.26f;
    // A single travel axis: scrolled header, expanded with header, stack, count.
    private float position=2, down, startDistance, firstTravel=1, secondTravel=1, countTravel=1;
    private VelocityTracker velocity;
    private SpringAnimation animation;
    private FlingAnimation inertia;
    private float releaseSpeed;
    private int dragScroll;
    NotificationDeck deck;
    private final java.util.WeakHashMap<View,RollLayer> rollLayers=new java.util.WeakHashMap<>();
    private int rollFrame, linearRange=-1, windowTop, fadeHeight;
    int scrollRange(int nativeRange){
        if(position>0 || linearRange<0)return nativeRange;
        WindowInsets insets=list.getRootWindowInsets();
        return insets!=null && insets.isVisible(WindowInsets.Type.ime())?Math.max(nativeRange,linearRange):linearRange;
    }
    // Visual list order, including the card or header immediately above the fold.
    private final java.util.ArrayList<View> rollOrder=new java.util.ArrayList<>();
    private boolean inViewport,paintingRoll,rollPainted;
    private final Path cover=new Path();
    private final Region coveredTouch=new Region(),touchViewport=new Region();
    private final Paint depthPaint=new Paint();
    private final LightingColorFilter[] depthTints=new LightingColorFilter[17];
    private final Paint mask=new Paint();
    private final LinearGradient fadeIn=new LinearGradient(0,0,0,1,0,0xff000000,Shader.TileMode.CLAMP);

    NotificationReveal(NotificationHistory history, LockClockMaterial clock) {
        this.history=history;
        this.clock=clock;
        for(int i=0;i<depthTints.length;i++)depthTints[i]=new LightingColorFilter(Color.rgb(255-i,255-i,255-i),0);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    void attach(ViewGroup host, boolean unlocked) {
        if (host==list && unlocked==shade) return;
        reset();
        list=host;
        shade=unlocked;
    }
    private int dp(float value) {return NotificationViewport.dp(list,value);}
    private static float unit(float value) {return Math.max(0,Math.min(1,value));}
    boolean active(ViewGroup host) {return host!=null && host==list && history.style()!=NotificationDisplay.COUNT;}
    boolean dragging(){return dragging;}
    boolean settling(){return animation!=null || inertia!=null;}
    int landing(){return resting;}
    boolean lockCount(){return list!=null && resting==HIDDEN;}
    private boolean stackStyle(){return history.style()==NotificationDisplay.STACK;}
    private float countProgress(){return stackStyle()?unit(position-2):0;}
    private void presentFooter(){
        history.compact.present(list,countProgress());
    }
    boolean presented(){return position>0 || dragging || settling();}
    boolean revealingHeader(){return position>0 && (int)callMethod(list,"getOwnScrollY")==0;}
    float countOpenDistance(){return countTravel+secondTravel+firstTravel/2f;}
    boolean canBegin(){return list!=null && bottom>top;}
    boolean changesStop(int next){return resting!=next;}
    float dimVisibility(){return list==null?0:1;}
    boolean hasRoomForRear(View row,float depth){
        if(!(row.getParent() instanceof ViewGroup) || !active((ViewGroup)row.getParent()))return true;
        if(layer(row)!=null)return depth<=layer(row).tail+.01f;
        return !foldedRow(row) && row.getY()-list.getScrollY()+(int)callMethod(row,"getActualHeight")+depth<=bottom;
    }

    boolean drawingMovingRow(ViewGroup host,View row){return active(host) && history.isCard(row);}

    // Read container geometry when the native layout algorithm starts. Its output
    // feeds row placement, content height and hit testing before the first draw.
    void measure() {
        if (!active(list)) return;
        // All anchors are in stack coordinates; the system insets describe window coordinates.
        int listTop=NotificationViewport.layoutY(list,origin);
        top=Math.max(0,-listTop);
        bottom=Math.max(top,history.foldEdge(list));
        header=headerAnchor();
        measureActivitySlots();
        activities.clear();
        activitiesHeight=0;
        for(int i=0;i<list.getChildCount();i++){
            View row=list.getChildAt(i);
            if(row.getVisibility()==View.GONE || !history.isLiveActivity(row))continue;
            int height=(int)callMethod(row,"getIntrinsicHeight");
            if(height<=0)continue;
            if(!activities.isEmpty())activitiesHeight+=dp(8);
            activities.add(row);
            activitiesHeight+=height;
        }
        int available=Math.max(0,bottom-header-activitiesHeight-(activities.isEmpty()?0:dp(8)));
        if(stackStyle())history.compact.measure(list,available);
        else history.compact.detach();
        int messages=0;
        if(history.compact.showsHistory()){
            messages=(int)callMethod(history.compact.front,"getIntrinsicHeight");
        }else{
            for(int i=0;i<list.getChildCount();i++){
                View row=list.getChildAt(i);
                if(history.ownsHeader(row))break;
                if(isActivity(row) || row.getVisibility()==View.GONE || history.historicalRow(row)
                        || !(history.isCard(row) || history.isLiveContainer(row)))continue;
                if(stackStyle() && row!=history.compact.front)continue;
                int height=(int)callMethod(row,"getIntrinsicHeight");
                if(height>0)messages+=height+(messages>0?dp(7):0);
            }
        }
        messages+=history.compact.height();
        int leading=activitiesHeight+messages+(!activities.isEmpty() && messages>0?dp(8):0);
        dock=Math.max(header,bottom-leading);

        firstTravel=Math.max(dp(64),anchor()-contentStart());
        secondTravel=Math.max(dp(64),dock-header);
        countTravel=Math.max(dp(64),bottom-dock-activitiesHeight);
        if(stackStyle() && !history.compact.hasCount() && position>2 && !dragging){
            stopAnimation();resting=PARKED;position=2;
        }
        if(!shade && !stackRequested && historyAlone() && resting==PARKED && position==2 && !dragging && !settling()){
            resting=HIDDEN;
            position=3;
            waitingForNew=true;
        }else if(waitingForNew && !historyAlone() && !dragging && !settling()){
            waitingForNew=false;
            if(resting==HIDDEN && history.compact.front!=null){resting=PARKED;position=2;}
        }
        presentFooter();
    }
    private int headerAnchor(){
        int value=shade?history.headerAnchor(list):clock.notificationAnchor(list);
        if(value<=0)value=(int)callMethod(list,"getIntrinsicPadding");
        return Math.max(top,Math.min(value,bottom));
    }
    void measureActivitySlots(){
        activitySlots=0;
        if(list==null)return;
        for(int i=0;i<list.getChildCount();i++){
            View row=list.getChildAt(i);
            if(row.getVisibility()==View.GONE || !history.isLiveActivity(row))continue;
            if("MediaContainerView".equals(row.getClass().getSimpleName()) && (int)callMethod(row,"getIntrinsicHeight")<=0)continue;
            activitySlots++;
        }
    }
    int liveHeightLimit(View row){
        if(list==null)return NotificationViewport.dp(row,112);
        int end=history.foldEdge(list);
        if(end<=0)return dp(112);
        int measured=shade?history.headerAnchor(list):clock.notificationAnchor(list);
        if(measured<=0)measured=(int)callMethod(list,"getIntrinsicPadding");
        // Reserve space for a compact message and its divider on a short viewport.
        int room=Math.max(0,end-measured);
        int slots=history.isLiveActivity(row)?activitySlots:1;
        int reserve=Math.min(dp(65),(room+1)/2);
        int budget=Math.max(0,room-reserve-Math.max(0,slots-1)*dp(8));
        return Math.min(dp(112),budget/Math.max(1,slots));
    }
    boolean historyAlone(){
        return stackStyle() && history.compact.showsHistory() && history.compact.hasCount();
    }
    int entryStop(){return shade?PREVIEW:(historyAlone()?HIDDEN:PARKED);}
    private float stopPosition(int stop){
        if(stop==OPEN)return 0;
        if(stop==PREVIEW)return 1;
        return stop==HIDDEN?3:2;
    }
    private float headerShown(){return unit(position);}
    float lockExpansion(){
        float progress=1-headerShown();
        return !shade && progress>0 && active(list) && history.hasLockContent(list)?progress:0;
    }
    private float historyFold(){return unit(position-1);}
    float leadingInset(float stackY){
        float sink=(dock-header)*historyFold()+Math.max(0,bottom-dock-activitiesHeight)*countProgress();
        int start=contentStart();
        return start+Math.max(0,anchor()-start)*headerShown()+sink-stackY;
    }
    int anchor(){return header;}
    private int contentStart(){return top;}
    private float headerTravel(){return Math.min(Math.max(0,header-top),Math.max(0,anchor()-contentStart()));}
    private float headerScroll(){
        return headerTravel()*(1-headerShown());
    }
    int viewportTop(){return Math.round(header-headerScroll());}
    private float distance(float phase){return firstTravel*unit(phase)+secondTravel*unit(phase-1)+countTravel*unit(phase-2);}
    private float phase(float distance){
        if(distance<=firstTravel)return unit(distance/firstTravel);
        float beyondHeader=distance-firstTravel;
        if(beyondHeader<=secondTravel)return 1+unit(beyondHeader/secondTravel);
        return stackStyle() && history.compact.hasCount()?2+unit((beyondHeader-secondTravel)/countTravel):2;
    }
    float rowGap(View previous,float nativeGap){
        if(isActivity(previous))return dp(8)-dp(7)-nativeGap;
        if(history.compact.showsHistory() && history.ownsHeader(previous)){
            int height=(int)callMethod(previous,"getIntrinsicHeight");
            return -(height+dp(7)+nativeGap)*unit((historyFold()-(1-HISTORY_REVEAL_START))/HISTORY_REVEAL_START);
        }
        return 0;
    }

    private float revealHistory(){
        float progress=unit((1-historyFold()-HISTORY_REVEAL_START)/.24f);
        return progress*progress*(3-2*progress);
    }

    private float rowVisibility(View row){
        if(isActivity(row))return 1;
        if(countProgress()>=1 && (history.isCard(row)||history.isLiveContainer(row)||history.ownsHeader(row)))return 0;
        if(history.ownsHeader(row) || foldsHistoryRow(row) || behindSummary(row))return revealHistory();
        return 1;
    }

    void applySectionState(){
        if(!active(list))return;
        for(int i=0;i<list.getChildCount();i++){
            View row=list.getChildAt(i);
            if(row.getVisibility()==View.GONE)continue;
            float visible=rowVisibility(row);
            if(visible>=1)continue;
            Object state=callMethod(row,"getViewState");
            float alpha=(float)callMethod(state,"getAlpha");
            if(alpha>visible)callMethod(state,"setAlpha",visible,"Notification section");
            if(visible==0)setBooleanField(state,"hidden",true);
        }
    }
    void drawCompact(Canvas canvas){
        if(stackStyle())history.compact.draw(list,canvas,1-historyFold(),countProgress(),bottom,
                position==2 && !dragging && !settling());
    }
    boolean hidesRear(View row){return stackStyle() && 1-historyFold()<=HISTORY_REVEAL_START
            && history.compact.front==row;}
    private boolean behindSummary(View row){
        return stackStyle() && !isActivity(row) && row!=history.compact.front && (history.isCard(row)||history.isLiveContainer(row));
    }
    void refresh(){
        heading();
        if(countProgress()>0)presentFooter();
        if(dragging || inertia!=null)syncDragScroll();
    }
    private void heading(){
        float offset=headerScroll();
        float fade=headerTravel()>0?unit(offset/headerTravel()):1-headerShown();
        history.scrollHeading(shade?fade:0,shade?offset:0);
        clock.notificationProgress(shade?0:fade,shade?0:offset);
    }
    private void apply(float fraction){
        position=Math.max(0,Math.min(stackStyle()?3:2,fraction));
        presentFooter();
        heading();
        if(list!=null){
            callMethod(list,"requestChildrenUpdate$1");
            list.invalidate();
        }
    }
    void retune(){measure();apply(position);}
    void prepare(int stop){
        stopAnimation();
        measure();
        resting=stop;
        waitingForNew=resting==HIDDEN && historyAlone();
        apply(stopPosition(resting));
    }
    void reset(){
        stopAnimation();releaseVelocity();
        history.compact.detach();
        rollLayers.clear();rollOrder.clear();linearRange=-1;
        dragging=false;waitingForNew=false;stackRequested=false;releaseSpeed=0;
        history.scrollHeading(0,0);
        clock.notificationProgress(0,0);
        list=null;activities.clear();activitiesHeight=0;position=2;resting=PARKED;
    }
    private boolean scrollRequested;
    Runnable scrollReady;

    void begin(MotionEvent e,float y){
        stopAnimation();measure();
        releaseSpeed=0;
        scrollRequested=false;
        dragScroll=(int)callMethod(list,"getOwnScrollY");
        dragging=true;beforeDrag=resting;
        startDistance=distance(position)-Math.max(0,(int)callMethod(list,"getOwnScrollY"));
        down=y;
        releaseVelocity();
        velocity=VelocityTracker.obtain();
        MotionEvent first=MotionEvent.obtain(e.getDownTime(),e.getDownTime(),MotionEvent.ACTION_DOWN,
                e.getX(),e.getY()+y-e.getRawY(),0);
        velocity.addMovement(first);
        first.recycle();
    }
    void move(MotionEvent e){
        velocity.addMovement(e);
        float next=startDistance+(e.getRawY()-down)*LiveTune.get("follow",1,.25f,3);
        if(beforeDrag==OPEN || beforeDrag==PREVIEW)next=Math.min(next,firstTravel+secondTravel);
        apply(phase(next));
        dragScroll=Math.max(0,Math.round(-next));
        if(next<firstTravel && !scrollRequested){
            scrollRequested=true;
            scrollReady.run();
        }else if(next>=firstTravel)scrollRequested=false;
        syncDragScroll();
    }
    private void syncDragScroll(){
        int current=(int)callMethod(list,"getOwnScrollY");
        if(current==0 && dragScroll==0)return;
        int bounded=Math.min(dragScroll,(int)callMethod(list,"getScrollRange"));
        if(current!=bounded)callMethod(list,"setOwnScrollY",bounded);
    }
    private void fling(float speed){
        if(Math.abs(speed)<=ViewConfiguration.get(list.getContext()).getScaledMinimumFlingVelocity())return;
        // The native shade transition can reset its scroller asynchronously.
        // Keep velocity and outstanding travel on the same axis as the drag;
        // the native list still owns the measured range and the actual scroll.
        int scroll=Math.max(dragScroll,(int)callMethod(list,"getOwnScrollY"));
        FlingAnimation fling=new FlingAnimation(new FloatValueHolder(scroll));
        fling.setStartVelocity(-speed);
        fling.setMinValue(0);
        fling.setMinimumVisibleChange(NotificationViewport.pixels(list,.35f));
        fling.addUpdateListener((anim,value,v)->{
            dragScroll=Math.round(value);
            syncDragScroll();
            int range=(int)callMethod(list,"getScrollRange");
            if(range>0 && dragScroll>=range && v>0)anim.cancel();
        });
        fling.addEndListener((anim,canceled,value,v)->{if(inertia==fling)inertia=null;});
        inertia=fling;
        fling.start();
    }
    int end(MotionEvent e){
        dragging=false;
        velocity.addMovement(e);velocity.computeCurrentVelocity(1000,ViewConfiguration.get(list.getContext()).getScaledMaximumFlingVelocity());
        float speed=velocity.getYVelocity();
        releaseVelocity();
        if(e.getActionMasked()==MotionEvent.ACTION_CANCEL)return beforeDrag;
        releaseSpeed=speed*LiveTune.get("follow",1,.25f,3);
        if(position==0 && (int)callMethod(list,"getOwnScrollY")>0)return OPEN;
        float predicted=phase(distance(position)+speed*.14f);
        if(beforeDrag==HIDDEN){
            float lift=down-e.getRawY();
            if(lift<=2*ViewConfiguration.get(list.getContext()).getScaledTouchSlop()
                    && predicted>=3-Math.min(.45f,dp(28)/countTravel))return HIDDEN;
            if(predicted>=1.5f)return PARKED;
        }
        if(beforeDrag==PARKED && stackStyle() && history.compact.hasCount()
                && predicted>2+Math.min(.5f,dp(48)/countTravel))return HIDDEN;
        if(predicted<.5f)return OPEN;
        return predicted<1.5f?PREVIEW:PARKED;
    }
    void settle(int stop,Runnable done){
        stopAnimation();
        resting=stop;
        stackRequested=stop==PARKED;
        waitingForNew=stop==HIDDEN && historyAlone();
        float target=stopPosition(stop);
        if(!ValueAnimator.areAnimatorsEnabled() || Math.abs(target-position)<.001f){
            float speed=releaseSpeed;
            releaseSpeed=0;
            apply(target);
            if(done!=null)done.run();
            if(stop==OPEN && list!=null)fling(speed);
            return;
        }
        float start=distance(position);
        float destination=distance(target);
        float speed=releaseSpeed;
        releaseSpeed=0;
        float timing=260f/LiveTune.get("settle",260,70,1100);
        SpringAnimation spring=new SpringAnimation(new FloatValueHolder(start));
        spring.setSpring(new SpringForce(destination).setDampingRatio(.89f)
                .setStiffness(Math.max(50f,Math.min(1700f,420f*timing*timing))));
        spring.setMinimumVisibleChange(NotificationViewport.pixels(list,.35f));
        // An interrupted pull retains the physical release velocity across both detents.
        spring.setStartVelocity(speed);
        boolean keepHeader=stackStyle() && stop==PARKED && start>=destination;
        spring.addUpdateListener((anim,value,v)->{
            apply(phase(keepHeader?Math.max(destination,value):value));
            if(stop==OPEN && value<=0 && speed<0 && v<0){
                // Cross the header/list seam with the spring's velocity on this exact frame.
                spring.cancel();
                fling(v);
                if(done!=null)done.run();
            }
        });
        spring.addEndListener((anim,canceled,value,v)->{
            if(animation!=spring)return;
            animation=null;
            apply(target);
            if(!canceled && done!=null)done.run();
        });
        animation=spring;
        spring.start();
    }

    private final RenderNode listContent=new RenderNode("Notification list beneath status bar");
    private final RenderNode statusStrip=new RenderNode("Notification status strip");
    private final RenderNode blurredStrip=new RenderNode("Notification status soft focus");
    private final RuntimeShader statusFocus=new RuntimeShader(
            "uniform shader content; uniform float windowTop,band,atlasOffset;" +
            "half4 main(float2 p){float sharp=smoothstep(.55,1.,(p.y-windowTop)/band);" +
            "return mix(content.eval(p+float2(0.,atlasOffset)),content.eval(p),sharp);}");
    private int statusHeight,focusTop,focusBand,focusOffset;
    private float focusRadius;

    Canvas contentCanvas(Canvas dest){
        if(!dest.isHardwareAccelerated() || viewportTop()>=windowTop+statusHeight)return dest;
        listContent.setPosition(0,0,list.getWidth(),bottom);
        return listContent.beginRecording();
    }

    private void compositeContent(Canvas dest){
        listContent.endRecording();
        int end=windowTop+statusHeight;
        int saved=dest.save();
        dest.clipRect(0,end,list.getWidth(),bottom);
        dest.drawRenderNode(listContent);
        dest.restoreToCount(saved);
        // Apply soft focus to list ink only. Keep the system glyphs above this layer.
        float radius=NotificationViewport.pixels(list,3);
        int height=Math.min(bottom,end+(int)Math.ceil(2*radius));
        if(focusRadius!=radius){
            blurredStrip.setRenderEffect(RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP));
            focusRadius=radius;
        }
        if(focusTop!=windowTop || focusBand!=statusHeight || focusOffset!=height){
            statusFocus.setFloatUniform("windowTop",windowTop);
            statusFocus.setFloatUniform("band",statusHeight);
            statusFocus.setFloatUniform("atlasOffset",height);
            statusStrip.setRenderEffect(RenderEffect.createRuntimeShaderEffect(statusFocus,"content"));
            focusTop=windowTop;focusBand=statusHeight;focusOffset=height;
        }
        int width=list.getWidth();
        blurredStrip.setPosition(0,0,width,height);
        Canvas soft=blurredStrip.beginRecording();
        try{soft.drawRenderNode(listContent);}finally{blurredStrip.endRecording();}
        statusStrip.setPosition(0,0,width,2*height);
        Canvas strip=statusStrip.beginRecording();
        try{
            strip.save();
            strip.clipRect(0,0,width,height);
            strip.drawRenderNode(listContent);
            strip.restore();
            strip.translate(0,height);
            strip.drawRenderNode(blurredStrip);
        }finally{statusStrip.endRecording();}
        saved=dest.save();
        dest.clipRect(0,viewportTop(),list.getWidth(),end);
        dest.drawRenderNode(statusStrip);
        dest.restoreToCount(saved);
    }

    int beginViewport(Canvas c){
        if(!active(list) || bottom<=viewportTop())return 0;
        inViewport=true;
        rollPainted=false;
        list.getLocationInWindow(origin);
        windowTop=-origin[1];
        statusHeight=NotificationViewport.statusBottom(list);
        fadeHeight=Math.round(statusHeight*.55f);
        return c.saveLayer(0,viewportTop(),list.getWidth(),bottom,null);
    }
    void finishViewport(Canvas c,int save,boolean recorded){
        if(save==0)return;
        inViewport=false;
        if(recorded)compositeContent(c);
        // Compose media, live activities and rows first. Mask only their pixels in
        // window space, including transforms applied by the shade transition.
        if(windowTop+fadeHeight>viewportTop())edge(c,windowTop,fadeHeight,fadeIn);
        c.restoreToCount(save);
    }
    private boolean isActivity(View row){return activities.contains(row);}
    void clipViewport(Canvas canvas){
        canvas.clipRect(0,viewportTop(),list.getWidth(),bottom);
    }
    private void edge(Canvas c,int y,int height,Shader shader){
        if(height<=0)return;
        int save=c.save();
        c.translate(0,y);c.scale(1,height);
        mask.setShader(shader);c.drawRect(0,0,list.getWidth(),1,mask);
        c.restoreToCount(save);
    }
    private boolean foldsHistoryRow(View row){
        return history.historicalRow(row) && row!=history.compact.front;
    }

    boolean foldedRow(View row){return rowVisibility(row)==0;}

    private static final class RollLayer {
        int frame,tier;
        float progress,tail,frontTail;
        View front;
        boolean covered;
        float widthScale(){return 1-.10f*progress;}
    }

    private RollLayer layer(View row){
        RollLayer value=rollLayers.get(row);
        return value!=null && value.frame==rollFrame?value:null;
    }

    // Run once on fresh linear stack states, before SystemUI applies or animates them.
    // The content height and scroll range still use the full, unprojected list.
    void rollUnder(java.util.List<?> rows) throws ReflectiveOperationException {
        rollFrame++;
        rollOrder.clear();
        float band=Math.min(dp(64),Math.max(0,bottom-viewportTop()))*(1-historyFold());

        View front=null;
        float frontBottom=Float.NEGATIVE_INFINITY;
        float frontZ=0;
        float maxRear=NotificationDeck.tail(list,2);
        int rearTier=0;
        int lastCard=-1;
        View lastInFlow=null;
        for(int i=rows.size()-1;i>=0;i--){
            View candidate=(View)rows.get(i);
            if(!history.isCard(candidate) && !history.isLiveContainer(candidate))continue;
            if(lastInFlow==null && candidate.getVisibility()!=View.GONE
                    && (int)callMethod(candidate,"getIntrinsicHeight")>0)lastInFlow=candidate;
            if(!foldedRow(candidate) && !getBooleanField(callMethod(candidate,"getViewState"),"hidden")){
                lastCard=i;break;
            }
        }
        if(lastInFlow==null)linearRange=0;
        else if(position<=1){
            // Native linear measurement includes every live activity, section gap
            // and expanded group before the last row. Remove the moving header
            // padding and compare to the viewport above the actual shortcut row.
            int lastTop=(int)callMethod(list,"getPositionInLinearLayout$1",lastInFlow)
                    -(int)callMethod(list,"getScrimTopPaddingOrZero");
            int height=(int)callMethod(lastInFlow,"getIntrinsicHeight");
            float lips=deck==null?0:deck.visibleTail(lastInFlow);
            linearRange=Math.max(0,Math.round(lastTop+height+lips)-(bottom-top));
        }
        if(position==0 && linearRange>=0 && lastCard>=0){
            View last=(View)rows.get(lastCard);
            Object state=callMethod(last,"getViewState");
            float end=last.getTop()-list.getScrollY()+(float)callMethod(state,"getYTranslation")
                    +getIntField(state,"height")+(deck==null?0:deck.visibleTail(last));
            // At a range change the scroller can already be at its limit while the
            // native layout still overflows. Keep projecting until the full face fits.
            float remaining=Math.max(end-bottom,linearRange-(int)callMethod(list,"getOwnScrollY"));
            band=Math.min(band,Math.max(0,remaining));
        }
        if(band<=0)return;
        float start=bottom-band;
        for(int i=0;i<rows.size();i++){
            View row=(View)rows.get(i);
            boolean heading=history.ownsHeader(row);
            if(!heading && !history.isCard(row) && !history.isLiveContainer(row))continue;
            Object state=callMethod(row,"getViewState");
            if(foldedRow(row) || getBooleanField(state,"hidden")
                    || (float)callMethod(state,"getAlpha")<=0)continue;
            int h=getIntField(state,"height");
            if(h<=0)continue;
            float y=(float)callMethod(state,"getYTranslation");
            float base=row.getTop()-list.getScrollY();
            float end=base+y+h;
            float z=(float)callMethod(state,"getZTranslation");
            float tail=deck==null || heading || end+maxRear<start?0:deck.visibleTail(row);
            // Where actual following rows supply the roll, they own the two rear
            // slots. A last group can still use its own remaining decorative lips.
            if(end+tail>start && i<lastCard)tail=0;
            end+=tail;
            if(!heading && end>start){
                RollLayer r=rollLayers.get(row);
                if(r==null){r=new RollLayer();rollLayers.put(row,r);}
                r.frame=rollFrame;r.front=front;r.tail=tail;
                RollLayer upper=layer(front);
                r.frontTail=upper!=null?upper.tail:deck!=null && front!=null?deck.visibleTail(front):0;
                r.progress=unit((end-start)/(2*band));
                // Unit slope at entry, zero slope at the fold. A deeper row has
                // a narrower face and less exposed travel than the one before it.
                float p=r.progress;
                float projected=start+band*p*(2-p);
                boolean lip=frontBottom>=start && projected-frontBottom<=dp(18) && end>bottom;
                rearTier=lip?rearTier+1:0;
                if(rearTier>0){
                    float step=NotificationDeck.tail(list,rearTier==1?1:2)
                            -(rearTier==1?0:NotificationDeck.tail(list,1));
                    projected=Math.min(bottom,frontBottom+step);
                    r.tail=tail=rearTier==1?Math.min(tail,NotificationDeck.tail(list,1)):0;
                }else if(i<lastCard){
                    projected=Math.min(projected,bottom-maxRear);
                }
                r.tier=rearTier;
                r.covered=rearTier>2 || frontBottom>=bottom || projected<=frontBottom;
                if(r.covered){
                    setBooleanField(state,"hidden",true);
                    continue;
                }
                // Native shelf clipping describes the original linear position.
                // Once projected, only the common viewport and foreground silhouette clip this card.
                de.robv.android.xposed.XposedHelpers.setIntField(state,"clipBottomAmount",0);
                callMethod(state,"setYTranslation",projected-base-h-tail,"Notification roll-under");
                if(front!=null){
                    if(rollOrder.isEmpty() || rollOrder.get(rollOrder.size()-1)!=front)rollOrder.add(front);
                    z=Math.min(z,frontZ-row.getElevation()-NotificationViewport.pixels(list,.5f));
                    callMethod(state,"setZTranslation",z);
                }
                rollOrder.add(row);
                end=projected;
            }
            if(heading)rearTier=0;
            front=row;frontBottom=end;frontZ=row.getElevation()+z;
        }
    }

    // Glass is translucent. Exclude the foreground silhouette from its rear
    // neighbor so the rear text cannot bleed through the front face.
    private float frontMask(RollLayer back){
        cover.rewind();
        View front=back.front;
        if(front==null)return viewportTop();
        int height=(int)callMethod(front,"getActualHeight");
        float y=front.getY()-list.getScrollY();
        RollLayer f=layer(front);
        if(f!=null)y=Math.min(y,bottom-height-f.tail);
        float end=y+height;
        if(history.ownsHeader(front))return end;
        float half=front.getWidth()*(f==null?1:f.widthScale())/2;
        float cx=front.getX()+front.getWidth()/2f-list.getScrollX();
        float r=Math.min(dp(22),height/2f);
        cover.addRoundRect(cx-half,y,cx+half,end,r,r,Path.Direction.CW);
        if(back.frontTail>0){
            // Include the folded group's own glass lips in its silhouette.
            float first=NotificationDeck.tail(list,1);
            float scale=f==null?1:f.widthScale();
            float inset=NotificationDeck.rearInset(front,1)*scale;
            cover.addRoundRect(cx-half+inset,y,cx+half-inset,end+Math.min(first,back.frontTail),r,r,Path.Direction.CW);
            if(back.frontTail>first){
                inset=NotificationDeck.rearInset(front,2)*scale;
                cover.addRoundRect(cx-half+inset,y,cx+half-inset,end+back.frontTail,r,r,Path.Direction.CW);
            }
        }
        return end-r;
    }

    boolean acceptsPoint(View row,float x,float y){
        RollLayer r=layer(row);
        if(r==null)return true;
        int height=(int)callMethod(row,"getActualHeight");
        float cx=row.getX()+row.getWidth()/2f-list.getScrollX();
        float top=Math.min(row.getY()-list.getScrollY(),bottom-height-r.tail);
        if(r.covered || Math.abs(x-cx)>row.getWidth()*r.widthScale()/2
                || y<top || y>=top+height || y<frontMask(r))return false;
        touchViewport.set(0,viewportTop(),list.getWidth(),bottom);
        coveredTouch.setPath(cover,touchViewport);
        return !coveredTouch.contains((int)x,(int)y);
    }

    // Feed complete groups (their glass lips and native content) through the
    // same hooked drawChild once. A single HWUI ordering boundary covers them
    // all, so a later translucent child cannot paint over an earlier group's lip.
    Boolean drawRollGroup(Canvas canvas,View row,long drawingTime){
        if(!inViewport || paintingRoll || !rollOrder.contains(row))return null;
        if(rollPainted)return Boolean.FALSE;
        rollPainted=true;
        paintingRoll=true;
        boolean animating=false;
        boolean hardware=canvas.isHardwareAccelerated();
        if(hardware)canvas.disableZ();
        try{
            for(int i=rollOrder.size()-1;i>=0;i--){
                View child=rollOrder.get(i);
                if(child.getParent()==list && (child.getVisibility()==View.VISIBLE || child.getAnimation()!=null))
                    animating|=(boolean)callMethod(list,"drawChild",canvas,child,drawingTime);
            }
        }finally{
            if(hardware)canvas.enableZ();
            paintingRoll=false;
        }
        return animating;
    }

    int beginRowDraw(ViewGroup host,Canvas c,View row){
        if(!active(host))return 0;
        if(foldedRow(row))return -1;
        if(!history.isCard(row) && !history.isLiveContainer(row) && !history.ownsHeader(row))return 0;
        int h=(int)callMethod(row,"getActualHeight");
        float y=row.getY()-host.getScrollY();
        float clipTop=viewportTop();
        float opacity=1;
        if(history.ownsHeader(row)){
            float fade=Math.min(Math.max(1,h),dp(26));
            float reveal=unit((y-clipTop+fade)/fade);
            opacity=reveal*reveal*(3-2*reveal);
        }
        RollLayer r=layer(row);
        float projectedY=r==null?y:Math.min(y,bottom-h-r.tail);
        if(projectedY>=bottom || projectedY+h+(r==null?0:r.tail)<=clipTop || opacity==0 || r!=null && r.covered)return -1;
        boolean clipped=projectedY<clipTop;
        if(r==null && opacity==1 && !clipped)return 0;
        int save=c.save();
        if(clipped)c.clipRect(0,clipTop,list.getWidth(),bottom);
        if(r!=null){
            c.clipRect(0,frontMask(r),list.getWidth(),bottom);
            c.clipOutPath(cover);
            c.translate(0,projectedY-y);
            float cx=row.getX()+row.getWidth()/2f;
            c.scale(r.widthScale(),1,cx,y);
            depthPaint.setAlpha(r.tier==0?255:Math.round(255*(r.tier==1?.65f:.35f)));
            depthPaint.setColorFilter(depthTints[Math.round(16*r.progress)]);
            c.saveLayer(row.getX(),y,row.getX()+row.getWidth(),y+h+r.tail,depthPaint);
        }else if(opacity<1)c.saveLayerAlpha(null,Math.round(255*opacity));
        return save;
    }

    void tuneBox(View row,Rect rect,int h,int y){
        if(active(list)&&foldedRow(row))rect.setEmpty();
    }
    void stopFling(){
        if(inertia!=null)inertia.cancel();
    }
    private void stopAnimation() {
        stopFling();
        SpringAnimation a=animation;
        animation=null;
        if (a!=null) a.cancel();
    }
    private void releaseVelocity() {
        if (velocity!=null) velocity.recycle();
        velocity=null;
    }
}
