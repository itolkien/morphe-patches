package app.morphe.extension.youtube.patches;

import android.text.SpannableStringBuilder;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import app.morphe.extension.youtube.settings.Settings;
import java.util.Collections;
import java.util.List;

/** Opt-in translated-only workaround. Does not modify the native timeline or original tracks. */
public final class TranslatedCaptionLayoutPatch {
    private TranslatedCaptionLayoutPatch() {}

    /** Replaced only when the optional bytecode patch is selected. */
    public static boolean isIncluded() { return false; }

    public static boolean isActive() {
        return isIncluded() && Settings.TRANSLATED_CAPTIONS_REFLOW_TEST.get();
    }

    public interface Pen { Object patch_copySizeOnly(); }

    public static Object safePen(Object pen) {
        if (TranslatedCaptionPensPatch.isTranslatedTrack() && isActive() && pen instanceof Pen nativePen) {
            return nativePen.patch_copySizeOnly();
        }
        return pen;
    }

    /** Captured when the native timeline is built, never inferred from the UI thread. */
    public static boolean captureTranslatedTrack() {
        return TranslatedCaptionPensPatch.isTranslatedTrack();
    }

    public static List<?> reflow(List<?> frame, boolean translated) {
        if (!translated) return frame;
        return reflow(frame, translated, Settings.TRANSLATED_CAPTIONS_REFLOW_TEST.get());
    }

    /** Explicit mode also supports controlled Android replay without changing user preferences. */
    public static List<?> reflow(List<?> frame, boolean translated, boolean enabled) {
        try {
            List<CaptionReflowPolicy.Cue> cues = CaptionReflowPolicy.select(frame, translated, enabled);
            if (cues == null || cues.isEmpty()) return frame;
            SpannableStringBuilder text = new SpannableStringBuilder();
            for (CaptionReflowPolicy.Cue cue : cues) {
                CharSequence part = readableText(cue.patch_getText());
                if (part.length() == 0) continue;
                if (text.length() != 0) text.append(' ');
                text.append(part);
            }
            if (text.length() == 0) return frame;
            Object merged = cues.get(0).patch_withText(text);
            return merged == null ? frame : Collections.singletonList(merged);
        } catch (RuntimeException | LinkageError ignored) {
            // Unknown native layouts must keep their existing rendering path.
            return frame;
        }
    }

    /** Retain source size/emphasis, remove source colors/opacity and hard line breaks. */
    static CharSequence readableText(CharSequence source) {
        SpannableStringBuilder result = new SpannableStringBuilder(source);
        for (Object span : result.getSpans(0, result.length(), Object.class)) {
            if (!(span instanceof RelativeSizeSpan || span instanceof StyleSpan || span instanceof UnderlineSpan)) {
                result.removeSpan(span);
            }
        }
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') result.replace(i, i + 1, " ");
        }
        for (int i = result.length() - 1; i >= 0; i--) {
            if (result.charAt(i) == ' ' && (i == 0 || i == result.length() - 1 || result.charAt(i - 1) == ' ')) {
                result.delete(i, i + 1);
            }
        }
        return result;
    }
}
