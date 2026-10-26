package app.morphe.extension.youtube.patches;

import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Screenshot-specific experiment: two native caption windows are anchored far apart vertically.
 * Move their existing Views, without touching their text, source timing, fonts or spans.
 */
@SuppressWarnings("unused")
public final class CaptionWindowGapPatch {
    private static final String TAG = "MorpheCaptionGap";
    private static final String WINDOW =
            "com.google.android.libraries.youtube.player.subtitles.ui.SubtitleWindowView";
    private static long lastLogMs;

    private CaptionWindowGapPatch() {}

    public static void afterLayout(ViewGroup overlay) {
        if (overlay == null || !Settings.CAPTION_WINDOW_GAP_MINIMAL.get()) return;

        View upper = null;
        View lower = null;
        int count = 0;
        for (int i = 0; i < overlay.getChildCount(); i++) {
            View child = overlay.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || !WINDOW.equals(child.getClass().getName())) continue;
            if (child.getWidth() <= 0 || child.getHeight() <= 0) continue;
            count++;
            if (count == 1) upper = child;
            else if (count == 2) lower = child;
        }
        if (count != 2) {
            report("visible windows=" + count);
            return;
        }
        if (upper.getTop() > lower.getTop()) {
            View temp = upper;
            upper = lower;
            lower = temp;
        }

        int spacing = Math.max(1, (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 4f, overlay.getResources().getDisplayMetrics()));
        int[] tops = correctedTops(
                overlay.getWidth(), overlay.getHeight(),
                upper.getLeft(), upper.getTop(), upper.getWidth(), upper.getHeight(),
                lower.getLeft(), lower.getTop(), lower.getWidth(), lower.getHeight(),
                spacing);
        if (tops == null) {
            report("two windows; unchanged; gap=" + (lower.getTop() - upper.getBottom()));
            return;
        }

        // These are the same native Views, with their original widths and measured heights.
        upper.layout(upper.getLeft(), tops[0], upper.getRight(), tops[0] + upper.getHeight());
        lower.layout(lower.getLeft(), tops[1], lower.getRight(), tops[1] + lower.getHeight());
        report("stacked two windows; tops=" + tops[0] + "," + tops[1]);
    }

    /** Pure layout decision. null means leave YouTube's native layout as-is. */
    static int[] correctedTops(
            int frameWidth, int frameHeight,
            int ax, int ay, int aw, int ah,
            int bx, int by, int bw, int bh, int spacing) {
        if (frameWidth <= 0 || frameHeight <= 0 || aw <= 0 || bw <= 0 || ah <= 0 || bh <= 0)
            return null;
        if (ax < 0 || bx < 0 || ay < 0 || by < 0 ||
                ax + aw > frameWidth || bx + bw > frameWidth ||
                ay + ah > frameHeight || by + bh > frameHeight)
            return null;

        int horizontalOverlap = Math.min(ax + aw, bx + bw) - Math.max(ax, bx);
        if (horizontalOverlap <= 0 || 20L * horizontalOverlap < 17L * Math.min(aw, bw))
            return null;
        if (Math.abs((2L * ax + aw) - (2L * bx + bw)) > frameWidth / 4L)
            return null;

        int gap = by - (ay + ah);
        if (gap <= Math.max(spacing * 3, Math.max(ah, bh) / 2)) return null;

        // Use the upper native window's former bottom, not an invented video viewport.
        // Both boxes end up inside the region where YouTube already positioned a caption.
        int lowerTop = ay + ah - bh;
        int upperTop = lowerTop - Math.max(1, spacing) - ah;
        if (upperTop < 0 || lowerTop < 0) return null;
        return new int[] {upperTop, lowerTop};
    }

    private static void report(String message) {
        long now = SystemClock.uptimeMillis();
        if (now - lastLogMs >= 1500) {
            lastLogMs = now;
            Log.i(TAG, message);
        }
    }
}
