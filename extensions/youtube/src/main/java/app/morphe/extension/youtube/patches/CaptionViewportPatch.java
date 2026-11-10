/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4149
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.content.res.Configuration;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

/** Preserve YouTube's font formula and preference scale, using known fitted content height. */
@SuppressWarnings("unused")
public final class CaptionViewportPatch {
    // YouTube uses captions_window_margin (5dp) above the video viewport.
    private static final int CAPTION_MARGIN_DP = 5;
    private static final CaptionViewportPolicy policy = new CaptionViewportPolicy();
    private static volatile WeakReference<View> latestView = new WeakReference<>(null);
    private static PlayerType lastPlayerType;
    private static volatile int viewportWidth, viewportHeight;
    private static final Map<View, Position> positions =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, RedrawListener> redrawListeners =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final View.OnLayoutChangeListener positionListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> applyOffset(view);
    private static FullscreenVideoScalePatch.VideoScaleMode lastScale;

    static {
        PlayerType.getOnChange().addObserver(type -> onPresentationChanged());
    }

    private CaptionViewportPatch() {}

    /** Injection point. */
    public static float scaleFontSize(float size) {
        View view = latestView.get();
        if (view != null && PlayerType.getCurrent() == PlayerType.WATCH_WHILE_PICTURE_IN_PICTURE) {
            size = CaptionViewportPolicy.pipFontSize(size, viewportWidth, viewportHeight);
        }
        return CaptionViewportPolicy.scaleFontSize(size, SeekBarPreference.clampToRange(Settings.CAPTION_SCALE));
    }

    /** Injection point. Narrows the measured caption window to the requested width. */
    public static int captionWidthSpec(int widthSpec) {
        try {
            final int mode = View.MeasureSpec.getMode(widthSpec);
            final int size = View.MeasureSpec.getSize(widthSpec);
            if (size <= 0 || mode == View.MeasureSpec.UNSPECIFIED) return widthSpec;
            final int width = CaptionViewportPolicy.captionWidth(
                    size, SeekBarPreference.clampToRange(Settings.CAPTION_WIDTH));
            return width == size ? widthSpec : View.MeasureSpec.makeMeasureSpec(width, mode);
        } catch (Exception ex) {
            Logger.printException(() -> "captionWidthSpec failure", ex);
            return widthSpec;
        }
    }

    /** Applies a changed appearance setting to a paused cue as well. */
    public static void requestRemeasure() {
        remeasure();
    }

    /** Injection point. */
    public static void onVideoId(String videoId) {
        policy.select(videoId);
        remeasure();
    }

