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
            AutoTranslateCaptionsPatch.CaptionTrackInterface {
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
        @Override public String patch_getUrl() { return url; }
        @Override public AutoTranslateCaptionsPatch.CaptionTrackInterface patch_copyTranslation(
                String language, String url, String vss, CharSequence label) {
            return new Track(language, url, vss, label.toString());
        }
        @Override public String patch_getLanguageCode() { return code; }
        @Override public String patch_getVssId() { return vss; }
        @Override public String toString() { return name; }
    }

    private static Track arabic() {
        return new Track("ar", ARABIC, "a.ar", "Arabic");
    }

    @Test public void nativeFallbackUsesEligibleAudioSourceNotAnotherTrack() {
        Track wrongAudio = new Track("fr", FRENCH, ".fr", "French");
        PreferredCaptionLanguagePatch.CaptionTrackInterface selected =
                PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                        List.of(wrongAudio), List.of(), wrongAudio, "tr", Locale.ENGLISH,
                        true, arabic());
        assertNotNull(selected);
        Track translated = (Track) selected;
        assertEquals("tr", translated.code);
        assertEquals("ta.ar.tr", translated.vss);
        assertEquals(ARABIC + "&tlang=tr", translated.url);
    }

    @Test public void missingEligibleSourceCannotGuessWrongAudio() {
        Track otherAudio = new Track("fr", FRENCH, ".fr", "French");
        assertNull(PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                List.of(otherAudio), List.of(), otherAudio, "tr", Locale.ENGLISH,
                true, null));
    }

    @Test public void nativeSourceSupportsLocaleAliasAndRegion() {
        Track source = arabic();
        Track portuguese = (Track) NativeTranslationBridge.preferredTranslation(
                source, "pt-BR", Locale.ENGLISH);
        Track traditionalChinese = (Track) NativeTranslationBridge.preferredTranslation(
                source, "zh-TW", Locale.ENGLISH);
        assertNotNull(portuguese);
        assertEquals("pt", portuguese.code);
        assertNotNull(traditionalChinese);
        assertEquals("zh-Hant", traditionalChinese.code);
        assertNull(NativeTranslationBridge.preferredTranslation(source, "qaa", Locale.ENGLISH));
        assertNull(NativeTranslationBridge.preferredTranslation(source, "zh", Locale.ENGLISH));
    }

    @Test public void existingTargetSpecificNativeTranslationWins() {
        Track portuguese = new Track("pt", ARABIC + "&tlang=pt", "ta.ar.pt", "Portuguese");
        Track portugal = new Track("pt-PT", ARABIC + "&tlang=pt-PT",
                "ta.ar.pt-PT", "Portuguese (Portugal)");
        assertSame(portugal, PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                List.of(arabic()), List.of(portuguese, portugal), null, "pt-PT",
                Locale.ENGLISH, true, arabic()));
    }

    @Test public void unsupportedVersionsKeepLegacyPreferredFallback() {
        Track source = arabic();
        PreferredCaptionLanguagePatch.CaptionTrackInterface selected =
                PreferredCaptionLanguagePatch.findAutoTranslatedTrack(
                        List.of(source), List.of(), null, "bn", Locale.ENGLISH,
                        false, null);
        assertNotNull(selected);
        assertEquals("bn", selected.patch_getLanguageCode());
        assertEquals(ARABIC + "&tlang=bn", ((Track) selected).url);
    }
}
