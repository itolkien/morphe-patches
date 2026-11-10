/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4376
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Keeps native styled cues and gives each language its own window identifiers. */
final class NativeCaptionPair {
    final UploadedCaptionPair tracks;
    @Nullable final CaptionSession sourceSession;
    @Nullable private final StableCaptionBlocks.Timeline source;
    private final List<Map<Integer, Integer>> ids = List.of(new HashMap<>(), new HashMap<>());
    private final List<Map<Integer, Integer>> fallbackIds =
            List.of(new HashMap<>(), new HashMap<>());

    NativeCaptionPair(UploadedCaptionPair tracks, @Nullable StableCaptionBlocks.Timeline source) {
        this(tracks, source, null);
    }

    private NativeCaptionPair(
            UploadedCaptionPair tracks,
            @Nullable StableCaptionBlocks.Timeline source,
            @Nullable CaptionSession sourceSession) {
        this.tracks = tracks;
        this.source = source;
        this.sourceSession = sourceSession;
    }

    /** Reuse native source windows for uploaded slots and window-aware provider slots. */
    @Nullable
    static NativeCaptionPair forSource(
            StableCaptionBlocks.Timeline timeline, StableCaptionBlocks.State state) {
        CaptionSession session = state.translation;
        if (!state.nativeSource || session == null || !session.isActive()) return null;
        boolean primaryNative = session.primaryLanguage.equalsIgnoreCase(session.sourceLanguage);
        if (primaryNative && session.secondaryLanguage == null) return null;
        boolean secondaryNative =
                session.secondaryLanguage != null
                        && session.secondaryLanguage.equalsIgnoreCase(session.sourceLanguage);
        if (!primaryNative && session.source.stream().noneMatch(block -> !block.windows.isEmpty()))
            return null;
        return new NativeCaptionPair(
                new UploadedCaptionPair(
                        primaryNative ? timeline : null, secondaryNative ? timeline : null),
                timeline,
                session);
    }

    boolean isSourceFor(@Nullable CaptionSession session) {
        return sourceSession != null && sourceSession == session && sourceSession.isActive();
    }

    synchronized List<?> at(long time, StableCaptionBlocks.State state) {
        List<Object> cues = new ArrayList<>();
        append(cues, tracks.first(), time, state, 0);
        append(cues, tracks.second(), time, state, 1);
        CaptionSession translation = state.translation;
        StableCaptionBlockPolicy.Block block =
                translation != null && translation.isActive()
                        ? StableCaptionBlockPolicy.at(translation.source, time)
                        : null;
        if (source != null
                && block != null
                && (tracks.first() == null || tracks.second() == null)) {
            int languages = translation.secondaryLanguage == null ? 1 : 2;
            for (int language = 0; language < languages; language++) {
                if (language == 0 ? tracks.first() != null : tracks.second() != null) continue;
                final int slot = language;
                for (Object item : source.patch_getOriginalCaptions(time)) {
                    if (!(item instanceof StableCaptionBlocks.Cue cue)
                            || !cue.patch_isCaptionVisible()) continue;
                    CharSequence text = cue.patch_getOriginalCaptionVisible();
                    if (text == null || text.length() == 0) continue;
                    Integer window = block.windows.isEmpty() ? null : cue.patch_getCaptionId();
                    if (window != null && !block.windows.containsKey(window)) continue;
                    Integer id =
                            window == null
                                    ? null
                                    : fallbackIds
                                            .get(slot)
                                            .computeIfAbsent(
                                                    window,
                                                    ignored ->
                                                            -(fallbackIds.get(slot).size() + 1) * 2
                                                                    + slot);
                    Object copy =
                            cue.patch_copyBlockFrame(
                                    new StableCaptionBlocks.Frame(
                                            state, time, block.start, language, false, window, id));
                    if (copy != null) cues.add(copy);
                    if (window == null) break;
                }
            }
        }
        return cues;
    }

    private void append(
            List<Object> cues,
            @Nullable StableCaptionBlocks.Timeline timeline,
            long time,
            StableCaptionBlocks.State state,
            int language) {
        if (timeline == null) return;
        Map<Integer, Integer> mapping = ids.get(language);
        for (Object item : timeline.patch_getOriginalCaptions(time)) {
            if (!(item instanceof StableCaptionBlocks.Cue cue)) continue;
            int id =
                    mapping.computeIfAbsent(
                            cue.patch_getCaptionId(), ignored -> mapping.size() * 2 + language);
            Object copy =
                    cue.patch_copyBlockFrame(
                            new StableCaptionBlocks.Frame(state, time, id, language, true));
            if (copy != null) cues.add(copy);
        }
    }

    /**
     * Native authored windows already show uploaded slots; the fallback window shows one missing
     * slot.
     */
    String fallback(CaptionSession.Snapshot translated, boolean secondary) {
        return fallback(translated, secondary, null);
    }

    CharSequence fallback(
            CaptionSession.Snapshot translated,
            boolean secondary,
            @Nullable Integer window,
            List<CharSequence> visible) {
        CaptionSession.WindowText text = window == null ? null : translated.windows.get(window);
        NativeCaptionRunPlan.Result runs =
                text == null ? null : secondary ? text.secondaryRuns() : text.primaryRuns();
        return runs == null
                ? fallback(translated, secondary, window)
                : NativeCaptionRuns.render(runs, visible);
    }

    String fallback(
            CaptionSession.Snapshot translated, boolean secondary, @Nullable Integer window) {
        if (window != null) {
            CaptionSession.WindowText text = translated.windows.get(window);
            if (text == null) return "";
            if (secondary) return tracks.second() == null ? text.secondary() : "";
            return tracks.first() == null ? text.primary() : "";
        }
        if (secondary) return tracks.second() == null ? translated.secondary : "";
        return tracks.first() == null ? translated.primary : "";
    }
}
