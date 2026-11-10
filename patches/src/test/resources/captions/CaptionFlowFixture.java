package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;
import java.util.*;

/** Selection/loader scenarios; only dispatch, startup flags and native row boundaries are fake. */
public class CaptionFlowFixture {
    record Track(String language, String audio) implements AutoTranslateCaptionsPatch.CaptionTrackInterface {
        public String patch_getLanguageCode() { return language; }
        public String patch_getVssId() { return "." + language; }
        public String patch_getUrl() {
            return "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=" + language
                    + "&audio_track_id=" + audio;
        }
        public AutoTranslateCaptionsPatch.CaptionTrackInterface patch_copyTranslation(
                String language, String url, String vss, CharSequence label) {
            throw new AssertionError("No synthetic native translation track expected");
        }
    }
    record Request(Track track) implements TranslatedCaptionPensPatch.CaptionRequestInterface {
        public Object patch_getCaptionTrack() { return track; }
    }
    record Cue(String text) implements StableCaptionBlocks.Cue {
        public int patch_getCaptionId() { return 7; }
        public boolean patch_isCaptionVisible() { return true; }
        public CharSequence patch_getOriginalCaptionFull() { return text; }
    }
    static class Timeline implements StableCaptionBlocks.Timeline {
        final String text;
        StableCaptionBlocks.State state;
        Timeline(String text) { this.text = text; }
        public List<?> patch_getOriginalCaptions(long time) { return List.of(new Cue(text)); }
        public StableCaptionBlocks.State patch_getBlockState() { return state; }
        public void patch_setBlockState(StableCaptionBlocks.State state) { this.state = state; }
        public StableCaptionBlocks.Timeline patch_mergeWith(StableCaptionBlocks.Timeline other) {
            return new Timeline("merged");
        }
        public List<Long> patch_getCaptionEventTimes() { return List.of(0L, 1000L); }
    }
    static class Handler implements CaptionTracks.CaptionTrackHandlerInterface {
        final Map<Object, CaptionTracks.UploadedCaptionCallback> callbacks = new LinkedHashMap<>();
        int reloads, updates;
        public void patch_fetchCaption(Object track, CaptionTracks.UploadedCaptionCallback callback) {
            callbacks.put(track, callback);
        }
        public void patch_reloadCaption() { reloads++; }
        public void patch_updateCaption(long time, float speed) {
            check(time == 100 && speed == 1.25f, "Refresh must use native player time/speed");
            updates++;
        }
    }
    static class Manager implements PreferredCaptionLanguagePatch.SubtitleManagerInterface {
        final Track source;
        Manager(Track source) { this.source = source; }
        public List<PreferredCaptionLanguagePatch.CaptionTrackInterface> patch_getAllCaptionTracks() {
            return List.of(source, new Track("de", source.audio), new Track("fr", source.audio));
        }
        public List<PreferredCaptionLanguagePatch.CaptionTrackInterface> patch_getDirectCaptionTracks() {
            return patch_getAllCaptionTracks();
        }
        public List<PreferredCaptionLanguagePatch.CaptionTrackInterface> patch_getAutoTranslateCaptionTracks() {
            return List.of();
        }
        public PreferredCaptionLanguagePatch.CaptionTrackInterface patch_getCaptionTranslationSource() {
            return source;
        }
        public PreferredCaptionLanguagePatch.CaptionTrackInterface patch_getCurrentAudioCaptionSource() {
            return source;
        }
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static void select(Track track, Timeline timeline) {
        CaptionTracks.onSelectedTrack(track);
        CaptionTracks.onNativeResult(new Request(track), timeline);
    }
    static Handler load(Track source) {
        return load(source, new Manager(source));
    }
    static Handler load(Track source, Manager manager) {
        CaptionTracks.onSelectedTrack(source);
        CaptionTracks.inspectUploadedTracks(manager, source);
        Handler handler = new Handler();
        CaptionTracks.fetchUploadedTracks(handler, source);
        check(handler.callbacks.size() == 2, "Both authored languages should be requested");
        return handler;
    }
    static UploadedCaptionPair pair() {
        var merged = (Timeline) CaptionTracks.mergeUploadedTracks(new Timeline("source"));
        check(merged.state != null && merged.state.nativePair != null, "Expected native uploaded pair");
        return merged.state.nativePair.tracks;
    }
    static CaptionTracks.UploadedCaptionCallback callback(Handler handler, String language) {
        return handler.callbacks.entrySet().stream()
                .filter(e -> ((Track) e.getKey()).language.equals(language))
                .findFirst().orElseThrow().getValue();
    }

