package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import app.morphe.extension.youtube.patches.captions.model.CaptionScene;

import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

public class NativeCaptionRunPlanTest {
    private static CaptionTranslator provider(List<CaptionTranslator.Part> parts) {
        return new CaptionTranslator() {
            public List<String> translate(String from, String to, List<String> texts) {
                return texts.stream().map(text -> "safe:" + text).toList();
            }

            public CaptionTranslator.StyledResult translateStyled(
                    String from, String to, List<CaptionScene.Run> runs, BooleanSupplier active) {
                return new CaptionTranslator.StyledResult(parts);
            }
        };
    }

    @Test
    public void reorderedProviderGroupsKeepTheirNativeRenderersAndVisibility() throws Exception {
        var red =
                new NativeCaptionRunPlan.Group(
                        "red", List.of("one"), null, (text, annotation) -> "red:" + text);
        var blue =
                new NativeCaptionRunPlan.Group(
                        "blue", List.of("two"), null, (text, annotation) -> "blue:" + text);
        var result =
                new NativeCaptionRunPlan(List.of(red, blue))
                        .translate(
                                "en",
                                "tr",
                                provider(
                                        List.of(
                                                new CaptionTranslator.Part("blue.0", "uzun ikinci"),
                                                new CaptionTranslator.Part("red.0", "bir"))),
                                () -> true);
        assertTrue(result.complete());
        assertEquals(List.of("blue:uzun ikinci", "red:bir"), result.render(Set.of("red", "blue")));
        assertEquals(List.of("red:bir"), result.render(Set.of("red")));
    }

    @Test
    public void providerCannotIntroduceAnUnownedNativeParagraph() throws Exception {
        var group =
                new NativeCaptionRunPlan.Group(
                        "one", List.of("one line"), null, (text, annotation) -> text);
        var result =
                new NativeCaptionRunPlan(List.of(group))
                        .translate(
                                "en",
                                "tr",
                                provider(List.of(new CaptionTranslator.Part("one.0", "bir\r\nsatir"))),
                                () -> true);
        assertEquals("bir satir", result.text());
    }

    @Test
    public void nativeParagraphBreaksStayOutsideProviderOwnedText() throws Exception {
        var first =
                new NativeCaptionRunPlan.Group(
                                "first", List.of("one"), null, (text, annotation) -> text)
                        .inParagraph("p0");
        var newline =
                new NativeCaptionRunPlan.Group(
                                "newline", List.of("\n"), null, (text, annotation) -> text)
                        .inParagraph("p0");
        var second =
                new NativeCaptionRunPlan.Group(
                                "second", List.of("two"), null, (text, annotation) -> text)
                        .inParagraph("p1");
        CaptionTranslator provider =
                new CaptionTranslator() {
                    public List<String> translate(String from, String to, List<String> texts) {
                        throw new AssertionError("Structured response is valid");
                    }

                    public CaptionTranslator.StyledResult translateStyled(
                            String from,
                            String to,
                            List<CaptionScene.Run> runs,
                            BooleanSupplier active) {
                        assertEquals(
                                List.of("first.0", "second.0"),
                                runs.stream().map(CaptionScene.Run::id).toList());
                        return new CaptionTranslator.StyledResult(
                                List.of(
                                        new CaptionTranslator.Part("first.0", "bir"),
                                        new CaptionTranslator.Part("second.0", "iki")));
                    }
                };
        var result =
                new NativeCaptionRunPlan(List.of(first, newline, second))
                        .translate("en", "tr", provider, () -> true);
        assertEquals("bir\niki", result.text());
        assertEquals(
                List.of("bir", "\n", "iki"), result.render(Set.of("first", "newline", "second")));
    }

    @Test
    public void providerCannotAttachTextToAnotherNativeParagraph() throws Exception {
        var one =
                new NativeCaptionRunPlan.Group(
                                "one", List.of("first"), null, (text, annotation) -> text)
                        .inParagraph("p0");
        var two =
                new NativeCaptionRunPlan.Group(
                                "two", List.of("second"), null, (text, annotation) -> text)
                        .inParagraph("p1");
        var result =
                new NativeCaptionRunPlan(List.of(one, two))
                        .translate(
                                "en",
                                "tr",
                                provider(
                                        List.of(
                                                new CaptionTranslator.Part("two.0", "untrusted second"),
                                                new CaptionTranslator.Part(
                                                        "one.0", "untrusted first"))),
                                () -> true);
        assertEquals(List.of("safe:first", "safe:second"), result.render(Set.of("one", "two")));
    }

