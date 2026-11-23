package cn.zhe.chaojibeauty;

import android.app.Application;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.*;
import android.widget.ImageView;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.util.*;

/** Replaces only background slots owned by the launcher and assistant screen. */
public final class ContainerGlass implements IXposedHookLoadPackage {
    private static final String HOME = "com.android.launcher";
    private static final String ASSIST = "com.coloros.assistantscreen";
    private static final String FOLDER = "com.android.launcher3.folder.FolderRoundImageView";
    private static final String PAGER_HOST = "com.android.launcher.pageindicators.OplusPageIndicator";
    private static final String INDICATOR = PAGER_HOST + "$g";
    private static final String CLEAR_BUTTON="com.android.launcher.views.PressFeedbackButton";
    private Class<?> blurTransition;
    private final Map<View, Scene> scenes = new IdentityHashMap<>();
    private final Map<View, Face> nativeFaces = new IdentityHashMap<>();
    private final Set<Method> hooked = new HashSet<>();
    private final boolean[] enabled = new boolean[5];
    private final AssistantSlide slide = new AssistantSlide();
    private final AssistantSide side = new AssistantSide();
    private boolean assistant;
    private Face drawingPlaceholder;
    private Path headerFill;
    private final Map<View,Face> materialBackings=new IdentityHashMap<>();
    // Sixteen subpixel steps across a 3dp radius; driven by the native overlay callback.
    private static final int BLUR_STEPS = 16;
    private int assistantBlurStep = BLUR_STEPS;
    private Context app;
    private Handler main;
    private final Runnable retrySettings = this::refresh;
    private boolean settingsLoaded;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) throws Throwable {
        if (android.os.Build.VERSION.SDK_INT<34) return;
        if (!HOME.equals(load.processName) && !ASSIST.equals(load.processName)) return;
        assistant = ASSIST.equals(load.processName);
        try { slide.install(load.classLoader,assistant); }
        catch (RuntimeException | LinkageError e) { Log.w("CJBContainers","Assistant slide unavailable",e); }
        try { side.install(load.classLoader, assistant); }
        catch (RuntimeException | LinkageError e) { Log.w("CJBContainers","Assistant placement unavailable",e); }
        if (!assistant) {
            XposedHelpers.findAndHookMethod("com.android.launcher3.OplusWorkspace", load.classLoader,
                    "onAttachedToWindow", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            View workspace = (View)p.thisObject;
                            workspace.post(() -> {
                                if (!workspace.isAttachedToWindow()) return;
                                if (!settingsLoaded && app != null) refresh();
                                watchRoot(workspace.getRootView());
                            });
                        }
                    });
            try { installRecentsClear(load.classLoader); }
            catch (RuntimeException | LinkageError e) { Log.w("CJBContainers","Recent clear material unavailable",e); }
            try {
                blurTransition = load.classLoader.loadClass("com.android.launcher3.uioverrides.states.blurdrawable.IBlurTransition");
                XposedHelpers.findAndHookMethod(PAGER_HOST, load.classLoader, "drawBackgroundIfNeeded",
                        Canvas.class, int.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        Face face = nativeFaces.get(p.thisObject);
                        if (face == null || face.target != Target.PAGER_FALLBACK) return;
                        View child = (View)XposedHelpers.getObjectField(face.v, "mBlurBgView");
                        face.pagerFallbackActive = (int)p.args[1] > 0 && (child == null || !blurTransition.isInstance(child.getBackground()));
                        if (!face.pagerFallbackActive) return;
                        if (face.paintPager((Canvas)p.args[0], (int)p.args[1] / 255f)) {
                            if (!XposedHelpers.getBooleanField(face.v, "mShowBackground")) {
                                XposedHelpers.setBooleanField(face.v, "mShowBackground", true);
                                XposedHelpers.callMethod(face.v, "updatePaintsColor");
                            }
                            p.setResult(null);
                        }
                    }
                });
            } catch (Throwable e) { Log.w("CJBContainers","Native pager fallback unavailable",e); }
        }
        if (assistant) {
            // These are the two launcher callback transports shipped on this ColorOS build.
            XC_MethodHook progress = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    float fraction = (float)p.args[0];
                    if (!Float.isFinite(fraction) || main == null) return;
                    if (Looper.myLooper() == main.getLooper()) setOverlayFraction(fraction);
                    else main.post(() -> setOverlayFraction(fraction));
                }
            };
            for (String name : new String[]{"com.heytap.assist.e",
                    "com.google.android.libraries.launcherclient.ILauncherOverlayCallback$Stub$Proxy"}) {
                Class<?> proxy = XposedHelpers.findClassIfExists(name,load.classLoader);
                if (proxy != null) XposedBridge.hookAllMethods(proxy,"overlayScrollChanged",progress);
            }
            String owner="com.coloros.common.widget.HighlightShadowConstraintLayout";
            XposedHelpers.findAndHookMethod(owner, load.classLoader, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Face f = nativeFaces.get(p.thisObject);
                    if (f != null && f.paint((Canvas)p.args[0])) p.setResult(null);
                }
            });
            // The placeholder's own onDraw also animates its native highlight after ImageView
            // content. Replace just the two fill passes, leaving that highlight in its owner.
            String imageOwner="com.coloros.common.widget.HighlightShadowImageView";
            XposedHelpers.findAndHookMethod(imageOwner,load.classLoader,"onDraw",Canvas.class,new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Face header=materialBackings.get(p.thisObject);
                    if (header!=null && header.target==Target.HEADER && header.reported && !header.failed) {
                        p.setObjectExtra("outerHeader",headerFill);
                        p.setObjectExtra("headerActive",true);
                        headerFill=(Path)XposedHelpers.callMethod(p.thisObject,"getContentPath");
                    }
                    Face face=nativeFaces.get(p.thisObject);
                    if (face!=null && face.target==Target.PLACEHOLDER && face.paint((Canvas)p.args[0])) {
                        p.setObjectExtra("previousFace",drawingPlaceholder);
                        drawingPlaceholder=face;
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getObjectExtra("headerActive")!=null) headerFill=(Path)p.getObjectExtra("outerHeader");
                    if (drawingPlaceholder!=null && drawingPlaceholder.v==p.thisObject)
                        drawingPlaceholder=(Face)p.getObjectExtra("previousFace");
                }
            });
            XposedHelpers.findAndHookMethod("com.coloros.common.ui.HighlightDelegate",load.classLoader,"c",
                    Canvas.class,Path.class,float.class,new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (headerFill!=null && headerFill==p.args[1] || drawingPlaceholder!=null && drawingPlaceholder.cardContour==p.args[1]) p.setResult(null);
                }
            });
            XposedHelpers.findAndHookMethod("ac.d",load.classLoader,"onDraw",Canvas.class,new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Face face=materialBackings.get(p.thisObject);
                    // The native quick-app host paints its solid fill here, before dispatching children.
                    if (face!=null && face.target==Target.APPS && face.reported && !face.failed) p.setResult(null);
                }
            });
            // Skip only a targeted owner's decorative base. Icons, text and state-list feedback
            // still use their original draw path, and replacing/recycling the view is reversible.
            XC_MethodHook baseFill=new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Drawable d=(Drawable)p.thisObject;
                    if (!(d.getCallback() instanceof View)) return;
                    View owner=(View)d.getCallback();
                    Face face=materialBackings.get(owner);
                    if (face!=null && face.reported && !face.failed && owner.getBackground()==d)
                        p.setResult(null);
                }
            };
            XposedHelpers.findAndHookMethod(android.graphics.drawable.ColorDrawable.class,"draw",Canvas.class,baseFill);
            XposedHelpers.findAndHookMethod(android.graphics.drawable.GradientDrawable.class,"draw",Canvas.class,baseFill);
            XposedHelpers.findAndHookMethod(ImageView.class,"onDraw",Canvas.class,new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (drawingPlaceholder!=null && drawingPlaceholder.v==p.thisObject) p.setResult(null);
                }
            });
        }
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                app = (Context)p.thisObject;
                main = new Handler(app.getMainLooper());
                try {
                    app.getContentResolver().registerContentObserver(FeatureProvider.CONTAINERS, false,
                            new ContentObserver(main) {
                                @Override public void onChange(boolean self) { refresh(); }
                            });
                    refresh();
                } catch (Exception e) { Log.w("CJBContainers","Material initialization",e); }
            }
        });
        XposedBridge.hookAllMethods(XposedHelpers.findClass("android.view.WindowManagerGlobal",null),
                "addView",new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || !(p.args[0] instanceof View)) return;
                View v=(View)p.args[0];
                if (v.getTag()==DockWallpaperStream.WALLPAPER_WINDOW) return;
                if (!load.packageName.equals(v.getContext().getPackageName())) return;
                v.post(() -> watchRoot(v));
            }
        });
    }

    private void installRecentsClear(ClassLoader loader) {
        // ColorOS can choose a drawable background or paint the same capsule inside onDraw.
        XposedHelpers.findAndHookMethod(View.class,"drawBackground",Canvas.class,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Face f=nativeFaces.get(p.thisObject);
                if (f!=null && f.target==Target.RECENTS_CLEAR && f.paint((Canvas)p.args[0])) {
                    f.clearPlateDrawn=true;
                    p.setResult(null);
                }
            }
        });
        XposedHelpers.findAndHookMethod(CLEAR_BUTTON,loader,"onDraw",Canvas.class,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Face f=nativeFaces.get(p.thisObject);
                if (f!=null && f.target==Target.RECENTS_CLEAR) {
                    f.clearContent=f.clearPlateDrawn || f.paint((Canvas)p.args[0]);
                    f.clearPlateDrawn=false;
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Face f=nativeFaces.get(p.thisObject);
                if (f!=null) f.clearContent=false;
            }
        });
        XposedHelpers.findAndHookMethod(CLEAR_BUTTON,loader,"f",boolean.class,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Face f=nativeFaces.get(p.thisObject);
                if (f!=null && f.clearContent) p.setResult(true);
            }
        });
    }

    private void setOverlayFraction(float value) {
        int step=Math.round(Math.max(0,Math.min(1,value))*BLUR_STEPS);
        if (assistantBlurStep==step) return;
        assistantBlurStep=step;
        for (Scene scene:scenes.values()) scene.invalidateFaces();
    }

    private void watchRoot(View root) {
        if (root.isAttachedToWindow() && !scenes.containsKey(root)) scenes.put(root,new Scene(root));
    }

    private void refresh() {
        main.removeCallbacks(retrySettings);
        try (Cursor c=app.getContentResolver().query(FeatureProvider.CONTAINERS,null,null,null,null)) {
            if (c==null || !c.moveToFirst()) {
                main.postDelayed(retrySettings, 2000);
                return;
            }
            settingsLoaded = true;
            for (int i=0;i<4;i++) enabled[i]=c.getInt(i)!=0;
            int recents=c.getColumnIndex("recents_clear_glass");
            enabled[4]=recents>=0 && c.getInt(recents)!=0;
            int slideColumn=c.getColumnIndex("assistant_slide");
            slide.setEnabled(slideColumn>=0 && c.getInt(slideColumn)!=0);
            int sideColumn=c.getColumnIndex("assistant_right");
            side.setRight(sideColumn>=0 && c.getInt(sideColumn)!=0);
            for (Scene s:scenes.values()) s.scan();
        } catch (RuntimeException e) {
            Log.w("CJBContainers","Reading switches",e);
            main.postDelayed(retrySettings, 2000);
        }
    }

    private static String id(View v) {
        if (v.getId()==View.NO_ID || v.getId()==0) return "";
        try { return v.getResources().getResourceEntryName(v.getId()); }
        catch (android.content.res.Resources.NotFoundException ignored) { return ""; }
    }
    private static boolean within(View v,String name) {
        for (ViewParent p=v.getParent();p instanceof View;p=p.getParent())
            if (p.getClass().getName().equals(name)) return true;
        return false;
    }
    private enum Target {
        FOLDER_ICON(0), FOLDER_OPEN(0), SUGGESTION(1), PAGER(2), PAGER_FALLBACK(2), HEADER(3), SERVICE(3), APPS(3), PLACEHOLDER(3), RECENTS_CLEAR(4);
        final int option;
        Target(int option) { this.option=option; }
    }
    private Target classify(View v) {
        String name=v.getClass().getName();
        String id=id(v);
        if (assistant) {
            if (id.equals("search_bar_blur")||id.equals("ai_icon_blur")||id.equals("add_icon_blur")) return Target.HEADER;
            if (id.equals("shortcut_card") && name.endsWith("HighlightShadowConstraintLayout")) return Target.SERVICE;
            if (id.equals("iv_net_pic") && name.equals("com.coloros.common.widget.HighlightShadowImageView") && within(v,"com.oplus.card.ui.frameguide.item.f")) return Target.PLACEHOLDER;
            if (id.equals("ref_3") && within(v,"ac.d") && v.getWidth()>0 && Math.abs(v.getWidth()-v.getHeight())<=2) return Target.APPS;
        } else {
            if (id.equals("btn_clear") && name.equals(CLEAR_BUTTON) && within(v,"com.oplus.quickstep.views.OplusClearAllPanelView")) return Target.RECENTS_CLEAR;
            if (name.equals(FOLDER)) return Target.FOLDER_ICON;
            if ((id.equals("folder_content_background") || id.equals("folder_animator_background")) && within(v,"com.android.launcher3.folder.OplusFolder")) return Target.FOLDER_OPEN;
            if (name.equals(INDICATOR)) return Target.PAGER;
            if (name.equals(PAGER_HOST) && id.equals("page_indicator")) return Target.PAGER_FALLBACK;
            if ((name.equals("ua.c") || name.equals("pantanal.appplugin.groupcard.widget.carousel.CarouselBlurBackgroundView"))
                    && within(v,"com.android.launcher3.card.groupcard.GroupCardView")) return Target.SUGGESTION;
        }
        return null;
    }

    @android.annotation.TargetApi(34)
    private final class Scene implements ViewTreeObserver.OnGlobalLayoutListener,ViewTreeObserver.OnPreDrawListener,
            ViewTreeObserver.OnWindowVisibilityChangeListener,View.OnAttachStateChangeListener {
        final View root;
        final Map<View,Face> faces=new IdentityHashMap<>();
        DockWallpaperStream stream;

        int width,height;
        boolean dark;
        long retryAt;
        final Runnable retryWallpaper = () -> invalidateFaces();
        Scene(View root) {
            this.root=root;
            dark = GlassAppearance.dark(root);
            root.addOnAttachStateChangeListener(this);
            root.getViewTreeObserver().addOnGlobalLayoutListener(this);
            root.getViewTreeObserver().addOnPreDrawListener(this);
            root.getViewTreeObserver().addOnWindowVisibilityChangeListener(this);
            scan();
        }
        @Override public void onWindowVisibilityChanged(int visibility) {
            if (visibility!=View.VISIBLE) release();
            else {
                retryAt=0;
                invalidateFaces();
            }
        }
        @Override public void onGlobalLayout(){scan();}
        void scan() {
            Iterator<Face> iter=faces.values().iterator();
            while (iter.hasNext()) {
                Face f=iter.next();
                if (!enabled[f.target.option] || !f.v.isAttachedToWindow() || f.v.getRootView()!=root) {
                    f.remove(); iter.remove();
                }
            }
            if (assistant ? enabled[3] : enabled[0]||enabled[1]||enabled[2]||enabled[4]) visit(root);
            if (faces.isEmpty()) release();
        }
        void visit(View v) {
            Face f=faces.get(v);
            if (f==null) {
                Target t=classify(v);
                if (t!=null && enabled[t.option]) {
                    f=new Face(this,v,t); faces.put(v,f);
                }
            }
            if (f!=null) f.bind();
            if (v instanceof ViewGroup) {
                ViewGroup group=(ViewGroup)v;
                for(int i=0;i<group.getChildCount();i++) visit(group.getChildAt(i));
            }
        }
        void invalidateFaces() {
            for (Face f : faces.values()) if (f.v.isShown()) f.invalidateMaterial();
        }
        boolean wallpaper() {
            if (assistant) return false;
            int w=root.getWidth(),h=root.getHeight();
            if (w!=width || h!=height) { release();width=w;height=h; }
            if (root.getWindowVisibility() != View.VISIBLE || w<=0 || h<=0 ||
                    android.os.SystemClock.uptimeMillis() < retryAt) return false;
            if (stream==null) {
                stream=new DockWallpaperStream(root,"Chaoji desktop containers",this::invalidateFaces);
                try { stream.open(w,h,0,0,1,1,false); }
                catch (RuntimeException | ReflectiveOperationException e) {
                    // Window visibility can precede attachment of the new wallpaper surface.
                    release();
                    retryAt = android.os.SystemClock.uptimeMillis() + 500;
                    root.postDelayed(retryWallpaper, 500);
                    return false;
                }
            }
            return stream.hasFrame();
        }
        void release() {
            if (stream!=null) {stream.close();stream=null;}
            root.removeCallbacks(retryWallpaper);
            retryAt = 0;
        }
        @Override public boolean onPreDraw() {
            if (!assistant) {
                boolean nextDark = GlassAppearance.dark(root);
                if (dark != nextDark) {
                    dark = nextDark;
                    release();
                    for (Face f : faces.values()) {
                        f.failed = f.shapePending = false;
                        f.maskSource = null;
                        f.material = null;
                        f.pagerRadius = -1;
                        f.bind();
                        f.invalidateMaterial();
                    }
                }
                // Launcher can exchange the plate without requesting another layout.
                for (Face f : faces.values())
                    if (f.target == Target.FOLDER_ICON || f.target == Target.PAGER) f.bind();
            }
            if (assistant || stream!=null) for (Face f:faces.values()) {
                if (f.reported && f.v.isShown() && (assistant && f.cropChanged() || f.dark != GlassAppearance.dark(f.v) || (!assistant && !f.matrix().equals(f.last)))) f.invalidateMaterial();
            }
            return true;
        }
        @Override public void onViewAttachedToWindow(View v){}
        @Override public void onViewDetachedFromWindow(View v) {
            scenes.remove(root);
            root.removeOnAttachStateChangeListener(this);
            ViewTreeObserver tree=root.getViewTreeObserver();
            if(tree.isAlive()){
                tree.removeOnPreDrawListener(this);tree.removeOnGlobalLayoutListener(this);
                tree.removeOnWindowVisibilityChangeListener(this);
            }
            for (Face f:faces.values()) f.remove();
            faces.clear();release();
        }
    }

    private final Map<Drawable,Face> nativeBackgrounds=new IdentityHashMap<>();
    private void hookBackground(Drawable d,Face face) throws ReflectiveOperationException {
        Method m=d.getClass().getMethod("draw",Canvas.class);
        if (!hooked.contains(m)) {
            XposedBridge.hookMethod(m,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    Face f=nativeBackgrounds.get(p.thisObject);
                    if (f!=null && f.paint((Canvas)p.args[0])) p.setResult(null);
                }
            });
            hooked.add(m);
        }
        nativeBackgrounds.put(d,face);
    }

    @android.annotation.TargetApi(34)
    private final class Face extends Drawable {
        final Scene scene; final View v; final Target target;
        final RenderNode node=new RenderNode("Chaoji container material");
        final Matrix forward=new Matrix(),inverse=new Matrix(),last=new Matrix();
        Method setBackdrop;
        RenderEffect[] backdropEffects;
        Paint surfacePaint;
        final Rect visibleBounds=new Rect();
        int cropLeft,cropTop;
        int appliedBlurStep=-1;
        final Path clip=new Path();
        final Outline outline=new Outline();
        Drawable original;
        ContainerOptics material;
        DockMaterial pagerMaterial;
        final RectF pagerBounds = new RectF();
        float pagerRadius = -1, pagerWeight = -1;
        boolean pagerFallbackActive;
        int width,height,alpha=255;
        Drawable maskSource;
        Path folderFill,cardContour;
        int cardGeneration;
        RenderEffect folderEffect;
        int folderGeneration;
        boolean clearPlateDrawn,clearContent;
        boolean shapePending;
        boolean failed,reported,dark;
        Face(Scene scene,View v,Target t) { this.scene=scene;this.v=v;target=t; }
        Matrix matrix(){forward.reset();v.transformMatrixToGlobal(forward);return forward;}
        private void bindMaterialBackings() {
            if (target==Target.HEADER && v.getParent() instanceof ViewGroup) {
                ViewGroup parent=(ViewGroup)v.getParent();
                String peer=id(v).replace("_blur","");
                for (int i=0;i<parent.getChildCount();i++) {
                    View child=parent.getChildAt(i);
                    if (child instanceof ImageView && peer.equals(id(child))) materialBackings.put(child,this);
                }
            } else if (target==Target.APPS) {
                // CardDelegator and its host can each draw a theme fill behind the quick-app card.
                for (ViewParent parent=v.getParent();parent instanceof View;parent=parent.getParent()) {
                    View owner=(View)parent;
                    if (owner.getWidth()!=v.getWidth() || owner.getHeight()!=v.getHeight()) break;
                    if (owner.getClass().getName().equals("org.hapjs.card.sdk.CardDelegator")
                            || owner.getClass().getName().equals("ac.d")) materialBackings.put(owner,this);
                    if (owner.getClass().getName().equals("ac.d")) break;
                }
                if (v instanceof ViewGroup) {
                    ViewGroup group=(ViewGroup)v;
                    for(int i=0;i<group.getChildCount();i++){
                        View child=group.getChildAt(i);
                        if ("ref_4".equals(id(child)) && child.getWidth()==v.getWidth() && child.getHeight()==v.getHeight())
                            materialBackings.put(child,this);
                    }
                }
            }
        }
        private void refreshBackings(boolean remove) {
            Iterator<Map.Entry<View,Face>> it=materialBackings.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<View,Face> entry=it.next();
                if (entry.getValue()!=this) continue;
                View owner=entry.getKey();
                if (remove) it.remove();
                if (owner.getBackground()!=null) owner.getBackground().invalidateSelf();
                owner.postInvalidateOnAnimation();
            }
        }
        void bind() {
            bindMaterialBackings();
            if (target==Target.SERVICE || target==Target.PLACEHOLDER || target==Target.PAGER_FALLBACK || target==Target.RECENTS_CLEAR) {
                if (nativeFaces.put(v,this)==null) v.invalidate();
                return;
            }
            if (target==Target.FOLDER_ICON || target==Target.PAGER || target==Target.SUGGESTION) {
                Drawable d=target==Target.FOLDER_ICON ? ((ImageView)v).getDrawable() : v.getBackground();
                if (target==Target.PAGER && blurTransition!=null && !blurTransition.isInstance(d)) d=null;
                if (original==d) return;
                failed = shapePending = false;
                nativeBackgrounds.remove(original);original=d;
                if(d!=null) try { hookBackground(d,this);d.invalidateSelf();v.invalidate(); }
                catch(Exception e){fail(e);}
                return;
            }
            if (failed) return;
            boolean image=target==Target.FOLDER_OPEN && ((ImageView)v).getDrawable()!=null;
            Drawable current=image?((ImageView)v).getDrawable():v.getBackground();
            if(current==this || (current==null && target==Target.FOLDER_OPEN)) return;
            original=current;
            if(image) ((ImageView)v).setImageDrawable(this);
            else v.setBackground(this);
        }
        boolean cropChanged() {
            return v.getLocalVisibleRect(visibleBounds) && (cropLeft!=visibleBounds.left || cropTop!=visibleBounds.top);
        }
        void invalidateMaterial() {
            if (target==Target.PAGER_FALLBACK && !pagerFallbackActive) return;
            if((target==Target.PAGER || target==Target.SUGGESTION) && original!=null) original.invalidateSelf();
            v.invalidate();
        }
        void remove(){
            refreshBackings(true);
            nativeFaces.remove(v);nativeBackgrounds.remove(original);
            if(v.getBackground()==this) v.setBackground(original);
            if(v instanceof ImageView && ((ImageView)v).getDrawable()==this) ((ImageView)v).setImageDrawable(original);
            node.discardDisplayList();invalidateMaterial();
        }
        @Override public void draw(Canvas c) {
            if(!paint(c) && original!=null) { original.setBounds(getBounds());original.draw(c); }
        }
        private boolean appendOutline(Outline o) {
            Rect rect=new Rect();
            if(o.getRect(rect)) {
                if(o.getRadius()<=0 || rect.isEmpty())return false;
                clip.addRoundRect(new RectF(rect),o.getRadius(),o.getRadius(),Path.Direction.CW);
                return true;
            }
            if(o.isEmpty())return false;
            Path path=(Path)XposedHelpers.getObjectField(o,"mPath");
            if(path==null || path.isEmpty())return false;
            clip.set(path);return true;
        }
        private boolean readShape(Drawable source,int w,int h) {
            clip.reset();
            if(target==Target.FOLDER_ICON){
                if(folderFill==null || folderFill.isEmpty()) return false;
                clip.set(folderFill);
                return true;
            }
            if(cardContour!=null) { clip.set(cardContour);return true; }
            if(source!=null) {
                if(source.getClass().getSimpleName().equals("RenderEffectDrawable")){
                    Drawable fill=(Drawable)XposedHelpers.getObjectField(source,"mDefaultDrawable");
                    if(fill!=null){outline.setEmpty();fill.getOutline(outline);if(appendOutline(outline))return true;}
                }
                source.setBounds(0,0,w,h);
                outline.setEmpty();source.getOutline(outline);
                if(appendOutline(outline))return true;
            }
            for(View owner=v;owner!=null;){
                if(owner.getWidth()!=w || owner.getHeight()!=h)break;
                if(owner.getOutlineProvider()!=null && (owner==v || owner.getClipToOutline())){
                    outline.setEmpty();owner.getOutlineProvider().getOutline(owner,outline);
                    if(appendOutline(outline))return true;
                }
                owner=owner.getParent() instanceof View?(View)owner.getParent():null;
            }
            if(source!=null && source.getClass().getSimpleName().equals("RenderEffectDrawable")){
                Object r=XposedHelpers.getObjectField(source,"mRadius"),weight=XposedHelpers.getObjectField(source,"mCornerWeight");
                float radius=((Number)XposedHelpers.callMethod(r,"peek")).floatValue();
                if(radius>0){
                    float smooth=((Number)XposedHelpers.callMethod(weight,"peek")).floatValue();
                    float[] radii=new float[8];Arrays.fill(radii,radius);
                    XposedHelpers.callMethod(clip,"addSmoothRoundRect16",new RectF(0,0,w,h),radii,Path.Direction.CW,smooth);
                    return !clip.isEmpty();
                }
            }
            return false;
        }
        // Evaluate the launcher's active backdrop material on a solid GPU input to isolate its alpha.
        // The resulting texture contains geometry only. Transmitted colors still come from the live wallpaper.
        private Bitmap folderEffectStamp(RenderEffect effect,int w,int h){
            RenderNode input=new RenderNode("Native folder coverage");
            RenderNode root=new RenderNode("Folder silhouette");
            Bitmap hardware=null;
            try {
                input.setPosition(1,1,w+1,h+1);
                input.beginRecording().drawColor(Color.WHITE);
                input.endRecording();
                input.setRenderEffect(effect);
                root.setPosition(0,0,w+2,h+2);
                root.beginRecording().drawRenderNode(input);
                root.endRecording();
                hardware=(Bitmap)XposedHelpers.callStaticMethod(HardwareRenderer.class,
                        "createHardwareBitmap",root,w+2,h+2);
                if(hardware==null)return null;
                Bitmap mask=hardware.copy(Bitmap.Config.ARGB_8888,true);
                if(Color.alpha(mask.getPixel(1,1))==0 && Color.alpha(mask.getPixel(w/2+1,h/2+1))>0)
                    return mask;
                Log.i("CJBContainers","No coverage in native GPU result: "+Integer.toHexString(mask.getPixel(1,1))+
                        " center="+Integer.toHexString(mask.getPixel(w/2+1,h/2+1)));
                mask.recycle();
                return null;
            } finally {
                if(hardware!=null)hardware.recycle();
                root.discardDisplayList();input.discardDisplayList();
            }
        }
        private Bitmap nativeStamp(Drawable source,int w,int h) {
            if(target==Target.FOLDER_ICON && source.getClass().getSimpleName().equals("RenderEffectDrawable"))
                return folderEffectStamp(folderEffect,w,h);
            boolean pathAvailable=readShape(source,w,h);
            if(!pathAvailable && (source==null || target==Target.FOLDER_ICON))return null;
            Bitmap stamp=Bitmap.createBitmap(w+2,h+2,Bitmap.Config.ARGB_8888);
            Canvas mask=new Canvas(stamp);mask.translate(1,1);
            if(pathAvailable){
                Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);ink.setColor(Color.WHITE);mask.drawPath(clip,ink);
            } else {
                source.draw(mask);
                // A full rectangle supplies no evidence of the actual curved plate.
                if(Color.alpha(stamp.getPixel(1,1))!=0 || Color.alpha(stamp.getPixel(w,1))!=0) return null;
            }
            return Color.alpha(stamp.getPixel(w/2+1,h/2+1))==0?null:stamp;
        }
        boolean paintPager(Canvas canvas, float opacity) {
            if (failed || !canvas.isHardwareAccelerated()) return false;
            try {
                boolean fallback = target == Target.PAGER_FALLBACK;
                float radius, weight;
                if (fallback) {
                    pagerBounds.set((RectF)XposedHelpers.getObjectField(v,"mBackgroundRect"));
                    radius = XposedHelpers.getIntField(v,"mBackgroundRadius");
                    weight = 0f;
                } else {
                    pagerBounds.set(original.getBounds());
                    radius = ((Number)XposedHelpers.callMethod(XposedHelpers.getObjectField(original,"mRadius"),"peek")).floatValue();
                    weight = ((Number)XposedHelpers.callMethod(XposedHelpers.getObjectField(original,"mCornerWeight"),"peek")).floatValue();
                    opacity *= nativeOpacity(original);
                }
                int w = Math.round(pagerBounds.width()), h = Math.round(pagerBounds.height());
                if (w < 1 || h < 1 || radius <= 0 || !scene.wallpaper()) return false;
                if (pagerMaterial == null) pagerMaterial = fallback ? new DockMaterial() : new DockMaterial(v.getContext().getClassLoader());
                boolean backdrop=assistant || target==Target.RECENTS_CLEAR;
                boolean appearance = GlassAppearance.dark(v);
                if (dark != appearance || width != w || height != h || radius != pagerRadius || weight != pagerWeight) {
                    node.setRenderEffect(pagerMaterial.resize(w,h,radius,weight,v.getResources().getDisplayMetrics().density,appearance));
                    dark=appearance;
                    node.setPosition(0,0,w,h);
                    width=w; height=h; pagerRadius=radius; pagerWeight=weight;
                }
                float sx = pagerBounds.width()/w, sy = pagerBounds.height()/h;
                last.set(matrix());
                forward.preTranslate(pagerBounds.left,pagerBounds.top);
                forward.preScale(sx,sy);
                if (!forward.invert(inverse)) return false;
                RecordingCanvas rec = node.beginRecording();
                try { rec.concat(inverse); scene.stream.record(rec); } finally { node.endRecording(); }
                node.setAlpha(Math.max(0f,Math.min(1f,opacity)));
                int save = canvas.save();
                try {
                    canvas.translate(pagerBounds.left,pagerBounds.top);
                    canvas.scale(sx,sy);
                    canvas.drawRenderNode(node);
                } finally { canvas.restoreToCount(save); }
                if (!reported) { Log.i("CJBContainers","Drawing Dock optics for "+target+" "+w+"x"+h); reported=true; }
                return true;
            } catch (Exception | LinkageError e) { fail(e); return false; }
        }
        boolean paint(Canvas c){
            if(failed || !c.isHardwareAccelerated())return false;
            if (target==Target.PAGER && original!=null && original.getClass().getSimpleName().equals("RenderEffectDrawable"))
                return paintPager(c,1f);
            int w=v.getWidth(),h=v.getHeight();
            if(w<=0 || h<=0)return false;
            try {
                Drawable source=original;
                if (target==Target.SERVICE || target==Target.PLACEHOLDER || target==Target.RECENTS_CLEAR) {
                    Path path=target==Target.RECENTS_CLEAR ? (Path)XposedHelpers.getObjectField(v,v.isEnabled()?"r":"s") :target==Target.SERVICE ? (Path)XposedHelpers.getObjectField(v,"i")
                            : (Path)XposedHelpers.callMethod(v,"getContentPath");
                    if (path==null || path.isEmpty()) return false;
                    int generation=path.getGenerationId();
                    if (cardContour!=path || cardGeneration!=generation) {
                        cardContour=path;cardGeneration=generation;material=null;shapePending=false;
                    }
                }
                if(target==Target.FOLDER_ICON){
                    if(source==null || source!=((ImageView)v).getDrawable()) return false;
                    if(maskSource!=source) folderFill=folderPath(source);
                    if(source.getClass().getSimpleName().equals("RenderEffectDrawable")){
                        RenderEffect active=(RenderEffect)XposedHelpers.getObjectField(source,"mRenderEffect");
                        if(active==null)return false;
                        if(active!=folderEffect && shapePending)shapePending=false;
                        folderEffect=active;
                    }
                    if(folderFill!=null && folderGeneration!=folderFill.getGenerationId()){
                        folderGeneration=folderFill.getGenerationId();material=null;shapePending=false;
                    }
                }
                if(width!=w || height!=h || maskSource!=source){
                    shapePending=false;width=w;height=h;maskSource=source;material=null;
                }
                if(shapePending)return false;
                boolean backdrop=assistant || target==Target.RECENTS_CLEAR;
                boolean appearance = GlassAppearance.dark(v);
                boolean newShape = material==null;
                if(newShape){
                    Rect previousBounds=source==null?null:source.copyBounds();
                    Bitmap stamp;
                    try {stamp=nativeStamp(source,w,h);}
                    finally {if(previousBounds!=null)source.setBounds(previousBounds);}
                    if(stamp==null){
                        shapePending=true;
                        Log.i("CJBContainers","No native outline "+target+" "+id(v)+" source="+(source==null?"none":source.getClass().getName()));
                        return false;
                    }
                    material=new ContainerOptics(stamp,v.getResources().getDisplayMetrics().density);
                    node.setPosition(0,0,w,h);
                    if (backdrop) {
                        surfacePaint=material.surfacePaint();
                        node.setClipToOutline(!clip.isEmpty());
                        if (!clip.isEmpty()) {
                            outline.setPath(clip);
                            node.setOutline(outline);
                        }
                    }
                }
                if (newShape || appearance != dark || backdrop && cropChanged()) {
                    if (backdrop) {
                        if (setBackdrop==null)
                            setBackdrop=RenderNode.class.getMethod("setBackdropRenderEffect",RenderEffect.class);
                        backdropEffects=new RenderEffect[BLUR_STEPS+1];
                        v.getLocalVisibleRect(visibleBounds);
                        cropLeft=visibleBounds.left;cropTop=visibleBounds.top;
                        backdropEffects[0]=material.transmission(appearance,cropLeft,cropTop);
                        appliedBlurStep=-1;
                        node.beginRecording();node.endRecording();
                    } else node.setRenderEffect(material.effect(appearance));
                    dark = appearance;
                }
                if (backdrop) {
                    int step=assistant ? assistantBlurStep : 0;
                    if (appliedBlurStep!=step) {
                        if (backdropEffects[step]==null) {
                            float radius=3f*v.getResources().getDisplayMetrics().density*step/BLUR_STEPS;
                            RenderEffect blur=RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP);
                            // Sample behind the face first. The mask and rim stay sharp, as does foreground text.
                            backdropEffects[step]=RenderEffect.createChainEffect(backdropEffects[0],blur);
                        }
                        setBackdrop.invoke(node,backdropEffects[step]);
                        appliedBlurStep=step;
                    }
                    float opacity = target==Target.SERVICE ? XposedHelpers.getFloatField(v,"g") : alpha/255f;
                    int a=Math.round(255*Math.max(0,Math.min(1,opacity)));
                    node.setAlpha(a/255f);c.drawRenderNode(node);
                    surfacePaint.setAlpha(a);
                    c.drawRect(0,0,w,h,surfacePaint);
                    if(!reported){refreshBackings(false);Log.i("CJBContainers","Live backdrop "+target+" "+id(v)+" "+w+"x"+h);reported=true;}
                    return true;
                }
                if(!scene.wallpaper())return false;
                last.set(matrix());if(!last.invert(inverse))return false;
                RecordingCanvas rec=node.beginRecording();
                try {
                    rec.concat(inverse);
                    scene.stream.record(rec);
                } finally {node.endRecording();}
                float opacity=alpha/255f;
                if(target==Target.SERVICE) opacity=XposedHelpers.getFloatField(v,"g");
                if((target==Target.SUGGESTION || target==Target.PAGER) && original!=null
                        && original.getClass().getSimpleName().equals("RenderEffectDrawable"))
                    opacity=nativeOpacity(original);
                if(target==Target.FOLDER_ICON)
                    opacity=source.getClass().getSimpleName().equals("RenderEffectDrawable") ? nativeOpacity(source) : source.getAlpha()/255f;
                node.setAlpha(Math.max(0,Math.min(1,opacity)));c.drawRenderNode(node);
                if(!reported){Log.i("CJBContainers","Drawing "+target+" "+id(v)+" "+w+"x"+h);reported=true;}
                return true;
            }catch(Exception|LinkageError e){fail(e);return false;}
        }
        // The launcher paints this path in SmoothRoundDrawable.draw after setting bounds.
        // Both folder sizes carry their own plate. The outer view may additionally clip it.
        private Path folderPath(Drawable drawable){
            Object fill=drawable;
            if(fill.getClass().getSimpleName().equals("RenderEffectDrawable"))
                fill=XposedHelpers.getObjectField(fill,"mDefaultDrawable");
            if(fill!=null && fill.getClass().getSimpleName().equals("GradientDrawableWrapper"))
                fill=XposedHelpers.getObjectField(fill,"mBaseDrawable");
            if(fill!=null && fill.getClass().getName().equals("com.android.launcher3.folder.big.SmoothRoundDrawable"))
                return (Path)XposedHelpers.getObjectField(fill,"mPath");
            return null;
        }
        private float nativeOpacity(Drawable source){
            float whole=((Number)XposedHelpers.callMethod(source,"getMWholeAlpha")).floatValue();
            float blur=((Number)XposedHelpers.callMethod(source,"getMBlurAlpha")).floatValue();
            float fill=((Number)XposedHelpers.callMethod(source,"getMDefaultAlpha")).floatValue();
            return whole*Math.min(1f,blur+fill);
        }
        void fail(Throwable e){failed=true;refreshBackings(true);Log.w("CJBContainers","Native material unavailable for "+target,e);}
        @Override public void setAlpha(int a){alpha=a;invalidateSelf();}
        @Override public int getAlpha(){return alpha;}
        @Override public void setColorFilter(ColorFilter filter){}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        @Override public int getIntrinsicWidth(){return original!=null?original.getIntrinsicWidth():-1;}
        @Override public int getIntrinsicHeight(){return original!=null?original.getIntrinsicHeight():-1;}
        @Override public boolean getPadding(Rect out){return original!=null && original.getPadding(out);}
        @Override public void getOutline(Outline out){
            if(original!=null){original.setBounds(getBounds());original.getOutline(out);}
            else out.setEmpty();
        }
    }
}
