package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;

public class StyledCaptionSourceRoutingTest {
    record Track(String language, String vss, String url)
            implements AutoTranslateCaptionsPatch.CaptionTrackInterface {
        public String patch_getLanguageCode() {
            return language;
        }

        public String patch_getVssId() {
            return vss;
        }

        public String patch_getUrl() {
            return url;
        }

        public AutoTranslateCaptionsPatch.CaptionTrackInterface patch_copyTranslation(
                String a, String b, String c, CharSequence d) {
            throw new AssertionError("Source selection must not manufacture tracks");
        }
    }

    private static final String URL =
            "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&sig=probe";

    private static PreferredCaptionLanguagePatch.SubtitleManagerInterface manager(Track source) {
        return new PreferredCaptionLanguagePatch.SubtitleManagerInterface() {
            public List<PreferredCaptionLanguagePatch.CaptionTrackInterface>
                    patch_getDirectCaptionTracks() {
                return List.of(source);
            }

            public List<PreferredCaptionLanguagePatch.CaptionTrackInterface>
                    patch_getAllCaptionTracks() {
                return List.of(source);
            }

            public List<PreferredCaptionLanguagePatch.CaptionTrackInterface>
                    patch_getAutoTranslateCaptionTracks() {
                return List.of();
            }

            public PreferredCaptionLanguagePatch.CaptionTrackInterface
                    patch_getCaptionTranslationSource() {
                return source;
            }

            public PreferredCaptionLanguagePatch.CaptionTrackInterface
                    patch_getCurrentAudioCaptionSource() {
                return source;
            }
        };
    }

    @Test
    public void translatedManualTrackUsesItsExactOriginalSource() {
        var source = new Track("en", ".en", URL);
        var target = new Track("tr", "t.en.tr", URL + "&tlang=tr");
        assertSame(
                source,
                PreferredCaptionLanguagePatch.sourceForStyledTranslation(manager(source), target));
    }

    @Test
    public void replacedNativeManagerKeepsExistingSelection() {
        var broken =
                (PreferredCaptionLanguagePatch.SubtitleManagerInterface)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {
                                    PreferredCaptionLanguagePatch.SubtitleManagerInterface.class
                                },
                                (proxy, method, args) -> {
                                    throw new IllegalStateException("Manager replaced");
                                });
        var target = new Track("tr", "t.en.tr", URL + "&tlang=tr");
        assertNull(PreferredCaptionLanguagePatch.sourceForStyledTranslation(broken, target));
    }

    @Test
    public void automaticCaptionsKeepNativeTranslation() {
        var source = new Track("en", "a.en", URL + "&kind=asr");
        var target = new Track("tr", "ta.en.tr", source.url() + "&tlang=tr");
        assertNull(
                PreferredCaptionLanguagePatch.sourceForStyledTranslation(manager(source), target));
    }

    @Test
    public void authoredOutputAndOtherAudioSourcesAreNotRedirected() {
        var source = new Track("en", ".en", URL + "&audio_track_id=one");
        assertNull(
                PreferredCaptionLanguagePatch.sourceForStyledTranslation(manager(source), source));
        var target = new Track("tr", "t.en.tr", URL + "&audio_track_id=two&tlang=tr");
        assertNull(
                PreferredCaptionLanguagePatch.sourceForStyledTranslation(manager(source), target));
    }
}
