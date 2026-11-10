/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4213
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import org.w3c.dom.Document;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.VideoState;

/**
 * Captures the caption source selected by the native player, and binds the caption
 * timelines of that source to a single {@link CaptionSession}.
 */
@SuppressWarnings("unused")
public final class CaptionTracks {
    /**
     * Number of timelines kept bound to a session. The native player caches recent timelines.
     */
    private static final int MAX_BOUND_STATES = 4;
    /**
     * Time to wait for the uploaded tracks of the output languages, before they are translated.
     */
    private static final long UPLOADED_TRACK_TIMEOUT_MS = 5000;

    private static final Map<StableCaptionBlocks.Timeline, Object> nativeSourceRequests = new WeakHashMap<>();
    private record SourceCapture(Track track, StableCaptionBlocks.Timeline timeline) {}
    @Nullable private static SourceCapture pendingNativeSource;
    private static final ThreadLocal<Track> parsing = new ThreadLocal<>();

    @Nullable
    private static String video, source, selectedInputLanguage;
    private record RequestedOutput(String source, String language, String target) {
        boolean translated() { return !language.equals(target); }
    }
    @Nullable
    private static RequestedOutput requestedOutput;
    private static boolean selectionDisabled;

    @Nullable private static TranslationBinding activeTranslation;

    /**
     * Native caption track handler. Methods are added during patching.
     */
    public interface CaptionTrackHandlerInterface {
        /**
         * Requests a track with the native loader, without selecting it.
         */
        void patch_fetchCaption(Object track, UploadedCaptionCallback callback);

        /**
         * Selects the current track of the subtitle manager again.
         */
        void patch_reloadCaption();

        /**
         * Updates the installed caption renderer with the playback time.
         */
        void patch_updateCaption(long time, float speed);
    }

    record UploadedCandidate(Object track, String language, String video) {}

    private record UploadedLoad(UploadedCandidate candidate, boolean primary) {}

    @Nullable private static UploadBinding activeUpload;
    // The native handler also refreshes single-language styled output without any upload.
    private static WeakReference<CaptionTrackHandlerInterface> selectedHandler = new WeakReference<>(null);
    private static long nextOwner;

    private CaptionTracks() {}

    private static void logOwner(long owner, String slot, String phase, String reason) {
        Logger.printDebug(() -> "Captions owner=" + owner + " slot=" + slot
                + " phase=" + phase + " reason=" + reason);
    }

    private record TranslationKey(String source, String language, String primary,
                                  String secondary, boolean primaryUploaded, boolean secondaryUploaded) {}

    private static final class TranslationBinding {
        final long id = ++nextOwner;
        final TranslationKey key;
        final StableCaptionBlockPolicy policy;
        final CaptionSession session;
        final List<WeakReference<StableCaptionBlocks.State>> states = new ArrayList<>();

        TranslationBinding(TranslationKey key, StableCaptionBlockPolicy policy) {
            this.key = key;
            this.policy = policy;
            session = new CaptionSession(policy.sourceBlocks(key.language), key.language,
                    key.primary, key.secondary, CaptionBlockTranslator.DEFAULT,
                    Utils::runOnBackgroundThread, () -> translationChanged(this),
                    () -> translationFailed(this), !key.primaryUploaded, !key.secondaryUploaded);
        }
    }

    private record UploadedRequest(String source, String url) {}

    private record UploadKey(String primary, String secondary, String selectedSource,
                             String nativeSourceUrl, UploadedRequest first, UploadedRequest second) {}

    @Nullable
    private static UploadedRequest uploadRequest(@Nullable UploadedCandidate candidate) {
        if (candidate == null) return null;
        var track = (AutoTranslateCaptionsPatch.CaptionTrackInterface) candidate.track;
        return new UploadedRequest(sourceIdentity(track), track.patch_getUrl());
    }

    private static final class UploadBinding {
        final long id = ++nextOwner;
        final UploadKey key;
        List<UploadedLoad> pending;
        boolean primaryPlanned, secondaryPlanned;
        @Nullable UploadedCaptionPair pair;

        UploadBinding(UploadKey key, UploadedCandidate first, UploadedCandidate second) {
            this.key = key;
            primaryPlanned = first != null;
            secondaryPlanned = second != null;
            var loads = new ArrayList<UploadedLoad>();
            if (first != null) loads.add(new UploadedLoad(first, true));
            if (second != null) loads.add(new UploadedLoad(second, false));
            pending = List.copyOf(loads);
        }
    }

    @Nullable
    private static CaptionSession currentSession() {
        return activeTranslation == null ? null : activeTranslation.session;
    }

    private static List<WeakReference<StableCaptionBlocks.State>> translationStates() {
        return activeTranslation == null ? List.of() : activeTranslation.states;
    }

    @Nullable
    private static UploadedCaptionPair uploadedPair() {
        return activeUpload == null ? null : activeUpload.pair;
    }

    private static boolean plannedUpload(boolean primary) {
        return activeUpload != null && (primary ? activeUpload.primaryPlanned : activeUpload.secondaryPlanned);
    }

    private static final class Track {
        final String source, language, video;
        @Nullable
        final String secondary;
        final boolean automatic, translated;

        Track(String source, String language, @Nullable String secondary, String video,
              boolean automatic, boolean translated) {
            this.source = source;
            this.language = language;
            this.secondary = secondary;
            this.video = video;
            this.automatic = automatic;
            this.translated = translated;
        }

        Track withSecondary(@Nullable String secondary) {
            return new Track(source, language, secondary, video, automatic, translated);
        }
    }

    static final class Captured {
        final Track track;
        final StableCaptionBlockPolicy policy;

        Captured(Track track, StableCaptionBlockPolicy policy) {
            this.track = track;
            this.policy = policy;
        }
    }

