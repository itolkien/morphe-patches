package app.morphe.extension.youtube.patches;

import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.util.Log;
import app.morphe.extension.youtube.settings.Settings;
import java.util.Collections;
import java.util.List;

/**
 * Minimal targeted workaround: the native sampler has already produced exactly
 * one active translated rolling caption. Only flatten its hard source line breaks.
 * Original timeline, text spans, time/ID, anchors and YouTube layout are preserved.
 */
@SuppressWarnings("unused")
public final class TranslatedCaptionHardBreakPatch {
    private static final String TAG = "MorpheCaptionBreakFix";
    private static long lastLogMs;

    private TranslatedCaptionHardBreakPatch() {}

    public interface Cue {
        CharSequence patch_getVisible();
        CharSequence patch_getFull();
        boolean patch_isScrollable();
        Object patch_withTexts(CharSequence visible, CharSequence full);
    }

    /** Called inside the native XML parse thread when its timeline is constructed. */
    public static boolean captureTranslation() {
        boolean translated = TranslatedCaptionPensPatch.isTranslatedTrack();
        if (Settings.TRANSLATED_CAPTIONS_FLATTEN_BREAKS_TEST.get()) {
            Log.i(TAG, "native caption timeline captured translated=" + translated);
        }
        return translated;
    }

    /** Called after the unmodified native YouTube apnt.c(long) samples the timeline. */
    public static List<?> normalize(List<?> active, boolean translated) {
        if (!translated || !Settings.TRANSLATED_CAPTIONS_FLATTEN_BREAKS_TEST.get())
            return active;
        if (active == null || active.size() != 1 || !(active.get(0) instanceof Cue cue)
                || !cue.patch_isScrollable()) return active;

        try {
            CharSequence visible = cue.patch_getVisible();
            CharSequence full = cue.patch_getFull();
            if (visible == null || full == null) return active;
            if (visible.length() > 2048 || full.length() > 2048) return active;
            if (!CaptionHardBreakPolicy.shouldNormalize(visible)
                    && !CaptionHardBreakPolicy.shouldNormalize(full)) return active;

            CharSequence newVisible = flatten(visible);
            CharSequence newFull = flatten(full);
            if (newVisible.length() == 0 || newFull.length() == 0) return active;
            Object replaced = cue.patch_withTexts(newVisible, newFull);
            if (replaced == null) return active;

            long now = SystemClock.uptimeMillis();
            synchronized (TranslatedCaptionHardBreakPatch.class) {
                if (now - lastLogMs >= 1200) {
                    lastLogMs = now;
                    Log.i(TAG, "normalized translated rolling cue"
                            + " visibleLines=" + breaks(visible)
                            + " fullLines=" + breaks(full)
                            + " newLengths=" + newVisible.length() + "/" + newFull.length());
                }
            }
            return Collections.singletonList(replaced);
        } catch (RuntimeException | LinkageError e) {
            // Never interrupt YouTube's native subtitle playback.
            return active;
        }
    }

    private static int breaks(CharSequence value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) == '\n' || value.charAt(i) == '\r') count++;
        return count;
    }

    /**
     * Preserve spans by editing a copy of the native CharSequence, not turning
     * it into a plain String. Delete duplicate spaces after replacing line breaks.
     */
    private static CharSequence flatten(CharSequence source) {
        if (!CaptionHardBreakPolicy.shouldNormalize(source)) return source;
        SpannableStringBuilder changed = new SpannableStringBuilder(source);
        for (int i = changed.length() - 1; i >= 0; i--) {
            char c = changed.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') changed.replace(i, i + 1, " ");
        }
        for (int i = changed.length() - 1; i >= 0; i--) {
            if (changed.charAt(i) == ' ' &&
                    (i == 0 || i == changed.length() - 1 || changed.charAt(i - 1) == ' ')) {
                changed.delete(i, i + 1);
            }
        }
        return changed;
    }
}
