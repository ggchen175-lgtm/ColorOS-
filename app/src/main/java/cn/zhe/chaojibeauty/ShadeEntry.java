package cn.zhe.chaojibeauty;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;

/** The notification page enters from above the live foreground window. */
final class ShadeEntry {
    private final ViewGroup panel;
    private final LockStylePreview header;
    private final ShadeLens glass;
    private final Drawable original;
    private final ShadeWallpaper wallpaper;
    private final View[] scrims;
    private final float[] alphas, origins;
    private final ViewOutlineProvider outline;
    private final boolean clip;
    private ValueAnimator settle;
    private float progress;
    private float interruptedProgress = -1f;
    private float paperMix, interruptedPaperMix, paperScale = 1f, interruptedPaperScale;
    private long landing;
    private boolean dismissing;
    private long openingStart;
    private static final long WALLPAPER_ZOOM_MS = 185;
    private boolean paperLanded;
    private boolean entryCancelled;
    private float releaseSpeed;
    private final boolean fromHome;
    private HomeIconSpread iconSpread;
    private HomeWallpaperMotion homePaper;
    private float lastFingerPosition = Float.NaN;
    private static final float PULL_DIM_END = .22f;
    private float pullDimEnd = PULL_DIM_END;
    private final AppReturnSurface app;
    private long appReturnAt;
    private static final long APP_SETTLE_MS = 320;
    private float appReturnMix;
    private boolean committed, requested, closed;
    private long revealHomeAt;
    private Runnable collapseWhenVisible;
    private final Runnable revealHome = () -> {
        if (closed || revealHomeAt != 0) return;
        revealHomeAt = android.os.SystemClock.uptimeMillis();
        move(progress);
    };
    private float releasedAt = -1, releasedProgress;

    private boolean queued;
    private final Runnable advanceMaterial = () -> { queued = false; move(progress); };
    private final Runnable finished;