    @Test
    public void providerCannotSeparateNativeRubyLineParts() throws Exception {
        var ruby =
                new NativeCaptionRunPlan.Group(
                        "ruby",
                        List.of("a\nb", "(", "c", ")"),
                        "3,4,5,4",
                        (text, annotation) -> text);
        CaptionTranslator provider =
                new CaptionTranslator() {
                    public List<String> translate(String from, String to, List<String> texts) {
                        return texts.stream().map(text -> "safe:" + text).toList();
                    }

                    public CaptionTranslator.StyledResult translateStyled(
                            String from,
                            String to,
                            List<CaptionScene.Run> runs,
                            BooleanSupplier active) {
                        var parts = new java.util.ArrayList<CaptionTranslator.Part>();
                        for (var run : runs)
                            parts.add(new CaptionTranslator.Part(run.id(), "untrusted:" + run.text()));
                        parts.add(parts.remove(0));
                        return new CaptionTranslator.StyledResult(parts);
                    }
                };
        var result =
                new NativeCaptionRunPlan(List.of(ruby)).translate("en", "tr", provider, () -> true);
        assertTrue(result.complete());
        assertFalse(result.text().contains("untrusted"));
        assertTrue(result.text().startsWith("safe:a\nsafe:b"));
    }

    @Test
    public void rubyHardBreaksStayOutsideProviderOwnedText() throws Exception {
        var ruby =
                new NativeCaptionRunPlan.Group(
                        "ruby",
                        List.of("a\nb", "(", "c", ")"),
                        "3,4,5,4",
                        (text, annotation) -> text + "@" + annotation);
        CaptionTranslator provider =
                new CaptionTranslator() {
                    public List<String> translate(String from, String to, List<String> texts) {
                        throw new AssertionError("Valid structured response");
                    }

                    public CaptionTranslator.StyledResult translateStyled(
                            String from,
                            String to,
                            List<CaptionScene.Run> runs,
                            BooleanSupplier active) {
                        assertEquals(
                                List.of("a", "b", "(", "c", ")"),
                                runs.stream().map(CaptionScene.Run::text).toList());
                        return new CaptionTranslator.StyledResult(
                                runs.stream()
                                        .map(
                                                run ->
                                                        new CaptionTranslator.Part(
                                                                run.id(),
                                                                switch (run.text()) {
                                                                    case "a" -> "A2";
                                                                    case "b" -> "B2";
                                                                    case "c" -> "read";
                                                                    default -> run.text();
                                                                }))
                                        .toList());
                    }
                };
        var result =
                new NativeCaptionRunPlan(List.of(ruby)).translate("en", "tr", provider, () -> true);
        assertEquals("A2\nB2(read)", result.text());
        assertEquals(List.of("A2\nB2(read)@5,6,10,4"), result.render(Set.of("ruby")));
    }

    @Test
    public void rubyOffsetsFollowTranslatedPartsInsteadOfSourceLengths() throws Exception {
        var ruby =
                new NativeCaptionRunPlan.Group(
                        "ruby",
                        List.of("字", "(", "じ", ")"),
                        "1,2,3,4",
                        (text, annotation) -> text + "@" + annotation);
        var result =
                new NativeCaptionRunPlan(List.of(ruby))
                        .translate(
                                "ja",
                                "tr",
                                provider(
                                        List.of(
                                                new CaptionTranslator.Part("ruby.0", "letter"),
                                                        new CaptionTranslator.Part("ruby.1", "("),
                                                new CaptionTranslator.Part("ruby.2", "reading"),
                                                        new CaptionTranslator.Part("ruby.3", ")"))),
                                () -> true);
        assertEquals(List.of("letter(reading)@6,7,14,4"), result.render(Set.of("ruby")));
    }

    @Test
    public void invalidProviderIdsNeverReachNativeRenderers() throws Exception {
        var source =
                new NativeCaptionRunPlan.Group(
                        "owned", List.of("source"), null, (text, annotation) -> "native:" + text);
        var result =
                new NativeCaptionRunPlan(List.of(source))
                        .translate(
                                "en",
                                "tr",
                                provider(List.of(new CaptionTranslator.Part("unknown", "untrusted"))),
                                () -> true);
        assertEquals(List.of("native:safe:source"), result.render(Set.of("owned")));
    }
}
