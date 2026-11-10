/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4149
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Encoded content geometry belongs to its video, never to the last prefetched response. */
final class CaptionViewportPolicy {
    private static final int DEFAULT_SCALE_PERCENTAGE = 100;

    /** Caption window width limit, never wider than the player and never below 40%. */
    static int captionWidth(int size, int percentage) {
        if (size <= 0) return size;
        final int percent = Math.max(40, Math.min(100, percentage));
        return percent >= 100 ? size : Math.max(1, Math.round(size * percent / 100f));
    }

    static float scaleFontSize(float size, int percentage) {
        return percentage == DEFAULT_SCALE_PERCENTAGE
                ? size
                : size * percentage / DEFAULT_SCALE_PERCENTAGE;
    }

    /** PiP must not inherit the native 13sp minimum intended for the full player. */
    static float pipFontSize(float size, int width, int height) {
        return width <= 0 || height <= 0 ? size : Math.min(size, Math.min(width, height) * 0.0625f);
    }

    /** Position slider range: 0 = native bottom, 100 = highest safe position. */
    static int positionPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    /**
     * Shared vertical translation for one caption group. The same delta is added to every native
     * window, so layered and bilingual windows keep their relative positions.
     *
     * @param groupTop Top of the group in parent coordinates before our translation.
     * @param safeTop  Highest parent coordinate the group top may occupy.
     * @return Translation to add to every group member; zero in PiP or when there is no room.
     */
    static float positionOffset(int positionPercent, float groupTop, float safeTop, boolean pip) {
        if (pip) return 0f;
        float available = Math.max(0f, groupTop - safeTop);
        return -Math.max(0, Math.min(100, positionPercent)) / 100f * available;
    }

    private final LinkedHashMap<String, Double> aspects = new LinkedHashMap<>(32, 0.75f, true);
    private String selected;

    synchronized void select(String videoId) {
        selected = videoId != null && videoId.matches("[A-Za-z0-9_-]{11}") ? videoId : null;
    }

    synchronized void record(String videoId, int width, int height) {
        if (videoId == null
                || !videoId.matches("[A-Za-z0-9_-]{11}")
                || width <= 0
                || height <= 0
                || width > 16384
                || height > 16384) return;
        final double aspect = width / (double) height;
        if (aspect < 0.1 || aspect > 10) return;
        aspects.put(videoId, aspect);
        if (aspects.size() > 32) {
            Iterator<Map.Entry<String, Double>> iterator = aspects.entrySet().iterator();
            while (iterator.hasNext()) {
                if (!iterator.next().getKey().equals(selected)) {
                    iterator.remove();
                    break;
                }
            }
        }
    }

    synchronized int height(
            int width, int height, boolean fullscreenFit, boolean landscapeTransition) {
        if (!fullscreenFit || width <= 0 || height <= 0 || selected == null) return height;
        // Animated bounds cross the stable portrait/near-square guards. The target
        // configuration, rather than one intermediate frame, identifies landscape.
        if (!landscapeTransition && (width < height || 4L * height < 3L * width)) return height;
        Double aspect = aspects.get(selected);
        if (aspect == null) return height;
        return fittedHeight(width, height, aspect);
    }

    /** Top edge of the caption overlay, in parent coordinates, below system insets. */
    static float safeTop(int parentHeight, int inset, int windowY, float margin) {
        final float visibleTop = Math.max(0, inset - windowY);
        return Math.max(0, Math.min(parentHeight, visibleTop + margin));
    }

    private static int fittedHeight(int width, int height, double aspect) {
        return (int) Math.min(height, Math.max(1L, Math.round(width / aspect)));
    }
}
