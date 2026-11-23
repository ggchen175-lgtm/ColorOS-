package cn.zhe.chaojibeauty;

import android.content.res.ColorStateList;
import android.widget.TextView;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/** Fold existing Oplus groups. ColorOS still owns the foreground, menu, entries and gestures. */
final class NotificationDeck {
    private final NotificationGlass glass;
    private final RectF rearBounds = new RectF();
    private final NotificationDisplay display;
    NotificationDeck(NotificationGlass glass, NotificationDisplay display) {
        this.glass = glass;
        this.display = display;
        display.setDeck(this);
    }
    private boolean ready, enabled;
    private Class<?> rowType;
    private Method children, expanded, headsUp, redacted, swipeExpand, childrenContainer;
    private Method radius, bgHeight, rowHeight, clipTop, clipBottom, requestUpdate, notifyHeight;
    private Method parentRow, showingLayout, contracted, contentHeight, encrypted;
    private Field contentOwner, rowEx, childrenExpanded, onKeyguard;
    private Method expansionProgress, lowPriority, getOverlay, rowOutline, overlayOutline, overlayRadius;
    private Field overlayExtension, dividerHeight;
    private final Matrix anchorToHost = new Matrix(), hostToAnchor = new Matrix();
    private final RectF faceBounds = new RectF(), viewport = new RectF();
    private final Rect nativeClip = new Rect();
    private Field owner, background, listClip, launching, listBlur;
    private final Set<ViewGroup> hosts = Collections.newSetFromMap(new WeakHashMap<>());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<View,Rect> hiddenInk = new WeakHashMap<>();
    private final Map<View,Float> badgeOffsets=new WeakHashMap<>();
    private final Matrix iconTransform=new Matrix();
    private final float[] iconCenter=new float[2];
    private final Rect noInk = new Rect();
    private final Map<View,BadgeColors> badgeColors = new WeakHashMap<>();
    private int pillId, countId;
    private Method groupChildCount;
    private static final ColorStateList LIGHT_BADGE = ColorStateList.valueOf(0xfff8f8f8);
    private static final ColorStateList DARK_BADGE = ColorStateList.valueOf(0xff191919);

    private static final class BadgeColors {
        final ColorStateList background, text;
        final float alpha;
        BadgeColors(View pill, TextView number) {
            background = pill.getBackgroundTintList();
            text = number.getTextColors();
            alpha = number.getAlpha();
        }
    }

    private void updateBadgeColors(View header, boolean restore) {
        if (pillId == 0) {
            pillId = header.getResources().getIdentifier("oplus_expand_button_pill","id","com.android.systemui");
            countId = header.getResources().getIdentifier("oplus_expand_button_number","id","com.android.systemui");
        }
        View pill = header.findViewById(pillId);
        View child = header.findViewById(countId);
        if (pill == null || !(child instanceof TextView)) return;
        TextView count = (TextView)child;
        if (restore) {
            BadgeColors original = badgeColors.remove(header);
            if (original != null) {
                pill.setBackgroundTintList(original.background);
                count.setTextColor(original.text);
                count.setAlpha(original.alpha);
            }
            return;
        }
        if (!badgeColors.containsKey(header)) badgeColors.put(header,new BadgeColors(pill,count));
        boolean dark = GlassAppearance.dark(header);
        ColorStateList tint = dark ? DARK_BADGE : LIGHT_BADGE;
        int text = dark ? Color.WHITE : 0xff343434;
        if (!tint.equals(pill.getBackgroundTintList())) pill.setBackgroundTintList(tint);
        if (count.getCurrentTextColor() != text) count.setTextColor(text);
        if (count.getAlpha() != 1f) count.setAlpha(1f);
    }

    private int expandButtonId, groupIconId, collapsedBadgeId;

