package app.morphe.extension.youtube.patches;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TranslatedCaptionStylePolicyTest {
    private Set<Integer> visible(String pens) {
        var document = CaptionXml.read((
                "<timedtext format=\"3\"><head>" + pens + "</head><body/></timedtext>"
        ).getBytes(StandardCharsets.UTF_8));
        assertNotNull(document);
        return TranslatedCaptionStylePolicy.visiblePens(document);
    }

    @Test public void preservesVisibleAuthoredStylesIncludingFadesAndDefaultWhite() {
        assertEquals(Set.of(1, 2, 3), visible(
                "<pen id=\"1\" fc=\"#FF6600\" fo=\"128\" sz=\"75\" b=\"1\"/>"
                + "<pen id=\"2\" fc=\"#123456\" fo=\"1\" i=\"1\"/>"
                + "<pen id=\"3\" u=\"1\"/>"));
    }

    @Test public void keepsBlackTextOnlyWithAnExplicitOpaqueNonblackBackground() {
        assertEquals(Set.of(2), visible(
                "<pen id=\"1\" fc=\"#000000\" bo=\"0\"/>"
                + "<pen id=\"2\" fc=\"#000000\" bc=\"#FFFFFF\" bo=\"255\"/>"
                + "<pen id=\"3\" fc=\"#000000\" bc=\"#FFFFFF\" bo=\"0\"/>"));
    }

    @Test public void rejectsTransparentMalformedAndInheritedPens() {
        assertEquals(Set.of(), visible(
                "<pen id=\"1\" fc=\"#FFFFFF\" fo=\"0\"/>"
                + "<pen id=\"2\" fc=\"not-a-color\"/>"
                + "<pen id=\"3\" fo=\"256\"/>"
                + "<pen id=\"4\" fo=\"oops\"/>"
                + "<pen id=\"5\" p=\"1\"/>"
                + "<pen id=\"-1\"/>"));
    }

    @Test public void rejectsDuplicateIdsInsteadOfDependingOnDeclarationOrder() {
        assertEquals(Set.of(2), visible(
                "<pen id=\"1\"/><pen id=\"1\" fo=\"0\"/><pen id=\"2\"/>"));
    }

    @Test public void rejectsForegroundMatchingAnOpaqueBackground() {
        assertEquals(Set.of(), visible(
                "<pen id=\"1\" fc=\"#FF0000\" bc=\"#FF0000\" bo=\"255\"/>"));
    }

    @Test public void acceptsZeroFontSizeAsSrv3MinimumAndRejectsNegativeOrMalformed() {
        // ytt.ytt: sz=0 is a real 75%, the smallest size the format can express.
        assertEquals(Set.of(1), visible(
                "<pen id=\"1\" sz=\"0\"/><pen id=\"2\" sz=\"-5\"/><pen id=\"3\" sz=\"oops\"/>"));
    }


    @Test public void unsupportedDocumentsHaveNoPreservedPens() {
        assertEquals(Set.of(), TranslatedCaptionStylePolicy.visiblePens(null));
    }

    @Test
    public void resolvesReadabilityWhileRetainingParentLinksForOtherMetadata() {
        assertEquals(
                Set.of(1, 2),
                visible(
                        "<pen id='1' fc='#FF6600'/><pen id='2' p='1'/>"
                                + "<pen id='3' p='1' fo='0'/><pen id='4' p='9'/>"
                                + "<pen id='5' p='6'/><pen id='6' p='5'/>"));
    }
}
