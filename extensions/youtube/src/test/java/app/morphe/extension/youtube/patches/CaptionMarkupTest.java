package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import app.morphe.extension.youtube.patches.captions.model.CaptionScene;

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class CaptionMarkupTest {
    @Test
    public void styledBudgetCountsDecodedCodepointsAcrossAllParts() throws Exception {
        var one = List.of(run("one", "source"));
        String boundary = "😀".repeat(4096);
        assertEquals(boundary,
                CaptionMarkup.decode("<span id='m0'>" + boundary + "</span>", one)
                        .parts().get(0).text());
        assertThrows(IOException.class, () -> CaptionMarkup.decode(
                "<span id='m0'>" + boundary + "x</span>", one));
        var two = List.of(run("one", "first"), run("two", "second"));
        assertThrows(IOException.class, () -> CaptionMarkup.decode(
                "<span id='m0'>" + "a".repeat(2049) + "</span>"
                        + "<span id='m1'>" + "b".repeat(2048) + "</span>", two));
    }

    @Test
    public void styledBudgetIncludesRestoredSourcePadding() {
        var padded = List.of(run("one", "  source "));
        assertThrows(IOException.class, () -> CaptionMarkup.decode(
                "<span id='m0'>" + "x".repeat(4094) + "</span>", padded));
    }

    private List<CaptionScene.Run> paragraphs() {
        return List.of(
                new CaptionScene.Run("first", "one", 0, Map.of(), Map.of(), "p1", 1000),
                new CaptionScene.Run("second", "two", 0, Map.of(), Map.of(), "p2", 1000));
    }

    @Test
    public void paragraphBoundariesAreContextNotTranslatedCharacters() throws Exception {
        assertEquals(
                "<span id=\"m0\">one</span><br/><span id=\"m1\">two</span>",
                CaptionMarkup.encode(paragraphs()));
    }

    @Test
    public void responseParagraphSeparatorsDoNotCreateDuplicateLineBreaks() throws Exception {
        var result =
                CaptionMarkup.decode(
                        "<span id='m0'>bir</span><br/><span id='m1'>iki</span>", paragraphs());
        assertEquals("bir", result.parts().get(0).text());
        assertEquals("iki", result.parts().get(1).text());
    }

    @Test
    public void hardLineBreaksRemainStructuralDuringHtmlTranslation() throws Exception {
        var runs = List.of(run("line", "one\ntwo"));
        assertEquals("<span id=\"m0\">one<br/>two</span>", CaptionMarkup.encode(runs));
        assertEquals(
                "bir\niki",
                CaptionMarkup.decode("<span id='m0'>bir<br>iki</span>", runs)
                        .parts()
                        .get(0)
                        .text());
    }

    private CaptionScene.Run run(String id, String text) {
        return new CaptionScene.Run(id, text, 100, Map.of("fc", "#FF0000"), Map.of());
    }

    @Test
    public void requestEscapesLiteralMarkupAndNeverExposesSourceIdsOrStyle() throws Exception {
        String request = CaptionMarkup.encode(List.of(run("private-source-id", "<b>&\"hello\"")));
        assertEquals("<span id=\"m0\">&lt;b&gt;&amp;\"hello\"</span>", request);
        assertFalse(request.contains("private-source-id"));
        assertFalse(request.contains("FF0000"));
    }

    @Test
    public void observedProviderSpacingAndReorderingRetainTheCorrectSourceIds() throws Exception {
        var runs = List.of(run("first", "Hello "), run("second", "world"));
        var observed =
                CaptionMarkup.decode(
                        "<span id=\"m0\">Selam</span> <span id=\"m1\">Dünya</span>", runs);
        assertEquals(
                List.of(
                        new CaptionTranslator.Part("first", "Selam "),
                        new CaptionTranslator.Part("second", "Dünya")),
                observed.parts());
        var reordered =
                CaptionMarkup.decode("<span id='m1'>Dünya</span> <span id='m0'>Selam</span>", runs);
        assertEquals("second", reordered.parts().get(0).id());
        assertEquals("Dünya ", reordered.parts().get(0).text());
    }

    @Test
    public void creatorPaddingAndLiteralEntitiesSurviveExactlyOneDecodePass() throws Exception {
        var runs = List.of(run("literal", "  <source> \n"));
        var result = CaptionMarkup.decode("<span id='m0'>&lt;script&gt;&amp;lt;</span>", runs);
        assertEquals("  <script>&lt; \n", result.parts().get(0).text());
    }

    @Test
    public void missingDuplicateUnknownAndExecutableMarkupAreRejected() {
        var runs = List.of(run("one", "source"));
        for (String html :
                List.of(
                        "",
                        "<span id='m1'>wrong</span>",
                        "<span id='m0'>one</span><span id='m0'>two</span>",
                        "<span id='m0'><script>bad</script></span>",
                        "<span id='m0'>one</span>unowned",
                        "<!DOCTYPE root><span id='m0'>one</span>")) {
            assertThrows(html, IOException.class, () -> CaptionMarkup.decode(html, runs));
        }
    }
}