    ShadeEntry(ViewGroup panel, LockStylePreview header, View behind, View notifications, NotificationGlass cards, ShadeWallpaper wallpaper, View wallpaperView, boolean fromHome, Runnable finished) {
        this.panel = panel;
        this.header = header;
        this.finished = finished;
        this.wallpaper = wallpaper;
        this.fromHome = fromHome;
        // The full-screen panel rests at the window origin in both shade and keyguard.
        outline = panel.getOutlineProvider();
        clip = panel.getClipToOutline();
        scrims = new View[]{behind, notifications};
        alphas = new float[scrims.length];
        origins = new float[scrims.length];
        for (int i = 0; i < scrims.length; i++) {
            if (scrims[i] == null) continue;
            alphas[i] = scrims[i].getAlpha();
            origins[i] = scrims[i].getTranslationY();
        }
        float density = panel.getResources().getDisplayMetrics().density;
        float radius = density * 30;
        original = panel.getBackground();
        ShadeLens material = null;
        try {
            material = new ShadeLens(panel, wallpaperView, radius, cards);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w("ChaojiBeauty", "Entry backdrop blur unavailable", e);
        }
        glass = material;
        if (!fromHome) wallpaper.openForApp();
        app = !fromHome && material != null ? AppReturnSurface.find(panel) : null;
        if (glass == null) {
            return;
        }
        panel.setBackground(null);
        panel.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline out) {
                out.setRoundRect(0, -(int)radius, view.getWidth(), view.getHeight(), radius);
            }
        });
        panel.setClipToOutline(true);
    }


    void clearBackdropBelow(int edge){if(glass!=null)glass.clearBackdropBelow(edge);}
    void notificationVisibility(float amount) { if (glass != null) glass.notificationVisibility(amount); }

    boolean available() { return glass != null; }

    void flingDecision(boolean expand, float velocity) {
        if (releasedAt < 0 || !expand) entryCancelled = !expand;
        releaseSpeed = Math.max(0f, velocity) / Math.max(1, panel.getHeight());
    }

    void release(float nativeFraction, boolean cancelled) {
        entryCancelled |= cancelled;
        releasedAt = nativeFraction;
        releasedProgress = progress;
        if (!entryCancelled) {
            preparePresentation();
            if (nativeFraction >= 1f) finish();
        }
    }

    float resumeClosingPull() {
        if (!entryCancelled || releasedAt < 0f || paperLanded || closed) return -1f;
        entryCancelled = false;
        releasedAt = -1f;
        lastFingerPosition = Float.NaN;
        return progress;
    }

    private void preparePresentation() {
        wallpaper.open();
        if (fromHome && iconSpread == null) {
            iconSpread = new HomeIconSpread(panel.getContext());
            homePaper = HomeWallpaperMotion.forWindow(panel);
        }
    }

    void followFinger(float fraction) {
        if (fraction == lastFingerPosition) return;
        lastFingerPosition = fraction;
        // Once the clock area approaches the viewport, it also needs live wallpaper.
        if ((1f - fraction) * panel.getHeight() <= panel.getWidth()) wallpaper.open();
        move(fraction);
    }

    void projectedOpen(float fraction) {
        // A short fast pull already dims the foreground. Keep the
        // same level while held and retrace the ramp if the finger reverses.
        if (fraction > 0f && fraction < pullDimEnd) {
            pullDimEnd = fraction;
            lastFingerPosition = Float.NaN;
        }
    }

    void nativeProgress(float fraction) {
        if (landing != 0 && fraction >= 1f) return;
        if (releasedAt < 0) { if (fraction >= 1) finish(); return; }
        if (entryCancelled && settle == null) {
            // ColorOS may publish a larger fraction on UP before it starts
            // collapsing. Rebase its return on that peak at the visible height.
            if (fraction > releasedAt) {
                releasedAt = fraction;
                releasedProgress = progress;
            } else if (releasedAt > 0f) {
                move(Math.min(progress, releasedProgress * fraction / releasedAt));
            }
            return;
        }
        if (fraction >= 1f && !entryCancelled) {
            // The native panel can jump to its endpoint on UP. Settle from the
            // visible finger position before publishing that endpoint to home.
            finish();
            return;
        }
        if (settle != null && fraction >= releasedAt) return;
        if (settle != null) {
            settle.removeAllListeners();
            settle.cancel();
            settle = null;
            landing = 0;
            releasedAt = 1f;
            releasedProgress = progress;
        }
        if (fraction >= releasedAt && releasedAt < 1f) {
            move(releasedProgress + (1f-releasedProgress)*(fraction-releasedAt)/(1f-releasedAt));
        } else if (releasedAt > 0f) {
            move(releasedProgress * fraction/releasedAt);
        }
    }

    void dismissTo(float value) {
        dismissing = value < 1f;
        // A reversed gesture may settle back to the presented endpoint.
        if (!dismissing) {
            if (!fromHome && !paperLanded && interruptedProgress >= 0f && !wallpaper.hasFrame()) {
                // The interrupted open settled back before its first wallpaper frame.
                // Resume the normal material handoff when that frame arrives.
                progress = 1f;
                interruptedProgress = -1f;
                openingStart = 0;
                finish();
                return;
            }
            paperLanded = true;
        }
        move(value);
    }

    boolean canReverseOpening() {
        return releasedAt >= 0f && !entryCancelled && !paperLanded && !closed;
    }

    float takeOpeningOffset() {
        if (settle != null) {
            settle.removeAllListeners();
            settle.cancel();
            settle = null;
        }
        landing = 0;
        interruptedProgress = progress;
        interruptedPaperMix = paperMix;
        interruptedPaperScale = paperScale;
        if (fromHome && openingStart == 0) openingStart = android.os.SystemClock.uptimeMillis();
        return (progress - 1f) * panel.getHeight();
    }

    void commitReturn() {
        committed = fromHome ? iconSpread != null && (paperLanded || interruptedProgress >= 0f)
                : wallpaper.hasFrame() && paperMix > 0f;
        // Only a covered task can be pre-scaled before it becomes visible.
        // An interrupted open already exposes live app pixels at their resting scale.
        if (!fromHome && committed && app != null && (paperLanded ||
                (interruptedProgress >= 1f && interruptedPaperMix >= 1f))) app.begin(panel);
        if (committed && iconSpread != null && !requested) {
            requested = true;
            iconSpread.returnDock();
            LauncherShadeReturn.request(panel.getContext(), iconSpread.owner, revealHome);
        }
        if (committed) move(progress);
    }

    void finishReturn(Runnable collapse) {
        if (!requested && appReturnAt == 0) { collapse.run(); return; }
        collapseWhenVisible = collapse;
        move(0f);
    }

    private float departureProgress(long now) {
        float seconds = (now - openingStart) * .001f;
        float omega = 38f;
        return Math.min(1f, 1f - (1f + (omega - Math.min(releaseSpeed, omega)) * seconds)
                * (float)Math.exp(-omega * seconds));
    }

    void retune(){if(!closed && progress>0 && progress<1)move(progress);}
    void move(float value) {
        if (closed) return;
        progress = Math.max(0, Math.min(1, value));

        float curve=LiveTune.get("pageFollow",1,.45f,2f);
        float shown=curve==1?progress:(float)Math.pow(progress,1f/curve);
        float y = (shown - 1) * panel.getHeight();
        panel.setTranslationY(y);
        header.pinStatusDuringEntry(-y);
        long now = android.os.SystemClock.uptimeMillis();
        boolean ready = wallpaper.hasFrame();
        float t = Math.max(0f,Math.min(1f,(progress-.10f)/.90f));
        float flyOut = 0f;
        float wallpaperScale = 1f;
        if (!dismissing && !paperLanded) {
            boolean opening = settle != null || landing != 0 || (releasedAt >= 0 && progress > releasedProgress);
            boolean present = opening && releasedAt >= 0 && !entryCancelled && (fromHome || ready);
            t = 0f;
            if (present) {
                if (openingStart == 0) openingStart = now;
                float elapsed = Math.min(1f, (now - openingStart) / (float)WALLPAPER_ZOOM_MS);
                float spring = elapsed >= 1f ? 1f : (1f-(1f+6f*elapsed)*(float)Math.exp(-6f*elapsed))/.98264873f;
                wallpaperScale = .94f + .06f * Math.min(spring, Math.min(1f, progress / .90f));
                flyOut = departureProgress(now);
                // Both origins use the same projected velocity and wallpaper edge.
                t = elapsed >= 1f ? 1f : flyOut;
            }
        }
        if (dismissing && !paperLanded && interruptedProgress >= 0f) {
            float rewind = interruptedProgress > 0f ? Math.min(1f, progress / interruptedProgress) : 0f;
            t = interruptedPaperMix * rewind;
            wallpaperScale = 1f + (interruptedPaperScale - 1f) * rewind;
        } else if (fromHome && dismissing) {
            t = 1f;
        }
        float returnExtent = 0f;
        if (!fromHome && dismissing && ready) {
            returnExtent = paperLanded ? 1f : Math.max(0f, interruptedProgress);
            if (paperLanded) t = 1f;
            else if (interruptedProgress >= 0f && progress > interruptedProgress && interruptedProgress < 1f) {
                // A cancelled bottom swipe retraces the presentation to the open endpoint.
                float resumed = (progress - interruptedProgress) / (1f - interruptedProgress);
                t = interruptedPaperMix + (1f - interruptedPaperMix) * resumed;
                wallpaperScale = interruptedPaperScale + (1f - interruptedPaperScale) * resumed;
            }
            if (committed && progress <= .18f) {
                if (appReturnAt == 0) {
                    appReturnAt = now;
                    appReturnMix = t;
                }
                float u = Math.min(1f, (now - appReturnAt) / (float)APP_SETTLE_MS);
                float remaining = 1f - u;
                float residual = remaining * remaining * remaining;
                if (app != null) app.scale(1f + LiveTune.get("appZoom",.16f,0,.34f) * appReturnMix * residual);
                t = appReturnMix * residual;
            }
        }
        float finish = ready ? t*t*(3f-2f*t) : 0f;
        if (iconSpread != null) {
            // The Dock belongs to the icon transition. Pulling the sheet alone
            // leaves it at home; after presentation, the return animator brings it back.
            float iconTravel = flyOut;
            if (paperLanded) {
                iconTravel = 1f;
                interruptedProgress = -1f;
            } else if (interruptedProgress >= 0f) {
                // The sheet reverses at its visible offset. Its accepted home
                // departure continues until Launcher takes ownership on release.
                iconTravel = departureProgress(now);
            }
            iconSpread.move(iconTravel, Math.min(1f, 4f * iconTravel), paperLanded);
            // A held or cancelled drag only dims the live desktop. Its wallpaper
            // participates in the icon departure once presentation is accepted.
            if (homePaper != null) homePaper.move(paperLanded || committed ? 0f : iconTravel);
        }
        paperMix = ready ? t : 0f;
        paperScale = wallpaperScale;
        wallpaper.sampleBackdrop(finish > 0f);
        glass.position(progress,finish,wallpaperScale,fromHome,fromHome || !paperLanded,
                dismissing && paperLanded ? PULL_DIM_END : pullDimEnd, returnExtent);
        for (int i = 0; i < scrims.length; i++) {
            View layer = scrims[i];
            if (layer == null) continue;
            layer.setTranslationY(origins[i] + y);
            float scrimMix = !paperLanded && (!dismissing || fromHome) ? (progress == 1f && finish == 1f ? 1f : 0f) : finish;
            layer.setAlpha(i == 0 ? 0 : alphas[i] * scrimMix);
        }
        if (collapseWhenVisible != null && (requested ? revealHomeAt != 0
                : !ready || (appReturnAt != 0 && now-appReturnAt >= APP_SETTLE_MS))) {
            Runnable collapse = collapseWhenVisible;
            collapseWhenVisible = null;
            collapse.run();
        } else if (landing != 0 && ((finish >= 1 && wallpaperScale >= 1f) || wallpaper.unavailable() ||
                android.os.SystemClock.uptimeMillis() - landing >= 1000)) {
            landing=0;
            paperLanded=true;
            if (iconSpread != null) iconSpread.move(1f, 1f, true);
            finished.run();
        } else if ((!paperLanded && (landing != 0 || (openingStart != 0 && now-openingStart < WALLPAPER_ZOOM_MS)) ||
                collapseWhenVisible != null) && !queued && (settle == null || !settle.isRunning() || landing != 0)) {
            queued = true;
            panel.postOnAnimation(advanceMaterial);
        }
    }

    void finish() {
        if (closed || paperLanded || settle != null || landing != 0) return;
        preparePresentation();
        if (progress >= 1f) {
            landing = android.os.SystemClock.uptimeMillis();
            move(1f);
            return;
        }
        settle = ValueAnimator.ofFloat(progress, 1f);
        settle.setDuration(Math.round(LiveTune.get("pageTime",1,.35f,2.5f)*Math.max(150, Math.round(310 * (1 - progress)))));
        settle.setInterpolator(new DecelerateInterpolator());
        settle.addUpdateListener(a -> move((float)a.getAnimatedValue()));
        settle.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animator) {
                landing = android.os.SystemClock.uptimeMillis();
                move(1f);
            }
        });
        settle.start();
    }

    void restore() {
        if (closed) return;
        closed=true;
        panel.setTranslationY(0f);
        if (homePaper != null) homePaper.close();
        if (app != null) app.restore();
        if (iconSpread != null) iconSpread.close();
        collapseWhenVisible=null;
        panel.removeCallbacks(revealHome);
        panel.removeCallbacks(advanceMaterial);
        queued = false;
        landing = 0;
        if (settle != null) {
            settle.removeAllListeners();
            settle.cancel();
        }
        if (glass != null) glass.close();
        header.pinStatusDuringEntry(0);
        panel.setOutlineProvider(outline);
        panel.setClipToOutline(clip);
        for (int i = 0; i < scrims.length; i++) {
            if (scrims[i] == null) continue;
            scrims[i].setTranslationY(origins[i]);
            scrims[i].setAlpha(alphas[i]);
        }
        if (glass != null && panel.getBackground()==null) panel.setBackground(original);
    }
}
