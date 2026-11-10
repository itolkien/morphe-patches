package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Text of the caption cues of one source clock. Translation delivery and display geometry never
 * choose new cue times.
 */
final class CaptionSession {
    static final class Snapshot {
        final Map<Integer, WindowText> windows;
        final long start, end;
        final String primary, secondary;
        final boolean complete;

        Snapshot(
                StableCaptionBlockPolicy.Block cue,
                String primary,
                String secondary,
                boolean complete) {
            this(cue, primary, secondary, complete, Map.of());
        }

        Snapshot(
                StableCaptionBlockPolicy.Block cue,
                String primary,
                String secondary,
                boolean complete,
                Map<Integer, WindowText> windows) {
            start = cue.start;
            end = cue.end;
            this.primary = primary;
            this.secondary = secondary;
            this.complete = complete;
            this.windows = Collections.unmodifiableMap(new LinkedHashMap<>(windows));
        }
    }

    record WindowText(
            String primary,
            String secondary,
            boolean complete,
            NativeCaptionRunPlan.Result primaryRuns,
            NativeCaptionRunPlan.Result secondaryRuns) {
        WindowText(String primary, String secondary, boolean complete) {
            this(primary, secondary, complete, null, null);
        }
    }

    private record Part(int index, int window, String text) {}

    private enum SlotState { PENDING, SUCCESS, TERMINAL_FAILURE }

    private record SlotResult(SlotState state, String text, NativeCaptionRunPlan.Result runs) {
        static final SlotResult PENDING = new SlotResult(SlotState.PENDING, null, null);
        static final SlotResult FAILED = new SlotResult(SlotState.TERMINAL_FAILURE, null, null);

        static SlotResult success(String text, NativeCaptionRunPlan.Result runs) {
            return new SlotResult(SlotState.SUCCESS, text, runs);
        }
    }

    private static final class WindowResult {
        final SlotResult[] slots = new SlotResult[SLOT_COUNT];
        WindowResult() { java.util.Arrays.fill(slots, SlotResult.PENDING); }
    }

    private static final class BlockResult {
        final Map<Integer, WindowResult> windows = new LinkedHashMap<>();
    }

    private static final byte STATUS_PENDING = 0;
    private static final byte STATUS_REQUESTED = 1;
    private static final byte STATUS_READY = 2;
    private static final byte STATUS_FAILED = 3;


    private static final int PRIMARY_SLOT = 0;
    private static final int SECONDARY_SLOT = 1;
    private static final int SLOT_COUNT = 2;

    private static final int CACHE_CAPACITY = 64;
    private static final byte MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_MS = 1000;
    private static final long SEEK_BACK_THRESHOLD_MS = 250;
    private static final long PREFETCH_MS = 30000;
    private static final float MAX_PREFETCH_SPEED = 4;

    final String sourceLanguage, primaryLanguage;
    @Nullable final String secondaryLanguage;
    final List<StableCaptionBlockPolicy.Block> source;

