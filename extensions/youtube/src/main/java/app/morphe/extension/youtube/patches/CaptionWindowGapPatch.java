package app.morphe.extension.youtube.patches;

import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import app.morphe.extension.youtube.settings.Settings;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Experimental fix/probe for unusually separated rows inside a SINGLE native
 * SubtitleWindowView. No content, timing, span, font or track modifications.
 *
 * YouTube 21.23.492 only. The Android render tree is measured after native layout.
 */
@SuppressWarnings("unused")
public final class CaptionWindowGapPatch {
    private static final String TAG = "MorpheCaptionRows";
    private static final String WINDOW =
            "com.google.android.libraries.youtube.player.subtitles.ui.SubtitleWindowView";
    private static final String ROW = "apow";
    private static final WeakHashMap<ViewGroup, Boolean> ATTACHED = new WeakHashMap<>();
    private static long lastReportMs;

    private CaptionWindowGapPatch() {}

    public static void afterLayout(ViewGroup overlay) {
        if (overlay == null) return;
        final ViewTreeObserver observer = overlay.getViewTreeObserver();
        if (!observer.isAlive()) return;
        synchronized (ATTACHED) {
            if (ATTACHED.containsKey(overlay)) return;
            ATTACHED.put(overlay, Boolean.TRUE);
        }
        final WeakReference<ViewGroup> ref = new WeakReference<>(overlay);
        final ViewTreeObserver.OnPreDrawListener listener = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                ViewGroup target = ref.get();
                if (target == null) {
                    if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                    return true;
                }
                try {
                    inspectAndFix(target);
                } catch (RuntimeException | LinkageError e) {
                    // A failed experiment must never prevent the YouTube player from drawing.
                    report("row inspection error: " + e.getClass().getSimpleName());
                }
                return true;
            }
        };
        observer.addOnPreDrawListener(listener);
        overlay.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) {
                if (observer.isAlive()) observer.removeOnPreDrawListener(listener);
                synchronized (ATTACHED) {
                    ATTACHED.remove(view);
                }
                view.removeOnAttachStateChangeListener(this);
            }
        });
        Log.i(TAG, "hook installed; enabled=" + Settings.CAPTION_WINDOW_GAP_MINIMAL.get()
                + " parent=" + overlay.getClass().getSimpleName());
    }

    private static void inspectAndFix(ViewGroup overlay) {
        int count = 0;
        ViewGroup window = null;
        for (int i = 0; i < overlay.getChildCount(); i++) {
            View child = overlay.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || !WINDOW.equals(child.getClass().getName()))
                continue;
            count++;
            if (child instanceof ViewGroup group) window = group;
        }
        if (count != 1 || window == null) {
            report("visible native windows=" + count
                    + " overlay=" + overlay.getWidth() + "x" + overlay.getHeight());
            return;
        }

        int numRows = window.getChildCount();
        StringBuilder evidence = new StringBuilder("window=(")
                .append(window.getLeft()).append(',').append(window.getTop()).append(' ')
                .append(window.getWidth()).append('x').append(window.getHeight())
                .append(") rows=").append(numRows);
        if (numRows < 2 || numRows > 4) {
            report(evidence + " native (not 2-4 rows)");
            return;
        }

        List<CaptionRowGapPolicy.Row> rows = new ArrayList<>(numRows);
        for (int i = 0; i < numRows; i++) {
            View row = window.getChildAt(i);
            evidence.append(" r").append(i).append("=(")
                    .append(row.getLeft()).append(',').append(row.getTop()).append(' ')
                    .append(row.getWidth()).append('x').append(row.getHeight()).append(')')
                    .append(" vis=").append(row.getVisibility())
                    .append(" cls=").append(row.getClass().getSimpleName());

            // Fail closed when YouTube does not use the expected actual text rows.
            if (row.getVisibility() != View.VISIBLE
                    || !ROW.equals(row.getClass().getSimpleName())) {
                report(evidence + " native (unknown row)");
                return;
            }
            rows.add(new CaptionRowGapPolicy.Row(
                    i, row.getLeft(), row.getTop(), row.getWidth(), row.getHeight()));
        }

        if (!Settings.CAPTION_WINDOW_GAP_MINIMAL.get()) {
            report(evidence + " setting OFF");
            return;
        }
        int gapPx = Math.max(1, (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 4.0f, window.getResources().getDisplayMetrics()));
        int[] newTops = CaptionRowGapPolicy.plan(
                window.getWidth(), window.getHeight(), rows, gapPx);
        if (newTops == null) {
            report(evidence + " native (no abnormally large inner gap)");
            return;
        }

        for (int i = 0; i < numRows; i++) {
            View row = window.getChildAt(i);
            if (newTops[i] != row.getTop()) {
                row.layout(row.getLeft(), newTops[i], row.getRight(), newTops[i] + row.getHeight());
            }
        }
        report(evidence + " compacted tops=" + java.util.Arrays.toString(newTops));
    }

    private static void report(String message) {
        long now = SystemClock.uptimeMillis();
        if (now - lastReportMs < 1200) return;
        lastReportMs = now;
        Log.i(TAG, message);
    }
}
