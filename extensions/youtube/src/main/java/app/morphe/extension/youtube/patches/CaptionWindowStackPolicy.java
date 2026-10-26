package app.morphe.extension.youtube.patches;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Conservative, Android-independent layout policy for translated subtitle windows.
 * This changes positions only; original text, cues, font, and measured sizes survive.
 * No layout is returned if there is no clearly abnormal vertical separation.
 */
public final class CaptionWindowStackPolicy {
    private CaptionWindowStackPolicy() {}

    public static final class Rect {
        public final int index;
        public final int left, top, width, height;
        public Rect(int index, int left, int top, int width, int height) {
            this.index = index;
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }
        public int bottom() { return top + height; }
    }

    /** Returns null if the original native layout should be preserved. */
    public static List<Rect> plan(int frameWidth, int frameHeight, List<Rect> windows, int gapPx) {
        if (frameWidth <= 0 || frameHeight <= 0 || windows == null || windows.size() < 2 || windows.size() > 3) return null;
        int gap = Math.max(1, gapPx);
        List<Rect> sorted = new ArrayList<>(windows);
        Collections.sort(sorted, Comparator.comparingInt((Rect r) -> r.top).thenComparingInt(r -> r.index));

        int heightSum = 0;
        int biggestGap = 0;
        int tallest = 0;
        int maxBottom = 0;
        Rect previous = null;
        for (Rect r : sorted) {
            if (r.width <= 0 || r.height <= 0 || r.width > frameWidth || r.height > frameHeight) return null;
            if (r.index < 0) return null;
            heightSum += r.height;
            tallest = Math.max(tallest, r.height);
            maxBottom = Math.max(maxBottom, r.bottom());
            if (previous != null) biggestGap = Math.max(biggestGap, r.top - previous.bottom());
            previous = r;
        }
        if (heightSum + (sorted.size() - 1) * gap > frameHeight * 0.45) return null;
        if (biggestGap <= Math.max(gap * 2, tallest / 2)) return null;

        int stackHeight = heightSum + (sorted.size() - 1) * gap;
        int desiredBottom = Math.min(maxBottom, (int) (frameHeight * 0.91f));
        int top = Math.max(0, Math.min(frameHeight - stackHeight, desiredBottom - stackHeight));
        List<Rect> result = new ArrayList<>(sorted.size());
        for (Rect r : sorted) {
            int left = Math.max(0, (frameWidth - r.width) / 2);
            result.add(new Rect(r.index, left, top, r.width, r.height));
            top += r.height + gap;
        }
        return result;
    }
}
