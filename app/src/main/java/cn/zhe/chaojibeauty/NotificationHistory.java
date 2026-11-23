package cn.zhe.chaojibeauty;

import android.app.Notification;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import static de.robv.android.xposed.XposedHelpers.*;

/** Presents native notification rows in two sections of the unlocked left shade. */
final class NotificationHistory implements ViewTreeObserver.OnPreDrawListener {
    private static final String TAG = "ChaojiHistory";
    private static final String BASE = "com.android.systemui.statusbar.notification.";
    private final Map<Object, Long> history = Collections.synchronizedMap(new WeakHashMap<>());
    private final WeakHashMap<Object, Long> observed = new WeakHashMap<>();
    private final NotificationGlass glass;
    private HistoryClearControl control;
    private boolean clearVisible;
    private boolean confirmingClearAll;

    final LockStackSummary compact;
    private final Map<View,Integer> stackCounts=new IdentityHashMap<>();
    private final Map<String,String> appNames=new HashMap<>();
    private final Map<View,Integer> totalCounts=new IdentityHashMap<>();
    private final Map<View,Integer> renderedGroupSizes=new IdentityHashMap<>();
    private final Map<View,String> appGroupHeads=new IdentityHashMap<>();
    private final Map<View,View> groupPreviews=new IdentityHashMap<>();
    View groupPreview(View front){return groupPreviews.get(front);}
    private final Set<String> openedAppGroups=new HashSet<>();
    private final Set<View> foldedHistoryGroups=Collections.newSetFromMap(new WeakHashMap<>());
    private Object vendorMediaType;

    boolean ownsAppGroup(View row){return appGroupHeads.containsKey(row);}

    void openAppGroup(View row){
        String key=appGroupHeads.get(row);
        if(key==null || stack==null || !openedAppGroups.add(key))return;
        replayAtBoundary(stack,false);
        callMethod(stack,"updateContentHeight");
        callMethod(stack,"requestChildrenUpdate$1");
        row.announceForAccessibility("已展开通知");
    }

    void resetExpandedHistoryGroups(){
        foldedHistoryGroups.clear();
        nextGroupScan=0;
        if(openedAppGroups.isEmpty())return;
        openedAppGroups.clear();
        if(stack!=null)replayAtBoundary(stack,false);
    }
    int renderedGroupSize(View row){return renderedGroupSizes.getOrDefault(row,1);}
    int totalCount(View row){return totalCounts.getOrDefault(row,1);}
    private long stackRevision;
    long stackRevision(){return stackRevision;}
    int stackCount(View row){return stackCounts.getOrDefault(row,0);}
    boolean canShowStackDetails(View row){
        if(!isCard(row) || (boolean)callMethod(row,"shouldShowPublic"))return false;
        Object extension=getObjectField(row,"mRowEx");
        return extension!=null && !(boolean)callMethod(extension,"getShouldEncrypted");
    }

    CharSequence stackPreviewTitle(View row){
        if(!canShowStackDetails(row))return stackSource(row);
        Notification notification=sbn(callMethod(row,"getEntry")).getNotification();
        CharSequence title=notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        return android.text.TextUtils.isEmpty(title)?stackSource(row):title;
    }

    CharSequence stackPreviewTime(View row){
        if(!isCard(row))return "";
        long when=sbn(callMethod(row,"getEntry")).getPostTime();
        return android.text.format.DateFormat.getTimeFormat(row.getContext()).format(new java.util.Date(when));
    }

    android.graphics.drawable.Drawable stackPreviewIcon(View row){
        if(!isCard(row))return null;
        StatusBarNotification post=sbn(callMethod(row,"getEntry"));
        android.content.Context context=row.getContext();
        try{
            if(!post.getUser().equals(android.os.Process.myUserHandle()))
                context=(android.content.Context)callMethod(context,"createContextAsUser",post.getUser(),0);
            return context.getPackageManager().getApplicationIcon(post.getPackageName()).mutate();
        }catch(android.content.pm.PackageManager.NameNotFoundException | SecurityException unavailable){
            android.graphics.drawable.Icon large=post.getNotification().getLargeIcon();
            return large!=null && canShowStackDetails(row)?large.loadDrawable(context):null;
        }
    }

    String stackSource(View row){
        if(!isCard(row))return "实时活动";
        Object entry=callMethod(row,"getEntry");
        if(entry==null)return "通知";
        String pkg=sbn(entry).getPackageName();
        String name=appNames.get(pkg);
        if(name==null){
            android.content.pm.PackageManager pm=row.getContext().getPackageManager();
            try{name=pm.getApplicationLabel(pm.getApplicationInfo(pkg,0)).toString();}
            catch(android.content.pm.PackageManager.NameNotFoundException ignored){name=pkg;}
            appNames.put(pkg,name);
        }
        return name;
    }
    private long newest(Object node){
        long newest=0;
        for(Object entry:members.get(node)){
            StatusBarNotification n=sbn(entry);
            if((n.getNotification().flags & Notification.FLAG_GROUP_SUMMARY)==0)
                newest=Math.max(newest,n.getPostTime());
        }
        return newest;
    }

    private final ShadeQuickActions actions;
    NotificationHistory(NotificationGlass glass, ShadeQuickActions actions) {
        this.glass = glass;
        compact=new LockStackSummary(glass,this);
        this.actions = actions;
    }