    public static void captureLatest() {
        Timeline[] timelines = new Timeline[6];
        for (int i = 0; i < timelines.length; i++) {
            timelines[i] = new Timeline("source " + i);
            select(new Track("en", "audio" + i), timelines[i]);
        }
        check(Utils.workers.size() <= 4, "Capture workers must remain bounded");
        Utils.drain();
        check(timelines[5].state != null && timelines[5].state.policy != null
                && timelines[5].state.translation != null, "Newest capture must eventually bind a session");
        for (int i = 0; i < 5; i++)
            check(timelines[i].state == null, "Superseded captures must not publish");
    }

    public static void captureSuperseded() {
        Timeline pending = null;
        for (int i = 0; i < 5; i++) {
            pending = new Timeline("old " + i);
            select(new Track("en", "audio" + i), pending);
        }
        CaptionTracks.onSelectedTrack(null);
        Utils.drain();
        check(pending.state == null, "CC off must discard the pending capture");
        check(!CaptionTracks.hasUploadedTracks(), "CC off must stay off");
        for (int i = 0; i < 5; i++) {
            pending = new Timeline("superseded " + i);
            select(new Track("en", "next" + i), pending);
        }
        CaptionTracks.onVideoId("lmnopqrstuv");
        Utils.drain();
        check(pending.state == null, "Video change must discard the pending capture");
    }

    public static void arrivalOrders() {
        for (boolean primaryFirst : new boolean[] {true, false}) {
            Track source = new Track("en", primaryFirst ? "first" : "second");
            Handler handler = load(source);
            Timeline primary = new Timeline("authored primary"), secondary = new Timeline("authored secondary");
            callback(handler, primaryFirst ? "en" : "de").onResult(primaryFirst ? primary : secondary);
            var partial = pair();
            check((primaryFirst ? partial.first() : partial.second()) == (primaryFirst ? primary : secondary),
                    "First language must remain visible while sibling loads");
            callback(handler, primaryFirst ? "de" : "en").onResult(primaryFirst ? secondary : primary);
            var complete = pair();
            check(complete.first() == primary && complete.second() == secondary,
                    "Arrival order must not swap languages");
            Utils.drain();
        }
    }

    public static void lateSuccess() {
        Handler handler = load(new Track("en", "one"));
        Timeline primary = new Timeline("primary"), late = new Timeline("late authored");
        callback(handler, "en").onResult(primary);
        Utils.drain();
        Utils.delayed.remove(0).run();
        callback(handler, "de").onResult(late);
        Utils.drain();
        check(pair().first() == primary && pair().second() == late,
                "Timeout fallback must still accept the current owner's late native success");
    }

    public static void independentFailures() {
        for (boolean primaryFails : new boolean[] {true, false}) {
            Handler handler = load(new Track("en", primaryFails ? "primary" : "secondary"));
            Timeline success = new Timeline("authored success");
            callback(handler, primaryFails ? "de" : "en").onResult(success);
            callback(handler, primaryFails ? "en" : "de").onError(new IllegalStateException("unavailable"));
            Utils.drain();
            var result = pair();
            check(primaryFails ? result.first() == null && result.second() == success
                    : result.first() == success && result.second() == null,
                    "A failed language must not erase its loaded sibling");
        }
    }

    public static void synchronousFailure() {
        Track source = new Track("en", "one");
        CaptionTracks.onSelectedTrack(source);
        CaptionTracks.inspectUploadedTracks(new Manager(source), source);
        Handler handler = new Handler() {
            public void patch_fetchCaption(Object track, CaptionTracks.UploadedCaptionCallback callback) {
                if (((Track) track).language.equals("en"))
                    throw new IllegalStateException("synchronous loader failure");
                super.patch_fetchCaption(track, callback);
            }
        };
        CaptionTracks.fetchUploadedTracks(handler, source);
        check(handler.callbacks.size() == 1, "Other language must still be requested");
        Timeline secondary = new Timeline("secondary");
        callback(handler, "de").onResult(secondary);
        Utils.drain();
        check(pair().first() == null && pair().second() == secondary,
                "Secondary must survive synchronous primary failure");
    }

