package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class NativeCaptionSourceInputTest {
    @Test
    public void inlineDefinitionsReserveUndefinedNumericAliasesAcrossWindowsAndParagraphs()
            throws Exception {
        for (String kind : new String[] {"wp", "ws"}) {
            String field = kind.equals("wp") ? "ah" : "ju";
            for (String reference : new String[] {"+1", "01"}) {
                byte[] input = ("<timedtext format='3'><body>"
                        + "<w id='5' " + kind + "='" + reference + "'/>"
                        + "<p t='0' d='100' " + kind + "='" + reference + "'>fallback</p>"
                        + "<p t='100' d='100' " + field + "='1'>inline</p>"
                        + "</body></timedtext>").getBytes(StandardCharsets.UTF_8);
                var original = CaptionXml.read(input);
                var before = original.cloneNode(true);
                var prepared = NativeCaptionSourceInput.prepare(input, original);
                var paragraphs = prepared.document().getElementsByTagName("p");
                assertEquals(reference, ((Element) paragraphs.item(0)).getAttribute(kind));
                assertEquals("2", ((Element) paragraphs.item(1)).getAttribute(kind));
                assertTrue(before.isEqualNode(original));
                assertSame(prepared.bytes(),
                        NativeCaptionSourceInput.prepare(prepared.bytes(), prepared.document()).bytes());
            }
        }
    }

    @Test
    public void inlineDefinitionsCannotSatisfyAnUndefinedHeaderParent() throws Exception {
        byte[] input = ("<timedtext format='3'><head><wp id='8' wp='+1'/></head>"
                + "<body><p t='0' d='100' ah='90'>inline</p></body></timedtext>")
                .getBytes(StandardCharsets.UTF_8);
        var prepared = NativeCaptionSourceInput.prepare(input, CaptionXml.read(input));
        assertEquals("2", ((Element) prepared.document().getElementsByTagName("p").item(0))
                .getAttribute("wp"));
        var positions = prepared.document().getElementsByTagName("wp");
        assertEquals(2, positions.getLength());
        assertEquals("2", ((Element) positions.item(0)).getAttribute("id"));
        assertEquals("8", ((Element) positions.item(1)).getAttribute("id"));
        assertEquals("+1", ((Element) positions.item(1)).getAttribute("wp"));
    }

    @Test
    public void numericWindowAliasesHaveTheSameLifetime() throws Exception {
        for (String declaration : new String[] {"1", "+1", "01"}) {
            byte[] input = ("<timedtext format='3'><body><w id='" + declaration
                    + "' t='0' d='100'/><p w='1' t='0' d='1000'>one</p>"
                    + "<p w='+1' t='0' d='1000'>two</p>"
                    + "<p w='01' t='0' d='1000'>three</p></body></timedtext>")
                    .getBytes(StandardCharsets.UTF_8);
            var prepared = NativeCaptionSourceInput.prepare(input, CaptionXml.read(input));
            var paragraphs = prepared.document().getElementsByTagName("p");
            for (int i = 0; i < 3; i++)
                assertEquals("100", ((Element) paragraphs.item(i)).getAttribute("d"));
        }
    }

    @Test
    public void timedRedefinitionRecognizesNumericAliases() throws Exception {
        byte[] input = ("<timedtext format='3'><body><w id='01'/>"
                + "<p w='+1' t='0' d='1000'>old</p><w id='+1' t='100' d='50'/>"
                + "<p w='1' t='100' d='1000'>new</p>"
                + "<w id='-1' t='0' d='1'/><p w='-1' t='0' d='1000'>invalid</p>"
                + "<w id='2147483648' t='0' d='1'/>"
                + "<p w='2147483648' t='0' d='1000'>overflow</p>"
                + "</body></timedtext>").getBytes(StandardCharsets.UTF_8);
        var prepared = NativeCaptionSourceInput.prepare(input, CaptionXml.read(input));
        var paragraphs = prepared.document().getElementsByTagName("p");
        assertEquals("100", ((Element) paragraphs.item(0)).getAttribute("d"));
        assertEquals("50", ((Element) paragraphs.item(1)).getAttribute("d"));
        assertEquals("1000", ((Element) paragraphs.item(2)).getAttribute("d"));
        assertEquals("1000", ((Element) paragraphs.item(3)).getAttribute("d"));
    }

    @Test
    public void nativeNumericAliasesCannotCollideWithSyntheticLayerIds() throws Exception {
        String events = "<p t='10' d='50' wp='0'>a</p><p t='10' d='50' wp='0'>b</p>";
        for (String[] extra : new String[][] {
                {"<wp id='01' ah='10'/>", ""},
                {"", "<p t='100' d='50' wp='+1'>fallback</p>"}
        }) {
            byte[] input = ("<timedtext format='3'><head><wp id='0' ah='50'/>" + extra[0]
                    + "</head><body>" + events + extra[1] + "</body></timedtext>")
                    .getBytes(StandardCharsets.UTF_8);
            var output = NativeCaptionSourceInput.prepare(input, CaptionXml.read(input));
            var second = (Element) output.document().getElementsByTagName("p").item(1);
            assertEquals("2", second.getAttribute("wp"));
        }
        byte[] ambiguous = ("<timedtext format='3'><head><wp id='1' ah='10'/>"
                + "<wp id='01' ah='50'/></head><body>" + events.replace("wp='0'", "wp='1'")
                + "</body></timedtext>").getBytes(StandardCharsets.UTF_8);
        assertSame(ambiguous,
                NativeCaptionSourceInput.prepare(ambiguous, CaptionXml.read(ambiguous)).bytes());
    }
    @Test
    public void simultaneousEffectLayersKeepIndependentNativeIdentities() throws Exception {
        byte[] input = ("<timedtext format='3'><head>"
                + "<wp id='0' ap='7' ah='50' av='95'/><wp id='1' ap='0' ah='10' av='10'/>"
                + "<ws id='1' ju='2'/><pen id='1' fo='0'/><pen id='2' fo='254'/>"
                + "</head><body><p t='10' d='50' wp='0' ws='1' p='1'>same</p>"
                + "<p t='10' d='50' wp='0' ws='1' p='2'>same</p>"
                + "<p t='60' d='50' wp='0' ws='1' p='1'>next</p>"
                + "<p t='60' d='50' wp='0' ws='1' p='2'>next</p>"
                + "</body></timedtext>").getBytes(StandardCharsets.UTF_8);
        var source = CaptionXml.read(input);
        var before = source.cloneNode(true);
        var result = NativeCaptionSourceInput.prepare(input, source);
        var paragraphs = result.document().getElementsByTagName("p");
        String first = ((Element) paragraphs.item(0)).getAttribute("wp");
        String second = ((Element) paragraphs.item(1)).getAttribute("wp");
        assertNotEquals("Native sampling selects one event per window", first, second);
        assertEquals(first, ((Element) paragraphs.item(2)).getAttribute("wp"));
        assertEquals(second, ((Element) paragraphs.item(3)).getAttribute("wp"));
        assertEquals(3, result.document().getElementsByTagName("wp").getLength());
        Element position = (Element) result.document().getElementsByTagName("wp").item(2);
        assertEquals("50", position.getAttribute("ah"));
        assertEquals("95", position.getAttribute("av"));
        assertEquals("7", position.getAttribute("ap"));
        assertEquals("0", ((Element) paragraphs.item(0)).getAttribute("wp"));
        assertTrue(before.isEqualNode(source));
        var again = NativeCaptionSourceInput.prepare(result.bytes(), result.document());
        assertSame(result.bytes(), again.bytes());
    }

    @Test
    public void layerAdaptationLeavesRollingExplicitAndUnverifiedTracksNative() throws Exception {
        String head = "<timedtext format='3'><head><wp id='0' ah='50'/></head><body>";
        String layers = "<p t='10' d='50' wp='0'>a</p><p t='10' d='50' wp='0'>b</p>";
        byte[] input = (head + layers + "</body></timedtext>").getBytes(StandardCharsets.UTF_8);
        var document = CaptionXml.read(input);
        assertSame(input, NativeCaptionSourceInput.prepare(input, document, false).bytes());
        for (String body : new String[] {
                layers + "<p t='20' d='50' wp='0' a='1'>append</p>",
                layers + "<p t='20' d='50' wp='0' a='01'>append</p>",
                layers + "<p t='20' d='50' wp='0' a='+1'>append</p>",
                layers + "<p t='20' d='50' wp='0' a='2'>append</p>",
                "<w id='7' wp='0'/>" + layers,
                layers.replace("wp='0'", "wp='9'"),
                "<p t='10' d='50' wp='0'>a</p><p t='60' d='50' wp='0'>b</p>"
        }) {
            byte[] source = (head + body + "</body></timedtext>").getBytes(StandardCharsets.UTF_8);
            assertSame(source, NativeCaptionSourceInput.prepare(source, CaptionXml.read(source)).bytes());
        }
    }
    private static byte[] xml(String head) {
        return ("<timedtext format='3'><head>"
                        + head
                        + "</head><body><p t='20' d='900' p='2'><s"
                        + " t='30'>🧑‍🔬</s></p></body></timedtext>")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void inlineWindowDefinitionsReachNativeHeadersAndAreIdempotent() throws Exception {
        byte[] input=("<timedtext format='3'><body><w id='5' ap='0' ah='20' av='30' rc='3' cc='12' mh='2' ju='1'/>"
                +"<p w='5' t='1000' d='1000'>Text</p><p t='2000' d='1000' ah='70' pd='3'>Other</p>"
                +"<p t='3000' d='1000' ah='70' pd='3'>Same</p></body></timedtext>").getBytes(StandardCharsets.UTF_8);
        var document=CaptionXml.read(input);var before=document.cloneNode(true);
        var result=NativeCaptionSourceInput.prepare(input,document);
        var w=(Element)result.document().getElementsByTagName("w").item(0);
        assertFalse(w.getAttribute("wp").isEmpty());assertFalse(w.getAttribute("ws").isEmpty());
        assertFalse(w.hasAttribute("ah"));assertEquals("0",((Element)result.document().getElementsByTagName("wp").item(0)).getAttribute("ap"));
        var a=(Element)result.document().getElementsByTagName("p").item(1);
        var b=(Element)result.document().getElementsByTagName("p").item(2);
        assertEquals(a.getAttribute("wp"),b.getAttribute("wp"));
        assertEquals(a.getAttribute("ws"),b.getAttribute("ws"));
        assertFalse(a.getAttribute("wp").isEmpty());assertFalse(a.getAttribute("ws").isEmpty());
        var twice=NativeCaptionSourceInput.prepare(result.bytes(),result.document());
        assertSame(result.bytes(),twice.bytes());assertTrue(before.isEqualNode(document));
    }

    @Test
    public void windowDurationClipsOnlyItsOwnParagraphs() throws Exception {
        byte[] input =
                ("<timedtext format='3'><body><w id='5' t='900' d='700'/>"
                                + "<p w='5' t='1000' d='2000'>One</p><w id='6'/>"
                                + "<p w='6' t='1000' d='2000'>Two</p></body></timedtext>")
                        .getBytes(StandardCharsets.UTF_8);
        var original = CaptionXml.read(input);
        var result = NativeCaptionSourceInput.prepare(input, original);
        assertEquals(
                "600",
                ((Element) result.document().getElementsByTagName("p").item(0)).getAttribute("d"));
        assertEquals(
                "2000",
                ((Element) result.document().getElementsByTagName("p").item(1)).getAttribute("d"));
        assertEquals(
                "2000", ((Element) original.getElementsByTagName("p").item(0)).getAttribute("d"));
    }

    @Test
    public void windowStartRebasesNativeWordOffsets() throws Exception {
        byte[] input =
                ("<timedtext format='3'><body><w id='5' t='1500' d='1000'/><p w='5' t='1000'"
                     + " d='2000'><s t='250'>early</s><s t='800'>later</s><s"
                     + " t='1800'>unreachable</s></p></body></timedtext>")
                        .getBytes(StandardCharsets.UTF_8);
        var result = NativeCaptionSourceInput.prepare(input, CaptionXml.read(input));
        var p = (Element) result.document().getElementsByTagName("p").item(0);
        assertEquals("1500", p.getAttribute("t"));
        assertEquals("1000", p.getAttribute("d"));
        var parts = CaptionXml.children(p);
        assertEquals(2, parts.size());
        assertEquals("0", parts.get(0).getAttribute("t"));
        assertEquals("300", parts.get(1).getAttribute("t"));
    }

    @Test
    public void timedRedefinitionEndsPreviousWindowWithoutChangingUntimedDefinitions()
            throws Exception {
        byte[] input =
                ("<timedtext format='3'><body><w id='5'/><p w='5' t='1000' d='2000'>Old</p><w"
                     + " id='5' t='1600'/><p w='5' t='1700' d='100'>New</p><w id='6' t='0'"
                     + " d='100'/><p w='6' t='500' d='100'>Expired</p></body></timedtext>")
                        .getBytes(StandardCharsets.UTF_8);
        var result = NativeCaptionSourceInput.prepare(input, CaptionXml.read(input));
        assertEquals(2, result.document().getElementsByTagName("p").getLength());
        assertEquals(
                "600",
                ((Element) result.document().getElementsByTagName("p").item(0)).getAttribute("d"));
        assertEquals(
                "100",
                ((Element) result.document().getElementsByTagName("p").item(1)).getAttribute("d"));
    }

    @Test
    public void mixedTextKeepsOrderParagraphTimingAndOriginalDocument() throws Exception {
        byte[] input =
                ("<timedtext format='3'><body><p t='1000' d='2000' a='1' p='7'>"
                                + "before<s t='400' p='2'>A</s>\n<s t='800'>B</s>after"
                                + "</p></body></timedtext>")
                        .getBytes(StandardCharsets.UTF_8);
        var document = CaptionXml.read(input);
        var before = document.cloneNode(true);
        var prepared = NativeCaptionSourceInput.prepare(input, document);
        var paragraph = (Element) prepared.document().getElementsByTagName("p").item(0);
        var parts = CaptionXml.children(paragraph);
        assertEquals(5, parts.size());
        assertEquals("beforeA\nBafter", paragraph.getTextContent());
        assertEquals("400", parts.get(1).getAttribute("t"));
        assertEquals("800", parts.get(3).getAttribute("t"));
        for (int index : new int[] {0, 2, 4}) {
            assertEquals("s", parts.get(index).getTagName());
            assertFalse(parts.get(index).hasAttribute("t"));
            assertFalse(parts.get(index).hasAttribute("p"));
        }
        assertEquals("1000", paragraph.getAttribute("t"));
        assertEquals("2000", paragraph.getAttribute("d"));
        assertEquals("1", paragraph.getAttribute("a"));
        assertTrue(before.isEqualNode(document));
        assertTrue(
                CaptionXml.read(prepared.bytes())
                        .getDocumentElement()
                        .isEqualNode(prepared.document().getDocumentElement()));
    }

    @Test
    public void sourceInheritanceReachesNativeInputWithoutDetachingUnknownMetadata()
            throws Exception {
        byte[] input =
                xml(
                        "<pen id='1' fc='#123456' future='retained'/><pen id='2' p='1'/>"
                                + "<ws id='1' mh='1' pd='2'/><ws id='2' ws='1'/>"
                                + "<wp id='1' ah='25' rc='2'/><wp id='2' wp='1'/>");
        var document = CaptionXml.read(input);
        var before = document.getDocumentElement().cloneNode(true);
        var result = NativeCaptionSourceInput.prepare(input, document);
        var parsed = CaptionXml.read(result.bytes());
        assertNotSame(document, result.document());
        assertTrue(before.isEqualNode(document.getDocumentElement()));
        assertTrue(result.document().getDocumentElement().isEqualNode(parsed.getDocumentElement()));
        var pen = (Element) parsed.getElementsByTagName("pen").item(1);
        assertEquals("#123456", pen.getAttribute("fc"));
        assertEquals("1", pen.getAttribute("p"));
        assertEquals("2", ((Element) parsed.getElementsByTagName("ws").item(1)).getAttribute("pd"));
        assertEquals(
                "25", ((Element) parsed.getElementsByTagName("wp").item(1)).getAttribute("ah"));
        assertTrue(
                document.getElementsByTagName("body")
                        .item(0)
                        .isEqualNode(parsed.getElementsByTagName("body").item(0)));
    }

    @Test
    public void failedExpansionLeavesCapturedDocumentIntact() {
        var head = new StringBuilder("<pen id='0' ec='").append("x".repeat(5000)).append("'/>");
        for (int i = 1; i <= 1000; i++) head.append("<pen id='").append(i).append("' p='0'/>");
        byte[] input = xml(head.toString());
        var document = CaptionXml.read(input);
        var before = document.getDocumentElement().cloneNode(true);
        assertThrows(IOException.class, () -> NativeCaptionSourceInput.prepare(input, document));
        assertTrue(before.isEqualNode(document.getDocumentElement()));
    }

    @Test
    public void combinedRubyAndSourcePreparationMatchesSequentialPasses() throws Exception {
        byte[] input;
        try (var stream =
                getClass().getResourceAsStream("/ytsubconverter/Ttml/Files/Ruby.ytt")) {
            assertNotNull(stream);
            input = stream.readAllBytes();
        }
        var sequentialSource = CaptionXml.read(input);
        var ruby = NativeCaptionRubyInput.prepare(input, sequentialSource);
        var sequential = NativeCaptionSourceInput.prepare(ruby.bytes(), ruby.document());
        var combined =
                NativeCaptionSourceInput.prepare(input, CaptionXml.read(input), true, true);
        assertTrue(
                CaptionXml.read(sequential.bytes())
                        .getDocumentElement()
                        .isEqualNode(
                                CaptionXml.read(combined.bytes()).getDocumentElement()));
    }

    @Test
    public void unmodifiedInputKeepsOriginalBytesAndDocument() throws Exception {
        byte[] input = xml("<pen id='2' fc='#123456'/><ws id='1' ju='0'/>");
        var document = CaptionXml.read(input);
        var result = NativeCaptionSourceInput.prepare(input, document);
        assertSame(input, result.bytes());
        assertSame(document, result.document());
    }
}
