package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.patches.captions.model.CaptionScene;
import app.morphe.extension.youtube.patches.captions.model.CaptionTextUnits;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Translates text values while preserving source run identities and formatting. */
final class CaptionSceneTranslator {
    record Result(CaptionScene scene, boolean complete, long retryAfterMs) {}

    static Result translate(
            CaptionScene scene,
            String source,
            String target,
            CaptionTranslator translator,
            BooleanSupplier active)
            throws IOException {
        checkActive(active);
        if (source.equalsIgnoreCase(target)) return new Result(scene, true, 0);
        var structured = new LinkedHashMap<CaptionScene.Window, List<CaptionScene.Run>>();
        var texts = new ArrayList<String>();
        long structuredRetry = 0;
        for (var window : scene.windows()) {
            if (!window.runs().isEmpty()
                    && window.runs().stream().anyMatch(run -> !run.text().isBlank())) {
                try {
                    var result = translator.translateStyled(source, target, window.runs(), active);
                    checkActive(active);
                    if (result != null)
                        structured.put(window, validatedRuns(window.runs(), result));
                } catch (CaptionTranslator.Failure failure) {
                    structuredRetry = Math.max(structuredRetry, failure.retryAfterMs);
                } catch (IOException ignored) {
                    // A provider can lose markup. Independently aligned text units
                    // still preserve the source formatting without trusting its IDs.
                }
            }
            if (!structured.containsKey(window))
                for (var run : window.runs()) texts.add(run.text());
        }
        var translated = translateLongBatch(source, target, texts, translator, active);
        var windows = new ArrayList<CaptionScene.Window>();
        int index = 0;
        boolean complete = true;
        for (var window : scene.windows()) {
            List<CaptionScene.Run> runs = structured.get(window);
            if (runs == null) {
                var fallback = new ArrayList<CaptionScene.Run>();
                for (var run : window.runs()) {
                    String value = translated.texts.get(index++);
                    complete &= value != null;
                    fallback.add(value == null ? run : run.withText(value));
                }
                runs = fallback;
            }
            windows.add(
                    new CaptionScene.Window(
                            window.id(),
                            window.startMs(),
                            window.endMs(),
                            window.attributes(),
                            window.position(),
                            window.style(),
                            runs));
        }
        checkActive(active);
        return new Result(
                new CaptionScene(windows),
                complete,
                Math.max(structuredRetry, translated.retryAfterMs));
    }

    private static List<CaptionScene.Run> validatedRuns(
            List<CaptionScene.Run> source, CaptionTranslator.StyledResult translated)
            throws IOException {
        if (translated.parts().size() != source.size())
            throw new IOException("Missing styled caption IDs");
        var originals = new LinkedHashMap<String, CaptionScene.Run>();
        for (var run : source) originals.put(run.id(), run);
        var result = new ArrayList<CaptionScene.Run>();
        for (var part : translated.parts()) {
            var original = originals.remove(part.id());
            if (original == null || part.text() == null)
                throw new IOException("Invalid styled caption ID");
            result.add(original.withText(part.text()));
        }
        // Paragraphs own layout and timing. Reordering text within a paragraph is
        // valid; crossing a paragraph boundary would attach it to another row.
        var paragraphOrder = new LinkedHashMap<String, Integer>();
        for (var run : source) paragraphOrder.putIfAbsent(run.paragraphId(), paragraphOrder.size());
        int previousParagraph = -1;
        for (var run : result) {
            int paragraph = paragraphOrder.get(run.paragraphId());
            if (paragraph < previousParagraph)
                throw new IOException("Reordered translated paragraphs");
            previousParagraph = paragraph;
        }
        // Ruby's four source spans form one structural group. A provider may move
        // that group, but separating its base/parentheses/annotation loses the design.
        for (int i = 0; i + 3 < source.size(); i++) {
            if (!"1".equals(source.get(i).pen().get("rb"))
                    || !"2".equals(source.get(i + 1).pen().get("rb"))
                    || !"2".equals(source.get(i + 3).pen().get("rb"))) continue;
            int at = -1;
            for (int j = 0; j < result.size(); j++)
                if (result.get(j).id().equals(source.get(i).id())) {
                    at = j;
                    break;
                }
            if (at < 0 || at + 3 >= result.size())
                throw new IOException("Broken translated ruby group");
            for (int j = 0; j < 4; j++)
                if (!result.get(at + j).id().equals(source.get(i + j).id()))
                    throw new IOException("Reordered translated ruby group");
        }
        if (result.stream().allMatch(run -> run.text().isBlank())
                && source.stream().anyMatch(run -> !run.text().isBlank()))
            throw new IOException("Empty styled translation");
        return List.copyOf(result);
    }