    /** Injection point. */
    public static List<VideoFormat.FormatInterface> onFormats(
            String videoId, List<VideoFormat.FormatInterface> formats) {
        try {
            long bestArea = 0;
            int bestWidth = 0, bestHeight = 0;
            for (VideoFormat.FormatInterface format : formats) {
                String mimeType = format.patch_getMimeType();
                if (mimeType == null || !mimeType.startsWith("video/")) continue;
                final int width = format.patch_getWidth(), height = format.patch_getHeight();
                final long area = (long) width * height;
                if (area > bestArea) {
                    bestArea = area;
                    bestWidth = width;
                    bestHeight = height;
                }
            }
            if (bestArea > 0) {
                policy.record(videoId, bestWidth, bestHeight);
                remeasure();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onFormats failure", ex);
        }
        return formats;
    }

    /**
     * Injection point.
     *
     * @param scale Caption size preference scale.
     * @param width Width of the subtitle overlay.
     * @param height Height of the subtitle overlay.
     * @return Height to calculate the caption font size with.
     */
    public static int normalizeHeight(View view, float scale, int width, int height) {
        try {
            register(view);
            if (latestView.get() != view) latestView = new WeakReference<>(view);
            applyOffset(view);
            viewportWidth = width;
            viewportHeight = height;
            PlayerType type = PlayerType.getCurrent();
            final boolean landscapeTransition =
                    type == PlayerType.WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN
                            && view.getResources().getConfiguration().orientation
                                    == Configuration.ORIENTATION_LANDSCAPE;
            final boolean fullscreenFit =
                    (type == PlayerType.WATCH_WHILE_FULLSCREEN || landscapeTransition)
                            && Settings.FULLSCREEN_VIDEO_SCALE.get()
                                    == FullscreenVideoScalePatch.VideoScaleMode.DEFAULT;
            return policy.height(width, height, fullscreenFit, landscapeTransition);
        } catch (Exception ex) {
            Logger.printException(() -> "normalizeHeight failure", ex);
            return height;
        }
    }

    /** A paused cue must be resized even when no new subtitle or format arrives. */
    public static synchronized void onPresentationChanged() {
        final PlayerType type = PlayerType.getCurrent();
        FullscreenVideoScalePatch.VideoScaleMode scale = Settings.FULLSCREEN_VIDEO_SCALE.get();
        if (type == lastPlayerType && scale == lastScale) return;
        lastPlayerType = type;
        lastScale = scale;
        remeasure();
    }

    /** Slider position: 0 = native bottom, 100 = highest position that still fits. */
    public static int captionPosition(int value) {
        return CaptionViewportPolicy.positionPercent(value);
    }

    /** Injection point. Applies the position setting whenever the caption window is measured. */
    public static void applyCaptionOffset(View view) {
        try {
            register(view);
            applyOffset(view);
        } catch (Exception ex) {
            Logger.printException(() -> "applyCaptionOffset failure", ex);
        }
    }

    /** Row layout width limited by the caption width setting. */
    public static int captionTextWidth(int width) {
        try {
            if (width <= 0) return width;
            final int reference = viewportWidth > 0 ? viewportWidth : width;
            final int max = CaptionViewportPolicy.captionWidth(
                    reference, SeekBarPreference.clampToRange(Settings.CAPTION_WIDTH));
            return Math.min(width, max);
        } catch (Exception ex) {
            Logger.printException(() -> "captionTextWidth failure", ex);
            return width;
        }
    }

    private static void register(View view) {
        boolean added = false;
        synchronized (positions) {
            if (!positions.containsKey(view)) {
                positions.put(view, new Position());
                added = true;
            }
        }
        if (added) view.addOnLayoutChangeListener(positionListener);

        ViewTreeObserver observer = view.getViewTreeObserver();
        synchronized (redrawListeners) {
            RedrawListener listener = redrawListeners.get(view);
            ViewTreeObserver previous = listener == null ? null : listener.observer.get();
            if (previous == observer) return;

            if (previous != null && previous.isAlive()) previous.removeOnPreDrawListener(listener);
            listener = new RedrawListener(view);
            redrawListeners.put(view, listener);
            if (observer.isAlive()) observer.addOnPreDrawListener(listener);
            listener.observer = new WeakReference<>(observer);
        }
    }

    /**
     * Applies one translation to every visible caption window under the same parent. YouTube can
     * place two language windows independently; moving each one to the same top edge would make
     * them overlap at high positions.
     */
    private static void applyOffset(View view) {
        if (hasOffsetDescendant(view)) {
            Position position = state(view);
            // This view is only a container for a registered caption window. Remove our
            // group translation while preserving any native translation underneath it.
            position.nativeTranslation = view.getTranslationY() - position.offset;
            position.offset = 0;
            view.setTranslationY(position.nativeTranslation);
            return;
        }

        List<View> group = group(view);
        if (group.isEmpty()) return;

        final int position = captionPosition(Settings.CAPTION_VERTICAL_OFFSET.get());
        final boolean pip = PlayerType.getCurrent() == PlayerType.WATCH_WHILE_PICTURE_IN_PICTURE;
        final float safeTop = safeTop(view);
        float groupTop = Float.MAX_VALUE;
        for (View member : group) {
            Position memberState = state(member);
            // Native changes, including YouTube animations, are separated from our own delta.
            memberState.nativeTranslation = member.getTranslationY() - memberState.offset;
            groupTop = Math.min(groupTop, member.getTop() + memberState.nativeTranslation);
        }
        if (groupTop == Float.MAX_VALUE) return;

        final float offset =
                CaptionViewportPolicy.positionOffset(position, groupTop, safeTop, pip);
        for (View member : group) {
            Position memberState = state(member);
            memberState.offset = offset;
            member.setTranslationY(memberState.nativeTranslation + offset);
        }
    }

    /** The hook runs for the caption container and its text window; offset only the innermost. */
    private static boolean hasOffsetDescendant(View view) {
        synchronized (positions) {
            for (View candidate : positions.keySet()) {
                if (candidate == view) continue;
                ViewParent parent = candidate.getParent();
                while (parent instanceof View) {
                    if (parent == view) return true;
                    parent = parent.getParent();
                }
            }
        }
        return false;
    }

    /** Visible caption windows that share this view's parent and have no nested caption window. */
    private static List<View> group(View view) {
        List<View> result = new ArrayList<>();
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup parentGroup) {
            synchronized (positions) {
                for (int i = 0; i < parentGroup.getChildCount(); i++) {
                    View child = parentGroup.getChildAt(i);
                    if (child.getVisibility() != View.VISIBLE) continue;
                    if (child != view && !positions.containsKey(child)) continue;
                    if (!hasOffsetDescendant(child)) result.add(child);
                }
            }
        }
        if (!result.contains(view) && !hasOffsetDescendant(view)) result.add(view);
        return result;
    }

