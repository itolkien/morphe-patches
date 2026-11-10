package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import app.morphe.extension.youtube.patches.captions.model.*;

import org.junit.Test;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;

public class CaptionSceneTranslatorTest {
    @Test
    public void structuredTranslationCannotMoveTextAcrossParagraphs() throws Exception {
        var first = new CaptionScene.Run("a", "one", 0, Map.of(), Map.of(), "p1", 1000);
        var second = new CaptionScene.Run("b", "two", 0, Map.of(), Map.of(), "p2", 1000);
        var translated =
                new CaptionTranslator.StyledResult(
                        List.of(
                                new CaptionTranslator.Part("b", "iki"),
                                new CaptionTranslator.Part("a", "bir")));
        CaptionTranslator provider =
                new CaptionTranslator() {
                    public List<String> translate(String from, String to, List<String> texts) {
                        return texts.stream().map(t -> "safe:" + t).toList();
                    }

                    public CaptionTranslator.StyledResult translateStyled(
                            String from,
                            String to,
                            List<CaptionScene.Run> runs,
                            java.util.function.BooleanSupplier active) {
                        return translated;
                    }
                };
        var source =
                new CaptionScene(
                        List.of(
                                new CaptionScene.Window(
                                        "window",
                                        0,
                                        1000,
                                        Map.of(),
                                        Map.of(),
                                        Map.of(),
                                        List.of(first, second))));
        var result = CaptionSceneTranslator.translate(source, "en", "tr", provider, () -> true);
        assertEquals(
                List.of("safe:one", "safe:two"),
                result.scene().windows().get(0).runs().stream()
                        .map(CaptionScene.Run::text)
                        .toList());
    }

    @Test
    public void invalidStructuredIdsUseIndependentUnitsWithoutTrustingReturnedText()
            throws Exception {
        CaptionTranslator provider =
                new CaptionTranslator() {
                    public List<String> translate(String from, String to, List<String> texts) {
                        return texts.stream().map(t -> "safe:" + t).toList();
                    }

                    public CaptionTranslator.StyledResult translateStyled(
                            String from,
                            String to,
                            List<CaptionScene.Run> runs,
                            java.util.function.BooleanSupplier active) {
                        return new CaptionTranslator.StyledResult(
                                List.of(new CaptionTranslator.Part("unknown", "untrusted")));
                    }
                };
        var result =
                CaptionSceneTranslator.translate(scene("one"), "en", "tr", provider, () -> true);
        assertTrue(result.complete());
        for (var run : result.scene().windows().get(0).runs()) assertEquals("safe:one", run.text());
    }

    @Test
    public void structuredProviderCanReorderWordsWithoutReplacingTheirSourceFormatting()
            throws Exception {
        CaptionTranslator provider =
                new CaptionTranslator() {
                    public List<String> translate(String from, String to, List<String> texts) {
                        throw new AssertionError("Use the sentence context request");
                    }

                    public CaptionTranslator.StyledResult translateStyled(
                            String from,
                            String to,
                            List<CaptionScene.Run> runs,
                            java.util.function.BooleanSupplier active) {
                        return new CaptionTranslator.StyledResult(
                                List.of(
                                        new CaptionTranslator.Part(runs.get(1).id(), "second target"),
                                        new CaptionTranslator.Part(runs.get(0).id(), "first target")));
                    }
                };
        var original = scene("one");
        var result = CaptionSceneTranslator.translate(original, "en", "tr", provider, () -> true);
        var runs = result.scene().windows().get(0).runs();
        assertEquals(
                original.windows().get(0).runs().get(1).withText("second target"), runs.get(0));
        assertEquals(original.windows().get(0).runs().get(0).withText("first target"), runs.get(1));
    }

