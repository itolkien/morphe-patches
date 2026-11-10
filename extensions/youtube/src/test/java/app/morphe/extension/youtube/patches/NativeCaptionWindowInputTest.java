package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;

public class NativeCaptionWindowInputTest {
    private static byte[] xml(String head) {
        return ("<timedtext format='3'><head>"
                        + head
                        + "</head><body><w id='5' ws='2' wp='4' t='30' d='80'/>"
                        + "<p t='40' d='60' w='5'><s t='10'>Text</s></p></body></timedtext>")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void resolvesForwardParentsWithoutChangingTimingOrUnknownAttributes() throws Exception {
        byte[] input =
                xml(
                        "<ws id='2' ws='1' ju='0' future='kept'/><ws id='1' pd='2' wfo='0'/><wp"
                            + " id='4' wp='3' rc='0'/><wp id='3' ah='25' av='75' cc='8' rc='3'/>");
        var document = CaptionXml.read(input);
        var before = document.getElementsByTagName("body").item(0).cloneNode(true);
        assertTrue(NativeCaptionWindowInput.expand(document));
        var style = (Element) document.getElementsByTagName("ws").item(0);
        assertEquals("2", style.getAttribute("pd"));
        assertEquals("0", style.getAttribute("wfo"));
        assertEquals("0", style.getAttribute("ju"));
        assertEquals("kept", style.getAttribute("future"));
        assertEquals("1", style.getAttribute("ws"));
        var position = (Element) document.getElementsByTagName("wp").item(0);
        assertEquals("0", position.getAttribute("rc"));
        assertEquals("8", position.getAttribute("cc"));
        assertEquals("25", position.getAttribute("ah"));
        assertEquals("75", position.getAttribute("av"));
        assertTrue(before.isEqualNode(document.getElementsByTagName("body").item(0)));
    }

    @Test
    public void doesNotInventInheritanceForMissingDuplicateOrCyclicParents() {
        for (String head :
                new String[] {
                    "<ws id='2' ws='9'/>",
                    "<ws id='1' ws='2'/><ws id='2' ws='1'/>",
                    "<wp id='1' cc='2'/><wp id='1' cc='4'/><wp id='2' wp='1'/>",
                    "<wp id='1' cc='2'/><wp id='01' cc='4'/><wp id='2' wp='+1'/>",
                    "<ws id='1' ju='2'/><wp id='2' wp='1'/>"
                }) {
            var document = CaptionXml.read(xml(head));
            var before = document.getDocumentElement().cloneNode(true);
            assertFalse(NativeCaptionWindowInput.expand(document));
            assertTrue(before.isEqualNode(document.getDocumentElement()));
        }
    }

    @Test
    public void expandsDeepChainsWithoutRecursion() {
        var head = new StringBuilder("<wp id='0' cc='12'/>");
        for (int i = 1; i <= 4000; i++)
            head.append("<wp id='").append(i).append("' wp='").append(i - 1).append("'/>");
        var document = CaptionXml.read(xml(head.toString()));
        assertTrue(NativeCaptionWindowInput.expand(document));
        assertEquals(
                "12",
                ((Element) document.getElementsByTagName("wp").item(4000)).getAttribute("cc"));
    }

    @Test
    public void independentDefinitionsRemainUntouched() {
        var document = CaptionXml.read(xml("<ws id='1' pd='2'/><wp id='1' cc='12'/>"));
        assertFalse(NativeCaptionWindowInput.expand(document));
    }

    @Test
    public void insertsDerivedDeclarationsInIncreasingIdOrder() {
        byte[] input =
                ("<timedtext format='3'><head><wp id='1' ah='0' av='10'/>"
                                + "<wp id='10' ah='100' av='10'/></head><body>"
                                + "<w id='5' wp='1' ah='50' av='50' t='30' d='80'/>"
                                + "<p t='40' d='60' w='5'><s t='10'>Text</s></p>"
                                + "</body></timedtext>")
                        .getBytes(StandardCharsets.UTF_8);
        var document = CaptionXml.read(input);
        assertTrue(NativeCaptionWindowInput.inline(document));
        var positions = document.getElementsByTagName("wp");
        assertEquals(3, positions.getLength());
        assertEquals("1", ((Element) positions.item(0)).getAttribute("id"));
        assertEquals("2", ((Element) positions.item(1)).getAttribute("id"));
        assertEquals("10", ((Element) positions.item(2)).getAttribute("id"));
        assertEquals("2", ((Element) document.getElementsByTagName("w").item(0)).getAttribute("wp"));
    }
}
