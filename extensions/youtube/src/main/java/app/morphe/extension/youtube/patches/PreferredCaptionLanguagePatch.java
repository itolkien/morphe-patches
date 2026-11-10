/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.youtube.patches.AutoTranslateCaptionsPatch.getLanguageKey;
import static app.morphe.extension.youtube.patches.AutoTranslateCaptionsPatch.normalizeLanguageCode;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class PreferredCaptionLanguagePatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface SubtitleManagerInterface {
        // Methods are added during patching.

        /**
         * @return Direct caption tracks, including the disable and auto-translate pseudo options.
         *         Null if no video is loaded.
         */
        @Nullable
        List<CaptionTrackInterface> patch_getDirectCaptionTracks();

        /**
         * @return All caption tracks of the video, including the tracks of other audio tracks.
         *         Null if no video is loaded.
         */
        @Nullable
        List<CaptionTrackInterface> patch_getAllCaptionTracks();

        /**
         * @return Auto-translated caption tracks. Null if no video is loaded.
         */
        @Nullable
        List<CaptionTrackInterface> patch_getAutoTranslateCaptionTracks();

        /**
         * @return Original caption track YouTube translates captions of the current audio track from,
         *         otherwise the default caption track of the current audio track. Null if not available.
         */
        @Nullable
        CaptionTrackInterface patch_getCaptionTranslationSource();

        /**
         * @return Default caption track of the current audio track,
         *         independent of the source order of the translation menu. Null if not available.
         */
        @Nullable
        CaptionTrackInterface patch_getCurrentAudioCaptionSource();

        /**
         * Selects a track as the native player does for a default selection.
         * Added only for YouTube 21.10 and newer, where it is used.
         */
        default void patch_selectCaptionTrack(CaptionTrackInterface track) {
        }
    }

    /**
     * Interface to use obfuscated methods.
     */
    public interface CaptionTrackInterface {
        // Methods are added during patching.

        /**
         * @return Language code (e.g. "ko", "en"), or a pseudo option such as {@link #DISABLE_OPTION}.
         */
        String patch_getLanguageCode();

        /**
         * @return Track vss id (e.g. ".ko", "a.en", "t.ko"), or "-" for the disable option.
         */
        String patch_getVssId();
    }

    static final String DISABLE_OPTION = "DISABLE_CAPTIONS_OPTION";
    static final String AUTO_TRANSLATE_OPTION = "AUTO_TRANSLATE_CAPTIONS_OPTION";
    private static final String PREFERRED_TRACK_SELECT_TYPE = "PREFERRED_TRACK";

    /**
     * Time after a new video starts where explicit track selections are not considered user selections.
     */
    private static final long USER_SELECTION_GRACE_PERIOD_MILLISECONDS = 500;

    private static final AtomicBoolean userSelectedTrack = new AtomicBoolean(false);
    private static final AtomicLong userInteractionAllowedAt = new AtomicLong(Long.MAX_VALUE);
    private static volatile long videoStartTime;
    private static volatile long userVisibilitySelectionAt;
    private static volatile WeakReference<SubtitleManagerInterface> activeManager = new WeakReference<>(null);
    private static volatile CaptionTrackInterface activeTrack;
    private static volatile String managerVideo;
    private static final ThreadLocal<Boolean> replayingSelection = new ThreadLocal<>();

    @Nullable
    private static volatile String lastProgrammaticTrackLang;
    @Nullable
    private static volatile String lastProgrammaticTrackVss;

    /**
     * Injection point: Called after SubtitleManager.getDefaultCaptionTrack() at caller-site.
     */
    public static CaptionTrackInterface getPreferredCaptionTrack(
            SubtitleManagerInterface subtitleManager, @Nullable CaptionTrackInterface originalTrack) {
        CaptionTrackInterface selected = choosePreferredCaptionTrack(subtitleManager, originalTrack);
        if (CaptionTracks.nativeStyledOutputsEnabled()) {
            CaptionTrackInterface source = sourceForStyledTranslation(subtitleManager, selected);
            if (source instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface original
                    && CaptionTracks.requestOutput(original, selected.patch_getLanguageCode())) {
                selected = recordProgrammaticSelection(source);
            }
        }
        rememberSelection(subtitleManager, selected);
        return selected;
    }

    private static CaptionTrackInterface choosePreferredCaptionTrack(
            SubtitleManagerInterface subtitleManager, @Nullable CaptionTrackInterface originalTrack) {
        try {
            // CC off guard: Preserve explicitly disabled caption option.
            if (isExplicitlyDisabled(originalTrack)) {
                return originalTrack;
            }

            if (CaptionTracks.dualCaptionEnabled() && !userSelectedTrack.get()) {
                // The native default is not an explicit source choice. Only an existing
                // translation needs its exact original resolved to retain the requested target.
                final boolean isTranslation = originalTrack instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface input
                        && AutoTranslateCaptionsPatch.isTranslationTrack(input);
                CaptionTrackInterface source = blockTranslationSource(subtitleManager, isTranslation ? originalTrack : null);
                if (source != null) {
                    if (isTranslation
                            && "off".equalsIgnoreCase(Settings.PREFERRED_CAPTION_LANGUAGE.get())
                            && source instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface original
                            && !CaptionTracks.requestOutput(original, originalTrack.patch_getLanguageCode())) {
                        return originalTrack;
                    }
                    return recordProgrammaticSelection(source);
                }
            }
            String prefLang = Settings.PREFERRED_CAPTION_LANGUAGE.get();
            Logger.printDebug(() -> "getPreferredCaptionTrack orig: " + originalTrack + " prefLang: " + prefLang);

            if ("off".equalsIgnoreCase(prefLang)) {
                return originalTrack;
            }

            if (userSelectedTrack.get()) {
                Logger.printDebug(() -> "User manually selected caption track, preserving: " + originalTrack);
                return originalTrack;
            }

            String targetLang = normalizeLanguageCode("default".equalsIgnoreCase(prefLang)
                    ? getAppLanguage()
                    : prefLang);
            if (targetLang.isEmpty()) {
                return originalTrack;
            }

            // Priority 1: Direct tracks (provider subtitle, or native language subtitle).
            CaptionTrackInterface nativeTrack = null;

            List<CaptionTrackInterface> directTracks = subtitleManager.patch_getDirectCaptionTracks();
            if (directTracks != null) {
                for (CaptionTrackInterface track : directTracks) {
                    String lang = track.patch_getLanguageCode();

                    if (AUTO_TRANSLATE_OPTION.equals(lang)) {
                        continue;
                    }

                    if (DISABLE_OPTION.equals(lang) || !matchesLanguage(lang, targetLang)) {
                        continue;
                    }

                    if (track.patch_getVssId().startsWith(".")) {
                        // Creator / provider subtitle in target language has the highest priority.
                        Logger.printDebug(() -> "Selected priority 1 (provider subtitle): " + track);
                        return recordProgrammaticSelection(track);
                    }

                    if (nativeTrack == null) {
                        // Native language track (video audio is in target language).
                        nativeTrack = track;
                    }
                }
            }

            if (nativeTrack != null) {
                CaptionTrackInterface selectedTrack = nativeTrack;
                Logger.printDebug(() -> "Selected priority 1b (native target language track): " + selectedTrack);
                return recordProgrammaticSelection(nativeTrack);
            }

            // Priority 2: Existing translation, then a validated current-audio source fallback.
            // A missing menu row does not mean the backend cannot translate the source.
            CaptionTrackInterface translated = findAutoTranslatedTrack(directTracks,
                    subtitleManager.patch_getAutoTranslateCaptionTracks(),
                    subtitleManager.patch_getCaptionTranslationSource(), targetLang, Requester.getAppLocale());
            if (translated != null) return recordProgrammaticSelection(translated);

            // Priority 3: Fallback to the original YouTube selection.
            Logger.printDebug(() -> "Falling back to original track: " + originalTrack);
            return recordProgrammaticSelection(originalTrack);
        } catch (Exception ex) {
            Logger.printException(() -> "getPreferredCaptionTrack failure", ex);
            return originalTrack;
        }
    }

    @Nullable
    static CaptionTrackInterface findAutoTranslatedTrack(@Nullable List<CaptionTrackInterface> directTracks,
            @Nullable List<CaptionTrackInterface> translatedTracks, @Nullable CaptionTrackInterface defaultSource,
            String targetLanguage, Locale locale) {
        if (translatedTracks != null) {
            // Prefer the requested region when both regional and generic translations exist.
            String normalizedTarget = normalizeLanguageCode(targetLanguage);
            for (CaptionTrackInterface track : translatedTracks) {
                if (normalizedTarget.equals(normalizeLanguageCode(track.patch_getLanguageCode()))) return track;
            }
            for (CaptionTrackInterface track : translatedTracks) {
                if (matchesLanguage(track.patch_getLanguageCode(), targetLanguage)) return track;
            }
        }
        String language = AutoTranslateCaptionsPatch.translationLanguage(targetLanguage);
        return language == null ? null : AutoTranslateCaptionsPatch.createTranslation(
                AutoTranslateCaptionsPatch.findFallbackSource(directTracks, defaultSource), language, locale);
    }

    /**
     * Injection point: SubtitleManager.setSubtitleTrack(SubtitleTrack, SelectType, ...)
     * Intercepts and overrides the subtitle track with preferred language if applicable.
     */
    public static CaptionTrackInterface onSetSubtitleTrack(SubtitleManagerInterface subtitleManager,
                                                           @Nullable CaptionTrackInterface track,
                                                           @Nullable Enum<?> selectType) {
        try {
            final boolean interactionAllowed = SystemClock.uptimeMillis() >= userInteractionAllowedAt.get();
            final boolean userSelection = interactionAllowed && selectType != null
                    && PREFERRED_TRACK_SELECT_TYPE.equals(selectType.name())
                    && SystemClock.uptimeMillis() - videoStartTime > USER_SELECTION_GRACE_PERIOD_MILLISECONDS;
            if (userSelection && (CaptionTracks.dualCaptionEnabled() || CaptionTracks.nativeStyledOutputsEnabled())
                    && track instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface original
                    && CaptionTracks.sourceIdentity(original) != null) userSelectedTrack.set(true);
            rememberSelection(subtitleManager, track);
            if ((CaptionTracks.dualCaptionEnabled() || CaptionTracks.nativeStyledOutputsEnabled())
                    && track instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface translated
                    && AutoTranslateCaptionsPatch.isTranslationTrack(translated)) {
                if (userSelection) userSelectedTrack.set(true);
                CaptionTrackInterface source = CaptionTracks.dualCaptionEnabled()
                        ? blockTranslationSource(subtitleManager, track)
                        : sourceForStyledTranslation(subtitleManager, track);
                if (source instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface original
                        && CaptionTracks.requestOutput(original, translated.patch_getLanguageCode())) {
                    return source;
                }
                return track;
            }
            if (StableCaptionBlocks.isEnabled() && (track == null || isExplicitlyDisabled(track))) {
                CaptionTracks.onSelectedTrack(null);
                return track;
            }
            Logger.printDebug(() -> "onSetSubtitleTrack track: " + track
                    + " selectType: " + selectType
                    + " interactionAllowed: " + interactionAllowed
                    + " userSelectedTrack: " + userSelectedTrack.get());

            // Respect explicit user selection only after the initial video load window.
            if (userSelection) {
                if (CaptionTracks.dualCaptionEnabled()
                        && track instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface original
                        && CaptionTracks.sourceIdentity(original) != null) {
                    userSelectedTrack.set(true);
                    CaptionTracks.requestOutput(original, original.patch_getLanguageCode());
                    return track;
                }
                CaptionTracks.clearRequestedOutput();
                if (isSameTrack(track, lastProgrammaticTrackLang, lastProgrammaticTrackVss)) {
                    Logger.printDebug(() -> "Ignoring PREFERRED_TRACK as it matches programmatic selection");
                    return track;
                }

                userSelectedTrack.set(true);
                Logger.printDebug(() -> "User explicitly selected subtitle track: " + track);
                return track;
            }

            if (userSelectedTrack.get()) {
                Logger.printDebug(() -> "User manually selected caption track previously, preserving: " + track);
                return track;
            }

            if (track == null || isExplicitlyDisabled(track)) {
                Logger.printDebug(() -> "Track is disable option or null, preserving: " + track);
                return track;
            }

            CaptionTrackInterface preferred = getPreferredCaptionTrack(subtitleManager, track);
            if (preferred != null) {
                Logger.printDebug(() -> "Overriding subtitle track with preferred track: " + preferred);
                return preferred;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onSetSubtitleTrack failure", ex);
        }

        return track;
    }

    /**
     * Injection point: Video start hook.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController ignoredController) {
        userInteractionAllowedAt.set(Long.MAX_VALUE);
        userSelectedTrack.set(false);
        recordProgrammaticSelection(null);
        videoStartTime = SystemClock.uptimeMillis();
        Logger.printDebug(() -> "newVideoStarted, user interaction locked startTime: " + videoStartTime);
    }

    /**
     * Injection point: SubtitleManager visibility setter, at method entry.
     * The CC button uses this path for both turning captions on and off.
     */
    public static void onCaptionVisibilityChanged(boolean visible) {
        try {
            final long selectionAge = SystemClock.uptimeMillis() - userVisibilitySelectionAt;
            if (selectionAge >= 0 && selectionAge < 2500
                    && !Boolean.TRUE.equals(replayingSelection.get())) {
                AutoCaptionsPatch.rememberVisibility(visible);
                userVisibilitySelectionAt = 0;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onCaptionVisibilityChanged failure", ex);
        }
    }

    /**
     * Injection point: the native captions menu/CC selection method.
     */
    public static void onCaptionUserSelected() {
        userVisibilitySelectionAt = SystemClock.uptimeMillis();
    }

    /**
     * Injection point: Video information loaded hook.
     */
    public static void videoInformationLoaded() {
        // Only the first loaded event starts the window; later events must not re-lock selection.
        userInteractionAllowedAt.compareAndSet(Long.MAX_VALUE, SystemClock.uptimeMillis() + 300);
    }

    /**
     * Keeps the selection, so it can be applied again if the bilingual captions toggle is changed.
     */
    private static void rememberSelection(SubtitleManagerInterface manager, @Nullable CaptionTrackInterface track) {
        if (!StableCaptionBlocks.isEnabled() || Boolean.TRUE.equals(replayingSelection.get())) return;

        try {
            CaptionTracks.inspectUploadedTracks(manager, track);
        } catch (Exception ex) {
            Logger.printException(() -> "inspectUploadedTracks failure", ex);
        }

        activeManager = new WeakReference<>(manager);
        activeTrack = track;
        managerVideo = null;
        try {
            if (track instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface input
                    && !isExplicitlyDisabled(track)) {
                var parameters = AutoTranslateCaptionsPatch.captionParameters(input.patch_getUrl());
                if (parameters != null) managerVideo = parameters.get("v");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "rememberSelection failure", ex);
        }
    }

    /**
     * Resolves the video language after the native handler commits a track selection.
     * The first subtitle selection can happen before a usable source track is known.
     */
    static void refreshVideoLanguage(@Nullable CaptionTrackInterface selected) {
        SubtitleManagerInterface manager = activeManager.get();
        if (manager == null || selected == null) return;

        try {
            CaptionTracks.inspectUploadedTracks(manager, selected);
        } catch (Exception ex) {
            Logger.printException(() -> "refreshVideoLanguage failure", ex);
        }
    }

    static void forgetSelectionForVideo(@Nullable String id) {
        if (managerVideo != null && managerVideo.equals(id)) return;
        activeManager.clear();
        activeTrack = null;
        managerVideo = null;
    }

    /**
     * Applies the current selection again.
     */
    static void refreshCaptionSelection() {
        SubtitleManagerInterface manager = activeManager.get();
        CaptionTrackInterface track = activeTrack;
        if (manager == null || track == null || isExplicitlyDisabled(track)
                || !CaptionTracks.isCurrentVideo(managerVideo)) return;

        CaptionTrackInterface selected = getPreferredCaptionTrack(manager, CaptionTracks.primaryTrack(track));
        if (selected == null) return;

        replayingSelection.set(true);
        try {
            manager.patch_selectCaptionTrack(selected);
        } finally {
            replayingSelection.remove();
        }
    }

    static boolean hasActiveSelection() {
        return activeManager.get() != null && activeTrack != null;
    }

    /**
     * Forces the native caption load path. Selecting the same track again can be a no-op
     * when the first load came from the native cache, so briefly select another original track.
     */
    static void replayCaptionSelection() {
        SubtitleManagerInterface manager = activeManager.get();
        CaptionTrackInterface track = activeTrack;
        if (manager == null || track == null) return;

        CaptionTrackInterface selected = getPreferredCaptionTrack(manager, CaptionTracks.primaryTrack(track));
        if (selected == null) return;

        CaptionTrackInterface alternate = null;
        try {
            List<CaptionTrackInterface> direct = manager.patch_getDirectCaptionTracks();
            if (direct != null) {
                for (CaptionTrackInterface candidate : direct) {
                    if (!(candidate instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface input)
                            || isExplicitlyDisabled(candidate)
                            || AutoTranslateCaptionsPatch.isTranslationTrack(input)
                            || candidate.patch_getVssId().equals(selected.patch_getVssId())) continue;
                    alternate = candidate;
                    break;
                }
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // Fall back to selecting the requested track directly.
        }

        final CaptionTrackInterface other = alternate;
        if (other == null) {
            manager.patch_selectCaptionTrack(selected);
            return;
        }

        userSelectedTrack.set(true);
        try {
            manager.patch_selectCaptionTrack(other);
        } finally {
            userSelectedTrack.set(false);
        }
        Utils.runOnMainThreadDelayed(() -> {
            userSelectedTrack.set(true);
            try {
                manager.patch_selectCaptionTrack(selected);
            } finally {
                userSelectedTrack.set(false);
            }
        }, 250);
    }

    static boolean hasUserSelectedTrack() {
        return userSelectedTrack.get();
    }

    static String blockTranslationTarget(String actualLanguage, String preferredLanguage,
            boolean explicitSelection, Locale appLocale) {
        if (explicitSelection || "off".equalsIgnoreCase(preferredLanguage))
            return getLanguageKey(actualLanguage);
        String requested = "default".equalsIgnoreCase(preferredLanguage)
                ? appLocale.toLanguageTag() : preferredLanguage;
        String target = AutoTranslateCaptionsPatch.translationLanguage(requested);
        if (target == null) target = AutoTranslateCaptionsPatch.translationLanguage(
                Locale.forLanguageTag(requested).getLanguage());
        return getLanguageKey(target == null ? actualLanguage : target);
    }

    @Nullable
    static CaptionTrackInterface sourceForStyledTranslation(SubtitleManagerInterface manager,
            CaptionTrackInterface translated) {
        try {
            if (!(translated instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface input)
                    || !AutoTranslateCaptionsPatch.isTranslationTrack(input)) return null;
            CaptionTrackInterface source = blockTranslationSource(manager, translated);
            return source instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface original
                    && original.patch_getVssId().startsWith(".")
                    && !AutoTranslateCaptionsPatch.isTranslationTrack(original)
                    && CaptionTracks.sourceIdentity(original) != null ? source : null;
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** Preserve a selected original; otherwise use the active audio's default caption. */
    @Nullable
    static CaptionTrackInterface blockTranslationSource(SubtitleManagerInterface manager,
            @Nullable CaptionTrackInterface selected) {
        if (selected != null) {
            if (!(selected instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface input)) return null;
            String identity = CaptionTracks.sourceIdentity(input);
            List<CaptionTrackInterface> direct = manager.patch_getDirectCaptionTracks();
            if (identity == null || direct == null) return null;
            // Native urls can differ by signed or transport fields the identity already covers.
            return findSource(direct, identity);
        }
        return AutoTranslateCaptionsPatch.findFallbackSource(manager.patch_getDirectCaptionTracks(),
                manager.patch_getCurrentAudioCaptionSource());
    }

    @Nullable
    private static CaptionTrackInterface findSource(List<CaptionTrackInterface> direct,
            String identity) {
        for (CaptionTrackInterface candidate : direct) {
            if (candidate instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface original
                    && !AutoTranslateCaptionsPatch.isTranslationTrack(original)
                    && identity.equals(CaptionTracks.sourceIdentity(original))) return candidate;
        }
        return null;
    }

    private static String getAppLanguage() {
        Locale appLocale = Requester.getAppLocale();
        String tag = appLocale.toLanguageTag();
        return !tag.isEmpty() && !"und".equalsIgnoreCase(tag)
                ? tag
                : appLocale.getLanguage();
    }

    private static boolean isExplicitlyDisabled(@Nullable CaptionTrackInterface track) {
        if (track == null) return false;
        String lang = track.patch_getLanguageCode();
        return lang.isEmpty() || DISABLE_OPTION.equals(lang) || "-".equals(track.patch_getVssId());
    }

    /**
     * @return The track parameter.
     */
    @Nullable
    private static CaptionTrackInterface recordProgrammaticSelection(@Nullable CaptionTrackInterface track) {
        if (track != null) {
            lastProgrammaticTrackLang = track.patch_getLanguageCode();
            lastProgrammaticTrackVss = track.patch_getVssId();
        } else {
            lastProgrammaticTrackLang = null;
            lastProgrammaticTrackVss = null;
        }
        return track;
    }

    private static boolean isSameTrack(@Nullable CaptionTrackInterface track,
                                       @Nullable String targetLang, @Nullable String targetVss) {
        if (track == null) return false;
        String vss = track.patch_getVssId();
        final boolean hasTargetVss = targetVss != null && !targetVss.isEmpty();

        if (hasTargetVss && targetVss.equals(vss)) {
            return true;
        }

        // Language match is only sufficient if one of the tracks has no vss id.
        return targetLang != null && !targetLang.isEmpty()
                && targetLang.equalsIgnoreCase(track.patch_getLanguageCode())
                && (!hasTargetVss || vss.isEmpty());
    }

    private static boolean matchesLanguage(@Nullable String trackLang, String targetLang) {
        if (trackLang == null) return false;
        String first = getLanguageKey(trackLang), second = getLanguageKey(targetLang);
        if (first.equals(second)) return true;
        String primary = getPrimaryLanguage(first);
        if (!primary.equals(getPrimaryLanguage(second))) return false;
        // Only known, opposing Chinese scripts block the usual regional/generic fallback.
        return !primary.equals("zh")
                || !(first.equals("zh-hans") && second.equals("zh-hant")
                        || first.equals("zh-hant") && second.equals("zh-hans"));
    }

    private static String getPrimaryLanguage(String code) {
        final int dashIndex = code.indexOf('-');
        return dashIndex < 0 ? code : code.substring(0, dashIndex);
    }
}