    @Test
    public void partialRetryKeepsSuccessfulUnitsAndCooldownMetadata() throws Exception {
        Map<String, String> cache = new HashMap<>();
        List<String> sent = new ArrayList<>();
        int[] attempts = {0};
        CaptionTranslator provider =
                new CaptionTranslator() {
                    public List<String> translate(String from, String to, List<String> texts) {
                        throw new AssertionError();
                    }

                    public String cached(String from, String to, String text) {
                        return cache.get(text);
                    }

                    public CaptionTranslator.Batch translateBatch(
                            String from,
                            String to,
                            List<String> texts,
                            java.util.function.BooleanSupplier active) {
                        sent.addAll(texts);
                        if (attempts[0]++ == 0) {
                            cache.put(texts.get(0), "first");
                            return new CaptionTranslator.Batch(
                                    Arrays.asList("first", null), 120000);
                        }
                        assertEquals(1, texts.size());
                        return new CaptionTranslator.Batch(List.of("second"), 0);
                    }
                };
        String source = "a".repeat(512) + "b";
        var first =
                CaptionSceneTranslator.translateLongBatch(
                        "en", "tr", List.of(source), provider, () -> true);
        assertNull(first.texts.get(0));
        assertEquals(120000, first.retryAfterMs);
        var second =
                CaptionSceneTranslator.translateLongBatch(
                        "en", "tr", List.of(source), provider, () -> true);
        assertEquals("firstsecond", second.texts.get(0));
        assertEquals(3, sent.size());
    }

    private CaptionScene scene(String text) {
        return new CaptionScene(
                List.of(
                        new CaptionScene.Window(
                                "window",
                                10,
                                200,
                                Map.of("a", "1"),
                                Map.of("ap", "8"),
                                Map.of("pd", "3"),
                                List.of(
                                        new CaptionScene.Run(
                                                "base",
                                                text,
                                                20,
                                                Map.of("rb", "4", "fc", "#FF0000"),
                                                Map.of()),
                                        new CaptionScene.Run(
                                                "effect",
                                                text,
                                                20,
                                                Map.of("fo", "0", "et", "3"),
                                                Map.of())))));
    }

    @Test
    public void translatedExpansionPreservesIdsTimingStylesAndDeduplicatesEffects()
            throws Exception {
        List<String> sent = new ArrayList<>();
        CaptionTranslator provider =
                (from, to, texts) -> {
                    sent.addAll(texts);
                    return texts.stream().map(t -> "expanded:" + t).toList();
                };
        var source = scene("one");
        var result = CaptionSceneTranslator.translate(source, "en", "tr", provider, () -> true);
        assertTrue(result.complete());
        assertEquals(List.of("one"), sent);
        assertEquals(
                source.windows().get(0).position(), result.scene().windows().get(0).position());
        for (int i = 0; i < 2; i++) {
            var original = source.windows().get(0).runs().get(i);
            var translated = result.scene().windows().get(0).runs().get(i);
            assertEquals(original.withText("expanded:one"), translated);
        }
    }

    @Test
    public void longCuesAreSplitIntoProviderBoundedRequestsAndReassembled() throws Exception {
        String text = " A👩‍💻e\u0301 ".repeat(300);
        CaptionTranslator provider =
                (from, to, texts) -> {
                    assertTrue(texts.size() <= CaptionTranslator.MAX_BATCH_TEXTS);
                    for (String value : texts)
                        assertTrue(value.codePointCount(0, value.length()) <= 512);
                    return texts;
                };
        var result =
                CaptionSceneTranslator.translate(scene(text), "en", "tr", provider, () -> true);
        assertTrue(result.complete());
        assertEquals(text, result.scene().windows().get(0).runs().get(0).text());
    }

    @Test
    public void incompleteResponseNeverAttachesTextToAnotherSourceRun() throws Exception {
        CaptionTranslator invalid = (from, to, texts) -> List.of();
        assertThrows(
                IOException.class,
                () ->
                        CaptionSceneTranslator.translate(
                                scene("one"), "en", "tr", invalid, () -> true));
        assertThrows(
                CancellationException.class,
                () ->
                        CaptionSceneTranslator.translate(
                                scene("one"), "en", "tr", invalid, () -> false));
    }
}