    /**
     * @return If a secondary language is selected, whether the captions menu toggle is on or off.
     */
    static boolean usesVideoLanguage() {
        return "video".equalsIgnoreCase(Settings.SECONDARY_CAPTION_LANGUAGE.get());
    }

    @Nullable private static volatile String videoLanguage;
    /** Prevents the cached-first-load refresh from repeating for the same video. */
    private static boolean selectionRefreshRequested;

    /** Whether a secondary language is configured, independent of the captions menu toggle. */
    static boolean hasSecondaryLanguage() {
        return StableCaptionBlocks.isEnabled()
                && (usesVideoLanguage() || AutoTranslateCaptionsPatch.translationLanguage(
                        Settings.SECONDARY_CAPTION_LANGUAGE.get()) != null);
    }

    static boolean dualCaptionEnabled() {
        return Settings.DUAL_CAPTIONS_ENABLED.get() && hasSecondaryLanguage();
    }

    @Nullable
    private static String secondaryLanguage() {
        if (!dualCaptionEnabled()) return null;
        return usesVideoLanguage() ? videoLanguage
                : AutoTranslateCaptionsPatch.getLanguageKey(Settings.SECONDARY_CAPTION_LANGUAGE.get());
    }

    /**
     * Applies a change of the captions menu toggle.
     * The native selection is applied again, but a cached original track may not load again.
     */
    static void setDualCaptionsEnabled(boolean enabled) {
        long playbackTime = Utils.isCurrentlyOnMainThread() ? VideoInformation.getVideoTime() : -1;
        Map<StableCaptionBlocks.State, Long> cached = new LinkedHashMap<>();
        synchronized (CaptionTracks.class) {
            if (!enabled && currentSession() != null && source != null
                    && !currentSession().primaryLanguage.equals(currentSession().sourceLanguage)) {
                requestedOutput = new RequestedOutput(source, currentSession().sourceLanguage,
                        currentSession().primaryLanguage);
            }
            Settings.DUAL_CAPTIONS_ENABLED.save(enabled);
            for (WeakReference<StableCaptionBlocks.State> reference : translationStates()) {
                StableCaptionBlocks.State state = reference.get();
                if (state != null && state.captured != null && state.translation != null) {
                    cached.put(state, playbackTime >= 0 ? playbackTime : state.translation.playbackTime());
                }
            }
            invalidateTranslation();
        }

        PreferredCaptionLanguagePatch.refreshCaptionSelection();

        synchronized (CaptionTracks.class) {
            for (Map.Entry<StableCaptionBlocks.State, Long> entry : cached.entrySet()) {
                StableCaptionBlocks.State state = entry.getKey();
                Captured captured = state.captured;
                Track previous = captured.track;
                if (!isCurrent(previous) || (enabled && previous.translated)) continue;

                // A cached native pair still contains the old two-language windows and can
                // otherwise survive the toggle. Rebuild it after the selection is refreshed.
                state.nativePair = null;
                Track next = previous.withSecondary(secondaryLanguage());
                state.captured = new Captured(next, captured.policy);
                attachTranslation(next, captured.policy, state);
                state.translation.demand(entry.getValue());
            }
            translationChanged(activeTranslation);
            for (StableCaptionBlocks.State state : cached.keySet()) {
                StableCaptionBlocks.requestLayout(state);
            }
        }
        // Cached styled/uploaded results are reused without another parser callback. Reload both
        // on enable and disable, otherwise a merged two-language result can remain installed
        // after the toggle is turned off.
        reloadUploaded();
    }

    static synchronized boolean isCurrentVideo(@Nullable String id) {
        return id != null && id.equals(video);
    }

    static synchronized boolean isSelectedVideo(@Nullable String id) {
        return !selectionDisabled && source != null && isCurrentVideo(id);
    }

    /**
     * @return Validated identity shared by the original and the translated forms of a source.
     */
    @Nullable
    static String sourceIdentity(@Nullable AutoTranslateCaptionsPatch.CaptionTrackInterface track) {
        if (track == null) return null;

        Map<String, String> fields = AutoTranslateCaptionsPatch.captionParameters(track.patch_getUrl());
        if (fields == null
                || fields.get("v") == null
                || !fields.get("v").matches("[A-Za-z0-9_-]{11}")
                || fields.get("lang") == null
                || !fields.get("lang").matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{2,8})*")) {
            return null;
        }

        String vss = track.patch_getVssId();
        if (vss == null) return null;

