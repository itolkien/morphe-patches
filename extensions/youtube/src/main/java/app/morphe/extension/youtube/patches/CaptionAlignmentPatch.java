package app.morphe.extension.youtube.patches;

import android.text.Layout;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Only replaces the native left-alignment override, and only when explicitly
 * enabled. Caption text, timing and window placement remain native.
 */
@SuppressWarnings("unused")
public final class CaptionAlignmentPatch {
    private CaptionAlignmentPatch() {}

    public static Layout.Alignment overrideAlignment(Layout.Alignment original) {
        return Settings.CENTER_LEFT_ALIGNED_CAPTIONS.get()
                ? Layout.Alignment.ALIGN_CENTER
                : original;
    }
}
