package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;
import app.morphe.extension.youtube.patches.captions.model.CaptionScene;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Ordered translation values; HTTP, markup and native renderers stay behind this boundary. */
interface CaptionTranslator {
    int MAX_BATCH_TEXTS = 5;

    List<String> translate(String source, String target, List<String> texts) throws IOException;

    default List<String> translate(
            String source, String target, List<String> texts, BooleanSupplier active)
            throws IOException {
        return translate(source, target, texts);
    }

    default Batch translateBatch(
            String source, String target, List<String> texts, BooleanSupplier active)
            throws IOException {
        return new Batch(translate(source, target, texts, active), 0);
    }

    @Nullable
    default StyledResult translateStyled(
            String source, String target, List<CaptionScene.Run> runs, BooleanSupplier active)
            throws IOException {
        return null;
    }

    @Nullable
    default String cached(String source, String target, String text) {
        return null;
    }

    /** Aligned partial results: a null entry is unavailable, not an empty translation. */
    final class Batch {
        final List<String> texts;
        final long retryAfterMs;

        Batch(List<String> texts, long retryAfterMs) {
            // A malformed null list is still rejected by consumers at their acceptance boundary.
            this.texts = texts == null ? null
                    : Collections.unmodifiableList(new ArrayList<>(texts));
            this.retryAfterMs = retryAfterMs;
        }
    }

    record Part(String id, String text) {}

    record StyledResult(List<Part> parts) {
        public StyledResult {
            parts = List.copyOf(parts);
        }
    }

    final class Failure extends IOException {
        final long retryAfterMs;
        final boolean retryable;

        Failure(String message, long retryAfterMs, boolean retryable) {
            super(message);
            this.retryAfterMs = retryAfterMs;
            this.retryable = retryable;
        }
    }
}
