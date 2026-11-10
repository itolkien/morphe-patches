package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class NativeCaptionPenStylesTest {
    private byte[] xml(String pens) {
        return ("<timedtext format='3'><head>"
                        + pens
                        + "</head><body><p t='125' d='750' w='4' p='2'><s"
                        + " t='50'>Text</s></p></body></timedtext>")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void inheritedHorizontalGroupingKeepsExplicitChildOverride() throws Exception {
        byte[] input = xml("<pen id='0' hg='1'/><pen id='1' p='0'/><pen id='2' p='1' hg='0'/>");
        var result = CaptionXml.read(NativeCaptionPenStyles.prepare(input, CaptionXml.read(input)));
        assertEquals(
                "1",
                ((org.w3c.dom.Element) result.getElementsByTagName("pen").item(1))
                        .getAttribute("hg"));
        assertEquals(
                "0",
                ((org.w3c.dom.Element) result.getElementsByTagName("pen").item(2))
                        .getAttribute("hg"));
    }

    @Test
    public void defaultFontMarkerDoesNotEraseInheritedFont() throws Exception {
        for (String marker : new String[] {"0", "00", "+0", "-0", "bad"}) {
            byte[] input = xml("<pen id='1' fs='5'/><pen id='2' p='1' fs='" + marker + "'/>");
            var doc = CaptionXml.read(input);
            var decoded = CaptionXml.read(NativeCaptionPenStyles.prepare(input, doc));
            assertEquals(
                    marker,
                    "5",
                    ((org.w3c.dom.Element) decoded.getElementsByTagName("pen").item(1))
                            .getAttribute("fs"));
        }
    }

    @Test
    public void expandedMetadataCannotExceedTheNativeInputBudget() throws Exception {
        StringBuilder pens =
                new StringBuilder("<pen id='0' ec='").append("x".repeat(5000)).append("'/>");
        for (int i = 1; i <= 1000; i++) pens.append("<pen id='").append(i).append("' p='0'/>");
        byte[] input = xml(pens.toString());
        assertTrue(input.length < CaptionXml.MAX_BYTES);
        assertThrows(
                java.io.IOException.class,
                () -> NativeCaptionPenStyles.prepare(input, CaptionXml.read(input)));
    }

    @Test
    public void deepForwardInheritanceDoesNotUseTheCallStack() throws Exception {
        StringBuilder pens = new StringBuilder();
        for (int i = 1500; i > 0; i--)
            pens.append("<pen id='").append(i).append("' p='").append(i - 1).append("'/>");
        pens.append("<pen id='0' fc='#123456'/>");
        byte[] input = xml(pens.toString());
        var doc = CaptionXml.read(input);
        var result = CaptionXml.read(NativeCaptionPenStyles.prepare(input, doc));
        assertEquals(
                "#123456",
                ((org.w3c.dom.Element) result.getElementsByTagName("pen").item(0))
                        .getAttribute("fc"));
    }

    @Test
    public void forwardParentStylesBecomeExplicitWithoutChangingBody() throws Exception {
        byte[] input = xml("<pen id='2' p='1' i='1'/><pen id='1' fc='#FF6600' fo='128' b='1'/>");
        var doc = CaptionXml.read(input);
        var before = doc.getElementsByTagName("body").item(0).cloneNode(true);
        byte[] prepared = NativeCaptionPenStyles.prepare(input, doc);
        var decoded = CaptionXml.read(prepared);
        var child = (org.w3c.dom.Element) decoded.getElementsByTagName("pen").item(0);
        assertEquals("#FF6600", child.getAttribute("fc"));
        assertEquals("128", child.getAttribute("fo"));
        assertEquals("1", child.getAttribute("b"));
        assertEquals("1", child.getAttribute("i"));
        assertEquals("1", child.getAttribute("p"));
        assertTrue(before.isEqualNode(decoded.getElementsByTagName("body").item(0)));
    }

    @Test
    public void childOverridesParentIncludingTransparencyAndFalseFlags() throws Exception {
        byte[] input = xml("<pen id='1' fo='0' b='1' sz='75'/><pen id='2' p='1' fo='255' b='0'/>");
        var doc = CaptionXml.read(input);
        var result = CaptionXml.read(NativeCaptionPenStyles.prepare(input, doc));
        var child = (org.w3c.dom.Element) result.getElementsByTagName("pen").item(1);
        assertEquals("255", child.getAttribute("fo"));
        assertEquals("0", child.getAttribute("b"));
        assertEquals("75", child.getAttribute("sz"));
    }

    @Test
    public void invalidParentsRemainUnmodified() throws Exception {
        for (String pens :
                new String[] {
                    "<pen id='2' p='99'/>",
                    "<pen id='1' p='2'/><pen id='2' p='1'/>",
                    "<pen id='1'/><pen id='1' fo='0'/><pen id='2' p='1'/>"
                }) {
            byte[] input = xml(pens);
            assertSame(input, NativeCaptionPenStyles.prepare(input, CaptionXml.read(input)));
        }
    }

    @Test
    public void independentPensAreByteForByteUntouched() throws Exception {
        byte[] input = xml("<pen id='2' fc='#123456'/>");
        assertSame(input, NativeCaptionPenStyles.prepare(input, CaptionXml.read(input)));
    }

    @Test
    public void transparentInheritanceStillUsesUnreadablePenFallback() throws Exception {
        byte[] input = xml("<pen id='1' fo='0'/><pen id='2' p='1'/>");
        var doc = CaptionXml.read(input);
        NativeCaptionPenStyles.prepare(input, doc);
        assertFalse(TranslatedCaptionStylePolicy.visiblePens(doc).contains(2));
    }
}
