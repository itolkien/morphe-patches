package app.morphe.extension.youtube.patches;

import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import app.morphe.extension.youtube.settings.Settings;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Diagnostic experiment for two vertically separated native subtitle windows.
 *
 * The old hook only called afterLayout at parent onLayout, which might not run
 * when the subtitle renderer updates its cue windows. Register an OnPreDraw
 * listener once so each updated frame can be inspected after native layout.
 */
@SuppressWarnings("unused")
public final class CaptionWindowGapPatch {
    private static final String TAG = "MorpheCaptionGap";
    private static final String WINDOW =
            "com.google.android.libraries.youtube.player.subtitles.ui.SubtitleWindowView";
    private static final WeakHashMap<ViewGroup, Boolean> ATTACHED = new WeakHashMap<>();
    private static long lastReportMs;

    private CaptionWindowGapPatch() {}

    /** Native onLayout hook, version-scoped by the Kotlin patch. */
    public static void afterLayout(ViewGroup overlay) {
        if (overlay == null) return;
        ViewTreeObserver observer = overlay.getViewTreeObserver();
        if (!observer.isAlive()) {
            Log.i(TAG, "hook reached but observer is not alive");
            return;
        }
        synchronized (ATTACHED) {
            if (ATTACHED.containsKey(overlay)) return;
            ATTACHED.put(overlay, Boolean.TRUE);
        }

        final WeakReference<ViewGroup> reference = new WeakReference<>(overlay);
        final ViewTreeObserver.OnPreDrawListener listener = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                ViewGroup target = reference.get();
                if (target == null) {
                    if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                    return true;
                }
                inspectAndFix(target);
                return true;
            }
        };
        observer.addOnPreDrawListener(listener);
        overlay.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) { }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (observer.isAlive()) observer.removeOnPreDrawListener(listener);
                synchronized (ATTACHED) {
                    ATTACHED.remove(v);
                }
                v.removeOnAttachStateChangeListener(this);
            }
        });

        Log.i(TAG, "onLayout hook reached; pre-draw observer installed; enabled="
                + Settings.CAPTION_WINDOW_GAP_MINIMAL.get() + " overlay=" + overlay.getClass().getName());
    }

    private static void inspectAndFix(ViewGroup overlay) {
        boolean enabled = Settings.CAPTION_WINDOW_GAP_MINIMAL.get();
        if (!enabled) {
            report(overlay, "preDraw reached, setting OFF");
            return;
        }
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
            report(overlay, "preDraw: visible caption window views=" + count);
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
            report(overlay, "preDraw: two windows, guard rejected; gap="
                    + (lower.getTop() - upper.getBottom()));
            return;
        }

        upper.layout(upper.getLeft(), tops[0], upper.getRight(), tops[0] + upper.getHeight());
        lower.layout(lower.getLeft(), tops[1], lower.getRight(), tops[1] + lower.getHeight());
        report(overlay, "preDraw: stacked windows; tops=" + tops[0] + "," + tops[1]);
    }

    /** No source text or identifying video information is read or logged. */
    private static void report(ViewGroup overlay, String reason) {
        long now = SystemClock.uptimeMillis();
        if (now - lastReportMs < 1600) return;
        lastReportMs = now;

        StringBuilder message = new StringBuilder(reason);
        message.append(" parent=").append(overlay.getWidth()).append('x').append(overlay.getHeight());
        message.append(" children=").append(overlay.getChildCount());
        for (int i = 0, max = Math.min(overlay.getChildCount(), 8); i < max; i++) {
            View child = overlay.getChildAt(i);
            message.append(" [").append(i).append(':').append(child.getClass().getSimpleName());
            message.append('(').append(child.getLeft()).append(',').append(child.getTop());
            message.append(' ').append(child.getWidth()).append('x').append(child.getHeight()).append(')');
            message.append(" vis=").append(child.getVisibility());
            if (child instanceof ViewGroup group) {
                message.append(" nested=").append(group.getChildCount());
                for (int j = 0, innerMax = Math.min(group.getChildCount(), 3); j < innerMax; j++) {
                    View inner = group.getChildAt(j);
                    message.append('/').append(inner.getClass().getSimpleName());
                }
            }
            message.append(']');
        }
        Log.i(TAG, message.toString());
    }

    /** Pure layout decision. null means retain original native positioning. */
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

        int lowerTop = ay + ah - bh;
        int upperTop = lowerTop - Math.max(1, spacing) - ah;
        if (upperTop < 0 || lowerTop < 0) return null;
        return new int[] {upperTop, lowerTop};
    }
}