    void install(ClassLoader loader) throws ReflectiveOperationException {
        String root = "com.android.systemui.statusbar.notification.";
        rowType = loader.loadClass(root+"row.ExpandableNotificationRow");
        Class<?> bg = loader.loadClass(root+"row.NotificationBackgroundView");
        Class<?> list = loader.loadClass(root+"stack.NotificationStackScrollLayout");
        Class<?> container = loader.loadClass(root+"stack.NotificationChildrenContainer");
        Class<?> algorithm = loader.loadClass(root+"stack.StackScrollAlgorithm");
        Class<?> content = loader.loadClass(root+"row.NotificationContentView");
        contentOwner = XposedHelpers.findField(content,"mContainingNotification");
        rowEx = XposedHelpers.findField(rowType,"mRowEx");
        encrypted = rowEx.getType().getMethod("getShouldEncrypted");
        expansionProgress = rowEx.getType().getMethod("getGroupExpansionProgress");
        childrenExpanded = XposedHelpers.findField(container,"mChildrenExpanded");
        onKeyguard = XposedHelpers.findField(rowType,"mOnKeyguard");
        lowPriority = container.getMethod("showingAsLowPriority");
        parentRow = rowType.getMethod("getNotificationParent");
        showingLayout = rowType.getMethod("getShowingLayout");
        contracted = content.getMethod("getContractedChild");
        contentHeight = content.getMethod("getViewHeight",int.class,boolean.class);
        owner = XposedHelpers.findField(container,"mContainingNotification");
        background = XposedHelpers.findField(rowType,"mBackgroundNormal");
        rowOutline = rowType.getMethod("getClipPath",boolean.class);
        listClip = XposedHelpers.findField(list,"mRoundedClipPath");
        launching = XposedHelpers.findField(list,"mLaunchingNotification");
        listBlur = XposedHelpers.findField(list,"mBlurEffect");
        children = rowType.getMethod("getAttachedChildren");
        expanded = rowType.getMethod("isGroupExpanded");
        childrenContainer = rowType.getMethod("getChildrenContainer");
        groupChildCount=container.getMethod("getUntruncatedChildCount");
        headsUp = rowType.getMethod("isHeadsUpState");
        redacted = rowType.getMethod("shouldShowPublic");
        swipeExpand = container.getMethod("isUserSwipingToExpandRow");
        clipTop = rowType.getMethod("getClipTopAmount");
        rowHeight = rowType.getMethod("getActualHeight");
        clipBottom = rowType.getMethod("getClipBottomAmount");
        radius = rowType.getMethod("getBottomCornerRadius");
        bgHeight = bg.getMethod("getActualHeight");
        requestUpdate = list.getMethod("requestChildrenUpdate$1");
        notifyHeight = rowType.getMethod("notifyHeightChanged",String.class,boolean.class);
        Method gap = null;
        for (Method m : algorithm.getDeclaredMethods()) {
            if (m.getName().equals("getGapHeightForChild") && m.getParameterCount()==6 && m.getReturnType()==float.class) gap=m;
        }
        if (gap==null) throw new NoSuchMethodException("getGapHeightForChild");
        Method maxChildren = container.getDeclaredMethod("getMaxAllowedVisibleChildren",boolean.class);
        Method draw = list.getDeclaredMethod("drawChild",Canvas.class,View.class,long.class);
        Method header=container.getMethod("getGroupHeader");
        Class<?> overlay=loader.loadClass("com.oplus.systemui.statusbar.notification.view.GroupOverlayContainerView");
        Class<?> rowExtension=loader.loadClass("com.oplus.systemui.statusbar.notification.row.OplusExpandableNotificationRowExImpl");
        Method animating=rowExtension.getMethod("isChildrenExpandedAnimating");
        Class<?> overlayImplementation=loader.loadClass("com.oplus.systemui.statusbar.notification.stack.OplusNotificationChildrenContainerOverlayExImpl");
        Method overlayContainer=overlayImplementation.getMethod("getChildrenContainer");
        overlayExtension=XposedHelpers.findField(container,"mOverlayEx");
        dividerHeight=XposedHelpers.findField(container,"mDividerHeight");
        getOverlay=overlayExtension.getType().getMethod("getOverlay");
        overlayOutline=overlay.getSuperclass().getDeclaredMethod("getRoundClipPath");
        overlayOutline.setAccessible(true);
        overlayRadius=overlay.getMethod("getBottomCornerRadius");
        XposedBridge.hookMethod(overlay.getDeclaredMethod("onDraw",Canvas.class),new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                ViewGroup mirror=(ViewGroup)p.thisObject;
                if (!(enabled || !hiddenInk.isEmpty()) || !container.isInstance(mirror.getParent())) return;
                Object summary=owner.get(mirror.getParent());
                View face=null;
                Object layout=null;
                if (enabled && summary!=null && !onKeyguard.getBoolean(summary) && depth(summary)>0) {
                    View first=(View)((List<?>)children.invoke(summary)).get(0);
                    layout=showingLayout.invoke(first);
                    face=(View)contracted.invoke(layout);
                    if (face.getWidth()<=0 || face.getHeight()<=0 || face.getWidth()>mirror.getWidth()) face=null;
                }
                // ColorOS crossfades a separate single-line copy over the real row.
                // Keep its material, mask and animation, replacing only that copy's ink.
                for (int i=0;i<mirror.getChildCount();i++) {
                    View copy=mirror.getChildAt(i);
                    if (face==null) restoreInk(copy);
                    else hideInk(copy);
                }
                if (face!=null) {
                    View groupHeader=(View)header.invoke(mirror.getParent());
                    updateFrontIcon(face,groupHeader,frontContent(layout));
                    Canvas canvas=(Canvas)p.args[0];
                    int save=canvas.save();
                    try {
                        canvas.translate(face.getLeft(),face.getTop());
                        canvas.clipRect(0,0,face.getWidth(),face.getHeight());
                        face.draw(canvas);
                    } finally { canvas.restoreToCount(save); }
                }
            }
        });
        XposedBridge.hookMethod(container.getDeclaredMethod("drawChild",Canvas.class,View.class,long.class),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (!enabled && hiddenInk.isEmpty()) return;
                Object child=p.args[1];
                if (enabled && child==header.invoke(p.thisObject)) {
                    alignBadge((View)child,owner.get(p.thisObject));
                    return;
                }
                if (!rowType.isInstance(child)) return;
                Object layout=showingLayout.invoke(child);
                View face=layout==null ? null : (View)contracted.invoke(layout);
                if (face==null) return;
                if (expandButtonId==0) expandButtonId=face.getResources().getIdentifier("expand_button","id","android");
                View button=face.findViewById(expandButtonId);
                View summary=(View)header.invoke(p.thisObject);
                boolean folded=enabled && summary!=null && summary.getVisibility()==View.VISIBLE && frontContent(layout);
                updateFrontIcon(face,summary,folded);
                if (button!=null) {
                    if (folded) hideInk(button);
                    else restoreInk(button);
                }
            }
        });
        XposedBridge.hookMethod(content.getDeclaredMethod("calculateVisibleType"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (enabled && (int)p.getResult()==3 && frontContent(p.thisObject)) p.setResult(0);
            }
        });
        XposedBridge.hookMethod(content.getDeclaredMethod("getMinHeight",boolean.class),new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (enabled && !(boolean)p.args[0] && frontContent(p.thisObject)) {
                    int height=(int)contentHeight.invoke(p.thisObject,0,false);
                    if (height>0) p.setResult(height);
                }
            }
        });
        // ColorOS also queries the folded endpoint while the group is open or animating.
        XposedBridge.hookMethod(container.getDeclaredMethod("getMinHeight",int.class,int.class,boolean.class),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (enabled && !(boolean)p.args[2] && (int)p.args[0]>0) useFaceHeight(p);
            }
        });
        XposedBridge.hookMethod(container.getDeclaredMethod("getIntrinsicHeight"),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (enabled && !childrenExpanded.getBoolean(p.thisObject) && !(boolean)swipeExpand.invoke(p.thisObject)) useFaceHeight(p);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (enabled && childrenExpanded.getBoolean(p.thisObject) && !(boolean)swipeExpand.invoke(p.thisObject)
                        && depth(owner.get(p.thisObject))>0) {
                    // The multi-child OEM expanded endpoint includes one trailing divider.
                    // The outer list already spaces the next row.
                    p.setResult((int)p.getResult()-dividerHeight.getInt(p.thisObject));
                }
            }
        });
        // A contracted child already has its own vertical padding. Align its origin
        // with the summary background, keeping the OEM expanded header geometry.
        Class<?> ext=loader.loadClass("com.oplus.systemui.statusbar.notification.stack.NotificationChildrenContainerExtImp");
        XposedBridge.hookMethod(ext.getDeclaredMethod("getGroupTopMargin",boolean.class,boolean.class,float.class,
                int.class,boolean.class,boolean.class,container),new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!enabled || (!(boolean)p.args[0] && (boolean)p.args[1]) || foldedFaceHeight(p.args[6])<=0) return;
                if ((boolean)p.args[0]) p.setResult(Math.round((int)p.args[3]*(float)p.args[2]));
                else if (!(boolean)p.args[1]) p.setResult(0);
            }
        });
        XposedBridge.hookMethod(ext.getDeclaredMethod("getGroupTopPaddingWhenCollapsed",int.class,boolean.class,container),
                new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (enabled && foldedFaceHeight(p.args[2])>0) p.setResult(0);
            }
        });
        XposedBridge.hookMethod(maxChildren,new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!enabled || (int)p.getResult()<=1 || (boolean)lowPriority.invoke(p.thisObject)) return;
                Object parent=owner.get(p.thisObject);
                if (parent==null) return;
                if (!(boolean)p.args[0] && (childrenExpanded.getBoolean(p.thisObject) || (boolean)swipeExpand.invoke(p.thisObject)
                        || (boolean)animating.invoke(rowEx.get(parent)))) return;
                if (depth(parent)>0) p.setResult(1);
            }
        });
        XposedBridge.hookMethod(gap,new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!enabled) return;
                int depth=rearDepth((View)p.args[3]); // previous row in scroll and size calculation
                if (depth>0 && (display.ownsAppGroup((View)p.args[3]) || !(boolean)expanded.invoke(p.args[3]))) p.setResult((float)p.getResult()+tail((View)p.args[3],depth));
            }
        });
        XposedBridge.hookMethod(draw,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                ViewGroup host=(ViewGroup)p.thisObject;
                hosts.add(host);
                if (enabled) drawLayers(host,(Canvas)p.args[0],(View)p.args[1]);
            }
        });
        XC_MethodHook rowMotion=new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (enabled) invalidateDeck((View)p.thisObject);
            }
        };
        XposedBridge.hookMethod(rowType.getMethod("setTranslation",float.class),rowMotion);
        XposedBridge.hookMethod(rowType.getDeclaredMethod("setTransitionAlpha",float.class),rowMotion);
        Class<?> expandableExtension=loader.loadClass("com.oplus.systemui.statusbar.notification.row.OplusExpandableViewExImpl");
        Method boundView=expandableExtension.getMethod("getView");
        XC_MethodHook onPressGeometry=new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!enabled) return;
                Object row=boundView.invoke(p.thisObject);
                if (rowType.isInstance(row)) invalidateDeck((View)row);
            }
        };
        XposedBridge.hookMethod(expandableExtension.getDeclaredMethod("setClipWidth",int.class),onPressGeometry);
        XposedBridge.hookMethod(expandableExtension.getDeclaredMethod("processAnimationToViewOrigin"),onPressGeometry);
        XposedBridge.hookMethod(overlayImplementation.getDeclaredMethod("onGroupExpansionProgressChange",float.class),new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!enabled) return;
                Object group=overlayContainer.invoke(p.thisObject);
                if (group!=null) invalidateDeck((View)owner.get(group));
            }
        });
        ready=true;
    }
    private void invalidateDeck(View row) throws ReflectiveOperationException {
        if (row==null || !hosts.contains(row.getParent())) return;
        List<?> entries=(List<?>)children.invoke(row);
        if (entries!=null && entries.size()>1) ((View)row.getParent()).invalidate();
    }
    private final View.OnAttachStateChangeListener releaseOnDetach=new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) {}
        @Override public void onViewDetachedFromWindow(View v) { restoreInk(v); }
    };
    private void hideInk(View view) {
        if (hiddenInk.containsKey(view)) return;
        hiddenInk.put(view,view.getClipBounds());
        view.setClipBounds(noInk);
        view.addOnAttachStateChangeListener(releaseOnDetach);
    }
    private void restoreInk(View button) {
        if (!hiddenInk.containsKey(button)) return;
        button.setClipBounds(hiddenInk.remove(button));
        button.removeOnAttachStateChangeListener(releaseOnDetach);
    }
    // Use the same coordinate space for both centers. The child icon keeps its layout
    // even when clipped. ColorOS animates icon_container inside this badge wrapper.
    private float centerY(View icon,View parent) {
        iconTransform.reset();
        icon.transformMatrixToGlobal(iconTransform);
        parent.transformMatrixToLocal(iconTransform);
        iconCenter[0]=icon.getWidth()*.5f;
        iconCenter[1]=icon.getHeight()*.5f;
        iconTransform.mapPoints(iconCenter);
        return iconCenter[1];
    }
    private void alignBadge(View header,Object row) throws ReflectiveOperationException {
        if (collapsedBadgeId==0) collapsedBadgeId=header.getResources().getIdentifier(
                "oplus_notification_collapsed_group_header","id","com.android.systemui");
        View badge=header.findViewById(collapsedBadgeId);
        if (badge==null || !(badge.getParent() instanceof View)) return;
        updateBadgeColors(badge,false);
        float offset=0f;
        Float previous=badgeOffsets.get(badge);
        float old=previous==null?0f:previous;
        if (depth(row)>0) {
            float fold=1f-Math.max(0f,Math.min(1f,(float)expansionProgress.invoke(rowEx.get(row))));
            if (fold>0f) {
                Object first=((List<?>)children.invoke(row)).get(0);
                View face=(View)contracted.invoke(showingLayout.invoke(first));
                View icon=face.findViewById(android.R.id.icon);
                View groupIcon=groupIcon(badge);
                if (icon instanceof ImageView && groupIcon instanceof ImageView
                        && icon.getHeight()>0 && groupIcon.getHeight()>0) {
                    View parent=(View)badge.getParent();
                    offset=fold*(old+centerY(icon,parent)-centerY(groupIcon,parent));
                }
            }
        }
        if (Math.abs(offset-old)<.05f && !(offset==0f && previous!=null)) return;
        badge.setTranslationY(badge.getTranslationY()+offset-old);
        if (offset==0f) badgeOffsets.remove(badge); else badgeOffsets.put(badge,offset);
    }
    private View groupIcon(View root) {
        if (groupIconId==0) groupIconId=root.getResources().getIdentifier("icon","id","com.android.systemui");
        return root.findViewById(groupIconId);
    }
    private void updateFrontIcon(View face,View header,boolean folded) {
        View appIcon=face.findViewById(android.R.id.icon);
        if (!(appIcon instanceof ImageView)) return;
        if (folded && header!=null) {
            View groupIcon=groupIcon(header);
            if (groupIcon instanceof ImageView && ((ImageView)groupIcon).getDrawable()!=null
                    && groupIcon.getWidth()>0 && groupIcon.isShown()) {
                // The OEM header draws this icon together with the group count.
                View v=groupIcon;
                while (v.getAlpha()>0f && v.getTransitionAlpha()>0f) {
                    if (v==header) { hideInk(appIcon); return; }
                    if (!(v.getParent() instanceof View)) break;
                    v=(View)v.getParent();
                }
            }
        }
        restoreInk(appIcon);
    }
    private boolean frontContent(Object layout) throws ReflectiveOperationException {
        Object row=contentOwner.get(layout);
        if (row==null || (boolean)redacted.invoke(row) || contracted.invoke(layout)==null) return false;
        Object parent=parentRow.invoke(row);
        if (depth(parent)==0 || (boolean)expanded.invoke(parent) || ((List<?>)children.invoke(parent)).get(0)!=row) return false;
        Object extension=rowEx.get(row);
        return extension!=null && !(boolean)encrypted.invoke(extension) && showingLayout.invoke(row)==layout;
    }

    private int foldedFaceHeight(Object container) throws ReflectiveOperationException {
        Object row=owner.get(container);
        if (depth(row)==0) return 0;
        Object first=((List<?>)children.invoke(row)).get(0);
        Object layout=showingLayout.invoke(first);
        return (int)contentHeight.invoke(layout,0,false);
    }
    private void useFaceHeight(XC_MethodHook.MethodHookParam p) throws ReflectiveOperationException {
        int height=foldedFaceHeight(p.thisObject);
        if (height>0) p.setResult(height);
    }

    void configure(boolean on) {
        if (!ready || enabled==on) return;
        enabled=on;
        if (!enabled) {
            for (View button:new ArrayList<>(hiddenInk.keySet())) restoreInk(button);
            for (Map.Entry<View,Float> e:badgeOffsets.entrySet()) {
                View badge=e.getKey();
                if (badge!=null) badge.setTranslationY(badge.getTranslationY()-e.getValue());
            }
            badgeOffsets.clear();
            for (View badge:new ArrayList<>(badgeColors.keySet())) updateBadgeColors(badge,true);
            badgeColors.clear();
        }
        for (ViewGroup host:new ArrayList<>(hosts)) {
            if (host==null || !host.isAttachedToWindow()) continue;
            try {
                for (int i=0;i<host.getChildCount();i++) {
                    View view=host.getChildAt(i);
                    if (depth(view)>0) { view.requestLayout(); notifyHeight.invoke(view,"ChaojiBeauty",false); }
                }
                requestUpdate.invoke(host);
                host.requestLayout();
                host.invalidate();
            } catch (ReflectiveOperationException e) { Log.w("ChaojiBeauty","Notification layout refresh",e); }
        }
    }
    private int depth(Object row) throws ReflectiveOperationException {
        if (!rowType.isInstance(row) || display.hidesStackRear((View)row)) return 0;
        List<?> entries=(List<?>)children.invoke(row);
        if (entries==null || entries.size()<2
                || (boolean)headsUp.invoke(row) || (boolean)redacted.invoke(row)) return 0;
        Object container=childrenContainer.invoke(row), first=entries.get(0);
        if(container==null || (boolean)lowPriority.invoke(container) || (boolean)redacted.invoke(first))return 0;
        int count=Math.min((int)groupChildCount.invoke(container),display.groupSizeLimit((View)row));
        if(count<2 || hasZeroBadge((View)container))return 0;
        Object layout=showingLayout.invoke(first), extension=rowEx.get(first);
        if (layout==null || contracted.invoke(layout)==null || extension==null || (boolean)encrypted.invoke(extension)) return 0;
        return Math.min(2,Math.min(entries.size(),count)-1);
    }
    private boolean hasZeroBadge(View container){
        if(countId==0)countId=container.getResources().getIdentifier("oplus_expand_button_number","id","com.android.systemui");
        View badge=container.findViewById(countId);
        if(!(badge instanceof TextView))return false;
        String number=((TextView)badge).getText().toString().trim();
        return number.length()==1 && Character.digit(number.charAt(0),10)==0;
    }

    private int rearDepth(View row) throws ReflectiveOperationException {
        if(row==null || display.hidesStackRear(row))return 0;
        if(display.ownsAppGroup(row))return Math.min(2,display.appGroupSize(row)-1);
        return depth(row);
    }

    float visibleTail(View row) throws ReflectiveOperationException {
        if(!enabled || !rowType.isInstance(row))return 0;
        int depth=rearDepth(row);
        if(depth<=0)return 0;
        float folded=display.ownsAppGroup(row)?1f:1f-Math.max(0f,Math.min(1f,(float)expansionProgress.invoke(rowEx.get(row))));
        return tail(row,depth)*folded;
    }

    static float tail(View row,int layer) {
        return NotificationViewport.dp(row,layer==1?7:11)*LiveTune.get("rear",1,0,2.5f);
    }
    static float rearInset(View view,int layer){
        return NotificationViewport.pixels(view,7)*layer;
    }
    static float rearOpacity(int layer){return layer==1?.65f:.35f;}

    private void drawLayers(ViewGroup host,Canvas canvas,View row) throws ReflectiveOperationException {
        float rowAlpha=row.getAlpha()*row.getTransitionAlpha();
        if (rowAlpha<=0f) return;
        boolean appGroup=display.ownsAppGroup(row);
        int count=rearDepth(row);
        while(count>0 && !display.hasRoomForRear(row,tail(row,count)))count--;
        if (count<=0 || row.getVisibility()!=View.VISIBLE || launching.getBoolean(host) || listBlur.get(host)!=null
                || (int)clipBottom.invoke(row)>0) return;
        // The tails attach to the bottom edge, which can remain visible under a top clip.
        int clippedTop=(int)clipTop.invoke(row);
        if (clippedTop>0 && clippedTop>=(int)rowHeight.invoke(row)) return;
        float fold=appGroup?1f:1f-Math.max(0f,Math.min(1f,(float)expansionProgress.invoke(rowEx.get(row))));
        if (fold<=0) return;
        View bg=(View)background.get(row);
        if (bg==null) return;
        boolean useOverlay=bg.getVisibility()!=View.VISIBLE;
        if (useOverlay) {
            if(appGroup)return;
            bg=(View)getOverlay.invoke(overlayExtension.get(childrenContainer.invoke(row)));
            if (bg==null || bg.getVisibility()!=View.VISIBLE || bg.getAlpha()<=0) return;
        }
        View anchor=useOverlay ? bg : row;
        Path outline=useOverlay ? (Path)overlayOutline.invoke(bg) : (Path)rowOutline.invoke(row,false);
        if (outline.isEmpty()) return;
        outline.computeBounds(faceBounds,true);
        if (anchor.getClipBounds(nativeClip) && !faceBounds.intersect(nativeClip.left,nativeClip.top,nativeClip.right,nativeClip.bottom)) return;
        float w=anchor.getWidth();
        float restHeight=useOverlay ? faceBounds.height() : (int)bgHeight.invoke(bg);
        float corner=useOverlay ? (float)overlayRadius.invoke(bg) : (float)radius.invoke(row);
        if (w<=0 || restHeight<=0 || corner<=0) return;
        // Both rear cards use the face's current local outline and its transform exactly once.
        anchorToHost.reset();
        anchor.transformMatrixToGlobal(anchorToHost);
        host.transformMatrixToLocal(anchorToHost);
        if (!anchorToHost.invert(hostToAnchor)) return;
        viewport.set(row.getLeft()-host.getScrollX(),0,row.getRight()-host.getScrollX(),host.getHeight());
        hostToAnchor.mapRect(viewport);
        float visibleLeft=Math.max(faceBounds.left,viewport.left), visibleRight=Math.min(faceBounds.right,viewport.right);
        if (visibleRight<=visibleLeft) return;
        float h=faceBounds.bottom;
        float tailScale=fold*Math.min(1f,faceBounds.height()/restHeight);
        int save=canvas.save();
        try {
            Path mask=(Path)listClip.get(host);
            if (!display.drawingMovingRow(host,row)) {
                if (mask!=null && !mask.isEmpty()) canvas.clipPath(mask);
                canvas.clipRect(row.getLeft()-host.getScrollX(),0,row.getRight()-host.getScrollX(),host.getHeight());
            }
            canvas.concat(anchorToHost);
            float overlap=Math.min(NotificationViewport.pixels(row,.6f),tail(row,1)*tailScale);
            // The native swipe clips the face but keeps its radius. Compress the
            // shoulders only when a rear card would run out of room for its corners.
            float fit=Math.min(1f,(visibleRight-visibleLeft)/(2*(corner+rearInset(row,count))));
            canvas.clipRect(visibleLeft,h-overlap,visibleRight,h+tail(row,count)*tailScale);
            for (int layer=count;layer>0;layer--) {
                float inset=rearInset(row,layer)*fit;
                float bottom=h+tail(row,layer)*tailScale;
                rearBounds.set(visibleLeft+inset,bottom-faceBounds.height(),visibleRight-inset,bottom);
                int back=canvas.saveLayerAlpha(visibleLeft,h-overlap,visibleRight,bottom,
                        Math.round(255*rowAlpha*bg.getAlpha()*rearOpacity(layer)));
                glass.drawStackLayer(anchor,canvas,rearBounds,corner,paint,layer);
                canvas.restoreToCount(back);
            }
        } finally { canvas.restoreToCount(save); }
    }
}
