package cn.zhe.chaojibeauty;

import android.app.KeyguardManager;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import java.lang.reflect.Method;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** The Cover Sheet actions are a sibling of the scrolling list. Both presentations use
 * the same circular face, with only the two actual controls participating in hit testing. */
final class ShadeQuickActions {
    private static final String TAG="ChaojiBeauty";
    private static final float PRESS_SCALE_GAIN=.6f, MAX_HIGHLIGHT=1.2f;
    private static final Uri SELECTIONS=Uri.parse("content://com.android.systemui.customization/lockscreen_quickaffordance/selections");
    private final NotificationGlass glass;
    private final LockClockMaterial lockClock;
    private Handler main;
    private ContentObserver changes;
    private final ExecutorService reader=Executors.newSingleThreadExecutor();
    private final ViewTreeObserver.OnGlobalLayoutListener layout=this::sync;
    private ViewGroup window, nativeGroup, sheet;
    private FrameLayout row, lockRow;
    private final ImageButton[] lockButtons=new ImageButton[2];
    private final ImageView[] originals=new ImageView[2];
    private final boolean[] availableOnKeyguard=new boolean[2];
    private View closeLockedList;
    private boolean lockedList;
    private View collapsedSlot;
    private int slotWidth,slotHeight,slotAccessibility;
    private void collapseNativeSlot(View slot){
        if(collapsedSlot!=null && collapsedSlot!=slot){
            ViewGroup.LayoutParams lp=collapsedSlot.getLayoutParams();
            if(lp!=null && lp.width==0 && lp.height==0){
                lp.width=slotWidth;lp.height=slotHeight;collapsedSlot.setLayoutParams(lp);
            }
            collapsedSlot.setImportantForAccessibility(slotAccessibility);
            collapsedSlot=null;
        }
        if(slot==null || slot.getLayoutParams()==null)return;
        ViewGroup.LayoutParams lp=slot.getLayoutParams();
        if(collapsedSlot==null){
            collapsedSlot=slot;slotWidth=lp.width;slotHeight=lp.height;
            slotAccessibility=slot.getImportantForAccessibility();
            slot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        if(lp.width!=0 || lp.height!=0){
            lp.width=lp.height=0;slot.setLayoutParams(lp);
        }
    }
    private final ViewTreeObserver.OnPreDrawListener lockPresentation=this::presentLockButtons;

    private boolean presentLockButtons() {
        if (nativeGroup==null || lockRow==null) return true;
        boolean nativeVisible=nativeGroup.isShown();
        float expanded=lockedList && closeLockedList.isShown()?closeLockedList.getAlpha():0f;
        for (int i=0;i<2;i++) {
            ImageView source=originals[i];
            ImageButton button=lockButtons[i];
            if (button==null || source==null) continue;
            boolean showing=nativeVisible && source.getVisibility()==View.VISIBLE;
            if (showing) availableOnKeyguard[i]=true;
            else if (source.getVisibility()==View.GONE) availableOnKeyguard[i]=false;
            float alpha=Math.max(showing?source.getAlpha()*nativeGroup.getAlpha():0f,availableOnKeyguard[i]?expanded:0f);
            int visibility=alpha>0f?View.VISIBLE:View.INVISIBLE;
            if (button.getVisibility()!=visibility) button.setVisibility(visibility);
            if (button.getAlpha()!=alpha) button.setAlpha(alpha);
        }
        return true;
    }

    private final Rect[] previousClips=new Rect[2];
    private final int[] previousAccessibility=new int[2];
    private final String[] lockKeys=new String[2];
    private View list;
    private final ImageButton[] buttons=new ImageButton[2];
    private final Drawable.ConstantState[] glyphs=new Drawable.ConstantState[2];
    private final String[] actions=new String[2], labels=new String[2];
    private boolean enabled, observing, torchOn, torchAvailable;
    private int readGeneration, marginAdded, appliedMargin=-1;
    private CameraManager cameras;
    private String torchId, backFlashId;
    private ImageView tracking, animated;
    private int pressedSlot=-1;
    private String pressedKey;
    private boolean sequence, lockPress, armed;
    private long downTime,lastFrame;
    private float highlight,velocity;
    private Object interactor,startAction,expandableCompanion;
    private Method trigger,fromView;
    private final Rect pressBounds=new Rect();
    private final Rect hit=new Rect();
    private final int[] parentXY=new int[2], rowXY=new int[2];
    private Runnable collapse;
    private View outgoingHeader;
    private float originalHeaderAlpha;
    private ValueAnimator cameraFade;
    private boolean cameraLostFocus;
    private final Runnable restoreCameraHeader=this::finishCameraExit;
    private final ViewTreeObserver.OnWindowFocusChangeListener cameraFocus=focused->{
        if (!focused) cameraLostFocus=true;
        else if (cameraLostFocus) finishCameraExit();
    };

    ShadeQuickActions(NotificationGlass glass,LockClockMaterial lockClock) {
        this.glass=glass;
        this.lockClock=lockClock;
    }

    void install(Class<?> windowType) throws ReflectiveOperationException {
        try {
            ClassLoader cl=windowType.getClassLoader();
            Class<?> owner=cl.loadClass("com.android.systemui.keyguard.domain.interactor.KeyguardQuickAffordanceInteractor");
            Class<?> expandable=cl.loadClass("com.android.systemui.animation.Expandable");
            Class<?> action=cl.loadClass("com.android.systemui.keyguard.shared.quickaffordance.ExtendAction");
            trigger=owner.getMethod("onQuickAffordanceTriggered",String.class,expandable,String.class,action);
            expandableCompanion=expandable.getField("Companion").get(null);
            fromView=expandableCompanion.getClass().getMethod("fromView",View.class);
            startAction=cl.loadClass(action.getName()+"$StartApp").getField("INSTANCE").get(null);
            XposedBridge.hookAllConstructors(owner,new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { interactor=p.thisObject; }
            });
        } catch (ReflectiveOperationException e) { Log.w(TAG,"Native lock action unavailable",e); }
        try {
            Class<?> helper=windowType.getClassLoader().loadClass("com.oplus.systemui.keyguard.KeyguardAffordanceHelper");
            XposedBridge.hookMethod(helper.getDeclaredMethod("getIconAtPosition",float.class,float.class),new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object icon=p.getResult();
                    if (enabled && icon!=null && (icon==originals[0] || icon==originals[1])) p.setResult(null);
                }
            });
        } catch (ReflectiveOperationException e) { Log.w(TAG,"Native lock hit test unavailable",e); }
        XposedBridge.hookMethod(windowType.getDeclaredMethod("onAttachedToWindow"),new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                attach((ViewGroup)p.thisObject);
            }
        });
        XposedBridge.hookMethod(windowType.getDeclaredMethod("onDetachedFromWindow"),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { if (window==p.thisObject) detach(); }
        });
    }

    private void attach(ViewGroup root) {
        if (window==root) return;
        detach();
        window=root;
        main=new Handler(Looper.getMainLooper());
        changes=new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) { readSelections(); }
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(layout);
        if (enabled) start();
    }

    void configure(boolean on) {
        if (enabled==on) return;
        enabled=on;
        if (window==null) return;
        if (on) start(); else stop();
    }

    private void start() {
        if (!observing) {
            window.getContext().getContentResolver().registerContentObserver(SELECTIONS,true,changes);
            observing=true;
        }
        readSelections();
        sync();
    }

    private void readSelections() {
        if (window==null || !enabled) return;
        int generation=++readGeneration;
        Context context=window.getContext();
        reader.execute(()->{
            String[] ids=new String[2], names=new String[2];
            try (Cursor c=context.getContentResolver().query(SELECTIONS,null,null,null,null)) {
                if (c==null) return;
                while (c.moveToNext()) {
                    String slot=c.getString(c.getColumnIndexOrThrow("slot_id"));
                    int i="bottom_start".equals(slot)?0:"bottom_end".equals(slot)?1:-1;
                    if (i<0) continue;
                    ids[i]=c.getString(c.getColumnIndexOrThrow("affordance_id"));
                    names[i]=c.getString(c.getColumnIndexOrThrow("affordance_name"));
                }
            } catch (RuntimeException e) { Log.w(TAG,"Quick action selection unavailable",e); return; }
            Drawable.ConstantState[] images=new Drawable.ConstantState[2];
            try {
                Resources res=context.getPackageManager().getResourcesForApplication("cn.zhe.chaojibeauty");
                String[] namesByType={"quick_torch","quick_camera"};
                for (int i=0;i<namesByType.length;i++) {
                    int id=res.getIdentifier(namesByType[i],"drawable","cn.zhe.chaojibeauty");
                    images[i]=res.getDrawable(id,null).getConstantState();
                }
            } catch (android.content.pm.PackageManager.NameNotFoundException | Resources.NotFoundException e) {
                Log.w(TAG,"Quick action glyph unavailable",e);
                return;
            }
            main.post(()->{
                if (generation!=readGeneration) return;
                System.arraycopy(images,0,glyphs,0,2);
                System.arraycopy(ids,0,actions,0,2);
                boolean needsTorch="flashlight".equals(ids[0]) || "flashlight".equals(ids[1]);
                if (needsTorch && cameras==null) startTorch(context);
                else if (!needsTorch) stopTorch();
                System.arraycopy(names,0,labels,0,2);
                if (row!=null) populate();
                else sync();
            });
        });
    }

    private void stop() {
        finishCameraExit();
        readGeneration++;
        stopTorch();
        close();
        reserve(null,0);
        collapseNativeSlot(null);
        cancelTouch();
        clearLockRow();
        if (observing) window.getContext().getContentResolver().unregisterContentObserver(changes);
        observing=false;
    }

    private void detach() {
        if (window==null) return;
        stop();
        if (window.getViewTreeObserver().isAlive()) window.getViewTreeObserver().removeOnGlobalLayoutListener(layout);
        window=nativeGroup=null;
        closeLockedList=null;
        lockedList=false;
        list=null;
        backFlashId=null;
    }

    private static View id(View root,String name) {
        int id=root.getResources().getIdentifier(name,"id","com.android.systemui");
        return id==0?null:root.findViewById(id);
    }

    private void sync() {
        if (window==null || !enabled) return;
        if (list==null || !list.isAttachedToWindow()) list=id(window,"notification_stack_scroller");
        if (nativeGroup==null || !nativeGroup.isAttachedToWindow()) {
            View found=LockStylePreview.findType(id(window,"keyguard_bottom_area"),"BottomAffordanceContainer");
            nativeGroup=found instanceof ViewGroup?(ViewGroup)found:null;
        }
        if (closeLockedList==null || !closeLockedList.isAttachedToWindow()) closeLockedList=id(window,"close_all");
        lockedList=row==null && closeLockedList!=null && closeLockedList.isShown()
                && window.getContext().getSystemService(KeyguardManager.class).isKeyguardLocked();
        collapseNativeSlot(row==null && window.getContext().getSystemService(KeyguardManager.class).isKeyguardLocked()?closeLockedList:null);
        syncLockRow();
        int top=Integer.MAX_VALUE;
        if (lockRow!=null && row==null && window.getContext().getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            int rowTop=NotificationViewport.layoutY(lockRow,rowXY);
            for (ImageButton button:lockButtons) {
                if (button!=null && button.getHeight()>0 && button.getVisibility()!=View.GONE)
                    top=Math.min(top,rowTop+button.getTop());
            }
        }
        if (row!=null) {
            for (int i=0;i<2;i++) {
                ImageButton b=buttons[i];
                if (b==null) continue;
                position(b,i,sheet);
                if (b.getHeight()>0) {
                    top=Math.min(top,NotificationViewport.layoutY(row,rowXY)+b.getTop());
                }
            }
        }
        boolean onPage=row!=null || window.getContext().getSystemService(KeyguardManager.class).isKeyguardLocked();
        int bottom=top==Integer.MAX_VALUE ? NotificationViewport.navigationTop(window,parentXY)
                : top-NotificationViewport.dp(window,NotificationViewport.ACTION_GAP);
        reserve(onPage?list:null,bottom);
    }

    View tuneButton(int i){return (row!=null ? buttons : lockButtons)[i];}
    int foldEdgeInWindow() {
        ImageButton[] slots = row != null ? buttons : lockButtons;
        int edge = Integer.MAX_VALUE;
        for (ImageButton button : slots) {
            if (button == null || !button.isShown() || button.getHeight() == 0) continue;
            edge = Math.min(edge,NotificationViewport.layoutY(button,rowXY));
        }
        return edge == Integer.MAX_VALUE ? -1 : edge;
    }

    int footerCenterInWindow() {
        ImageButton[] slots = row != null ? buttons : lockButtons;
        for (ImageButton b : slots) {
            if (b != null && b.isShown() && b.getHeight() > 0 && b.getParent() instanceof View) {
                ((View)b.getParent()).getLocationInWindow(rowXY);
                return rowXY[1] + b.getTop() + b.getHeight() / 2;
            }
        }
        return -1;
    }

    // Restore the native margin when this page releases the shared stack.
    private View marginOwner;
    private void reserve(View target,int top) {
        if (list==null || !(list.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)
                || !(list.getParent() instanceof View)) return;
        View parent=(View)list.getParent();
        ViewGroup.MarginLayoutParams lp=(ViewGroup.MarginLayoutParams)list.getLayoutParams();
        if(marginOwner!=list){
            marginOwner=list;appliedMargin=-1;marginAdded=0;
        }
        int base=lp.bottomMargin==appliedMargin?lp.bottomMargin-marginAdded:lp.bottomMargin;
        int extra=0;
        if (target!=null && parent.getHeight()>0) {
            extra=NotificationViewport.layoutY(parent,parentXY)+parent.getHeight()-base-top;
        }
        marginAdded=extra;
        appliedMargin=base+extra;
        if(lp.bottomMargin==appliedMargin)return;
        lp.bottomMargin=appliedMargin;
        list.setLayoutParams(lp);
    }

    private final class Circle extends Drawable {
        private final View host;
        private final RectF shape=new RectF();
        private final Paint mask=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int width,height;
        private int opacity=255;
        Circle(View host,int width,int height) { this.host=host; this.width=width; this.height=height; }
        @Override public void draw(Canvas c) {
            shape.set(getBounds());
            if (opacity==0) return;
            int saved=opacity==255?-1:c.saveLayerAlpha(shape,opacity);
            mask.setColor(0xffffffff);
            if (glass.paintQuickAction(host,c,shape,mask)) {
                IconModule.firstDraw(host.getParent()!=lockRow?"shade quick action glass":"lock quick action glass");
            } else {
                mask.setColor(0x32e1edff);
                c.drawOval(shape,mask);
            }
            if (saved>=0) c.restoreToCount(saved);
        }
        @Override public int getIntrinsicWidth(){return width;}
        @Override public int getIntrinsicHeight(){return height;}
        @Override public void setAlpha(int alpha) { if (opacity!=alpha) { opacity=alpha; invalidateSelf(); } }
        @Override public int getAlpha() { return opacity; }
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }

    private void clearLockSlot(int slot) {
        ImageButton button=lockButtons[slot];
        if (button!=null) {
            if (tracking==button || animated==button) cancelTouch();
            lockRow.removeView(button);
        }
        ImageView original=originals[slot];
        if (original!=null) {
            original.setClipBounds(previousClips[slot]);
            original.setImportantForAccessibility(previousAccessibility[slot]);
        }
        lockButtons[slot]=null;
        originals[slot]=null;
        availableOnKeyguard[slot]=false;
        previousClips[slot]=null;
        lockKeys[slot]=null;
    }

    private void clearLockRow() {
        if (lockRow==null) return;
        for (int i=0;i<2;i++) clearLockSlot(i);
        if (lockRow.getViewTreeObserver().isAlive())
            lockRow.getViewTreeObserver().removeOnPreDrawListener(lockPresentation);
        if (lockRow.getParent() instanceof ViewGroup) ((ViewGroup)lockRow.getParent()).removeView(lockRow);
        lockRow=null;
    }

    private void syncLockRow() {
        View parent=nativeGroup!=null && nativeGroup.getParent() instanceof FrameLayout?(View)nativeGroup.getParent():null;
        if (lockRow!=null && lockRow.getParent()!=parent) clearLockRow();
        if (parent==null) return;
        if (!(nativeGroup instanceof FrameLayout) || interactor==null || trigger==null || startAction==null) return;
        for (int i=0;i<2;i++) {
            View child=nativeGroup.getChildAt(i);
            ImageView original=child instanceof ImageView?(ImageView)child:null;
            String key=actions[i];
            if (original!=originals[i] || !java.util.Objects.equals(lockKeys[i],key)) clearLockSlot(i);
            if (original==null || !supported(key)) continue;
            if (lockRow==null) {
                lockRow=new FrameLayout(window.getContext());
                lockRow.setClipChildren(false);
                lockRow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                ((FrameLayout)parent).addView(lockRow,new FrameLayout.LayoutParams(-1,-1));
                lockRow.getViewTreeObserver().addOnPreDrawListener(lockPresentation);
            }
            if (lockButtons[i]==null) {
                lockButtons[i]=createButton(lockRow,i,true);
                originals[i]=original;
                previousClips[i]=original.getClipBounds();
                previousAccessibility[i]=original.getImportantForAccessibility();
                original.setClipBounds(new Rect());
                original.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                lockKeys[i]=key;
                if ("flashlight".equals(key)) applyTorch(lockButtons[i],original.isEnabled());
            }
            ImageButton button=lockButtons[i];
            button.setEnabled(original.isEnabled() && (!"flashlight".equals(key) || torchAvailable));
            position(button,i,nativeGroup);
        }
        presentLockButtons();
    }

    private static boolean supported(String key) {
        return "flashlight".equals(key) || "camera".equals(key);
    }

    void open(ViewGroup panel,Runnable dismiss) {
        close();
        if (!enabled) return;
        if (window==null && panel.getRootView() instanceof ViewGroup) attach((ViewGroup)panel.getRootView());
        sheet=panel;
        collapse=dismiss;
        row=new FrameLayout(panel.getContext());
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.setForceDarkAllowed(false);
        panel.addView(row,new ViewGroup.LayoutParams(-1,-1));
        populate();
    }

    private void populate() {
        cancelTouch();
        row.removeAllViews();
        for (int i=0;i<2;i++) {
            buttons[i]=null;
            boolean flash="flashlight".equals(actions[i]);
            if (!flash && !"camera".equals(actions[i])) continue;
            ImageButton b=createButton(row,i,false);
            buttons[i]=b;
            position(b,i,sheet);
            if (flash) updateTorch();
        }
        sync();
    }

    private ImageButton createButton(FrameLayout layer,int slot,boolean locked) {
        final boolean flash="flashlight".equals(actions[slot]);
        Context ctx=layer.getContext();
        ImageButton b=new ImageButton(ctx);
        b.setBackground(new Circle(b,-1,-1));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setContentDescription(labels[slot]);
        int pad=dp(12);
        b.setPadding(pad,pad,pad,pad);
        Drawable.ConstantState glyph=glyphs[flash?0:1];
        if (glyph!=null) b.setImageDrawable(glyph.newDrawable(ctx.getResources()).mutate());
        // Accessibility activation is explicit. Ordinary touch is owned by the window
        // gesture so ImageView cannot independently fire a long-click before UP.
        b.setClickable(false);
        b.setLongClickable(false);
        b.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host,info);
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            }
            @Override public boolean performAccessibilityAction(View host,int action,android.os.Bundle args) {
                if (action==android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK && host.isEnabled()) {
                    if (locked) triggerLock(slot,actions[slot],(ImageButton)host);
                    else activate(flash);
                    return true;
                }
                return super.performAccessibilityAction(host,action,args);
            }
        });
        b.setFocusable(true);
        layer.addView(b,new FrameLayout.LayoutParams(dp(48),dp(48)));
        return b;
    }

    private int dp(float x) { return NotificationViewport.dp(window,x); }

    private boolean portraitPhone(View host) {
        return host.getHeight()>host.getWidth() && host.getWidth()>0
                && host.getResources().getConfiguration().smallestScreenWidthDp<600;
    }

    // Portrait video cross-check: button centres at 18.75% of the width,
    // with about one diameter between the lower edge of a button and the screen edge.
    private int portraitSide(View host,int diameter) {
        return Math.max(0,Math.round(host.getWidth()*.1875f)-diameter/2);
    }

    private int portraitBottom(View host,int diameter) {
        WindowInsets insets=host.getRootWindowInsets();
        int safe=insets==null?0:insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.navigationBars()|WindowInsets.Type.displayCutout()).bottom;
        return Math.max(diameter,safe+dp(12));
    }

    private void position(ImageButton button,int slot,View container) {
        // Use the actual plugin slots if available, including device and rotation overrides.
        int diameter=dp(48), side=dp(48), bottom=dp(38);
        WindowInsets insets=container.getRootWindowInsets();
        if (insets!=null) {
            android.graphics.Insets safe=insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()|WindowInsets.Type.displayCutout());
            side+=Math.max(safe.left,safe.right);
            bottom=Math.max(bottom,safe.bottom+dp(12));
        }
        if (nativeGroup!=null && nativeGroup.getChildCount()>slot
                && nativeGroup.getWidth()==container.getWidth() && nativeGroup.getHeight()==container.getHeight()) {
            View original=nativeGroup.getChildAt(slot);
            if (original.getWidth()>0 && original.getHeight()>0) {
                diameter=Math.min(original.getWidth(),original.getHeight());
                side=Math.min(original.getLeft(),nativeGroup.getWidth()-original.getRight());
                bottom=nativeGroup.getHeight()-original.getBottom();
            }
        }
        if (portraitPhone(container)) {
            side=portraitSide(container,diameter);
            bottom=portraitBottom(container,diameter);
        }
        diameter=Math.round(diameter*LiveTune.get("buttonSize",1,.65f,1.45f));
        diameter=Math.round(diameter*LiveTune.size(slot==0?"torchSize":"cameraSize"));
        int dx=Math.round(LiveTune.dp(container,slot==0?"torchX":"cameraX"));
        boolean left=(slot==0)==(container.getLayoutDirection()!=View.LAYOUT_DIRECTION_RTL);
        side=Math.max(0,Math.min(container.getWidth()-diameter,side+(left?dx:-dx)));
        bottom=Math.max(0,Math.min(container.getHeight()-diameter,bottom-Math.round(LiveTune.dp(container,slot==0?"torchY":"cameraY"))));
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)button.getLayoutParams();
        int gravity=Gravity.BOTTOM|(slot==0?Gravity.START:Gravity.END);
        if (p.width==diameter && p.gravity==gravity && p.bottomMargin==bottom && p.getMarginStart()==side && p.getMarginEnd()==side) return;
        p.gravity=gravity;
        p.width=p.height=diameter;
        p.bottomMargin=bottom;
        p.setMarginStart(side);
        p.setMarginEnd(side);
        button.setLayoutParams(p);
    }

    private final CameraManager.TorchCallback torch=new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String id,boolean on) {
            if (id.equals(torchId)) { torchAvailable=true; torchOn=on; updateTorch(); }
        }
        @Override public void onTorchModeUnavailable(String id) {
            if (id.equals(torchId)) { torchAvailable=false; torchOn=false; updateTorch(); }
        }
    };

    private void startTorch(Context context) {
        try {
            cameras=context.getSystemService(CameraManager.class);
            if (backFlashId==null) for (String id:cameras.getCameraIdList()) {
                CameraCharacteristics c=cameras.getCameraCharacteristics(id);
                if (Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))
                        && Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(c.get(CameraCharacteristics.LENS_FACING))) {
                    backFlashId=id;
                    break;
                }
            }
            torchId=backFlashId;
            if (torchId!=null) cameras.registerTorchCallback(torch,main);
        } catch (CameraAccessException | RuntimeException e) { Log.w(TAG,"Torch unavailable",e); }
        updateTorch();
    }

    private void updateTorch() {
        for (int i=0;i<2;i++) if ("flashlight".equals(actions[i])) {
            applyTorch(buttons[i],true);
            applyTorch(lockButtons[i],originals[i]!=null && originals[i].isEnabled());
        }
    }

    private void applyTorch(ImageButton button,boolean enabledSlot) {
        if (button==null) return;
        button.setEnabled(torchAvailable && enabledSlot);
        button.setActivated(torchOn);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(torchOn?0xff26303c:0xffffffff));
    }

    private void stopTorch() {
        if (cameras!=null && torchId!=null) cameras.unregisterTorchCallback(torch);
        cameras=null;
        torchId=null;
        torchOn=torchAvailable=false;
    }

    private void activate(boolean flash) {
        if (row==null || row.getContext().getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
        try {
            if (flash) {
                if (torchAvailable && cameras!=null && torchId!=null) cameras.setTorchMode(torchId,!torchOn);
            } else {
                Context ctx=row.getContext();
                Intent camera=new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (camera.resolveActivity(ctx.getPackageManager())!=null) {
                    Runnable dismiss=collapse;
                    dismiss.run();
                    ctx.startActivity(camera);
                }
            }
        } catch (CameraAccessException | RuntimeException e) { Log.w(TAG,"Quick action unavailable",e); }
    }

    boolean touch(View root,MotionEvent e,boolean shadeReady) {
        if (!enabled || window!=root) return false;
        int action=e.getActionMasked();
        if (action==MotionEvent.ACTION_DOWN) {
            if (sequence) cancelTouch();
            boolean locked=row==null && lockRow!=null && lockRow.isShown() && interactor!=null
                    && window.getContext().getSystemService(KeyguardManager.class).isKeyguardLocked();
            if (!locked && (!shadeReady || row==null)) return false;
            for (int i=0;i<2;i++) {
                View candidate=locked?lockButtons[i]:buttons[i];
                if (!("flashlight".equals(actions[i]) || "camera".equals(actions[i])) ||
                        !(candidate instanceof ImageView) || !candidate.isShown() || candidate.getAlpha()<=0f
                        || !candidate.getGlobalVisibleRect(hit) || !hit.contains((int)e.getRawX(),(int)e.getRawY())) continue;
                cancelTouch();
                sequence=true;
                if (!candidate.isEnabled()) return true;
                lockPress=locked;
                pressedSlot=i;
                pressedKey=actions[i];
                tracking=animated=(ImageView)candidate;
                tracking.setPressed(true);
                pressBounds.set(hit);
                int slop=ViewConfiguration.get(window.getContext()).getScaledTouchSlop();
                pressBounds.inset(-slop,-slop);
                downTime=e.getDownTime(); lastFrame=SystemClock.uptimeMillis();
                main.postAtTime(arm,downTime+ViewConfiguration.getLongPressTimeout());
                animated.postOnAnimation(frame);
                break;
            }
        }
        if (!sequence) return false;
        if ((action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_UP) && tracking!=null &&
                !pressBounds.contains((int)e.getRawX(),(int)e.getRawY()))
            releasePress();
        if (action==MotionEvent.ACTION_POINTER_DOWN) releasePress();
        if (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
            if (action==MotionEvent.ACTION_UP && tracking!=null &&
                    e.getEventTime()-downTime>=ViewConfiguration.getLongPressTimeout()) {
                armPress();
                if (armed) activateOnRelease();
            }
            releasePress();
            sequence=false;
        }
        return true;
    }

    private final Runnable arm=this::armPress;
    private final Runnable frame=this::advancePress;

    private void armPress() {
        if (!armed && tracking!=null && tracking.isShown() && window.hasWindowFocus()) {
            armed=true;
            tracking.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    }

    private void activateOnRelease() {
        ImageView button=tracking;
        if (!button.isShown() || !button.isEnabled() || !window.hasWindowFocus()) return;
        boolean locked=window.getContext().getSystemService(KeyguardManager.class).isKeyguardLocked();
        if (locked!=lockPress || pressedSlot<0 || !pressedKey.equals(actions[pressedSlot])) return;
        String key=pressedKey;
        if ("flashlight".equals(key) && !torchAvailable) return;
        armed=false;
        if (lockPress) triggerLock(pressedSlot,key,button);
        else activate("flashlight".equals(key));
    }

    private void triggerLock(int index,String key,ImageView button) {
        if (outgoingHeader!=null || !canLaunch(index,key,button)) return;
        if ("camera".equals(key) && beginCameraExit(()->{
            if (canLaunch(index,key,button)) dispatchLock(index,key,button);
            else finishCameraExit();
        })) return;
        dispatchLock(index,key,button);
    }

    private boolean canLaunch(int index,String key,ImageView button) {
        return window!=null && button==lockButtons[index] && key.equals(actions[index]) && button.isEnabled()
                && button.isShown() && button.hasWindowFocus() && button.getAlpha()>0f
                && window.getContext().getSystemService(android.os.PowerManager.class).isInteractive()
                && window.getContext().getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    private void dispatchLock(int index,String key,ImageView button) {
        String slot=index==0?"bottom_start":"bottom_end";
        try {
            trigger.invoke(interactor,slot+"::"+key,fromView.invoke(expandableCompanion,button),slot,startAction);
        } catch (ReflectiveOperationException e) {
            finishCameraExit();
            Log.w(TAG,"Lock action failed",e);
        }
    }

    // UP commits the handoff. Let the header finish its group transition
    // before the secure camera window is allowed to cover the last frame.
    private boolean beginCameraExit(Runnable launch) {
        View header=id(window,"keyguard_style_clock");
        if (header==null || !header.isShown() || !ValueAnimator.areAnimatorsEnabled()) return false;
        outgoingHeader=header;
        lockClock.cameraHandoff(true);
        originalHeaderAlpha=header.getTransitionAlpha();
        cameraLostFocus=false;
        window.getViewTreeObserver().addOnWindowFocusChangeListener(cameraFocus);
        cameraFade=ValueAnimator.ofFloat(originalHeaderAlpha,0f);
        int duration=header.getResources().getInteger(android.R.integer.config_shortAnimTime);
        cameraFade.setDuration(duration);
        cameraFade.addUpdateListener(a->header.setTransitionAlpha((float)a.getAnimatedValue()));
        cameraFade.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (outgoingHeader!=header || cameraLostFocus) { finishCameraExit(); return; }
                launch.run();
                if (outgoingHeader==header) {
                    int timeout=header.getResources().getInteger(android.R.integer.config_longAnimTime);
                    main.postDelayed(restoreCameraHeader,timeout*2L);
                }
            }
        });
        cameraFade.start();
        return true;
    }

    private void finishCameraExit() {
        if (outgoingHeader==null) return;
        main.removeCallbacks(restoreCameraHeader);
        if (window.getViewTreeObserver().isAlive())
            window.getViewTreeObserver().removeOnWindowFocusChangeListener(cameraFocus);
        lockClock.cameraHandoff(false);
        cameraFade.removeAllListeners();
        cameraFade.cancel();
        outgoingHeader.setTransitionAlpha(originalHeaderAlpha);
        outgoingHeader=null;
        cameraFade=null;
    }

    private void advancePress() {
        ImageView button=animated;
        if (button==null) return;
        if (!button.isAttachedToWindow() || !button.isShown()) { cancelTouch(); return; }
        long now=SystemClock.uptimeMillis();
        float target=tracking==null?0f:Math.min(1f,(now-downTime)/(float)Math.max(1,ViewConfiguration.getLongPressTimeout()));
        // CSProminentButtonControl maps highlight 0..1 to container scale 1..1.6.
        // Its spring has tension 500, friction 18..20, plus four on release.
        float friction=18f+2f*target+(tracking==null?4f:0f);
        float remain=Math.min(48L,now-lastFrame)*.001f;
        lastFrame=now;
        while (remain>0f) {
            float dt=Math.min(.004f,remain);
            velocity+=((target-highlight)*500f-friction*velocity)*dt;
            highlight+=velocity*dt;
            remain-=dt;
        }
        boolean settled=Math.abs(target-highlight)<.001f && Math.abs(velocity)<.025f;
        if (settled) { highlight=target; velocity=0f; }
        float scale=1f+LiveTune.get("pressGain",PRESS_SCALE_GAIN,.05f,1)*Math.max(0f,Math.min(MAX_HIGHLIGHT,highlight));
        button.setScaleX(scale);
        button.setScaleY(scale);
        if (tracking==null && settled) animated=null;
        else if (!settled || target<1f || tracking==null) button.postOnAnimation(frame);
    }

    private void releasePress() {
        if (tracking==null) return;
        main.removeCallbacks(arm);
        tracking.setPressed(false);
        tracking=null;
        if (animated!=null) {
            animated.removeCallbacks(frame);
            lastFrame=SystemClock.uptimeMillis();
            animated.postOnAnimation(frame);
        }
    }

    private void cancelTouch() {
        if (main!=null) main.removeCallbacks(arm);
        if (tracking!=null) tracking.setPressed(false);
        if (animated!=null) {
            animated.removeCallbacks(frame);
            animated.setScaleX(1f);
            animated.setScaleY(1f);
        }
        animated=tracking=null;
        sequence=armed=false;
        pressedSlot=-1;
        pressedKey=null;
        velocity=highlight=0f;
    }

    void close() {
        if (row==null) return;
        if (!lockPress) cancelTouch();
        sheet.removeView(row);
        sheet=null;row=null;collapse=null;
        buttons[0]=buttons[1]=null;
        reserve(null,0);
    }
}