    private final Rect visible = new Rect();
    private Class<?> rowType, headerType, headerControllerType, specType;
    private ViewGroup stack, orderingStack;
    private View sectionHeader;
    private LockStylePreview preview;
    private final Set<View> olderRows = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<View> ordinaryRows = Collections.newSetFromMap(new IdentityHashMap<>());
    private final int[] previewLocation = new int[2], stackLocation = new int[2];
    private int displayStyle = NotificationDisplay.STACK;
    private TextView countView, headerLabel;
    Runnable revealCount, parkClearedRows;
    private NotificationClearExit clearExit;
    private boolean parkedAfterClear;
    private final Set<View> styledGroups = Collections.newSetFromMap(new WeakHashMap<>());
    private long nextGroupScan;
    int style() { return displayStyle; }
    private boolean explicitCount;
    void setStyle(int style, boolean countPreference) {
        if (displayStyle == style && explicitCount == countPreference) return;
        explicitCount = countPreference;
        displayStyle = style;
        styledGroups.clear();
        nextGroupScan = 0;
        if (stack != null && headerController != null) replayAtBoundary(stack,true);
    }
    private View nativeButton;
    private Object nativeButtonController, differ, spec, headerController;
    private BooleanSupplier fullyOpen;
    private final Map<Object,List<Object>> members = new IdentityHashMap<>();
    private List<Object> recentNodes = Collections.emptyList(), historyNodes = Collections.emptyList();
    private boolean ready, applying;
    private long nextObservation;
    private final Runnable expirePrompt = this::cancelConfirmation;