    public static void staleUpload() {
        Handler old = load(new Track("en", "one"));
        Runnable timeout = Utils.delayed.get(0);
        callback(old, "en").onResult(new Timeline("old primary"));
        Settings.SECONDARY_CAPTION_LANGUAGE.save("fr");
        Handler current = load(new Track("en", "two"));
        callback(old, "de").onResult(new Timeline("old secondary"));
        callback(old, "en").onError(new IllegalStateException("old failure"));
        timeout.run();
        Utils.drain();
        check(!CaptionTracks.hasUploadedTracks(), "Old callbacks must not fill replacement slots");
        check(current.reloads == 0, "Old queued publication and timeout must not reload replacement");
        Timeline primary = new Timeline("new primary"), secondary = new Timeline("new secondary");
        callback(current, "en").onResult(primary);
        callback(current, "fr").onResult(secondary);
        Utils.drain();
        check(pair().first() == primary && pair().second() == secondary, "Replacement must own both slots");
    }

    public static void sourceReplacement() {
        Track selected = new Track("en", "two");
        replaceWithoutLanguageChange(selected, new Manager(selected));
    }

    public static void catalogReplacement() {
        Track selected = new Track("en", "one");
        Manager catalog = new Manager(selected) {
            public List<PreferredCaptionLanguagePatch.CaptionTrackInterface> patch_getAllCaptionTracks() {
                return List.of(source, new Track("de", "new-catalog"));
            }
        };
        replaceWithoutLanguageChange(selected, catalog);
    }

    private static void replaceWithoutLanguageChange(Track selected, Manager catalog) {
        Handler old = load(new Track("en", "one"));
        Runnable timeout = Utils.delayed.get(0);
        Timeline previous = new Timeline("old primary");
        callback(old, "en").onResult(previous);
        Utils.drain();
        check(pair().first() == previous, "Old binding should initially own its primary");

        CaptionTracks.onSelectedTrack(selected);
        CaptionTracks.inspectUploadedTracks(catalog, selected);
        check(!CaptionTracks.hasUploadedTracks(),
                "Source/catalog replacement must invalidate uploaded output with unchanged languages");
        Handler current = new Handler();
        CaptionTracks.fetchUploadedTracks(current, selected);
        check(current.callbacks.size() == 2, "Replacement must request both current candidates");

        callback(old, "de").onResult(new Timeline("stale secondary"));
        callback(old, "en").onError(new IllegalStateException("stale error"));
        timeout.run();
        Utils.drain();
        check(!CaptionTracks.hasUploadedTracks(), "Stale deliveries must not fill replacement slots");
        check(current.reloads == 0, "Stale delivery/timeout must not refresh the new native handler");
        Timeline primary = new Timeline("new primary"), secondary = new Timeline("new secondary");
        callback(current, "en").onResult(primary);
        callback(current, "de").onResult(secondary);
        Utils.drain();
        check(pair().first() == primary && pair().second() == secondary,
                "Current candidate results must own both output slots");
    }

    public static void pausedPrimary() {
        Settings.DUAL_CAPTIONS_ENABLED.save(false);
        Settings.SECONDARY_CAPTION_LANGUAGE.save("off");
        Track source = new Track("en", "one");
        CaptionTracks.onSelectedTrack(source);
        check(CaptionTracks.requestOutput(source, "de"), "Styled primary request should be valid");
        Handler handler = new Handler();
        CaptionTracks.fetchUploadedTracks(handler, source);
        check(handler.callbacks.isEmpty(), "Single styled primary needs no uploaded request");
        Timeline timeline = new Timeline("source");
        CaptionTracks.onNativeResult(new Request(source), timeline);
        Utils.drain();
        check(timeline.state != null && timeline.state.translation != null, "Source must own a session");
        CaptionTracks.onNativeResultReady(handler, new Request(source), timeline);
        Utils.drain();
        check(handler.updates == 1, "No-upload paused result must still refresh native renderer");
        CaptionTracks.onNativeResultReady(handler, new Request(source), timeline);
        CaptionTracks.onSelectedTrack(new Track("en", "two"));
        Utils.drain();
        check(handler.updates == 1, "Queued old source must not update replacement");
    }
}
