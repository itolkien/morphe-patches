package app.morphe.extension.youtube.patches;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import app.morphe.extension.youtube.settings.Settings;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Read-only Android diagnostics for one problematic video.
 *
 * Captures:
 * 1. Native timed-text sample output (apnt.c -> apoo windows, before View rendering).
 * 2. The actual native SubtitleWindowView/apow tree (after layout, before drawing).
 *
 * It NEVER modifies the original cue list, native subtitle strings, spans,
 * fonts, timestamps, anchors, view measurements or view coordinates.
 */
@SuppressWarnings("unused")
public final class CaptionSourceRenderProbe {
    private static final String SOURCE = "MorpheCaptionSource";
    private static final String VIEW = "MorpheCaptionView";
    private static final String WINDOW =
            "com.google.android.libraries.youtube.player.subtitles.ui.SubtitleWindowView";

    private static final WeakHashMap<ViewGroup, Boolean> INSTALLED = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, Long> LAST_VIEW_TRACE = new WeakHashMap<>();
    private static long lastSourceTrace;

    private CaptionSourceRenderProbe() {}

    public static List<?> traceNativeFrame(List<?> nativeFrame) {
        try {
            if (!Settings.CAPTION_SOURCE_RENDER_PROBE.get()) return nativeFrame;
            long now = SystemClock.uptimeMillis();
            synchronized (CaptionSourceRenderProbe.class) {
                if (now - lastSourceTrace < 450) return nativeFrame;
                lastSourceTrace = now;
            }

            StringBuilder out = new StringBuilder("sample frame=");
            if (nativeFrame == null) {
                Log.i(SOURCE, out.append("null").toString());
                return null;
            }

            out.append(nativeFrame.size());
            for (int i = 0, limit = Math.min(nativeFrame.size(), 5); i < limit; i++) {
                Object cue = nativeFrame.get(i);
                if (cue == null) {
                    out.append(" [").append(i).append(" null]");
                    continue;
                }
                Object id = getField(cue, "a");
                Object time = getField(cue, "b");
                Object anchor = getField(cue, "c");
                Object visible = getField(cue, "d");
                Object full = getField(cue, "e");
                out.append(" [").append(i).append(" id=").append(id)
                        .append(" t=").append(time)
                        .append(" anchor=(")
                        .append(getField(anchor, "b")).append(',')
                        .append(getField(anchor, "c")).append(',')
                        .append(getField(anchor, "d"))
                        .append(") flags=(")
                        .append(getField(anchor, "e")).append(',')
                        .append(getField(anchor, "f")).append(')')
                        .append(" visible{").append(CaptionProbeText.describe(visible))
                        .append("} full{").append(CaptionProbeText.describe(full)).append("}]");
            }
            Log.i(SOURCE, out.toString());
        } catch (RuntimeException | LinkageError e) {
            Log.w(SOURCE, "probe error=" + e.getClass().getSimpleName());
        }
        // This is a pure observer; always return the original unmodified list.
        return nativeFrame;
    }

    private static Object getField(Object obj, String name) {
        if (obj == null) return null;
        try {
            Field field = obj.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(obj);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return "?";
        }
    }

    public static void observeCaptionOverlay(ViewGroup overlay) {
        if (overlay == null) return;
        ViewTreeObserver observer = overlay.getViewTreeObserver();
        if (!observer.isAlive()) return;

        synchronized (INSTALLED) {
            if (INSTALLED.containsKey(overlay)) return;
            INSTALLED.put(overlay, true);
        }

        WeakReference<ViewGroup> weak = new WeakReference<>(overlay);
        ViewTreeObserver.OnPreDrawListener listener = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                ViewGroup target = weak.get();
                if (target == null) {
                    if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                    return true;
                }
                try {
                    if (Settings.CAPTION_SOURCE_RENDER_PROBE.get()) traceRender(target);
                } catch (RuntimeException | LinkageError e) {
                    Log.w(VIEW, "render probe error=" + e.getClass().getSimpleName());
                }
                return true;
            }
        };
        observer.addOnPreDrawListener(listener);
        overlay.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) {
                if (observer.isAlive()) observer.removeOnPreDrawListener(listener);
                synchronized (INSTALLED) {
                    INSTALLED.remove(v);
                }
                synchronized (LAST_VIEW_TRACE) {
                    LAST_VIEW_TRACE.remove(v);
                }
                v.removeOnAttachStateChangeListener(this);
            }
        });
        Log.i(VIEW, "hook installed overlay@" + System.identityHashCode(overlay)
                + " enabled=" + Settings.CAPTION_SOURCE_RENDER_PROBE.get());
    }

    private static void traceRender(ViewGroup overlay) {
        long now = SystemClock.uptimeMillis();
        synchronized (LAST_VIEW_TRACE) {
            Long last = LAST_VIEW_TRACE.get(overlay);
            if (last != null && now - last < 700) return;
            LAST_VIEW_TRACE.put(overlay, now);
        }

        View root = overlay.getRootView();
        StringBuilder out = new StringBuilder("overlay@")
                .append(System.identityHashCode(overlay))
                .append(" overlay=").append(describe(overlay))
                .append(" root=").append(root.getWidth()).append('x').append(root.getHeight());

        // Traverse the whole root view so a second subtitle overlay cannot escape
        // detection just because it is not a direct child of the observed aose view.
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        int visited = 0;
        int nativeWindows = 0;
        while (!pending.isEmpty() && visited < 2500) {
            View v = pending.removeFirst();
            visited++;
            if (WINDOW.equals(v.getClass().getName())) {
                nativeWindows++;
                if (nativeWindows <= 6) {
                    out.append(" window#").append(nativeWindows)
                            .append(' ').append(describe(v));
                    if (v instanceof ViewGroup group) {
                        out.append(" rows=").append(group.getChildCount());
                        for (int i = 0; i < Math.min(group.getChildCount(), 5); i++) {
                            View row = group.getChildAt(i);
                            out.append(" row").append(i)
                                    .append(':').append(row.getClass().getSimpleName())
                                    .append(' ').append(describe(row));
                        }
                    }
                }
            }
            if (v instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) {
                    pending.addLast(group.getChildAt(i));
                }
            }
        }
        out.append(" rootCaptionWindows=").append(nativeWindows)
                .append(" scannedViews=").append(visited);
        Log.i(VIEW, out.toString());
    }

    private static String describe(View v) {
        int[] xy = new int[2];
        v.getLocationOnScreen(xy);
        return v.getClass().getSimpleName()
                + "@screen(" + xy[0] + "," + xy[1] + ")"
                + " local(" + v.getLeft() + "," + v.getTop() + ")"
                + " size=" + v.getWidth() + "x" + v.getHeight()
                + " vis=" + v.getVisibility();
    }
}
