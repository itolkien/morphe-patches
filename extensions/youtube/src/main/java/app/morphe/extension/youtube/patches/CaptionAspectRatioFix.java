package app.morphe.extension.youtube.patches;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import java.util.WeakHashMap;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Observe a STABLE video-window aspect ratio, and use it solely to normalize
 * the height passed to YouTube's original subtitle size calculator when its
 * container later expands to near-square fullscreen.
 *
 * Never chooses a font size, changes YouTube's mode decision, or assumes 16:9.
 */
public final class CaptionAspectRatioFix {
    private static final String TAG = "MorpheCaptionFix";
    private static final WeakHashMap<View, State> STATES = new WeakHashMap<>();

    // An animation may change dimensions every frame; only a ratio maintained
    // for this period can replace the known-good media viewport ratio.
    private static final long STABILITY_MS = 550L;
    private static final float ASPECT_TOLERANCE = 0.008f;
    private static final long RECENT_ASPECT_TTL_MS = 20000L;
    private static final long LOG_INTERVAL_MS = 750L;

    private static final ThreadLocal<FontTrace> FONT_TRACE = new ThreadLocal<>();
    private static long lastFontTraceMs;

    private static final class FontTrace {
        final float scale;
        final int width;
        final int inputHeight;
        final boolean enabled;
        int effectiveHeight;

        FontTrace(float scale, int width, int height, boolean enabled) {
            this.scale = scale;
            this.width = width;
            this.inputHeight = height;
            this.effectiveHeight = height;
            this.enabled = enabled;
        }
    }

    /** Diagnostic hook: logs the size actually returned by the native YouTube selector. */
    public static float traceResult(float fontSize) {
        FontTrace trace = FONT_TRACE.get();
        FONT_TRACE.remove();
        if (trace != null) {
            long now = SystemClock.uptimeMillis();
            synchronized (STATES) {
                if (now - lastFontTraceMs >= 900L) {
                    Log.i(TAG, "font result=" + fontSize
                            + " scale=" + trace.scale
                            + " input=" + trace.width + "x" + trace.inputHeight
                            + " effective=" + trace.width + "x" + trace.effectiveHeight
                            + " geometry=" + (trace.enabled ? "on" : "off"));
                    lastFontTraceMs = now;
                }
            }
        }
        return fontSize;
    }

    private static float recentStableAspect = Float.NaN;
    private static long recentStableTimeMs;

    private CaptionAspectRatioFix() {}

    /**
     * Playback lifecycle hook. Caption views may be retained across videos;
     * never reuse geometry observed for an earlier video or audio track.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController ignoredController) {
        synchronized (STATES) {
            STATES.clear();
            recentStableAspect = Float.NaN;
            recentStableTimeMs = 0L;
        }
        Log.i(TAG, "video started: cleared cached viewport ratios");
    }

    private static final class State {
        float stableAspect = Float.NaN;
        float candidateAspect = Float.NaN;
        long candidateStartMs;
        int sourceWidth;
        int sourceHeight;
        long lastLogTimeMs;
    }

    private static boolean isNearSquareLandscape(int width, int height) {
        return width > 0 && height > 0 && width >= height
                && 4L * height >= 3L * width;
    }

    /** Original font scale, mode selector and font formula stay unchanged. */
    public static int normalizeHeight(View view, float scale, int width, int height) {
        final boolean enabled = Settings.CAPTION_GEOMETRY_DEBUG_ENABLED.get();
        final FontTrace trace = new FontTrace(scale, width, height, enabled);
        FONT_TRACE.set(trace);
        if (!enabled || view == null || width <= 0 || height <= 0) return height;
        synchronized (STATES) {
            long now = SystemClock.uptimeMillis();
            State state = STATES.get(view);
            if (state == null) {
                state = new State();
                STATES.put(view, state);
            }

            if (!isNearSquareLandscape(width, height)) {
                if (width > height && 4L * height < 3L * width) {
                    float observed = (float) height / (float) width;
                    if (!Float.isFinite(state.candidateAspect)
                            || Math.abs(observed - state.candidateAspect) > ASPECT_TOLERANCE) {
                        state.candidateAspect = observed;
                        state.candidateStartMs = now;
                    } else if (now - state.candidateStartMs >= STABILITY_MS) {
                        if (!Float.isFinite(state.stableAspect)
                                || Math.abs(observed - state.stableAspect) > ASPECT_TOLERANCE) {
                            state.stableAspect = observed;
                            state.sourceWidth = width;
                            state.sourceHeight = height;
                            Log.i(TAG, "stable reference=" + width + "x" + height
                                    + " heightPerWidth=" + observed);
                        }
                        recentStableAspect = state.stableAspect;
                        recentStableTimeMs = now;
                    }
                }
                return height;
            }

            float aspect = state.stableAspect;
            String source = "sameViewStable";
            if (!(aspect > 0.0f) || !Float.isFinite(aspect)) {
                long age = now - recentStableTimeMs;
                if (age >= 0L && age <= RECENT_ASPECT_TTL_MS
                        && Float.isFinite(recentStableAspect)
                        && recentStableAspect > 0.0f) {
                    aspect = recentStableAspect;
                    source = "recentStableViewport";
                }
            }
            if (!(aspect > 0.0f) || !Float.isFinite(aspect)) {
                if (state.lastLogTimeMs == 0 || now - state.lastLogTimeMs >= LOG_INTERVAL_MS) {
                    Log.i(TAG, "near-square input=" + width + "x" + height
                            + " reference=missing -> original geometry retained");
                    state.lastLogTimeMs = now;
                }
                return height;
            }

            int inferredHeight = Math.round(width * aspect);
            int effectiveHeight = Math.min(height, Math.max(1, inferredHeight));
            if (state.lastLogTimeMs == 0 || now - state.lastLogTimeMs >= LOG_INTERVAL_MS) {
                Log.i(TAG, "near-square input=" + width + "x" + height
                        + " videoViewport=" + width + "x" + effectiveHeight
                        + " learnedAspect=" + aspect
                        + " source=" + source
                        + " scale=" + scale
                        + " mode=originalYouTubeCalculation");
                state.lastLogTimeMs = now;
            }
            trace.effectiveHeight = effectiveHeight;
            return effectiveHeight;
        }
    }
}
