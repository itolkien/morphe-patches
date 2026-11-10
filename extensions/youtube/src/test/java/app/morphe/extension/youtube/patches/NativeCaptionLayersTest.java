package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;

public class NativeCaptionLayersTest {
    @Test
    public void insertsLayerPositionsInIncreasingIdOrder() {
        byte[] input =
                ("<timedtext format='3'><head>"
                                + "<wp id='1' ap='0' ah='0' av='10'/>"
                                + "<wp id='10' ap='2' ah='100' av='10'/>"
                                + "</head><body>"
                                + "<p t='1000' d='500' wp='1'>First</p>"
                                + "<p t='1000' d='500' wp='1'>Second</p>"
                                + "</body></timedtext>")
                        .getBytes(StandardCharsets.UTF_8);
        var document = CaptionXml.read(input);
        assertTrue(NativeCaptionLayers.needed(document));
        assertTrue(NativeCaptionLayers.normalize(document));
        var positions = document.getElementsByTagName("wp");
        assertEquals(3, positions.getLength());
        assertEquals("1", ((Element) positions.item(0)).getAttribute("id"));
        assertEquals("2", ((Element) positions.item(1)).getAttribute("id"));
        assertEquals("10", ((Element) positions.item(2)).getAttribute("id"));
        var paragraphs = document.getElementsByTagName("p");
        assertEquals("1", ((Element) paragraphs.item(0)).getAttribute("wp"));
        assertEquals("2", ((Element) paragraphs.item(1)).getAttribute("wp"));
    }
}