    private final CaptionTranslator translator;
    private final Executor worker;
    private final Runnable changed, failed;
    private final LongSupplier clock;
    private final boolean translatePrimary, translateSecondary;
    private final byte[] status, attempts;
    private final long[] retryAt;
    /** One eviction removes every language, window and native renderer of a block. */
    private final Map<Integer, BlockResult> results =
            new LinkedHashMap<>(CACHE_CAPACITY, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, BlockResult> entry) {
                    if (size() > CACHE_CAPACITY)
                        logResult(entry.getKey(), -1, -1, "evict", "capacity");
                    return size() > CACHE_CAPACITY;
                }
            };
    private float playbackRate = 1;
    private boolean active = true, running, failureReported;
    private long position, lastDisplayTime = Long.MIN_VALUE;
    private int lastIndex = -1;
    @Nullable private Snapshot displayed;

    /**
     * @param translatePrimary If the primary language is translated. False if an uploaded track of
     *     the language is shown instead.
     * @param translateSecondary If the secondary language is translated.
     */
    CaptionSession(
            List<StableCaptionBlockPolicy.Block> source,
            String sourceLanguage,
            String primaryLanguage,
            @Nullable String secondaryLanguage,
            CaptionTranslator translator,
            Executor worker,
            Runnable changed,
            Runnable failed,
            boolean translatePrimary,
            boolean translateSecondary) {
        this(
                source,
                sourceLanguage,
                primaryLanguage,
                secondaryLanguage,
                translator,
                worker,
                changed,
                failed,
                () -> System.nanoTime() / 1_000_000L,
                translatePrimary,
                translateSecondary);
    }

    CaptionSession(
            List<StableCaptionBlockPolicy.Block> source,
            String sourceLanguage,
            String primaryLanguage,
            @Nullable String secondaryLanguage,
            CaptionTranslator translator,
            Executor worker,
            Runnable changed,
            Runnable failed) {
        this(
                source,
                sourceLanguage,
                primaryLanguage,
                secondaryLanguage,
                translator,
                worker,
                changed,
                failed,
                true,
                true);
    }

    CaptionSession(
            List<StableCaptionBlockPolicy.Block> source,
            String sourceLanguage,
            String primaryLanguage,
            @Nullable String secondaryLanguage,
            CaptionTranslator translator,
            Executor worker,
            Runnable changed,
            Runnable failed,
            LongSupplier clock) {
        this(
                source,
                sourceLanguage,
                primaryLanguage,
                secondaryLanguage,
                translator,
                worker,
                changed,
                failed,
                clock,
                true,
                true);
    }

    private CaptionSession(
            List<StableCaptionBlockPolicy.Block> source,
            String sourceLanguage,
            String primaryLanguage,
            @Nullable String secondaryLanguage,
            CaptionTranslator translator,
            Executor worker,
            Runnable changed,
            Runnable failed,
            LongSupplier clock,
            boolean translatePrimary,
            boolean translateSecondary) {
        this.source = List.copyOf(source);
        this.sourceLanguage = Objects.requireNonNull(sourceLanguage);
        this.primaryLanguage = Objects.requireNonNull(primaryLanguage);
        this.secondaryLanguage =
                primaryLanguage.equalsIgnoreCase(secondaryLanguage) ? null : secondaryLanguage;
        this.translator = translator;
        this.worker = worker;
        this.changed = changed;
        this.failed = failed;
        this.clock = clock;
        this.translatePrimary = translatePrimary;
        this.translateSecondary = translateSecondary;
        status = new byte[source.size()];
        attempts = new byte[source.size()];
        retryAt = new long[source.size()];

        long previousEnd = -1;
        for (StableCaptionBlockPolicy.Block cue : source) {
            if (cue.start < previousEnd || cue.end <= cue.start) {
                throw new IllegalArgumentException("Invalid source clock");
            }
            previousEnd = cue.end;
        }
    }

    synchronized long playbackTime() {
        return position;
    }

    synchronized boolean isActive() {
        return active;
    }

    synchronized void invalidate() {
        active = false;
        results.clear();
        displayed = null;
    }

    void demand(long time) {
        demand(time, 1);
    }

    void demand(long time, float speed) {
        synchronized (this) {
            if (!active) return;

            // A user seek may recover transient failures. Ordinary frames never retry them.
            if (time < position - SEEK_BACK_THRESHOLD_MS) {
                for (int i = 0; i < status.length; i++) {
                    if (status[i] == STATUS_FAILED) {
                        status[i] = STATUS_PENDING;
                        attempts[i] = 0;
                        retryAt[i] = 0;
                    }
                }
            }
            position = time;
            playbackRate = Float.isFinite(speed) && speed > 0 ? speed : 1;

            if (running || !needsTranslation()) return;
            running = true;
        }
        worker.execute(this::pump);
    }

    private boolean needsTranslation() {
        return (translatePrimary && !primaryLanguage.equalsIgnoreCase(sourceLanguage))
                || (translateSecondary
                        && secondaryLanguage != null
                        && !secondaryLanguage.equalsIgnoreCase(sourceLanguage));
    }

    /**
     * @return The cues to translate next, marked as requested. Empty if there are none.
     */
    private List<Integer> nextBatch() {
        List<Integer> batch = new ArrayList<>();
        int first = 0;
        while (first < source.size() && source.get(first).end <= position) first++;

        final long prefetchEnd =
                position
                        + (long)
                                (PREFETCH_MS
                                        * Math.min(MAX_PREFETCH_SPEED, Math.max(1, playbackRate)));
        // Never prefetch more blocks than can remain cached, or evictions requeue completed work.
        final int limit = Math.min(source.size(), first + CACHE_CAPACITY);
        for (int i = first;
                i < limit && batch.size() < CaptionTranslator.MAX_BATCH_TEXTS;
                i++) {
            StableCaptionBlockPolicy.Block cue = source.get(i);
            if (cue.start > prefetchEnd) break;

            if (cue.end <= position
                    || status[i] == STATUS_REQUESTED
                    || (status[i] == STATUS_READY && results.get(i) != null)
                    || (status[i] == STATUS_FAILED
                            && (attempts[i] >= MAX_ATTEMPTS || clock.getAsLong() < retryAt[i]))) {
                continue;
            }

            status[i] = STATUS_REQUESTED;
            batch.add(i);
        }
        return batch;
    }

    private List<Part> parts(List<Integer> batch) {
        List<Part> result = new ArrayList<>();
        for (int index : batch) {
            var cue = source.get(index);
            if (cue.windows.isEmpty()) result.add(new Part(index, 0, cue.text));
            else cue.windows.forEach((id, text) -> result.add(new Part(index, id, text)));
        }
        return result;
    }

    /** Caller holds the session lock; missing data is pending, never a failure sentinel. */
    private SlotResult slotResult(int index, int window, int slot) {
        BlockResult block = results.get(index);
        WindowResult value = block == null ? null : block.windows.get(window);
        return value == null ? SlotResult.PENDING : value.slots[slot];
    }

    private void storeResult(Part part, int slot, SlotResult result) {
        SlotState previous = slotResult(part.index, part.window, slot).state;
        results.computeIfAbsent(part.index, ignored -> new BlockResult())
                .windows.computeIfAbsent(part.window, ignored -> new WindowResult())
                .slots[slot] = result;
        if (previous != result.state)
            logResult(part.index, part.window, slot, "publish",
                    result.state == SlotState.SUCCESS ? "ready" : "terminal_failure");
    }

    private void logResult(int block, int window, int slot, String phase, String reason) {
        Logger.printDebug(() -> "Captions owner=" + System.identityHashCode(this)
                + " slot=" + (slot < 0 ? "both" : slot) + " phase=" + phase
                + " reason=" + reason + " block=" + block + " window=" + window);
    }

    private void publishNativeResult(Part part, int slot, NativeCaptionRunPlan.Result result) {
        boolean current;
        synchronized (this) {
            if (!active) throw new CancellationException();
            storeResult(part, slot, SlotResult.success(result.text(), result));
            current = indexOfCue(position) == part.index;
        }
        if (current) changed.run();
    }

    @Nullable
    private CaptionTranslator.Batch translatedSingleRuns(String target, List<Part> batch, int slot)
            throws IOException {
        for (Part part : batch) {
            var plan = source.get(part.index).runPlans.get(part.window);
            if (plan == null || !plan.isSingleRun()) return null;
        }
        if (sourceLanguage.equalsIgnoreCase(target))
            return new CaptionTranslator.Batch(batch.stream().map(Part::text).toList(), 0);
        var result = new ArrayList<String>();
        var missing = new ArrayList<String>();
        var positions = new ArrayList<Integer>();
        for (Part part : batch) {
            String value;
            synchronized (this) {
                SlotResult cached = slotResult(part.index, part.window, slot);
                if (cached.state == SlotState.TERMINAL_FAILURE) {
                    result.add(null);
                    continue;
                }
                value = cached.text;
            }
            if (value == null) {
                positions.add(result.size());
                missing.add(part.text);
            }
            result.add(value);
        }
        if (missing.isEmpty()) return new CaptionTranslator.Batch(result, 0);
        var translated =
                CaptionSceneTranslator.translateLongBatch(
                        sourceLanguage, target, missing, translator, this::isActive);
        for (int i = 0; i < positions.size(); i++) {
            String value = translated.texts.get(i);
            if (value == null || value.isBlank()) continue;
            int index = positions.get(i);
            Part part = batch.get(index);
            var styled = source.get(part.index).runPlans.get(part.window).withSingleText(value);
            publishNativeResult(part, slot, styled);
            result.set(index, styled.text());
        }
        return new CaptionTranslator.Batch(result, translated.retryAfterMs);
    }

    private CaptionTranslator.Batch translated(@Nullable String target, List<Part> batch, int slot)
            throws IOException {
        if (target == null) return new CaptionTranslator.Batch(Collections.nCopies(batch.size(), ""), 0);
        try {
            CaptionTranslator.Batch scalar = translatedSingleRuns(target, batch, slot);
            if (scalar != null) return scalar;
        } catch (CaptionTranslator.Failure failure) {
            if (failure.retryable && failure.retryAfterMs > 0) throw failure;
            // A cached structured response can still succeed when a plain batch is unavailable.
        } catch (IOException unavailable) {
            Logger.printDebug(() -> "Plain native caption batch unavailable");
        }

        List<String> result = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<Integer> plainPositions = new ArrayList<>();
        long retry = 0;
        for (Part part : batch) {
            String value;
            synchronized (this) {
                SlotResult cached = slotResult(part.index, part.window, slot);
                if (!sourceLanguage.equalsIgnoreCase(target)
                        && cached.state == SlotState.TERMINAL_FAILURE) {
                    result.add(null);
                    continue;
                }
                value = sourceLanguage.equalsIgnoreCase(target) ? part.text : cached.text;
            }
            NativeCaptionRunPlan plan = source.get(part.index).runPlans.get(part.window);
            if (value == null && plan != null) {
                try {
                    var translated =
                            plan.translate(sourceLanguage, target, translator, this::isActive);
                    retry = Math.max(retry, translated.retryAfterMs());
                    if (translated.complete()) {
                        value = translated.text();
                        publishNativeResult(part, slot, translated);
                    }
                } catch (CaptionTranslator.Failure failure) {
                    retry = Math.max(retry, failure.retryAfterMs);
                    if (!failure.retryable) {
                        synchronized (this) {
                            if (!active) throw new CancellationException();
                            storeResult(part, slot, SlotResult.FAILED);
                        }
                        result.add(null);
                        continue;
                    }
                } catch (IOException failure) {
                    Logger.printInfo(() -> "Styled caption window unavailable", failure);
                }
            } else if (value == null) {
                plainPositions.add(result.size());
                missing.add(part.text);
            }
            result.add(value);
        }
        if (missing.isEmpty()) return new CaptionTranslator.Batch(result, retry);
        boolean longCue =
                batch.stream().anyMatch(part -> !source.get(part.index).windows.isEmpty())
                        || missing.stream()
                                .anyMatch(
                                        text ->
                                                text.codePointCount(0, text.length())
                                                        > StableCaptionBlockPolicy.MAX_BLOCK_CHARS);
        CaptionTranslator.Batch response =
                longCue
                        ? CaptionSceneTranslator.translateLongBatch(
                                sourceLanguage, target, missing, translator, this::isActive)
                        : translator.translateBatch(
                                sourceLanguage, target, missing, this::isActive);
        if (response.texts == null || response.texts.size() != missing.size())
            throw new CaptionTranslator.Failure("Incomplete caption batch", 0, false);
        for (int i = 0; i < plainPositions.size(); i++) {
            String value = response.texts.get(i);
            result.set(plainPositions.get(i), value == null || value.isBlank() ? null : value);
        }
        return new CaptionTranslator.Batch(result, Math.max(retry, response.retryAfterMs));
    }

    /**
     * Translates one language of a batch and stores the result.
     *
     * @return Minimum time to wait before retrying the batch.
     */
    private long translateSlot(List<Integer> batch, int slot) {
        List<Part> parts = parts(batch);
        List<String> output = null;
        long retryDelay = 0;
        try {
            CaptionTranslator.Batch response =
                    translated(
                            slot == PRIMARY_SLOT ? primaryLanguage : secondaryLanguage,
                            parts,
                            slot);
            output = response.texts;
            retryDelay = response.retryAfterMs;
        } catch (CaptionTranslator.Failure failure) {
            Logger.printInfo(() -> "Caption translation unavailable", failure);
            retryDelay = failure.retryAfterMs;
            if (!failure.retryable) {
                synchronized (this) {
                    if (!active) return retryDelay;
                    for (Part part : parts) {
                        if (slotResult(part.index, part.window, slot).state == SlotState.PENDING)
                            storeResult(part, slot, SlotResult.FAILED);
                    }
                }
            }
        } catch (CancellationException ex) {
            Logger.printDebug(() -> "Caption translation cancelled");
        } catch (IOException ex) {
            Logger.printInfo(() -> "Caption translation failed", ex);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "translateSlot failure", ex);
        }

        if (output != null) {
            synchronized (this) {
                if (!active) return retryDelay;
                for (int i = 0; i < parts.size(); i++) {
                    if (output.get(i) != null) {
                        Part part = parts.get(i);
                        SlotResult previous = slotResult(part.index, part.window, slot);
                        // A structured result was already published atomically with this text.
                        if (previous.state != SlotState.SUCCESS
                                || !output.get(i).equals(previous.text))
                            storeResult(part, slot, SlotResult.success(output.get(i), null));
                    }
                }
            }
        }
        return retryDelay;
    }

    /**
     * Updates the status of the cues of a translated batch.
     *
     * @return If this is the first failure of the session.
     */
    private synchronized boolean finishBatch(
            List<Integer> batch, long retryDelay, int completedSlots) {
        boolean error = false;
        for (int index : batch) {
            boolean missing = false, failedOutput = false, attemptedMissing = false;
            for (Part part : parts(List.of(index))) {
                for (int slot = 0; slot < SLOT_COUNT; slot++) {
                    if (slot == PRIMARY_SLOT && !translatePrimary
                            || slot == SECONDARY_SLOT
                                    && (!translateSecondary || secondaryLanguage == null)) continue;
                    SlotState state = slotResult(index, part.window, slot).state;
                    boolean absent = state == SlotState.PENDING;
                    missing |= absent;
                    failedOutput |= state == SlotState.TERMINAL_FAILURE;
                    attemptedMissing |= absent && (completedSlots & (1 << slot)) != 0;
                }
            }
            if (attemptedMissing) {
                attempts[index]++;
                retryAt[index] =
                        clock.getAsLong()
                                + Math.max(retryDelay, RETRY_BASE_MS << (attempts[index] - 1));
            } else if (!missing) {
                attempts[index] = failedOutput ? MAX_ATTEMPTS : 0;
            }
            status[index] =
                    attemptedMissing || failedOutput
                            ? STATUS_FAILED
                            : missing ? STATUS_PENDING : STATUS_READY;
            error |= attemptedMissing || failedOutput;
        }

        final boolean firstFailure = error && !failureReported;
        failureReported |= error;
        return firstFailure;
    }

    private void pump() {
        nextDemand:
        while (true) {
            List<Integer> batch;
            long batchPosition;
            synchronized (this) {
                batch = active ? nextBatch() : List.of();
                if (batch.isEmpty()) {
                    running = false;
                    return;
                }
                batchPosition = position;
            }

            long retryDelay = 0;
            int completedSlots = 0;
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                if (slot == PRIMARY_SLOT && !translatePrimary
                        || slot == SECONDARY_SLOT && !translateSecondary) continue;
                retryDelay = Math.max(retryDelay, translateSlot(batch, slot));
                completedSlots |= 1 << slot;
                if (!isActive()) return;

                // One slow target must not hold an already translated language off screen.
                changed.run();
                if (!isActive()) return;

                boolean moved;
                synchronized (this) {
                    moved =
                            position < batchPosition - SEEK_BACK_THRESHOLD_MS
                                    || position >= source.get(batch.get(batch.size() - 1)).end;
                }
                // Keep completed results and their failure deadlines before selecting the new
                // demand.
                if (moved) {
                    if (finishBatch(batch, retryDelay, completedSlots)) failed.run();
                    continue nextDemand;
                }
            }

            if (finishBatch(batch, retryDelay, completedSlots)) failed.run();
        }
    }

    @Nullable
    synchronized Snapshot snapshot(long time) {
        return snapshot(time, false);
    }

    /**
     * @return The cue to show at a time. Null if no cue is shown.
     */
    @Nullable
    synchronized Snapshot snapshot(long time, boolean paused) {
        if (!active) return null;

        int found = indexOfCue(time);
        if (found < 0) {
            lastIndex = -1;
            displayed = null;
            lastDisplayTime = time;
            return null;
        }

        StableCaptionBlockPolicy.Block cue = source.get(found);
        Map<Integer, WindowText> windows = new LinkedHashMap<>();
        WindowText output;
        if (cue.windows.isEmpty()) output = output(found, 0, cue.text);
        else {
            cue.windows.forEach((id, text) -> windows.put(id, output(found, id, text)));
            String primary =
                    windows.values().stream()
                            .map(WindowText::primary)
                            .collect(java.util.stream.Collectors.joining("\n"));
            String secondary =
                    windows.values().stream()
                            .map(WindowText::secondary)
                            .collect(java.util.stream.Collectors.joining("\n"));
            output =
                    new WindowText(
                            primary,
                            secondary,
                            windows.values().stream().allMatch(WindowText::complete));
        }
        Snapshot candidate =
                new Snapshot(cue, output.primary, output.secondary, output.complete, windows);

        // Text does not change while it is being read, unless enough of the cue remains.
        final boolean seekBack =
                lastDisplayTime != Long.MIN_VALUE
                        && time < lastDisplayTime - SEEK_BACK_THRESHOLD_MS;
        if (found != lastIndex || displayed == null || seekBack || !cue.windows.isEmpty()) {
            displayed = candidate;
        } else if (!displayed.complete
                && (paused
                        || cue.end - time
                                >= StableCaptionBlockPolicy.MIN_DURATION_MS * (double) playbackRate)
                && (!candidate.primary.equals(displayed.primary)
                        || !candidate.secondary.equals(displayed.secondary))) {
            displayed = candidate;
        }
        lastIndex = found;
        lastDisplayTime = time;
        return displayed;
    }

    private WindowText output(int found, int window, String text) {
        SlotResult primary = slotResult(found, window, PRIMARY_SLOT);
        SlotResult secondary = slotResult(found, window, SECONDARY_SLOT);
        String first = !translatePrimary ? ""
                : primaryLanguage.equalsIgnoreCase(sourceLanguage) ? text : primary.text;
        String second = !translateSecondary || secondaryLanguage == null ? ""
                : secondaryLanguage.equalsIgnoreCase(sourceLanguage) ? text : secondary.text;
        NativeCaptionRunPlan plan = source.get(found).runPlans.get(window);
        if (plan == null) {
            if (first == null && primary.state != SlotState.TERMINAL_FAILURE)
                first = translator.cached(sourceLanguage, primaryLanguage, text);
            if (second == null && secondaryLanguage != null
                    && secondary.state != SlotState.TERMINAL_FAILURE)
                second = translator.cached(sourceLanguage, secondaryLanguage, text);
        }
        boolean unavailable = translatePrimary
                && (primary.state == SlotState.TERMINAL_FAILURE || first == null && attempts[found] > 0);
        NativeCaptionRunPlan.Result primaryRuns = primary.runs;
        if (plan != null && unavailable && first == null) primaryRuns = plan.original();
        return new WindowText(first == null ? (unavailable ? text : "") : first,
                second == null ? "" : second, first != null && second != null,
                primaryRuns, secondary.runs);
    }

    private int indexOfCue(long time) {
        int low = 0, high = source.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            StableCaptionBlockPolicy.Block cue = source.get(mid);
            if (time < cue.start) high = mid - 1;
            else if (time >= cue.end) low = mid + 1;
            else return mid;
        }
        return -1;
    }
}
