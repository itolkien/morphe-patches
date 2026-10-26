package app.morphe.extension.youtube.patches;

import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Experimental post-layout correction for separated auto-translated subtitle windows.
 * Only positions are changed; font sizing, cue content and measured dimensions are untouched.
 * Disabled by default. Never use global changes for native/creator-positioned captions.
 */
@SuppressWarnings("unused")
public final class CaptionWindowStackPatch {
    private static final String TAG = "MorpheCaptionWindows";
    private static volatile boolean translatedTrackSelected;
    private static String lastLoggedMode = "";

    private CaptionWindowStackPatch() {}

    /** Called after Morphe's preferred-caption substitution has selected a track. */
    public static void onTrackSelected(Object track) {
        boolean translated = track instanceof PreferredCaptionLanguagePatch.CaptionTrackInterface candidate
                && candidate.patch_getVssId() != null
                && candidate.patch_getVssId().startsWith("t");
        translatedTrackSelected = translated;
        String mode = translated ? "translated" : "original-or-none";
        if (!mode.equals(lastLoggedMode)) {
            lastLoggedMode = mode;
            Log.i(TAG, "selected track=" + mode);
        }
    }

    /** Called immediately before aose.onLayout returns, after native window layout. */
    public static void afterNativeLayout(ViewGroup subtitleOverlay) {
        if (!translatedTrackSelected
                || !Settings.STACK_TRANSLATED_CAPTION_WINDOWS_TEST.get()
                || subtitleOverlay == null) return;
        final int width = subtitleOverlay.getWidth();
        final int height = subtitleOverlay.getHeight();
        if (width <= 0 || height <= 0) return;

        List<CaptionWindowStackPolicy.Rect> windows = new ArrayList<>();
        for (int i = 0; i < subtitleOverlay.getChildCount(); i++) {
            View child = subtitleOverlay.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE
                    && child.getClass().getName().equals(
                        "com.google.android.libraries.youtube.player.subtitles.ui.SubtitleWindowView")) {
                windows.add(new CaptionWindowStackPolicy.Rect(i,
                        child.getLeft(), child.getTop(), child.getWidth(), child.getHeight()));
            }
        }

        int gapPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4.0f,
                subtitleOverlay.getResources().getDisplayMetrics());
        List<CaptionWindowStackPolicy.Rect> changed =
                CaptionWindowStackPolicy.plan(width, height, windows, gapPx);
        if (changed == null) return;

        for (CaptionWindowStackPolicy.Rect rect : changed) {
            View child = subtitleOverlay.getChildAt(rect.index);
            child.layout(rect.left, rect.top, rect.left + rect.width, rect.top + rect.height);
        }
        Log.i(TAG, "restacked translated caption windows=" + changed.size()
                + " frame=" + width + "x" + height);
    }
}
