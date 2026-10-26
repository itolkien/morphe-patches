package app.morphe.extension.youtube.patches;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import java.util.WeakHashMap;

/**
 * Observe a STABLE video-window aspect ratio, and use it solely to normalize
 * the height passed to YouTube's original subtitle size calculator when its
 * container later expands to near-square fullscreen.
 *
 * Never chooses a font size or changes YouTube's mode decision.
 * For safety, this experimental candidate currently normalizes only near-square
 * windows after observing a stable 16:9-ish reference. Other media remain native.
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

    private static float recentStableAspect = Float.NaN;
    private static long recentStableTimeMs;

    private CaptionAspectRatioFix() {}

    /** The video ID hook can fire repeatedly for the same video. */
    private static String currentVideoId;

    /**
     * Reset learned viewport geometry only when the actual video changes.
     * The player controller can be reused across video navigation.
     */
    public static void onVideoId(String videoId) {
        if (videoId == null || videoId.isEmpty()) return;

        synchronized (STATES) {
            if (videoId.equals(currentVideoId)) return;
            currentVideoId = videoId;
            STATES.clear();
            recentStableAspect = Float.NaN;
            recentStableTimeMs = 0L;
        }
        // Keep the video ID private: only report that a switch was observed.
        Log.i(TAG, "video ID changed: cleared cached viewport ratios");
    }

    private static final class State {
        float stableAspect = Float.NaN;
        float candidateAspect = Float.NaN;
        long candidateStartMs;
        int sourceWidth;
        int sourceHeight;
        long lastLogTimeMs;
    }

    /** Only the near-square Fold fullscreen layout is targeted (height / width >= 0.85). */
    static boolean isNearSquareLandscape(int width, int height) {
        return width > 0 && height > 0 && width >= height
                && 20L * height >= 17L * width;
    }

    /** Transitional and outer-display dimensions are not reliable video references. */
    static boolean isTrustedReferenceAspect(float aspect) {
        return Float.isFinite(aspect)
                && Math.abs(aspect - (9.0f / 16.0f)) <= 0.035f;
    }

    /** Original font scale, mode selector and font formula stay unchanged. */
    public static int normalizeHeight(View view, float scale, int width, int height) {
        if (view == null || width <= 0 || height <= 0) return height;
        synchronized (STATES) {
            long now = SystemClock.uptimeMillis();
            State state = STATES.get(view);
            if (state == null) {
                state = new State();
                STATES.put(view, state);
            }

            if (!isNearSquareLandscape(width, height)) {
                if (width > height) {
                    float observed = (float) height / (float) width;
                    if (!isTrustedReferenceAspect(observed)) {
                        // A 21:9 display, 4:3 media or transition is not proof of
                        // the currently displayed video's subtitle viewport.
                        return height;
                    }
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
            if (!isTrustedReferenceAspect(aspect)) {
                long age = now - recentStableTimeMs;
                if (age >= 0L && age <= RECENT_ASPECT_TTL_MS
                        && isTrustedReferenceAspect(recentStableAspect)) {
                    aspect = recentStableAspect;
                    source = "recentStableViewport";
                }
            }
            if (!isTrustedReferenceAspect(aspect)) {
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
            return effectiveHeight;
        }
    }
}