    private static Position state(View view) {
        synchronized (positions) {
            Position state = positions.get(view);
            if (state == null) {
                state = new Position();
                positions.put(view, state);
            }
            return state;
        }
    }

    private static void detach(View view, RedrawListener listener) {
        Position state;
        synchronized (positions) {
            state = positions.remove(view);
        }
        if (state != null) {
            // Remove only our contribution; native may have moved since the last pre-draw.
            view.setTranslationY(view.getTranslationY() - state.offset);
            view.removeOnLayoutChangeListener(positionListener);
        }

        ViewTreeObserver observer = listener.observer.get();
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(listener);
        synchronized (redrawListeners) {
            if (redrawListeners.get(view) == listener) redrawListeners.remove(view);
        }
    }

    private static float safeTop(View view) {
        final int height = view.getHeight() > 0 ? view.getHeight() : view.getMeasuredHeight();
        final int parentHeight = parentHeight(view, height);
        final int inset = topInset(view);

        int windowY = 0;
        View parent = view.getParent() instanceof View ? (View) view.getParent() : null;
        if (parent != null) {
            int[] location = new int[2];
            parent.getLocationInWindow(location);
            windowY = location[1];
        }

        final float margin =
                Math.round(CAPTION_MARGIN_DP * view.getResources().getDisplayMetrics().density);
        // YouTube moves captions inside the caption overlay, not inside the fitted video frame.
        // Using the video letterbox here made the top slider position follow the video height.
        return CaptionViewportPolicy.safeTop(parentHeight, inset, windowY, margin);
    }

    /** Window insets are in window coordinates; convert the overlap into parent coordinates. */
    private static int topInset(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null) return 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top;
        }
        int top = insets.getSystemWindowInsetTop();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) top = Math.max(top, cutout.getSafeInsetTop());
        }
        return top;
    }

    private static int parentHeight(View view, int fallbackHeight) {
        View parent = view.getParent() instanceof View ? (View) view.getParent() : null;
        return parent != null && parent.getHeight() > 0
                ? parent.getHeight()
                : Math.max(fallbackHeight, viewportHeight);
    }

    private static void remeasure() {
        List<View> views;
        synchronized (positions) {
            views = new ArrayList<>(positions.keySet());
        }
        for (View view : views) {
            if (view != null) Utils.runOnMainThreadNowOrLater(view::requestLayout);
        }
    }

    private static final class Position {
        float nativeTranslation;
        float offset;
    }

    private static final class RedrawListener implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> view;
        private WeakReference<ViewTreeObserver> observer = new WeakReference<>(null);

        RedrawListener(View view) {
            this.view = new WeakReference<>(view);
        }

        @Override
        public boolean onPreDraw() {
            View target = view.get();
            if (target == null) {
                ViewTreeObserver observer = this.observer.get();
                if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
                return true;
            }
            if (!target.isAttachedToWindow()) {
                detach(target, this);
                return true;
            }
            applyOffset(target);
            return true;
        }
    }
}
