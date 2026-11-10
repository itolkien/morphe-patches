package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;
import org.w3c.dom.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class CaptionXmlWriterTest {
    private Document read(String text) {
        Document document = CaptionXml.read(text.getBytes(StandardCharsets.UTF_8));
        assertNotNull(document);
        return document;
    }

    @Test
    public void preservesUnicodeMixedContentAndAttributeWhitespace() throws Exception {
        var document =
                read(
                        "<?meta value?><timedtext format='3'"
                            + " extra='A&amp;B&#13;&#10;&#9;&quot;🧑‍💻'><head/><body><p>始<![CDATA[🧑‍💻"
                            + " &#55358;]]><!--comment--><?cue extra?><s"
                            + " p='1'>A&amp;B&lt;C&gt;&#13;終</s>終</p></body></timedtext>");
        var before = document.cloneNode(true);
        byte[] bytes = CaptionXml.write(document);
        assertTrue(before.isEqualNode(document));
        var decoded = CaptionXml.read(bytes);
        assertNotNull(decoded);
        assertTrue(document.isEqualNode(decoded));
    }

    @Test
    public void rejectsUnpairedSurrogatesInsteadOfReplacingText() {
        var document = read("<timedtext format='3'><body/></timedtext>");
        document.getElementsByTagName("body").item(0).setTextContent("A\uD800B");
        assertThrows(IOException.class, () -> CaptionXml.write(document));
    }

    @Test
    public void retainsTheByteBudgetForMultibyteText() {
        var document = read("<timedtext format='3'><body/></timedtext>");
        document.getElementsByTagName("body")
                .item(0)
                .setTextContent("字".repeat(CaptionXml.MAX_BYTES / 2));
        assertThrows(IOException.class, () -> CaptionXml.write(document));
    }

    @Test
    public void serializesDeepTreesWithoutUsingTheCallStack() throws Exception {
        var document = read("<timedtext format='3'><body/></timedtext>");
        var node = document.getElementsByTagName("body").item(0);
        for (int i = 0; i < 4000; i++) {
            var child = document.createElement("unknown");
            node.appendChild(child);
            node = child;
        }
        node.setTextContent("終");
        assertNotNull(CaptionXml.read(CaptionXml.write(document)));
    }
}