        String translated = fields.get("tlang");
        if (translated != null) {
            if (AutoTranslateCaptionsPatch.translationLanguage(translated) == null
                    || !translated.equals(track.patch_getLanguageCode())
                    || !vss.startsWith("t")
                    || !vss.endsWith("." + translated)) {
                return null;
            }
            vss = vss.substring(1, vss.length() - translated.length() - 1);
        } else if (!AutoTranslateCaptionsPatch.getLanguageKey(fields.get("lang"))
                .equals(AutoTranslateCaptionsPatch.getLanguageKey(track.patch_getLanguageCode()))) {
            return null;
        }
        if (!vss.matches("(?:a)?\\.[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) return null;

        final boolean asr = vss.startsWith("a.");
        String vssLanguage = vss.substring(asr ? 2 : 1).split("\\.", 2)[0];
        if (!AutoTranslateCaptionsPatch.getLanguageKey(vssLanguage)
                .equals(AutoTranslateCaptionsPatch.getLanguageKey(fields.get("lang")))) {
            return null;
        }
        String kind = fields.get("kind");
        if (asr ? !"asr".equals(kind) : kind != null && !kind.isEmpty()) return null;

        String audio = fields.get("audio_track_id");
        // Length-prefix the audio field so a separator in its value cannot alias the track name.
        return fields.get("v") + "|" + vss + "|" + fields.get("lang") + "|"
                + (audio == null ? "-1:" : audio.length() + ":" + audio) + "|" + fields.get("name");
    }

    @Nullable
    private static Track track(@Nullable Object object) {
        if (!StableCaptionBlocks.isEnabled()
                || !(object instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface track)) {
            return null;
        }
        try {
            String key = sourceIdentity(track);
            if (key == null) return null;

            var parameters = AutoTranslateCaptionsPatch.captionParameters(track.patch_getUrl());
            final boolean translated = parameters.containsKey("tlang");
            String secondary = secondaryLanguage();
            // A single language keeps the text and clock of the track. A translated response
            // must never become the source of a translation to a second language.
            if (secondary != null && translated) return null;

            return new Track(
                    key,
                    AutoTranslateCaptionsPatch.getLanguageKey(track.patch_getLanguageCode()),
                    secondary,
                    parameters.get("v"),
                    "asr".equals(parameters.get("kind")),
                    translated);
        } catch (Exception ex) {
            Logger.printException(() -> "track failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     */
    public static synchronized void onVideoId(@Nullable String id) {
        if (!StableCaptionBlocks.isEnabled() || (id != null && id.equals(video))) return;

        PreferredCaptionLanguagePatch.forgetSelectionForVideo(id);
        invalidateUploaded();
        selectedHandler.clear();
        nativeSourceRequests.clear();
        pendingNativeSource = null;
        video = id;
        videoLanguage = null;
        selectionRefreshRequested = false;
        source = null;
        selectedInputLanguage = null;
        selectionDisabled = false;
        clearRequestedOutput();
        invalidateTranslation();
    }

    /**
     * Injection point.
     * Only an actual selection of the player can change the source, never a late response.
     */
    public static synchronized void onSelectedTrack(@Nullable Object object) {
        if (!StableCaptionBlocks.isEnabled()) return;

        Track track = track(object);
        if (track == null) {
            pendingNativeSource = null;
            if (!dualCaptionEnabled() && !hasStyledPrimaryRequest()) {
                clearRequestedOutput();
            } else if (currentSession() != null && source != null) {
                requestedOutput = new RequestedOutput(source, currentSession().sourceLanguage,
                        currentSession().primaryLanguage);
            }
            selectionDisabled = true;
            selectedInputLanguage = null;
            invalidateTranslation();
            return;
        }

        onVideoId(track.video);
        selectionDisabled = false;
        selectedInputLanguage = track.language;
        if (requestedOutput == null || !track.source.equals(requestedOutput.source)) clearRequestedOutput();
        if (activeTranslation != null && (!track.source.equals(activeTranslation.key.source)
                || !track.language.equals(activeTranslation.key.language))) {
            invalidateTranslation();
        }
        source = track.source;

        // The initial selection can be ambiguous on videos with several audio tracks.
        // Resolve again once the native handler commits a concrete track, so the user does
        // not have to toggle captions off and on to populate the second language.
        if (usesVideoLanguage()
                && object instanceof PreferredCaptionLanguagePatch.CaptionTrackInterface selected) {
            PreferredCaptionLanguagePatch.refreshVideoLanguage(selected);
        }
    }

    /**
     * Sets the language to show as primary language of the given original track.
     *
     * @return If the track is a valid source.
     */
    static synchronized boolean requestOutput(AutoTranslateCaptionsPatch.CaptionTrackInterface original,
                                              String target) {        Track selected = track(original);
        if (selected == null) return false;

        onVideoId(selected.video);
        requestedOutput = new RequestedOutput(selected.source, selected.language,
                AutoTranslateCaptionsPatch.getLanguageKey(target));

        List<StableCaptionBlocks.State> cached = new ArrayList<>();
        for (WeakReference<StableCaptionBlocks.State> reference : translationStates()) {
            StableCaptionBlocks.State state = reference.get();
            if (state != null && state.captured != null
                    && !state.captured.track.translated
                    && state.captured.track.source.equals(requestedOutput.source)
                    && state.captured.track.language.equals(selected.language)) {
                cached.add(state);
            }
        }
        invalidateTranslation();
        for (StableCaptionBlocks.State state : cached) {
            attachTranslation(selected, state.captured.policy, state);
        }
        translationChanged(activeTranslation);
        return true;
    }

    /**
     * @return The track to select to keep the primary language. The native player remembers the
     *         source track, while bilingual captions can use another language as primary language.
     */
    static synchronized PreferredCaptionLanguagePatch.CaptionTrackInterface primaryTrack(
            PreferredCaptionLanguagePatch.CaptionTrackInterface track) {
        try {
            if (track instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface original
                    && requestedOutput != null && requestedOutput.source.equals(sourceIdentity(original))) {
                var translated = AutoTranslateCaptionsPatch.createTranslation(
                        original, requestedOutput.target, Requester.getAppLocale());
                if (translated != null) return translated;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "primaryTrack failure", ex);
        }
        return track;
    }

    static synchronized void clearRequestedOutput() {
        requestedOutput = null;
    }

    /**
     * Called before a caption track is parsed.
     */
    static void setTrack(@Nullable Object object) {
        parsing.remove();
        Track track = track(object);
        if (track != null) parsing.set(track);
    }

    static boolean observing() {
        return parsing.get() != null;
    }

    static boolean needsNativeSourceCapture(@Nullable StableCaptionBlocks.State state, boolean styled) {
        return state == null || styled && !state.nativeSource;
    }

    /**
     * Injection point.
     * Result of a track request. Can be a cached native timeline.
     */
    public static synchronized void onNativeResult(@Nullable Object request, @Nullable Object result) {
        if (!StableCaptionBlocks.isEnabled()) return;

        try {
            if (!(request instanceof TranslatedCaptionPensPatch.CaptionRequestInterface captionRequest)
                    || !(result instanceof StableCaptionBlocks.Timeline timeline)) return;

            Track track = track(captionRequest.patch_getCaptionTrack());
            if (track == null || !isCurrent(track)) return;
            StableCaptionBlocks.State state = timeline.patch_getBlockState();
            if (needsNativeSourceCapture(state, nativeStyledOutputsEnabled()) && nativeSourceOutputEnabled() && !track.automatic && !track.translated
                    && !(plannedUpload(true) && plannedUpload(false))) {
                captureNativeSource(track, timeline);
                return;
            }
            Captured captured = state == null ? null : state.captured;
            if (captured == null
                    || !track.source.equals(captured.track.source)
                    || !track.language.equals(captured.track.language)) return;

            attachTranslation(track, captured.policy, state);
        } catch (Exception ex) {
            Logger.printException(() -> "onNativeResult failure", ex);
        }
    }

    /**
     * Injection point.
     * Called after the native handler installed a result. A new renderer is not updated
     * by the player while the video is paused, so this updates it once.
     */
    public static void onNativeResultReady(Object handler, @Nullable Object request, @Nullable Object result) {
        if (!StableCaptionBlocks.isEnabled()) return;

        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                if (!(request instanceof TranslatedCaptionPensPatch.CaptionRequestInterface captionRequest)
                        || !(result instanceof StableCaptionBlocks.Timeline timeline)) return;
                Track selected = track(captionRequest.patch_getCaptionTrack());
                StableCaptionBlocks.State state = timeline.patch_getBlockState();
                if (selected == null) return;
                synchronized (CaptionTracks.class) {
                    if (handler != selectedHandler.get() || !isCurrent(selected)) return;
                    if (state == null || state.nativeSource && !nativeSourceOutputEnabled()) {
                        // Turning bilingual captions off can install a cached styled primary.
                        if (dualCaptionEnabled()) return;
                    } else if (state.nativePair != null) {
                        if (!isCurrentNativePair(state.nativePair)) return;
                    } else if (state.translation == null || state.translation != currentSession()
                            || !state.translation.isActive()) return;
                }
                final long time = VideoInformation.getVideoTime();
                if (time >= 0) {
                    ((CaptionTrackHandlerInterface) handler).patch_updateCaption(
                            time, VideoInformation.getPlaybackSpeed());
                }
            } catch (Exception ex) {
                Logger.printException(() -> "onNativeResultReady failure", ex);
            }
        });
    }

    /** Sample immutable native windows off the UI thread; cache source metadata on the parsed result. */
    private static void captureNativeSource(Track track, StableCaptionBlocks.Timeline timeline) {
        if (nativeSourceRequests.containsKey(timeline)) return;
        if (nativeSourceRequests.size() >= MAX_BOUND_STATES) {
            pendingNativeSource = new SourceCapture(track, timeline);
            logOwner(System.identityHashCode(timeline), "source", "queue", "capacity");
            return;
        }
        Object token = new Object();
        nativeSourceRequests.put(timeline, token);
        Utils.runOnBackgroundThread(() -> {
            StableCaptionBlockPolicy captured = null;
            try {
                captured = StableCaptionBlockPolicy.fromNative(timeline, () -> {
                    synchronized (CaptionTracks.class) {
                        return nativeSourceRequests.get(timeline) == token && isCurrent(track);
                    }
                });
            } catch (Exception ex) {
                Logger.printException(() -> "captureNativeSource failure", ex);
            }
            StableCaptionBlockPolicy policy = captured;
            Utils.runOnMainThread(() -> {
                synchronized (CaptionTracks.class) {
                    if (nativeSourceRequests.get(timeline) != token) return;
                    nativeSourceRequests.remove(timeline);
                    SourceCapture next = pendingNativeSource;
                    pendingNativeSource = null;
                    if (next != null && isCurrent(next.track))
                        captureNativeSource(next.track, next.timeline);
                    if (policy == null || !isCurrent(track)) return;
                    StableCaptionBlocks.State state = new StableCaptionBlocks.State(false);
                    state.nativeSource = true;
                    state.policy = policy;
                    state.captured = new Captured(track, policy);
                    timeline.patch_setBlockState(state);
                    if (nativeSourceOutputEnabled()) attachTranslation(track.withSecondary(secondaryLanguage()), policy, state);
                }
                if (nativeSourceOutputEnabled()) reloadUploaded();
            });
        });
    }

    private static boolean isCurrent(Track track) {
        return !selectionDisabled
                && (video == null || track.video.equals(video))
                && (source == null || track.source.equals(source))
                && (selectedInputLanguage == null || selectedInputLanguage.equals(track.language));
    }

    /**
     * Called with the document of the track given to {@link #setTrack(Object)}.
     */
    static synchronized void observe(@Nullable Document document, @Nullable StableCaptionBlocks.State state) {
        Track track = parsing.get();
        parsing.remove();
        if (track == null) {
            refreshUncapturedSelection();
            return;
        }
        boolean current = isCurrent(track);

        StableCaptionBlockPolicy primary;
        if (state != null && state.policy != null) {
            primary = state.policy;
        } else {
            primary = track.automatic
                    ? StableCaptionBlockPolicy.parseSource(document)
                    : StableCaptionBlockPolicy.parseManual(document);
        }
        if (primary == null) {
            if (current) invalidateTranslation();
            return;
        }

        // A native prefetch may later be selected from the native parsed-result cache.
        if (state != null) {
            state.captured = new Captured(track, primary);
            state.policy = primary;
        }
        if (current) attachTranslation(track, primary, state);
    }

    /**
     * A cached first load can arrive without a native track, so no session is attached.
     * Reapply the selection once, the same way toggling captions off and on does.
     */
    private static synchronized void refreshUncapturedSelection() {
        if (selectionRefreshRequested || currentSession() != null || videoLanguage == null
                || !PreferredCaptionLanguagePatch.hasActiveSelection()
                || !safeDualCaptionEnabled()) return;
        selectionRefreshRequested = true;
        Utils.runOnMainThreadDelayed(PreferredCaptionLanguagePatch::replayCaptionSelection, 300);
    }

    private static boolean safeDualCaptionEnabled() {
        try {
            return dualCaptionEnabled();
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    /**
     * Toggling captions can reuse a cached timeline without another result callback.
     *
     * @return If the timeline of the state has an active session.
     */
    static synchronized boolean restoreTranslation(StableCaptionBlocks.State state) {
        Captured captured = state.captured;
        if (captured == null || !isCurrent(captured.track)) return false;

        String secondary = secondaryLanguage();
        if (secondary != null && captured.track.translated) return false;

        attachTranslation(captured.track.withSecondary(secondary), captured.policy, state);
        return state.translation != null && state.translation.isActive();
    }

    private static void invalidateTranslation() {
        TranslationBinding previous = activeTranslation;
        activeTranslation = null;
        if (previous != null) {
            logOwner(previous.id, "both", "cancel", "selection");
            previous.states.clear();
            previous.session.invalidate();
            Utils.runOnBackgroundThread(CaptionBlockTranslator.DEFAULT::cancelInactive);
        }
    }

    private static void attachTranslation(Track track, StableCaptionBlockPolicy policy,
                                          @Nullable StableCaptionBlocks.State state) {
        if (state == null) return;
        boolean requested = requestedOutput != null && track.source.equals(requestedOutput.source);
        String primary = requested ? requestedOutput.target : track.language;
        if (track.secondary != null && !requested) {
            primary = PreferredCaptionLanguagePatch.blockTranslationTarget(track.language,
                    Settings.PREFERRED_CAPTION_LANGUAGE.get(),
                    PreferredCaptionLanguagePatch.hasUserSelectedTrack(), Requester.getAppLocale());
        }
        var key = new TranslationKey(track.source, track.language, primary, track.secondary,
                plannedUpload(true) && uploadedEnabled(), plannedUpload(false) && uploadedEnabled());
        if (activeTranslation == null || !activeTranslation.session.isActive()
                || activeTranslation.policy != policy || !key.equals(activeTranslation.key)) {
            invalidateTranslation();
            activeTranslation = new TranslationBinding(key, policy);
            TranslationBinding owner = activeTranslation;
            Logger.printDebug(() -> "Captions owner=" + owner.id + " slot=both phase=bind"
                    + " reason=source session=" + System.identityHashCode(owner.session));
        }
        state.translation = activeTranslation.session;
        state.policy = policy;
        var states = activeTranslation.states;
        states.removeIf(reference -> reference.get() == null || reference.get() == state);
        if (states.size() == MAX_BOUND_STATES) states.remove(0);
        states.add(new WeakReference<>(state));
    }

    private static void translationChanged(@Nullable TranslationBinding owner) {
        Utils.runOnMainThread(() -> {
            List<StableCaptionBlocks.State> refresh = new ArrayList<>();
            synchronized (CaptionTracks.class) {
                if (owner == null || owner != activeTranslation) return;
                for (WeakReference<StableCaptionBlocks.State> reference : owner.states) {
                    StableCaptionBlocks.State state = reference.get();
                    if (state != null) refresh.add(state);
                }
            }
            // The presentation boundary checks the session again; native views are touched
            // outside the selection lock, including when a view callback is reentrant.
            for (StableCaptionBlocks.State state : refresh)
                StableCaptionBlocks.refreshTranslation(state, owner.session);
        });
    }

    private static void translationFailed(TranslationBinding owner) {
        Utils.runOnMainThread(() -> {
            synchronized (CaptionTracks.class) {
                if (owner == activeTranslation)
                    logOwner(owner.id, "both", "fallback", "translation_failed");
                else return;
            }
            Utils.showToastShort(StringRef.str("morphe_caption_block_translation_failed"));
        });
    }

    /**
     * Language of the video's audio, used as the second caption language. YouTube does not
     * always expose the current audio caption source, and the direct list can contain uploaded
     * tracks of other audio tracks, so a fallback must be validated before it is used.
     */
    @Nullable
    static String resolveVideoLanguage(
            PreferredCaptionLanguagePatch.SubtitleManagerInterface manager,
            @Nullable PreferredCaptionLanguagePatch.CaptionTrackInterface selected) {
        try {
            var direct = manager.patch_getDirectCaptionTracks();
            var currentAudio = manager.patch_getCurrentAudioCaptionSource();
            String currentVideo = videoId(currentAudio), selectedVideo = videoId(selected);
            if (currentVideo != null && selectedVideo != null && !currentVideo.equals(selectedVideo)) {
                return null;
            }

            // The current audio's default caption source is authoritative when it is available.
            var audio = AutoTranslateCaptionsPatch.findFallbackSource(direct, currentAudio);
            String audioVideo = videoId(audio), language = sourceLanguage(audio);
            if (language != null && (audioVideo == null || selectedVideo == null
                    || audioVideo.equals(selectedVideo))) return language;

            // Otherwise the selected track still identifies the audio being listened to.
            language = sourceLanguage(selected);
            if (language != null) return language;

            // Only an unambiguous original language is safe without a track identifying the audio.
            if (direct != null) {
                String unique = null;
                for (var candidate : direct) {
                    if (!(candidate instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface track)
                            || AutoTranslateCaptionsPatch.isTranslationTrack(track)) continue;
                    language = sourceLanguage(track);
                    if (language == null) continue;
                    if (unique != null && !unique.equals(language)) return null;
                    unique = language;
                }
                return unique;
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // A missing native method must not close bilingual captions.
        }
        return null;
    }

    @Nullable
    private static String videoId(@Nullable PreferredCaptionLanguagePatch.CaptionTrackInterface track) {
        if (!(track instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface original)) return null;
        var parameters = AutoTranslateCaptionsPatch.captionParameters(original.patch_getUrl());
        return parameters == null ? null : parameters.get("v");
    }

    /** Validated source language of a track, for both original and translated forms. */
    @Nullable
    private static String sourceLanguage(@Nullable PreferredCaptionLanguagePatch.CaptionTrackInterface track) {
        if (!(track instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface original)
                || sourceIdentity(original) == null) return null;
        var parameters = AutoTranslateCaptionsPatch.captionParameters(original.patch_getUrl());
        return parameters == null ? null
                : AutoTranslateCaptionsPatch.translationLanguage(parameters.get("lang"));
    }

    /**
     * Plans requests of the uploaded tracks of the output languages, so bilingual captions
     * can show uploaded text instead of a machine translation.
     * Called when the subtitle manager selects a track.
     */
    static synchronized void inspectUploadedTracks(
            PreferredCaptionLanguagePatch.SubtitleManagerInterface manager,
            @Nullable PreferredCaptionLanguagePatch.CaptionTrackInterface selected) {
        if (!Settings.DUAL_CAPTIONS_ENABLED.get()) return;
        String secondary = Settings.SECONDARY_CAPTION_LANGUAGE.get();
        if (usesVideoLanguage()) {
            String resolvedVideoLanguage = resolveVideoLanguage(manager, selected);
            String selectedVideo = videoId(selected);
            if (selectedVideo != null) onVideoId(selectedVideo);
            if (!Objects.equals(videoLanguage, resolvedVideoLanguage)) {
                videoLanguage = resolvedVideoLanguage;
                boolean refresh = currentSession() != null;
                invalidateTranslation();
                // A cached native pair also needs to be rebuilt for the new audio language.
                if (refresh) Utils.runOnMainThread(CaptionTracks::reloadUploaded);
            }
            secondary = videoLanguage;
            if (secondary == null) {
                invalidateUploaded();
                return;
            }
        }
        if ("off".equalsIgnoreCase(secondary)) {
            invalidateUploaded();
            return;
        }

        String primary = Settings.PREFERRED_CAPTION_LANGUAGE.get();
        boolean appLanguage = "default".equalsIgnoreCase(primary);
        if (appLanguage) primary = Requester.getAppLocale().toLanguageTag();
        if ("off".equalsIgnoreCase(primary) || PreferredCaptionLanguagePatch.hasUserSelectedTrack()) {
            primary = selected == null ? "" : selected.patch_getLanguageCode();
            appLanguage = false;
        }
        List<PreferredCaptionLanguagePatch.CaptionTrackInterface> catalog = manager.patch_getAllCaptionTracks();
        if (catalog == null) return;
        boolean nativeTranslations = nativeStyledOutputsEnabled();
        PreferredCaptionLanguagePatch.CaptionTrackInterface nativeSource = nativeTranslations
                ? PreferredCaptionLanguagePatch.blockTranslationSource(manager, selected) : null;
        UploadedCandidate first = nativeTranslations
                ? findAuthoredOutputTrack(catalog, nativeSource, primary, appLanguage)
                : findUploadedTrack(catalog, primary, appLanguage);
        UploadedCandidate second = first != null
                && first.language.equals(AutoTranslateCaptionsPatch.normalizeLanguageCode(secondary))
                ? null : nativeTranslations
                        ? findAuthoredOutputTrack(catalog, nativeSource, secondary, false)
                        : findUploadedTrack(catalog, secondary);
        if ((first == null && second == null)
                || (first != null && second != null && !first.video.equals(second.video))) {
            invalidateUploaded();
            return;
        }
        onVideoId(first != null ? first.video : second.video);
        String sourceUrl = nativeSource instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface track
                ? track.patch_getUrl() : null;
        String selectedSource = selected instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface track
                ? sourceIdentity(track) : null;
        // Source and actual requests own the load in every rendering mode, not just styled output.
        var key = new UploadKey(primary, secondary, selectedSource, sourceUrl,
                uploadRequest(first), uploadRequest(second));
        if (activeUpload != null && key.equals(activeUpload.key)) return;
        var owner = new UploadBinding(key, first, second);
        activeUpload = owner;
        logOwner(owner.id, "both", "load", "authored");
        // Timeout opens only missing slots to translation; a valid late result still owns its slot.
        Utils.runOnMainThreadDelayed(() -> {
            final boolean missing;
            synchronized (CaptionTracks.class) {
                if (owner != activeUpload) return;
                boolean firstMissing = owner.pair == null || owner.pair.first() == null;
                boolean secondMissing = owner.pair == null || owner.pair.second() == null;
                missing = owner.primaryPlanned && firstMissing || owner.secondaryPlanned && secondMissing;
                if (firstMissing) owner.primaryPlanned = false;
                if (secondMissing) owner.secondaryPlanned = false;
            }
            if (missing) {
                logOwner(owner.id, "missing", "fallback", "upload_timeout");
                reloadUploaded();
            }
        }, UPLOADED_TRACK_TIMEOUT_MS);
    }

    /** Keep creator-provided output native; machine text needs source-owned run mapping. */
    @Nullable
    static UploadedCandidate findAuthoredOutputTrack(
            List<PreferredCaptionLanguagePatch.CaptionTrackInterface> catalog,
            @Nullable PreferredCaptionLanguagePatch.CaptionTrackInterface selected,
            String language, boolean appLanguage) {
        try {
            AutoTranslateCaptionsPatch.CaptionTrackInterface source =
                    selected instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface track
                            && track.patch_getVssId() != null && track.patch_getVssId().startsWith(".")
                            && !AutoTranslateCaptionsPatch.isTranslationTrack(track)
                            && sourceIdentity(track) != null ? track : null;
            String video = source == null ? null
                    : AutoTranslateCaptionsPatch.captionParameters(source.patch_getUrl()).get("v");
            UploadedCandidate selectedSource = source == null ? null
                    : findUploadedTrack(List.of(source), language, appLanguage);
            UploadedCandidate authored = findUploadedTrack(catalog, language, appLanguage);
            if (selectedSource != null && (authored == null
                    || selectedSource.language.equals(authored.language))) return selectedSource;
            if (authored != null) return video == null || video.equals(authored.video) ? authored : null;
            return null;
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /**
     * @param appLanguage If the language is the app language, which can also use
     *                    an uploaded track of the language without a region.
     */
    @Nullable
    static UploadedCandidate findUploadedTrack(
            List<PreferredCaptionLanguagePatch.CaptionTrackInterface> catalog, String language,
            boolean appLanguage) {
        UploadedCandidate exact = findUploadedTrack(catalog, language);
        if (exact != null || !appLanguage) return exact;
        String fallback = AutoTranslateCaptionsPatch.getLanguageKey(language);
        String script = Locale.forLanguageTag(language).getScript();
        if (!script.isEmpty() && !script.equals(Locale.forLanguageTag(fallback).getScript())) return null;
        return findUploadedTrack(catalog, fallback);
    }

    /**
     * @return The uploaded (not auto-generated and not translated) track of the language.
     */
    @Nullable
    static UploadedCandidate findUploadedTrack(
            List<PreferredCaptionLanguagePatch.CaptionTrackInterface> catalog, String language) {
        String target = AutoTranslateCaptionsPatch.normalizeLanguageCode(language);
        for (var item : catalog) {
            if (!(item instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface track)
                    || !target.equals(AutoTranslateCaptionsPatch.normalizeLanguageCode(track.patch_getLanguageCode()))
                    || sourceIdentity(track) == null || !track.patch_getVssId().startsWith(".")) continue;
            var fields = AutoTranslateCaptionsPatch.captionParameters(track.patch_getUrl());
            if (fields == null || fields.containsKey("tlang") || fields.containsKey("kind")) continue;
            return new UploadedCandidate(track, target, fields.get("v"));
        }
        return null;
    }

    /**
     * Injection point.
     * Called when the native caption track handler selects a track,
     * and requests the planned uploaded tracks with the same handler.
     */
    public static void fetchUploadedTracks(Object handler, @Nullable Object selected) {
        if (!StableCaptionBlocks.isEnabled()) return;

        try {
            if (!(selected instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface selectedTrack)) return;
            CaptionTrackHandlerInterface trackHandler = (CaptionTrackHandlerInterface) handler;
            var fields = AutoTranslateCaptionsPatch.captionParameters(selectedTrack.patch_getUrl());
            List<UploadedLoad> loads;
            UploadBinding owner;
            synchronized (CaptionTracks.class) {
                if (fields == null || !isCurrentVideo(fields.get("v"))) return;
                selectedHandler = new WeakReference<>(trackHandler);
                owner = activeUpload;
                if (owner == null || owner.pending.isEmpty()) return;
                loads = owner.pending;
                owner.pending = List.of();
            }
            for (UploadedLoad load : loads) {
                fetchUploadedTrack(trackHandler, load.candidate.track,
                        new UploadedCaptionCallback(owner, load.primary));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "fetchUploadedTracks failure", ex);
        }
    }

    /** A synchronous native loader failure must not skip the other language's request. */
    static void fetchUploadedTrack(CaptionTrackHandlerInterface handler, Object track,
            UploadedCaptionCallback callback) {
        try {
            handler.patch_fetchCaption(track, callback);
        } catch (Exception ex) {
            callback.onError(ex);
        }
    }

    private static void invalidateUploaded() {
        activeUpload = null;
    }

    /**
     * Selects the current track again, so the native result is merged with the uploaded tracks.
     */
    private static void reloadUploaded() {
        try {
            CaptionTrackHandlerInterface handler;
            synchronized (CaptionTracks.class) {
                handler = selectedHandler.get();
            }
            if (handler != null) handler.patch_reloadCaption();
            else PreferredCaptionLanguagePatch.refreshCaptionSelection();
        } catch (Exception ex) {
            Logger.printException(() -> "reloadUploaded failure", ex);
        }
    }

    private static boolean uploadedEnabled() {
        return dualCaptionEnabled() && isSelectedVideo(video);
    }

    static synchronized boolean hasUploadedTracks() {
        return uploadedPair() != null && uploadedEnabled();
    }

    /**
     * Injection point.
     * Adds the windows of the uploaded tracks to a result the native renderer installs,
     * so the renderer keeps the timing of all languages. Cached results are not changed.
     */
    @Nullable
    public static synchronized Object mergeUploadedTracks(@Nullable Object result) {
        if (!StableCaptionBlocks.isEnabled()) return result;

        try {
            if (!(result instanceof StableCaptionBlocks.Timeline timeline) || !hasUploadedTracks()) return result;
            UploadedCaptionPair uploaded = uploadedPair();
            if (requiresNativeWindows()) {
                // Keep uploaded windows native; a missing output uses a separate translated window.
                // Cached windows and event lists remain unchanged.
                StableCaptionBlocks.Timeline sourceTimeline = timeline;
                boolean fallback = uploaded.first() == null || uploaded.second() == null;
                StableCaptionBlocks.State sourceState = sourceTimeline.patch_getBlockState();
                if (fallback) {
                    if (uploaded.first() != null) timeline = timeline.patch_mergeWith(uploaded.first());
                    if (uploaded.second() != null) timeline = timeline.patch_mergeWith(uploaded.second());
                } else timeline = uploaded.first().patch_mergeWith(uploaded.second());
                StableCaptionBlocks.State state = new StableCaptionBlocks.State(false);
                state.nativePair = new NativeCaptionPair(uploaded, fallback ? sourceTimeline : null);
                if (fallback && sourceState != null && sourceState.captured != null) {
                    Captured captured = sourceState.captured;
                    state.captured = captured;
                    attachTranslation(captured.track.withSecondary(secondaryLanguage()), captured.policy, state);
                }
                timeline.patch_setBlockState(state);
                return timeline;
            }
            if (uploaded.first() != null) timeline = timeline.patch_mergeWith(uploaded.first());
            if (uploaded.second() != null) timeline = timeline.patch_mergeWith(uploaded.second());
            return timeline;
        } catch (Exception ex) {
            Logger.printException(() -> "mergeUploadedTracks failure", ex);
            return result;
        }
    }

    /** Opt-in metadata must survive cached-source reuse when a translation is selected later. */
    static boolean captureNativeRunStyles() {
        return nativeStyledOutputsEnabled();
    }

    private static boolean hasStyledPrimaryRequest() {
        return nativeStyledOutputsEnabled() && requestedOutput != null && requestedOutput.translated();
    }

    static synchronized boolean nativeSourceOutputEnabled() {
        return dualCaptionEnabled() || hasStyledPrimaryRequest() && !selectionDisabled
                && requestedOutput.source.equals(source);
    }

    static boolean nativeStyledOutputsEnabled() {
        return TranslatedCaptionPensPatch.preserveTranslatedStyles();
    }

    private static synchronized boolean requiresNativeWindows() {
        UploadedCaptionPair uploaded = uploadedPair();
        return uploaded != null && uploadedEnabled() && (nativeStyledOutputsEnabled()
                || nativeWindows(uploaded.first()) || nativeWindows(uploaded.second()));
    }

    private static boolean nativeWindows(@Nullable StableCaptionBlocks.Timeline timeline) {
        if (timeline == null) return false;
        StableCaptionBlocks.State state = timeline.patch_getBlockState();
        return state == null || state.nativeSource;
    }

    static synchronized boolean isCurrentNativePair(NativeCaptionPair pair) {
        if (pair.sourceSession != null) {
            return pair.isSourceFor(currentSession()) && nativeSourceOutputEnabled();
        }
        return uploadedEnabled() && uploadedPair() == pair.tracks;
    }

    /**
     * @param fallback Session that translates the languages without an uploaded track.
     * @return Text of the uploaded tracks at the time, or null if there are no uploaded tracks.
     */
    @Nullable
    static synchronized UploadedCaptionPair.Frame uploadedFrame(long time, @Nullable CaptionSession fallback) {
        UploadedCaptionPair current = uploadedPair();
        if (current == null || !uploadedEnabled()) return null;
        CaptionSession.Snapshot translated = fallback == null ? null
                : fallback.snapshot(time, VideoState.getCurrent() == VideoState.PAUSED);
        return current.at(time, translated == null ? "" : translated.primary,
                translated == null ? "" : translated.secondary, translated == null ? -1 : translated.start);
    }

    private static void publishUploaded(UploadedCaptionCallback callback,
            @Nullable StableCaptionBlocks.Timeline timeline) {
        UploadBinding owner = callback.owner;
        synchronized (CaptionTracks.class) {
            if (owner != activeUpload) {
                logOwner(owner.id, callback.primary ? "primary" : "secondary", "reject", "stale");
                return;
            }
            StableCaptionBlocks.Timeline first = owner.pair == null ? null : owner.pair.first();
            StableCaptionBlocks.Timeline second = owner.pair == null ? null : owner.pair.second();
            if (callback.primary) {
                first = timeline;
                owner.primaryPlanned = timeline != null;
            } else {
                second = timeline;
                owner.secondaryPlanned = timeline != null;
            }
            owner.pair = first == null && second == null ? null : new UploadedCaptionPair(first, second);
            logOwner(owner.id, callback.primary ? "primary" : "secondary", "publish",
                    timeline == null ? "native_unavailable" : "native_ready");
        }
        Utils.runOnMainThread(() -> {
            synchronized (CaptionTracks.class) {
                if (owner != activeUpload) return;
            }
            reloadUploaded();
        });
    }

    /**
     * Result callback of an uploaded track request.
     * During patching this class implements the request callback interface of the native loader.
     */
    public static final class UploadedCaptionCallback {
        private final UploadBinding owner;
        private final boolean primary;

        UploadedCaptionCallback(UploadBinding owner, boolean primary) {
            this.owner = owner;
            this.primary = primary;
        }

        /**
         * Called by the native loader.
         */
        public void onResult(@Nullable Object result) {
            try {
                // A track without any cues has at most one event time.
                publishUploaded(this, result instanceof StableCaptionBlocks.Timeline timeline
                        && timeline.patch_getCaptionEventTimes().size() > 1 ? timeline : null);
            } catch (Exception ex) {
                Logger.printException(() -> "onResult failure", ex);
            }
        }

        /**
         * Called by the native loader.
         */
        public void onError(@Nullable Exception error) {
            try {
                Logger.printDebug(() -> "Uploaded caption track request failed", error);
                publishUploaded(this, null);
            } catch (Exception ex) {
                Logger.printException(() -> "onError failure", ex);
            }
        }
    }
}
