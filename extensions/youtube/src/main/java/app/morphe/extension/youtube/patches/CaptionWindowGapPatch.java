package app.morphe.extension.youtube.patches;

import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import app.morphe.extension.youtube.settings.Settings;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
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
    private static final WeakHashMap<ViewGroup, Long> LAST_REPORT_MS = new WeakHashMap<>();

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
                    report(target, "row inspection error: " + e.getClass().getSimpleName());
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
        Log.i(TAG, "hook installed overlay@" + System.identityHashCode(overlay)
                + " enabled=" + Settings.CAPTION_WINDOW_GAP_MINIMAL.get()
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
        int[] overlayScreen = new int[2];
        overlay.getLocationOnScreen(overlayScreen);
        String prefix = "overlay@" + System.identityHashCode(overlay)
                + " screen=(" + overlayScreen[0] + "," + overlayScreen[1] + ")"
                + " size=" + overlay.getWidth() + "x" + overlay.getHeight()
                + " root=" + overlay.getRootView().getWidth() + "x" + overlay.getRootView().getHeight();
        if (count != 1 || window == null) {
            report(overlay, prefix + " visible native windows=" + count);
            return;
        }

        int numRows = window.getChildCount();
        int[] windowScreen = new int[2];
        window.getLocationOnScreen(windowScreen);
        StringBuilder evidence = new StringBuilder(prefix)
                .append(" windowScreen=(").append(windowScreen[0]).append(',')
                .append(windowScreen[1]).append(") window=(")
                .append(window.getLeft()).append(',').append(window.getTop()).append(' ')
                .append(window.getWidth()).append('x').append(window.getHeight())
                .append(") rows=").append(numRows);
        if (numRows < 2 || numRows > 4) {
            report(overlay, evidence + " native (not 2-4 rows)");
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
                report(overlay, evidence + " native (unknown row)");
                return;
            }
            rows.add(new CaptionRowGapPolicy.Row(
                    i, row.getLeft(), row.getTop(), row.getWidth(), row.getHeight()));
        }

        if (!Settings.CAPTION_WINDOW_GAP_MINIMAL.get()) {
            report(overlay, evidence + " setting OFF");
            return;
        }
        int gapPx = Math.max(1, (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 4.0f, window.getResources().getDisplayMetrics()));
        int[] newTops = CaptionRowGapPolicy.plan(
                window.getWidth(), window.getHeight(), rows, gapPx);
        if (newTops == null) {
            report(overlay, evidence + " native (no abnormally large inner gap)");
            return;
        }

        for (int i = 0; i < numRows; i++) {
            View row = window.getChildAt(i);
            if (newTops[i] != row.getTop()) {
                row.layout(row.getLeft(), newTops[i], row.getRight(), newTops[i] + row.getHeight());
            }
        }
        report(overlay, evidence + " compacted tops=" + java.util.Arrays.toString(newTops));
    }

    private static void report(ViewGroup overlay, String message) {
        long now = SystemClock.uptimeMillis();
        synchronized (LAST_REPORT_MS) {
            Long last = LAST_REPORT_MS.get(overlay);
            if (last != null && now - last < 1200L) return;
            LAST_REPORT_MS.put(overlay, now);
        }
        Log.i(TAG, message + inspectRootSubtitleWindows(overlay));
    }

    /**
     * A second caption view might belong to a different overlay, not the
     * currently inspected aose instance. Scan the root ONLY on throttled logs.
     */
    private static String inspectRootSubtitleWindows(ViewGroup overlay) {
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(overlay.getRootView());
        int visited = 0;
        int found = 0;
        StringBuilder result = new StringBuilder(" rootCaptionViews=");
        StringBuilder views = new StringBuilder();
        while (!pending.isEmpty() && visited < 4000) {
            View current = pending.removeFirst();
            visited++;
            if (WINDOW.equals(current.getClass().getName())) {
                found++;
                if (found <= 8) {
                    int[] xy = new int[2];
                    current.getLocationOnScreen(xy);
                    views.append(" #").append(found).append('@')
                            .append(xy[0]).append(',').append(xy[1])
                            .append(' ').append(current.getWidth()).append('x')
                            .append(current.getHeight()).append(" vis=")
                            .append(current.getVisibility());
                }
            }
            if (current instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) {
                    pending.addLast(group.getChildAt(i));
                }
            }
        }
        return result.append(found).append(" nodes=").append(visited).append(views).toString();
    }
}
