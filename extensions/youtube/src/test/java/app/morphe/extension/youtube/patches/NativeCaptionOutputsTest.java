package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;

public class NativeCaptionOutputsTest {
    private record Track(String code, String vss, String url)
            implements AutoTranslateCaptionsPatch.CaptionTrackInterface {
        public String patch_getLanguageCode() {
            return code;
        }

        public String patch_getVssId() {
            return vss;
        }

        public String patch_getUrl() {
            return url;
        }

        public AutoTranslateCaptionsPatch.CaptionTrackInterface patch_copyTranslation(
                String code, String url, String vss, CharSequence label) {
            return new Track(code, vss, url);
        }
    }

    private Track source(String code) {
        return new Track(
                code,
                "." + code,
                "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=" + code + "&sig=A%2BB");
    }

    private CaptionTracks.UploadedCandidate output(
            List<PreferredCaptionLanguagePatch.CaptionTrackInterface> catalog,
            Track source,
            String target) {
        return CaptionTracks.findAuthoredOutputTrack(catalog, source, target, false);
    }

    @Test
    public void uploadedTargetWinsOverMachineTranslation() {
        Track en = source("en"), tr = source("tr");
        assertSame(tr, output(List.of(en, tr), en, "tr").track());
    }

    @Test
    public void machineTranslationDoesNotClaimAuthoredStyleOwnership() {
        Track en = source("en");
        assertNull(output(List.of(en), en, "tr"));
        assertFalse(en.url().contains("tlang"));
    }

    @Test
    public void twoAuthoredTargetsRetainIndependentNativeDescriptors() {
        Track en = source("en"), tr = source("tr"), de = source("de");
        var first = output(List.of(en, tr, de), en, "tr");
        var second = output(List.of(en, tr, de), en, "de");
        assertSame(tr, first.track());
        assertSame(de, second.track());
        assertEquals(first.video(), second.video());
    }

    @Test
    public void selectedSourceVariantWinsForItsOwnLanguage() {
        Track first = source("en"),
                selected =
                        new Track(
                                "en",
                                ".en.alt",
                                first.url() + "&name=Alternate&audio_track_id=second");
        assertSame(selected, output(List.of(first, selected), selected, "en").track());
        assertNull(output(List.of(first, selected), selected, "tr"));
    }

    @Test
    public void automaticAndAlreadyTranslatedSourcesKeepExistingFallback() {
        Track automatic = new Track("en", "a.en", source("en").url() + "&kind=asr");
        assertNull(output(List.of(automatic), automatic, "tr"));
        Track translated = new Track("tr", "t.en.tr", source("en").url() + "&tlang=tr");
        assertNull(output(List.of(translated), translated, "de"));
    }

    @Test
    public void invalidOrForeignSourcesCannotCreateOutputTracks() {
        Track en = source("en");
        assertNull(output(List.of(en), en, "not-a-language"));
        Track bad =
                new Track("en", ".en", "https://example.org/api/timedtext?v=abcdefghijk&lang=en");
        assertNull(output(List.of(bad), bad, "tr"));
        Track foreign =
                new Track("tr", ".tr", source("tr").url().replace("abcdefghijk", "12345678901"));
        assertNull(output(List.of(en, foreign), en, "tr"));
    }

    @Test
    public void appRegionFallbackStillKeepsTheSelectedSourceVariant() {
        Track first = source("en"),
                selected = new Track("en", ".en", first.url() + "&audio_track_id=second");
        assertSame(
                selected,
                CaptionTracks.findAuthoredOutputTrack(
                                List.of(first, selected), selected, "en-US", true)
                        .track());
    }

    @Test
    public void missingAuthoredOutputDoesNotInvokeNativeTranslationCopy() {
        Track original = source("en");
        var broken =
                new AutoTranslateCaptionsPatch.CaptionTrackInterface() {
                    public String patch_getLanguageCode() {
                        return "en";
                    }

                    public String patch_getVssId() {
                        return ".en";
                    }

                    public String patch_getUrl() {
                        return original.url();
                    }

                    public AutoTranslateCaptionsPatch.CaptionTrackInterface patch_copyTranslation(
                            String code, String url, String vss, CharSequence label) {
                        throw new AssertionError("Authored output selection must not manufacture machine tracks");
                    }
                };
        assertNull(
                CaptionTracks.findAuthoredOutputTrack(
                        List.of(original), broken, "tr", false));
    }

    @Test
    public void exactRegionalAuthoredTrackOutranksSelectedGenericFallback() {
        Track en = source("en"), regional = source("en-US");
        assertSame(
                regional,
                CaptionTracks.findAuthoredOutputTrack(
                                List.of(en, regional), en, "en-US", true)
                        .track());
    }
}