    static CaptionTranslator.Batch translateLongBatch(
            String source,
            String target,
            List<String> texts,
            CaptionTranslator translator,
            BooleanSupplier active)
            throws IOException {
        checkActive(active);
        var slices = new ArrayList<List<Slice>>();
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < texts.size(); i++) {
            var parts = new ArrayList<Slice>();
            for (var unit :
                    CaptionTextUnits.split(
                            Integer.toString(i),
                            texts.get(i),
                            StableCaptionBlockPolicy.MAX_BLOCK_CHARS)) {
                Slice slice = Slice.of(unit.text());
                parts.add(slice);
                if (!slice.text.isEmpty() && !values.containsKey(slice.text)) {
                    values.put(
                            slice.text,
                            source.equalsIgnoreCase(target)
                                    ? slice.text
                                    : translator.cached(source, target, slice.text));
                }
            }
            slices.add(parts);
        }
        var missing = new ArrayList<String>();
        values.forEach(
                (key, value) -> {
                    if (value == null) missing.add(key);
                });
        long retry = 0;
        for (int start = 0;
                start < missing.size();
                start += CaptionTranslator.MAX_BATCH_TEXTS) {
            checkActive(active);
            List<String> request =
                    List.copyOf(
                            missing.subList(
                                    start,
                                    Math.min(
                                            start + CaptionTranslator.MAX_BATCH_TEXTS,
                                            missing.size())));
            var response = translator.translateBatch(source, target, request, active);
            checkActive(active);
            if (response == null
                    || response.texts == null
                    || response.texts.size() != request.size()) {
                throw new IOException("Incomplete structured caption response");
            }
            retry = Math.max(retry, response.retryAfterMs);
            for (int i = 0; i < request.size(); i++) {
                String value = response.texts.get(i);
                if (value != null && value.isBlank())
                    throw new IOException("Empty structured caption translation");
                values.put(request.get(i), value);
            }
        }
        var result = new ArrayList<String>();
        for (var parts : slices) {
            var text = new StringBuilder();
            boolean complete = true;
            for (Slice part : parts) {
                String value = part.text.isEmpty() ? "" : values.get(part.text);
                if (value == null) {
                    complete = false;
                    break;
                }
                text.append(part.prefix).append(value).append(part.suffix);
            }
            result.add(complete ? text.toString() : null);
        }
        return new CaptionTranslator.Batch(Collections.unmodifiableList(result), retry);
    }

    private record Slice(String prefix, String text, String suffix) {
        static Slice of(String text) {
            int start = 0, end = text.length();
            while (start < end && space(text.codePointAt(start)))
                start += Character.charCount(text.codePointAt(start));
            while (end > start && space(text.codePointBefore(end)))
                end -= Character.charCount(text.codePointBefore(end));
            return new Slice(
                    text.substring(0, start), text.substring(start, end), text.substring(end));
        }

        private static boolean space(int cp) {
            return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
        }
    }

    private static void checkActive(BooleanSupplier active) {
        if (!active.getAsBoolean())
            throw new CancellationException("Caption translation cancelled");
    }
}
