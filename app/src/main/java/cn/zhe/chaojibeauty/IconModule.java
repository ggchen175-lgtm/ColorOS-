package cn.zhe.chaojibeauty;

import android.app.Application;
import android.content.*;
import android.content.res.Resources;
import android.database.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.util.Log;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class IconModule implements IXposedHookLoadPackage {
    static final String SYSTEM = "com.android.systemui";
    static final String PACKAGE = "cn.zhe.chaojibeauty";
    private static final String TAG = "ChaojiBeauty";
    private static final Set<String> logged = ConcurrentHashMap.newKeySet();
    private final Map<Drawable, Boolean> active = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Integer, Integer> mapped = new ConcurrentHashMap<>();
    private volatile Resources material;
    private Ios27BatteryRenderer battery;
    private final NetworkSlot networkSlot = new NetworkSlot();
    private final CarrierLabel carrierLabel = new CarrierLabel();
    private final NotificationGlass notificationGlass = new NotificationGlass();
    private final WidgetAccessoryLayout accessories = new WidgetAccessoryLayout();
    private final LockClockMaterial lockClock = new LockClockMaterial(notificationGlass,accessories);
    private final LockWidgetMaterial widgetMaterial = new LockWidgetMaterial(lockClock);
    private final LockCenter lockCenter = new LockCenter(notificationGlass,lockClock,accessories);
    private final ControlCenterGlass controlGlass=new ControlCenterGlass();
    private final NotificationDeck deck = new NotificationDeck(notificationGlass,lockCenter.display);
    private final GroupHeaderControls groupControls = new GroupHeaderControls(notificationGlass);
    private final BatteryCharge batteryCharge = new BatteryCharge();
    static volatile boolean enabled;

    static void firstDraw(String part) {
        if (!logged.contains(part) && logged.add(part)) Log.i(TAG, "draw " + part);
    }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        if (!SYSTEM.equals(pkg.packageName) || !SYSTEM.equals(pkg.processName)) return;
        try { controlGlass.install(pkg.classLoader); }
        catch (RuntimeException | LinkageError e) { Log.w(TAG,"Control glass unavailable",e); }
        try { carrierLabel.install(pkg.classLoader); }
        catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG,"Carrier typography unavailable",e); }
        try { widgetMaterial.install(pkg.classLoader);lockCenter.previewWidgets.install(pkg.classLoader); }
        catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG,"Widget material unavailable",e); }
        try { HomeWallpaperMotion.install(pkg.classLoader); }
        catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG,"Home wallpaper motion unavailable",e); }
        try { AppReturnSurface.install(pkg.classLoader); }
        catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG,"Full-screen task animation unavailable",e); }
        try { lockCenter.install(pkg.classLoader); }
        catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG,"Lock center unavailable",e); }
        try { lockCenter.installHistory(pkg.classLoader); }
        catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG,"Notification history unavailable",e); }
        try { notificationGlass.install(pkg.classLoader); }
        catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG,"Notification glass unavailable",e); }
        try { groupControls.install(pkg.classLoader); }
        catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG, "Group header controls unavailable", e); }
        try { deck.install(pkg.classLoader); }
        catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG, "Notification deck unavailable", e); }
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                start((Application)p.thisObject);
            }
        });
        XC_MethodHook radio = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (material == null || !(p.getResult() instanceof Drawable) || p.getResult() instanceof RadioIcon) return;
                Resources res = (Resources)p.thisObject;
                if (res == material) return;
                int id = (Integer)p.args[0];
                if ((id >>> 24) == 1 || id == 0) return;
                if (!SYSTEM.equals(res.getResourcePackageName(id))) return;
                int mappedId = mapped.computeIfAbsent(id, key -> lookup(res,key));
                if (mappedId == 0) return;
                String name = material.getResourceEntryName(mappedId);
                RadioIcon icon = new RadioIcon((Drawable)p.getResult(),material.getDrawable(mappedId,null),
                    name.startsWith("signal_") ? .96f : 1f);
                active.put(icon, Boolean.TRUE);
                p.setResult(icon);
            }
        };
        XposedBridge.hookAllMethods(Resources.class, "getDrawable", radio);
        XposedBridge.hookAllMethods(Resources.class, "getDrawableForDensity", radio);
        try { networkSlot.install(pkg.classLoader); }
        catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG, "Network slot unavailable", e); }
        try { hookBattery(pkg.classLoader); }
        catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG,"Battery interface unavailable",e); }
    }
    private int lookup(Resources res, int id) {
        try {
            if (!SYSTEM.equals(res.getResourcePackageName(id))) return 0;
            String mappedName = SignalResources.moduleName(res.getResourceEntryName(id), false);
            if (mappedName == null) return 0;
            if (mappedName.startsWith("c17_signal_single_")) mappedName = "signal_" + mappedName.substring("c17_signal_single_".length());
            if (mappedName.startsWith("stat_signal_wifi_signal_"))
                mappedName = "wifi_" + mappedName.charAt("stat_signal_wifi_signal_".length());
            return material.getIdentifier(mappedName, "drawable", PACKAGE);
        } catch (Resources.NotFoundException missing) { return 0; }
    }
    private void start(Application app) {
        try {
            Resources source = app.getPackageManager().getResourcesForApplication(PACKAGE);
            battery = new Ios27BatteryRenderer();
            material = source;
        } catch (Exception | LinkageError failure) { Log.w(TAG,"Status icon material unavailable",failure); }
        try {
            ContentResolver resolver = app.getContentResolver();
            Handler main = new Handler(Looper.getMainLooper());
            ContentObserver observer = new ContentObserver(main) {
                @Override public void onChange(boolean selfChange) { readEnabled(resolver); }
            };
            resolver.registerContentObserver(FeatureProvider.FEATURES, false, observer);
            main.post(new Runnable() {
                int retries;
                @Override public void run() {
                    if (!readEnabled(resolver) && retries++ < 4) main.postDelayed(this, 1500L * retries);
                }
            });
            // The first SystemUI start can precede the user provider becoming available.
            IntentFilter userEvents = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            userEvents.addAction(Intent.ACTION_USER_PRESENT);
            userEvents.addAction(Intent.ACTION_SCREEN_ON);
            userEvents.addAction(Intent.ACTION_SCREEN_OFF);
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (Intent.ACTION_USER_PRESENT.equals(i.getAction())) {
                        lockCenter.userPresent();
                        widgetMaterial.refresh();
                    }
                    else if (Intent.ACTION_USER_UNLOCKED.equals(i.getAction())) readEnabled(resolver);
                    else {
                        boolean asleep=Intent.ACTION_SCREEN_OFF.equals(i.getAction());
                        setAmbient(asleep);
                        if(!asleep)lockCenter.screenOn();
                    }
                }
            }, userEvents);
            setAmbient(!app.getSystemService(PowerManager.class).isInteractive());
            Log.i(TAG, "SystemUI icon hooks ready");
        } catch (Exception | LinkageError failure) { Log.w(TAG,"Feature settings unavailable",failure); }
    }
    private void setAmbient(boolean asleep) {
        lockClock.setAmbient(asleep);
        widgetMaterial.setAmbient(asleep);
        lockCenter.previewWidgets.setAmbient(asleep);
        lockCenter.ambient(asleep);
    }

    private boolean readEnabled(ContentResolver resolver) {
        boolean next;
        try (Cursor c = resolver.query(FeatureProvider.FEATURES,null,null,null,null)) {
            if (c == null || !c.moveToFirst()) return false;
            next = c.getInt(0) == 1;
            carrierLabel.configure(next);
            groupControls.configure(c.getInt(1) == 1);
            deck.configure(c.getInt(1) == 1);
            int glass = c.getColumnIndex("notification_glass");
            notificationGlass.configure(glass >= 0 && c.getInt(glass) == 1);
            int timeWeight = c.getColumnIndex("time_weight");
            int dateWeight = c.getColumnIndex("date_weight");
            if (timeWeight >= 0 && dateWeight >= 0 &&
                    ClockTypography.setWeights(c.getInt(timeWeight),c.getInt(dateWeight))) lockCenter.refreshClock();
            int center = c.getColumnIndex("lock_center");
            lockCenter.configure(center >= 0 && c.getInt(center) == 1);
            int style = c.getColumnIndex("display_style");
            lockCenter.display.configure(center >= 0 && c.getInt(center) == 1, style < 0 ? 1 : c.getInt(style));
            int widgets = c.getColumnIndex("widget_material");
            boolean accessories = widgets >= 0 && c.getInt(widgets) == 1;
            int qsGlass=c.getColumnIndex("control_glass");
            controlGlass.configure(qsGlass>=0 && c.getInt(qsGlass)==1);
            widgetMaterial.configure(accessories);
            lockCenter.setWidgetActions(accessories);
        } catch (RuntimeException unavailable) { Log.w(TAG, "Feature setting unavailable", unavailable); return false; }
        if (next == enabled) return true;
        enabled = next;
        networkSlot.refresh();
        Drawable[] snapshot;
        synchronized(active) { snapshot=active.keySet().toArray(new Drawable[0]); }
        for (Drawable d : snapshot) if (d != null) d.invalidateSelf();
        Log.i(TAG, "Status icons enabled=" + enabled);
        return true;
    }
    private void hookBattery(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = loader.loadClass("com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.HorizontalBatteryContentDrawable");
        Method level = type.getMethod("getBatteryLevel"), charge = type.getMethod("getChargeIconId");
        Field outline = XposedHelpers.findField(type, "outlineColor");
        Field progress = XposedHelpers.findField(type, "progressColor");
        Field percent = XposedHelpers.findField(type, "isShowPercentIn");
        Method draw = type.getDeclaredMethod("draw",Canvas.class);
        XposedBridge.hookMethod(draw, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                Drawable d = (Drawable)p.thisObject;
                if (!active.containsKey(d)) active.put(d,Boolean.TRUE);
                if (!enabled || battery == null || d.getBounds().isEmpty()) return;
                int value = (int)level.invoke(d);
                if (value < 0 || value > 100) return;
                battery.draw((Canvas)p.args[0], d.getBounds(),value,batteryCharge.active(d) || (int)charge.invoke(d)>0,
                    percent.getBoolean(d),outline.getInt(d),progress.getInt(d),d.getAlpha());
                firstDraw("battery");
                p.setResult(null);
            }
        });
        batteryCharge.install(loader, type);
    }
}
