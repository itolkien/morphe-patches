package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.patches.captions.CaptionTranslationList;
import app.morphe.extension.youtube.patches.captions.NativeTranslationBridge;
import org.junit.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

public class PreferredCaptionRoutingTest {
    private static final String ARABIC =
            "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=ar&kind=asr&sig=a%2Bb";
    private static final String FRENCH =
            "https://www.youtube.com/api/timedtext?v=zyxwvutsrqp&lang=fr&sig=x%2By";

    private static final class Track implements CaptionTranslationList.CaptionTrack,
            PreferredCaptionLanguagePatch.CaptionTrackInterface {
        final String code;
        final String url;
        final String vss;
        final String name;

        Track(String code, String url, String vss, String name) {
            this.code = code;
            this.url = url;
            this.vss = vss;
            this.name = name;
        }

        @Override public String universalCaptions_getLanguageCode() { return code; }
        @Override public String universalCaptions_getVssId() { return vss; }
        @Override public String universalCaptions_getCaptionUrl() { return url; }
        @Override public CharSequence universalCaptions_getCaptionLabel() { return name; }
        @Override public CaptionTranslationList.CaptionTrack universalCaptions_copyTranslation(
                String language, String url, String vss, CharSequence label) {
            return new Track(language, url, vss, label.toString());
        }
        @Override public String patch_getLanguageCode() { return code; }
        @Override public String patch_getVssId() { return vss; }
    }

    private static Track arabic() {
        return new Track("ar", ARABIC, "a.ar", "Arabic");
    }

    @Test public void nativePreferredFallbackUsesVerifiedAudioSource() {
        Track wrongAudio = new Track("fr", FRENCH, ".fr", "French");
        // Menu does not contain the preferred target; only the verified source is safe.
        assertNull(PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                List.of(wrongAudio), "tr"));
        Track selected = (Track) NativeTranslationBridge.preferredTranslation(
                arabic(), "tr", Locale.ENGLISH);
        assertNotNull(selected);
        assertEquals("tr", selected.code);
        assertEquals("ta.ar.tr", selected.vss);
        assertEquals(ARABIC + "&tlang=tr", selected.url);
    }

    @Test public void missingOrUntrustedSourceCannotCreatePreferredTranslation() {
        assertNull(NativeTranslationBridge.preferredTranslation(null, "tr", Locale.ENGLISH));
        assertNull(NativeTranslationBridge.preferredTranslation(
                new Track("fr", "https://example.com/api/timedtext?v=abc&lang=fr",
                        ".fr", "French"), "tr", Locale.ENGLISH));
        assertNull(NativeTranslationBridge.preferredTranslation(arabic(), "qaa", Locale.ENGLISH));
    }

    @Test public void nativePreferredFallbackHandlesRegionalAliases() {
        Track portuguese = (Track) NativeTranslationBridge.preferredTranslation(
                arabic(), "pt-BR", Locale.ENGLISH);
        Track traditionalChinese = (Track) NativeTranslationBridge.preferredTranslation(
                arabic(), "zh-TW", Locale.ENGLISH);
        assertNotNull(portuguese);
        assertEquals("pt", portuguese.code);
        assertNotNull(traditionalChinese);
        assertEquals("zh-Hant", traditionalChinese.code);
        assertNull(NativeTranslationBridge.preferredTranslation(arabic(), "zh", Locale.ENGLISH));
    }

    @Test public void exactExistingTranslationBeatsBroaderLanguageMatch() {
        Track portuguese = new Track("pt", ARABIC + "&tlang=pt",
                "ta.ar.pt", "Portuguese");
        Track portugal = new Track("pt-PT", ARABIC + "&tlang=pt-PT",
                "ta.ar.pt-PT", "Portuguese (Portugal)");
        assertSame(portugal, PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                List.of(portuguese, portugal), "pt-PT"));
        assertSame(portuguese, PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                List.of(portuguese), "pt-PT"));
    }

    @Test public void missingNativeTranslationDoesNotPickAnotherLanguage() {
        Track french = new Track("fr", FRENCH, ".fr", "French");
        assertNull(PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                List.of(french), "tr"));
        assertNull(PreferredCaptionLanguagePatch.findAutoTranslatedTrack(null, "tr"));
    }
}
