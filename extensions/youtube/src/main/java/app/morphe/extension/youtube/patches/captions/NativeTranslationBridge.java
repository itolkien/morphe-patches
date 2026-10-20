package app.morphe.extension.youtube.patches.captions;

import java.util.List;
import java.util.Locale;
import app.morphe.extension.youtube.patches.captions.CaptionTranslationList.CaptionTrack;

/** Menu augmentation only. Caption visibility and preferred-language policy belong to Morphe. */
public final class NativeTranslationBridge {
    private NativeTranslationBridge() { }

    private static Locale menuLocale() {
        try {
            Locale locale = app.morphe.extension.shared.requests.Requester.getAppLocale();
            return locale == null ? Locale.getDefault() : locale;
        } catch (RuntimeException | LinkageError ignored) {
            return Locale.getDefault();
        }
    }

    /** Preferred-language fallback uses the same verified current-audio original
     * as the native Auto-translate menu, even when extra menu languages are disabled.
     */
    public static app.morphe.extension.youtube.patches.PreferredCaptionLanguagePatch.CaptionTrackInterface
            preferredTranslation(Object eligible, String language, Locale locale) {
        if (!(eligible instanceof CaptionTrack source) || locale == null) return null;
        OriginalCaptionSource original = OriginalCaptionSource.from(source);
        if (original == null) return null;

        String target = preferredTarget(language);
        if (target == null) return null;
        CaptionTrack track = original.translate(target, locale);
        return track instanceof app.morphe.extension.youtube.patches.PreferredCaptionLanguagePatch.CaptionTrackInterface
                ? (app.morphe.extension.youtube.patches.PreferredCaptionLanguagePatch.CaptionTrackInterface) track
                : null;
    }

    private static String preferredTarget(String requested) {
        if (!CaptionTranslationList.validLanguageCode(requested)) return null;
        String key = CaptionTranslationList.languageKey(requested);
        for (String code : CaptionTranslationList.TARGETS) {
            if (code.equalsIgnoreCase(requested)) return code;
        }
        for (String code : CaptionTranslationList.TARGETS) {
            if (CaptionTranslationList.languageKey(code).equals(key)) return code;
        }
        String primary = key.substring(0, key.indexOf('-') < 0 ? key.length() : key.indexOf('-'));
        // Never guess between Traditional and Simplified Chinese.
        if ("zh".equals(primary)) return null;
        for (String code : CaptionTranslationList.TARGETS) {
            if (code.equals(primary)) return code;
        }
        return null;
    }

    public static List<CaptionTrack> fallbackDirect(List<CaptionTrack> tracks,
            EmptyTranslationFallback.CaptionSource source) {
        if (!app.morphe.extension.youtube.settings.Settings.AUTO_TRANSLATE_ADDITIONAL_LANGUAGES.get())
            return tracks;
        return EmptyTranslationFallback.direct(tracks, source, menuLocale());
    }

    public static List<CaptionTrack> fallbackTranslated(List<CaptionTrack> tracks,
            EmptyTranslationFallback.CaptionSource source) {
        if (!app.morphe.extension.youtube.settings.Settings.AUTO_TRANSLATE_ADDITIONAL_LANGUAGES.get())
            return tracks;
        return EmptyTranslationFallback.translated(tracks, source, menuLocale());
    }
}