    private void installHistoryAgePolicy(ClassLoader loader){
        try{
            Class<?> provider=loader.loadClass("com.oplus.systemui.statusbar.notification.interruption.OplusKeyguardNotificationVisibilityProviderExImpl");
            Class<?> debug=loader.loadClass("com.oplus.systemui.notification.debug.OplusNotificationDebug");
            java.lang.reflect.Method age=debug.getDeclaredMethod("getSkipCheckPostedBeforeLock");
            java.lang.reflect.Method filter=provider.getDeclaredMethod("shouldHideNotification",loader.loadClass(BASE+"collection.NotificationEntry"));
            ThreadLocal<Boolean> archiveCheck=new ThreadLocal<>();
            XposedBridge.hookMethod(filter,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    p.setObjectExtra("priorAgeCheck",archiveCheck.get());
                    archiveCheck.set(enabled && displayStyle==NotificationDisplay.STACK && seenEntry(p.args[0]));
                }
                @Override protected void afterHookedMethod(MethodHookParam p){
                    Boolean prior=(Boolean)p.getObjectExtra("priorAgeCheck");
                    if(prior==null)archiveCheck.remove();else archiveCheck.set(prior);
                }
            });
            XposedBridge.hookMethod(age,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(Boolean.TRUE.equals(archiveCheck.get()))p.setResult(true);
                }
            });
        }catch(ReflectiveOperationException e){
            Log.w(TAG,"Native keyguard history age policy unavailable",e);
        }
    }

    void install(ClassLoader loader) throws ReflectiveOperationException {
        installHistoryAgePolicy(loader);
        rowType = loader.loadClass(BASE + "row.ExpandableNotificationRow");
        Class<?> vendorRow=loader.loadClass("com.oplus.systemui.statusbar.notification.customcard.OplusCustomRow");
        vendorMediaType=getStaticObjectField(vendorRow,"Companion");
        Class<?> clicker=loader.loadClass(BASE+"NotificationClicker");
        XposedBridge.hookMethod(clicker.getDeclaredMethod("onClick",View.class),new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                View row=(View)p.args[0];
                if(row==null || row.getParent()!=stack || !ownsAppGroup(row) || (boolean)callMethod(row,"areGutsExposed"))return;
                openAppGroup(row);
                p.setResult(null);
            }
        });
        headerType = loader.loadClass(BASE + "stack.SectionHeaderView");
        headerControllerType = loader.loadClass(BASE + "collection.render.SectionHeaderNodeControllerImpl");
        specType = loader.loadClass(BASE + "collection.render.NodeSpecImpl");
        Class<?> specInterface = loader.loadClass(BASE + "collection.render.NodeSpec");
        Class<?> buttonMaterial = loader.loadClass("com.oplus.systemui.notification.row.oplusgroup.ExpandGroupHeaderButtonBlurHelper");
        XposedBridge.hookMethod(buttonMaterial.getDeclaredMethod("apply", android.content.Context.class, ImageView.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (control != null && control.owns(p.args[1])) p.setResult(null);
            }
        });
        // SystemUI would otherwise keep a second, stationary transient view
        // fading behind the moving copy after the collection removes the entry.
        Class<?> scroller=loader.loadClass(BASE+"stack.NotificationStackScrollLayout");
        XposedBridge.hookMethod(scroller.getDeclaredMethod("generateRemoveAnimation",loader.loadClass(BASE+"row.ExpandableView")),new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (clearExit!=null && p.thisObject==stack && clearExit.owns((View)p.args[0])) p.setResult(false);
            }
        });
        Class<?> clear = loader.loadClass("com.oplus.systemui.notification.clearall.ClearAllController");
        Class<?> button = loader.loadClass("com.oplus.systemui.notification.clearall.OplusClearAllButton");
        XposedBridge.hookMethod(clear.getDeclaredMethod("setVisible", button, boolean.class, boolean.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                nativeButtonController = p.thisObject;
                if (nativeButton != null && nativeButton == p.args[0] && headerController != null) {
                    p.args[1] = false;
                    p.args[2] = false;
                }
            }
        });
        Class<?> ordering = loader.loadClass("com.oplus.systemui.statusbar.notification.collection.render.ShadeViewDifferExImpl");
        XposedBridge.hookMethod(ordering.getDeclaredMethod("isSupportToFirst", View.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                View v = (View)p.args[0];
                if (orderingStack != null && v != null && v.getParent() == orderingStack
                        && (rowType.isInstance(v) || headerType.isInstance(v))) p.setResult(true);
            }
        });
        Class<?> diff = loader.loadClass(BASE + "collection.render.ShadeViewDiffer");
        XposedBridge.hookMethod(diff.getDeclaredMethod("applySpec", specInterface), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                differ = p.thisObject;
                spec = p.args[0];
                if (stack == null || headerController == null) return;
                applying = true;
                List<Object> children = children(spec);
                ArrayList<Object> original = new ArrayList<>(children);
                try {
                    section(children);
                    orderingStack = stack;
                    p.setObjectExtra("original", original);
                } catch (RuntimeException | LinkageError | XposedHelpers.InvocationTargetError e) {
                    children.clear();
                    children.addAll(original);
                    Log.e(TAG, "Cannot present notification sections", e);
                    abandon();
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                @SuppressWarnings("unchecked") List<Object> original = (List<Object>)p.getObjectExtra("original");
                if (original != null) {
                    List<Object> children = children(p.args[0]);
                    children.clear();
                    children.addAll(original);
                }
                orderingStack = null;
                applying = false;
            }
        });
        ready = true;
    }

    boolean ownsHeader(View view) { return view != null && view == sectionHeader; }

    void open(ViewGroup list, View button, BooleanSupplier expanded, LockStylePreview preview) {
        if (!ready || button == null) return;
        stack = list;
        nativeButton = button;
        fullyOpen = expanded;
        this.preview = preview;
        try {
            headerController = newInstance(headerControllerType, "Chaoji Notification Center",
                    LayoutInflater.from(list.getContext()), android.R.string.untitled, null, "");
            callMethod(headerController, "reinflateView", list);
            View header = (View)callMethod(headerController, "getView");
            sectionHeader = header;
            TextView label = headerLabel = (TextView)getObjectField(header, "mLabelView");
            label.setText("通知中心");
            label.setTextSize(20);
            label.setTextColor(glass.palette(label).panelInk);
            label.setShadowLayer(NotificationViewport.pixels(label,1.5f),0,
                    NotificationViewport.pixels(label,.5f),0x99142235);
            label.setOnClickListener(v -> {
                cancelConfirmation();
                setClearVisible(hasClearableNotifications());
            });
            label.setEnabled(true);
            label.setAccessibilityDelegate(null);
            label.setFocusable(true);
            ImageView clear = (ImageView)getObjectField(header, "mClearAllButton");
            control = new HistoryClearControl(clear, glass, label);
            control.show(false);
            callMethod(headerController, "setOnClearSectionClickListener", (View.OnClickListener)v -> onClearPressed());
            list.getViewTreeObserver().addOnPreDrawListener(this);
            if (nativeButtonController != null) callMethod(nativeButtonController, "updateVisibility", false);
            replayAtBoundary(list, true);
        } catch (RuntimeException | LinkageError | XposedHelpers.InvocationTargetError e) {
            Log.e(TAG, "Cannot attach history header", e);
            abandon();
        }
    }

    void close() {
        if (stack == null) return;
        ViewGroup list = stack;
        orderingStack = list;
        abandon();
        try { replayAtBoundary(list, false); } finally { orderingStack = null; }
    }

    private void abandon() {
        compact.detach();totalCounts.clear();renderedGroupSizes.clear();stackCounts.clear();appNames.clear();
        appGroupHeads.clear();groupPreviews.clear();openedAppGroups.clear();foldedHistoryGroups.clear();
        if (stack != null && stack.getViewTreeObserver().isAlive()) stack.getViewTreeObserver().removeOnPreDrawListener(this);
        cancelConfirmation();
        if (countView != null && countView.getParent() instanceof ViewGroup)
            ((ViewGroup)countView.getParent()).removeView(countView);
        countView = null;
        footerProgress=0;
        availableCount=incomingCount=footerCount=0;
        styledGroups.clear();
        sectionHeader = null;
        headerLabel = null;
        ordinaryRows.clear();
        olderRows.clear();
        preview = null;
        control = null;
        clearVisible = false;
        stack = null;
        nativeButton = null;
        headerController = null;
        fullyOpen = null;
        members.clear();
        recentNodes = historyNodes = Collections.emptyList();
        parkedAfterClear = false;
        if (clearExit != null) { clearExit.finish(); clearExit = null; }
        if (nativeButtonController != null) callMethod(nativeButtonController, "updateVisibility", false);
    }

    private ViewGroup collectionHost;
    private android.content.SharedPreferences archivedPosts;
    void watchCollection(ViewGroup host){
        collectionHost=host;
        archivedPosts=host.getContext().createDeviceProtectedStorageContext()
                .getSharedPreferences("chaoji_lock_archived_posts",android.content.Context.MODE_PRIVATE);
    }
    volatile boolean enabled;
    void userPresent() {
        history.putAll(observed);
        if(collectionHost!=null){
            Object controller=getObjectField(collectionHost,"mController");
            Object collection=controller==null?null:getObjectField(controller,"mNotifCollection");
            if(collection!=null){
                android.content.SharedPreferences.Editor snapshot=archivedPosts.edit().clear();
                for(Object entry:(Collection<?>)callMethod(collection,"getAllNotifs")){
                    StatusBarNotification post=sbn(entry);
                    history.put(entry,post.getPostTime());
                    snapshot.putLong(post.getKey(),post.getPostTime());
                }
                snapshot.apply();
            }
        }
        observed.clear();
    }

    void forgetObservations() {
        history.clear();
        observed.clear();
        if(archivedPosts!=null && !archivedPosts.getAll().isEmpty())archivedPosts.edit().clear().apply();
    }

    private void replayAtBoundary(ViewGroup list, boolean opening) {
        if (differ == null || spec == null || applying) return;
        boolean animations = getBooleanField(list, "mAnimationsEnabled");
        try {
            // Native changeViewPosition() otherwise enqueues a separate animation
            // for each row moved when entering or leaving this presentation.
            setBooleanField(list, "mAnimationsEnabled", false);
            callMethod(differ, "applySpec", spec);
            if (opening) callMethod(list, "resetScrollPosition");
        } finally {
            setBooleanField(list, "mAnimationsEnabled", animations);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> children(Object node) { return (List<Object>)callMethod(node, "getChildren"); }
    private static View view(Object node) { return (View)callMethod(callMethod(node, "getController"), "getView"); }
    private static StatusBarNotification sbn(Object entry) { return (StatusBarNotification)callMethod(entry, "getSbn"); }


    // ColorOS materializes only part of a large group as row views. Resolve the
    // remaining members by the system grouping key, without claiming promoted top-level rows.
    private void indexMembers(List<Object> nodes) {
        members.clear();
        renderedGroupSizes.clear();
        Object controller = getObjectField(stack, "mController");
        if (controller == null) return;
        Object collection = getObjectField(controller, "mNotifCollection");
        Map<String,List<Object>> groups = new HashMap<>();
        Set<Object> separate = new HashSet<>();
        for (Object node : nodes) {
            View row = view(node);
            if (rowType.isInstance(row) && !(boolean)callMethod(node, "getKeepGone")) separate.add(callMethod(row, "getEntry"));
        }
        Set<Object> active=new HashSet<>((Collection<?>)callMethod(collection,"getAllNotifs"));
        for (Object entry : active) {
            StatusBarNotification n = sbn(entry);
            if (n.isGroup() && !separate.contains(entry))
                groups.computeIfAbsent(n.getGroupKey(), key -> new ArrayList<>()).add(entry);
        }
        for (Object node : nodes) {
            View row = view(node);
            if (!rowType.isInstance(row)) continue;
            Object entry = callMethod(row, "getEntry");
            if (entry == null) continue;
            LinkedHashSet<Object> values = new LinkedHashSet<>();
            values.add(entry);
            for (Object child : children(node)) {
                if (rowType.isInstance(view(child)) && !(boolean)callMethod(child,"getKeepGone")) {
                    Object childEntry = callMethod(view(child), "getEntry");
                    if (active.contains(childEntry)) values.add(childEntry);
                }
            }
            int represented=0;
            for(Object member:values){
                if((sbn(member).getNotification().flags & Notification.FLAG_GROUP_SUMMARY)==0)represented++;
            }
            renderedGroupSizes.put(row,represented);
            StatusBarNotification n = sbn(entry);
            if (preview != null && (n.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0)
                values.addAll(groups.getOrDefault(n.getGroupKey(), Collections.emptyList()));
            members.put(node, new ArrayList<>(values));
        }
    }

    private boolean seenEntry(Object entry) {
        Long stamp = history.get(entry);
        StatusBarNotification post=sbn(entry);
        return stamp!=null && stamp.longValue()==post.getPostTime()
                || archivedPosts!=null && archivedPosts.getLong(post.getKey(),-1)==post.getPostTime();
    }

    private boolean wasSeen(Object node) {
        List<Object> entries = members.get(node);
        if (entries == null) return false;
        for (Object entry : entries) {
            if (!seenEntry(entry)) return false;
        }
        return true;
    }

    private boolean canPresentChild(Object node){
        View row=view(node);
        return rowType.isInstance(row) && !(boolean)callMethod(node,"getKeepGone")
                && !(boolean)callMethod(row,"shouldShowPublic")
                && !(boolean)callMethod(getObjectField(row,"mRowEx"),"getShouldEncrypted");
    }

    private void unpackGroups(List<Object> nodes){
        // Only lift children already supplied by the native lock-screen pipeline.
        // Protected groups retain the system's redacted presentation.
        ArrayList<Object> flat=new ArrayList<>(nodes.size());
        for(Object group:nodes){
            List<Object> rows=children(group);
            if(rows.isEmpty() || !canPresentChild(group) || !rows.stream().allMatch(this::canPresentChild)){
                flat.add(group);
                continue;
            }
            boolean summary=(sbn(callMethod(view(group),"getEntry")).getNotification().flags
                    & Notification.FLAG_GROUP_SUMMARY)!=0;
            // ColorOS can use a real notification as the group head. Keep its own card.
            if(!summary)flat.add(newInstance(specType,spec,callMethod(group,"getController")));
            for(Object row:rows)flat.add(newInstance(specType,spec,callMethod(row,"getController")));
        }
        nodes.clear();nodes.addAll(flat);
    }

    private void section(List<Object> nodes) {
        unpackGroups(nodes);
        indexMembers(nodes);
        ArrayList<Object> retained = new ArrayList<>(), incoming = new ArrayList<>(), older = new ArrayList<>();
        ArrayList<Integer> slots = new ArrayList<>();
        ArrayList<Object> layout = new ArrayList<>();
        availableCount = 0;
        incomingCount = 0;
        stackCounts.clear();appNames.clear();totalCounts.clear();stackRevision++;
        ordinaryRows.clear();
        olderRows.clear();
        appGroupHeads.clear();groupPreviews.clear();
        boolean countOnly = displayStyle == NotificationDisplay.COUNT && preview != null;
        for (Object node : nodes) {
            View row = view(node);
            if (headerType.isInstance(row)) continue;
            List<Object> group = members.get(node);
            if (group == null || group.isEmpty() || (boolean)callMethod(node, "getKeepGone")) {
                layout.add(node);
                continue;
            }
            int amount = 0, currentAmount = 0;
            boolean ordinary = !isLiveContainer(row);
            for (Object entry : group) {
                Notification n = sbn(entry).getNotification();
                if (!ordinary(n)) ordinary = false;
                if ((n.flags & Notification.FLAG_GROUP_SUMMARY) == 0) {
                    amount++;
                    if (!seenEntry(entry)) currentAmount++;
                }
            }
            totalCounts.put(row,Math.max(1,amount));
            if (ordinary) {
                ordinaryRows.add(row);
                availableCount += Math.max(1, amount);
                if (amount == 0 ? wasSeen(node) : currentAmount == 0) {
                    older.add(node);
                    olderRows.add(row);
                } else {
                    stackCounts.put(row,Math.max(1,currentAmount));
                    incoming.add(node);
                    incomingCount += Math.max(1, currentAmount);
                }
                if (countOnly) continue;
            } else {
                retained.add(node);
            }
            slots.add(layout.size());
            layout.add(node);
        }
        // A count chosen in settings may summarize history. A count reached by
        // dragging represents only the current messages, even if one is withdrawn.
        footerCount = countOnly && explicitCount && incomingCount == 0 ? availableCount : incomingCount;
        footer(countOnly ? 1f : footerProgress);
        if (countOnly) {
            setClearVisible(false);
            recentNodes = historyNodes = Collections.emptyList();
            nodes.clear();
            nodes.addAll(layout);
            orderLiveActivities(nodes);
            return;
        }
        int slot = 0;
        java.util.Comparator<Object> newestFirst=(a,b)->Long.compare(newest(b),newest(a));
        incoming.sort(newestFirst);
        older.sort(newestFirst);
        Set<String> currentGroups=new HashSet<>();
        Map<Object,Object> hiddenIncoming=foldByApp(incoming,"new",currentGroups);
        Map<Object,Object> hiddenHistory=foldByApp(older,"history",currentGroups);
        openedAppGroups.retainAll(currentGroups);
        for(Object node:retained)layout.set(slots.get(slot++),node);
        for(Object node:incoming)layout.set(slots.get(slot++),hiddenIncoming.getOrDefault(node,node));
        int historySlot = slot;
        for(Object node:older)layout.set(slots.get(slot++),hiddenHistory.getOrDefault(node,node));
        boolean clearable = (!incoming.isEmpty() || !older.isEmpty()) && hasClearableNotifications();
        if (!older.isEmpty()) {
            int index = slots.get(historySlot);
            layout.add(index, newInstance(specType, spec, headerController));
        }
        setClearVisible(!older.isEmpty() && clearable);
        recentNodes = incoming;
        historyNodes = older;
        nodes.clear();
        nodes.addAll(layout);
        orderLiveActivities(nodes);
        if (parkedAfterClear && !incoming.isEmpty()) {
            parkedAfterClear = false;
        }
    }

    // Hide only rows supplied by the native pipeline. Native groups continue
    // using their original controllers, privacy presentation and expand action.
    private Map<Object,Object> foldByApp(List<Object> section,String scope,Set<String> currentGroups){
        Map<String,List<Object>> apps=new java.util.LinkedHashMap<>();
        for(Object node:section){
            View row=view(node);
            if(!canShowStackDetails(row) || !children(node).isEmpty())continue;
            StatusBarNotification post=sbn(callMethod(row,"getEntry"));
            if((post.getNotification().flags & Notification.FLAG_GROUP_SUMMARY)!=0)continue;
            String conversation=post.getNotification().getShortcutId();
            String key=scope+":"+post.getUid()+":"+post.getPackageName()+":"+(conversation==null?"":conversation);
            apps.computeIfAbsent(key,unused->new ArrayList<>()).add(node);
        }

        Map<Object,List<Object>> expanded=new IdentityHashMap<>();
        Set<Object> expandedChildren=Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Object,Object> hidden=new IdentityHashMap<>();
        apps.forEach((app,group)->{
            if(group.size()<2)return;
            currentGroups.add(app);
            if(openedAppGroups.contains(app)){
                expanded.put(group.get(0),group);
                expandedChildren.addAll(group.subList(1,group.size()));
                return;
            }
            // Keep the first on-screen position and the rest in the native render
            // tree so dismissal, updates and unlock still act on every entry.
            View front=view(group.get(0));
            appGroupHeads.put(front,app);
            groupPreviews.put(front,view(group.get(1)));
            totalCounts.put(front,group.stream().mapToInt(node->totalCount(view(node))).sum());
            if(stackCounts.containsKey(front))
                stackCounts.put(front,group.stream().mapToInt(node->stackCount(view(node))).sum());
            for(int index=1;index<group.size();index++){
                Object node=group.get(index);
                Object collapsed=newInstance(specType,spec,callMethod(node,"getController"));
                callMethod(collapsed,"setKeepGone",true);
                hidden.put(node,collapsed);
            }
        });
        if(!expanded.isEmpty()){
            ArrayList<Object> ordered=new ArrayList<>(section.size());
            for(Object node:section){
                List<Object> group=expanded.get(node);
                if(group!=null)ordered.addAll(group);
                else if(!expandedChildren.contains(node))ordered.add(node);
            }
            section.clear();section.addAll(ordered);
        }
        return hidden;
    }

    private int availableCount, incomingCount, footerCount;
    int ordinaryCount(){return availableCount;}
    int incomingCount(){return incomingCount;}
    private float footerProgress;

    void footer(float progress) {
        footerProgress = progress;
        updateCount(progress > 0 ? footerCount : 0);
        if (countView == null) return;
        float scale=(.25f+.75f*progress)*LiveTune.size("countSize");
        countView.setAlpha(progress);
        countView.setScaleX(scale);
        countView.setScaleY(scale);
        countView.setClickable(progress >= .99f);
    }

    boolean footerHit(float x,float y) {
        return countView != null && countView.isShown() && countView.getGlobalVisibleRect(visible)
                && visible.contains((int)x,(int)y);
    }

    // The section owns the bounds of its measured native rows, including groups.
    boolean isLiveContainer(View row) {
        return row!=null && ("OplusCustomRow".equals(row.getClass().getSimpleName()) || isMediaContainer(row));
    }

    private void orderLiveActivities(List<Object> nodes){
        // Stable partition also includes activities supplied outside the notification collection.
        ArrayList<Object> activities=new ArrayList<>();
        nodes.removeIf(node->{
            if(!isLiveActivity(view(node)))return false;
            activities.add(node);
            return true;
        });
        nodes.addAll(0,activities);
    }

    boolean isLiveActivity(View row){
        return !historicalRow(row) && (isLiveContainer(row)
                || totalCounts.containsKey(row) && !ordinaryRows.contains(row));
    }

    boolean isMediaContainer(View row){
        if(row==null)return false;
        String kind=row.getClass().getSimpleName();
        if("MediaContainerView".equals(kind))return true;
        if(!"OplusCustomRow".equals(kind) || vendorMediaType==null)return false;
        Object entry=callMethod(row,"getEntry");
        return entry!=null && (boolean)callMethod(vendorMediaType,"isOplusMediaCustomRow",callMethod(entry,"getServiceId"));
    }

    boolean isCard(View row) { return rowType != null && rowType.isInstance(row); }

    int statusEdge(ViewGroup host){
        int safe=NotificationViewport.statusBottom(host);
        return preview==null?safe:Math.max(safe,preview.statusBottomInWindow(previewLocation));
    }

    int headerAnchor(ViewGroup host) {
        if (preview == null) return 0;
        return NotificationViewport.layoutY(preview,previewLocation) - NotificationViewport.layoutY(host,stackLocation) + preview.headerHeight(0);
    }

    void scrollHeading(float progress,float offset) {
        if (preview != null) preview.sectionProgress(progress,offset);
    }

    boolean historicalRow(View row) { return olderRows.contains(row); }

    private static boolean ordinary(Notification n) {
        boolean ongoingControl = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0 &&
                (n.actions != null && n.actions.length > 0 || n.bigContentView != null ||
                        n.contentView != null || n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER));
        if (ongoingControl) return false;
        return !n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
                && !n.extras.containsKey("oplusLiveAlertOptions") && !Notification.CATEGORY_CALL.equals(n.category);
    }

    boolean ordinaryRow(View row) {
        if (stack != null && row.getParent() == stack) return ordinaryRows.contains(row);
        if (rowType == null || !rowType.isInstance(row)) return false;
        Object entry = callMethod(row, "getEntry");
        return entry != null && ordinary(sbn(entry).getNotification());
    }

    boolean hasLockContent(ViewGroup host) {
        if(host==null)return false;
        // The native pipeline sets GONE for entries excluded from the lock screen.
        // An INVISIBLE historical row can still be revealed by the section.
        for(int i=0;i<host.getChildCount();i++){
            View row=host.getChildAt(i);
            if(row.getVisibility()==View.GONE)continue;
            if(isCard(row) || (isLiveContainer(row) && row.getMeasuredHeight()>0))return true;
        }
        return false;
    }

    boolean hasOrdinaryNotifications() { return availableCount > 0; }



    int contentTouchTop(int fallback) {
        if (preview == null) return fallback;
        preview.getLocationOnScreen(previewLocation);
        // headerHeight includes the ordinary spacing after the last widget.
        int gap = NotificationViewport.dp(preview,NotificationViewport.HEADER_GAP);
        return Math.min(fallback, previewLocation[1] + preview.headerHeight(0) - gap);
    }

    int foldEdge(ViewGroup host) {
        int edge = actions.foldEdgeInWindow();
        int navigation=NotificationViewport.navigationTop(host,stackLocation);
        edge=edge>0 ? Math.min(navigation,edge-NotificationViewport.dp(host,NotificationViewport.ACTION_GAP)) : navigation;
        return Math.min(host.getHeight(), Math.max(0, edge - NotificationViewport.layoutY(host,stackLocation)));
    }

    View tuneCount(){return countView;}
    View tuneButton(int i){return actions.tuneButton(i);}
    void expandedKeyguard(View host,float progress){glass.expandedKeyguard(host,progress);}

    NotificationMaterial.Palette palette(View owner){return glass.palette(owner);}

    int shortcutCenterInWindow() { return actions.footerCenterInWindow(); }

    boolean onlyHistory(ViewGroup list) {
        return list == stack && recentNodes.isEmpty() && !historyNodes.isEmpty();
    }

    private boolean countSuppressed;
    void suppressCount(boolean value){
        countSuppressed=value;
        compact.suppressCount(value);
        footer(footerProgress);
    }
    private void updateCount(int number) {
        if (number == 0 || countSuppressed) {
            if (countView != null) countView.setVisibility(View.GONE);
            return;
        }
        if (preview == null || !(preview.getParent() instanceof android.widget.FrameLayout)) return;
        if (countView == null) {
            countView = NotificationDisplay.label(stack.getContext());
            ((android.widget.FrameLayout)preview.getParent()).addView(countView,
                    new android.widget.FrameLayout.LayoutParams(-2,-2,android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL));
            countView.setOnClickListener(v -> revealCount.run());
        }
        countView.setVisibility(View.VISIBLE);
        NotificationDisplay.count(countView,number);
        positionCount();
    }

    private void positionCount() {
        if (countView == null || countView.getVisibility() != View.VISIBLE) return;
        ((View)countView.getParent()).getLocationInWindow(previewLocation);
        int center = actions.footerCenterInWindow();
        if (center < 0) {
            android.view.WindowInsets insets=preview.getRootWindowInsets();
            int safe=insets==null ? 0 : insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()).bottom;
            center=preview.getRootView().getHeight()-safe-countView.getHeight()/2;
        }
        countView.setTranslationX(LiveTune.dp(countView,"countX"));
        countView.setTranslationY(center-previewLocation[1]-countView.getHeight()*.5f-countView.getTop()+LiveTune.dp(countView,"countY"));
        if(footerProgress>0){float size=(.25f+.75f*footerProgress)*LiveTune.size("countSize");countView.setScaleX(size);countView.setScaleY(size);}
    }

    void updateGroups(ViewGroup list, int style) {
        long now = SystemClock.uptimeMillis();
        if (rowType == null || now < nextGroupScan) return;
        nextGroupScan = now + 120;
        for (int i=0;i<list.getChildCount();i++) {
            View row = list.getChildAt(i);
            if(!rowType.isInstance(row) || !(boolean)callMethod(row,"isSummaryWithChildren")
                    || (boolean)callMethod(row,"shouldShowPublic"))continue;
            boolean historical=historicalRow(row);
            if(!(historical?foldedHistoryGroups:styledGroups).add(row))continue;
            boolean expand=!historical && style==NotificationDisplay.LIST;
            if (expand != (boolean)callMethod(row,"isGroupExpanded"))
                callMethod(getObjectField(row,"mGroupExpansionManager"),"setGroupExpanded",callMethod(row,"getEntryAdapter"),expand);
        }
    }

    @Override public boolean onPreDraw() {
        if (stack != null) {
            positionCount();
            if(headerLabel!=null){
                int ink=glass.palette(headerLabel).panelInk;
                if(ink!=headerLabel.getCurrentTextColor())headerLabel.setTextColor(ink);
            }
            if (displayStyle != NotificationDisplay.COUNT) updateGroups(stack, displayStyle);
        }
        if (stack == null || !fullyOpen.getAsBoolean()) return true;
        long now = SystemClock.uptimeMillis();
        if (now < nextObservation) return true;
        nextObservation = now + 120;
        // A row folded into ColorOS's bottom shelf hasn't been presented to the user.
        for (Object node : recentNodes) {
            if (members.get(node) == null) continue;
            View row = view(node);
            if (row.getAlpha() > .95f && !getBooleanField(row, "mInShelf")
                    && row.getGlobalVisibleRect(visible) && visible.height() >= (int)callMethod(row, "getActualHeight") * .8f) {
                remember(node);
            }
        }
        return true;
    }

    private void remember(Object node) {
        for (Object entry : members.getOrDefault(node, Collections.emptyList())) {
            observed.put(entry, sbn(entry).getPostTime());
        }
    }

    private boolean hasClearableNotifications() {
        Object collection = getObjectField(getObjectField(stack,"mController"),"mNotifCollection");
        for (Object entry : (Collection<?>)callMethod(collection,"getAllNotifs")) {
            if (sbn(entry).isClearable()) return true;
        }
        return false;
    }

    static Set<Object> eligible(ViewGroup stack, Map<View,List<Object>> rows) {
        Set<Object> result = new LinkedHashSet<>();
        Set<Object> selected = new HashSet<>();
        for (Map.Entry<View,List<Object>> group : rows.entrySet()) {
            selected.addAll(group.getValue());
            Object policy = getObjectField(group.getKey(), "mDismissibilityProvider");
            for (Object entry : group.getValue()) {
                StatusBarNotification n = sbn(entry);
                View row = (View)callMethod(entry, "getRow");
                if (!n.isClearable() || (n.getNotification().flags & Notification.FLAG_BUBBLE) != 0
                        || !(boolean)callMethod(policy, "isDismissable", n.getKey())) continue;
                // An offscreen child can have an inflated row that has never finished
                // its entrance animation. Eligibility is a property of the notification.
                if (row != null && (!(boolean)callMethod(row, "canViewBeDismissed")
                        || (boolean)callMethod(row, "areGutsExposed"))) continue;
                result.add(entry);
            }
        }
        if (result.isEmpty()) return result;
        Object controller = getObjectField(stack, "mController");
        Object collection = getObjectField(controller, "mNotifCollection");
        Set<String> mixed = new HashSet<>(), protectedMember = new HashSet<>(), protectedSummary = new HashSet<>();
        Set<Object> active=new HashSet<>((Collection<?>)callMethod(collection,"getAllNotifs"));
        for (Object entry : active) {
            StatusBarNotification n = sbn(entry);
            if (!n.isGroup() || result.contains(entry)) continue;
            if (!selected.contains(entry)) mixed.add(n.getGroupKey());
            else protectedMember.add(n.getGroupKey());
            if ((n.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) protectedSummary.add(n.getGroupKey());
        }
        result.removeIf(entry -> {
            StatusBarNotification n = sbn(entry);
            String key = n.getGroupKey();
            if (mixed.contains(key) || callMethod(collection, "getEntry", n.getKey()) != entry) return true;
            if ((n.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) return protectedMember.contains(key);
            // ColorOS may also remove the summary when its last child is dismissed.
            return protectedSummary.contains(key) && callMethod(collection, "fetchSummaryToDismiss", entry) != null;
        });
        return result;
    }

    private void setClearVisible(boolean show) {
        if (clearVisible == show || headerController == null) return;
        clearVisible = show;
        if (!show) cancelConfirmation();
        callMethod(headerController,"setClearSectionEnabled",show);
        control.show(show);
    }

    void onDown(float x, float y) {
        if (confirmingClearAll && control != null && !control.contains(x, y)) cancelConfirmation();
    }

    private void cancelConfirmation() {
        if (stack != null) stack.removeCallbacks(expirePrompt);
        confirmingClearAll = false;
        if (control != null) control.setExpanded(false);
    }

    private void onClearPressed() {
        if (stack == null || !fullyOpen.getAsBoolean() || applying) return;
        try {
            if (sectionHeader.getParent() != stack || !hasClearableNotifications()) return;
            if (!confirmingClearAll) {
                confirmingClearAll = true;
                control.setExpanded(true);
                stack.postDelayed(expirePrompt,2000);
                return;
            }
            ViewGroup host = stack;
            if (clearExit != null) clearExit.finish();
            clearExit = null;
            try { clearExit = NotificationClearExit.capture(host,sectionHeader,ordinaryRows,foldEdge(host)); }
            catch (RuntimeException | LinkageError | XposedHelpers.InvocationTargetError e) {
                Log.w(TAG,"Clear transition capture unavailable",e);
            }
            cancelConfirmation();
            // The native completion for selection 0 commits clear-all for the active user.
            // Keep the visual exit independent of list rebuilds and newly arriving entries.
            for (List<Object> group : members.values()) {
                for (Object entry : group) history.put(entry,sbn(entry).getPostTime());
            }
            parkedAfterClear = true;
            callMethod(host,"onClearAllAnimationsEnd",0,Collections.emptyList());
            if (stack == host) {
                replayAtBoundary(host,true);
                if (parkedAfterClear) parkClearedRows.run();
                if (clearExit != null) clearExit.start();
            }
        } catch (RuntimeException | LinkageError | XposedHelpers.InvocationTargetError e) {
            parkedAfterClear = false;
            if (clearExit != null) clearExit.finish();
            Log.e(TAG, "Notification center clear-all failed", e);
        }
    }

    static void dismiss(ViewGroup stack, Set<Object> candidates, Class<?> statsType, Class<?> itemType) {
        Object controller = getObjectField(stack, "mController");
        Object collection = getObjectField(controller, "mNotifCollection");
        Object visibility = getObjectField(controller, "mVisibilityProvider");
        ArrayList<Object> batch = new ArrayList<>();
        for (Object entry : candidates) {
            StatusBarNotification n = sbn(entry);
            Object stats = newInstance(statsType, 3, callMethod(visibility, "obtain", n.getKey(), true));
            batch.add(newInstance(itemType, stats, n.getKey(), entry.hashCode()));
        }
        if (batch.isEmpty()) return;
        callMethod(collection, "dismissNotifications", batch, false);
    }
}
