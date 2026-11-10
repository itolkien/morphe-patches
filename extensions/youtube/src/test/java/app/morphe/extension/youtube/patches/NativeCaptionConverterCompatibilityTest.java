package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.HashSet;

/** Exercises the native preparation boundary with pinned converter outputs. */
@RunWith(Parameterized.class)
public class NativeCaptionConverterCompatibilityTest {
    @Parameterized.Parameters(name = "{0}")
    public static List<Object[]> fixtures() {
        var result = new ArrayList<Object[]>();
        for (String name : List.of("Alignment", "BoldItalicUnderline", "Chroma", "Colors",
                "Fade", "FaultTolerance", "Fonts", "HighTimestamp", "Karaoke", "Move",
                "NoDefaultScale", "Offset", "Ruby", "Shadows", "Shake", "Simultaneous",
                "SimultaneousReverse", "TextDirection", "Transform"))
            result.add(new Object[] {"Ass/Files/" + name + ".ytt"});
        for (String name : List.of("Alignment", "BoldItalicUnderline", "Colors", "Fonts",
                "Positioning", "Ruby", "Shadows", "StyleDerivation", "TextDirection", "Visibility"))
            result.add(new Object[] {"Ttml/Files/" + name + ".ytt"});
        return result;
    }

    private final String fixture;

    public NativeCaptionConverterCompatibilityTest(String fixture) {
        this.fixture = fixture;
    }

    @Test
    public void preservesAuthoredEventsAndDefinitionsThroughNativePreparation() throws Exception {
        byte[] input;
        try (var stream = getClass().getResourceAsStream("/ytsubconverter/" + fixture)) {
            assertNotNull("Missing converter fixture: " + fixture, stream);
            input = stream.readAllBytes();
        }
        Document source = CaptionXml.read(input);
        assertNotNull(source);
        var original = source.cloneNode(true);
        var ruby = NativeCaptionRubyInput.prepare(input, source);
        var prepared = NativeCaptionSourceInput.prepare(ruby.bytes(), ruby.document());
        assertTrue("Preparation mutated captured source", original.isEqualNode(source));
        Document output = prepared.document();
        assertNotNull(CaptionXml.read(prepared.bytes()));
        assertTrue(output.getDocumentElement().isEqualNode(
                CaptionXml.read(prepared.bytes()).getDocumentElement()));

        var before = source.getElementsByTagName("p");
        var after = output.getElementsByTagName("p");
        var sourcePositions = definitions(source, "wp");
        var nativePositions = definitions(output, "wp");
        var nativeEvents = new HashSet<List<String>>();
        assertEquals("Lost or duplicated effect events", before.getLength(), after.getLength());
        for (int i = 0, count = before.getLength(); i < count; i++) {
            Element a = (Element) before.item(i), b = (Element) after.item(i);
            var expected = attributes(a);
            var actual = attributes(b);
            expected.remove("wp");
            actual.remove("wp");
            assertEquals("Event attributes at " + i, expected, actual);
            var sourcePosition = new TreeMap<>(sourcePositions.get(a.getAttribute("wp")));
            var nativePosition = new TreeMap<>(nativePositions.get(b.getAttribute("wp")));
            sourcePosition.remove("id");
            nativePosition.remove("id");
            assertEquals("Effect position at " + i, sourcePosition, nativePosition);
            assertTrue("Co-timed layers share a native timeline at " + i,
                    nativeEvents.add(List.of(b.getAttribute("wp"), b.getAttribute("t"),
                            b.getAttribute("d"))));
            assertEquals("Event text at " + i, a.getTextContent(), b.getTextContent());
            assertEquals("Word offsets at " + i, offsets(a), offsets(b));
        }
        for (String kind : List.of("wp", "ws", "pen")) {
            var definitions = source.getElementsByTagName(kind);
            var actual = output.getElementsByTagName(kind);
            Map<String, Element> byId = new TreeMap<>();
            for (int i = 0, count = actual.getLength(); i < count; i++) {
                Element element = (Element) actual.item(i);
                assertNull("Duplicate " + kind + " ID", byId.put(element.getAttribute("id"), element));
            }
            for (int i = 0, count = definitions.getLength(); i < count; i++) {
                Element expected = (Element) definitions.item(i);
                Element found = byId.get(expected.getAttribute("id"));
                assertNotNull("Missing " + kind + " definition", found);
                assertEquals("Changed authored " + kind, attributes(expected), attributes(found));
            }
        }
    }

    private static Map<String, Map<String, String>> definitions(Document document, String kind) {
        Map<String, Map<String, String>> result = new TreeMap<>();
        var elements = document.getElementsByTagName(kind);
        for (int i = 0, count = elements.getLength(); i < count; i++) {
            Element element = (Element) elements.item(i);
            result.put(element.getAttribute("id"), attributes(element));
        }
        return result;
    }

    private static Map<String, String> attributes(Element element) {
        Map<String, String> result = new TreeMap<>();
        var attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            var attribute = attributes.item(i);
            result.put(attribute.getNodeName(), attribute.getNodeValue());
        }
        return result;
    }

    private static List<String> offsets(Element paragraph) {
        var result = new ArrayList<String>();
        for (Element segment : CaptionXml.children(paragraph))
            if (segment.hasAttribute("t"))
                result.add(segment.getAttribute("t") + ":" + segment.getTextContent());
        return result;
    }
}
