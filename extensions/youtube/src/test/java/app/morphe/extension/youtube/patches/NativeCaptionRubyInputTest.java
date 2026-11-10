package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;

public class NativeCaptionRubyInputTest {
    private byte[] xml(String extraPens, String body) {
        return ("<timedtext format='3'><head><pen id='1' rb='1' fc='#123456' fs='4'/>"
                        + "<pen id='2' rb='2'/><pen id='3' rb='4'/>"
                        + extraPens
                        + "</head><body>"
                        + body
                        + "</body></timedtext>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private String group() {
        return "<s p='1' t='500'>漢字</s><s p='2' t='700'>(</s>"
                + "<s p='3' t='900'>かんじ</s><s p='2' t='1100'>)</s>";
    }

    @Test
    public void baseInheritsParagraphPenWhenSegmentPenIsMissingOrUnresolved() throws Exception {
        for (String pen : new String[] {"", " p='999'", " p='-1'", " p='2147483648'"}) {
            byte[] bytes = xml("", "<p p='1' t='1000' d='2000'>"
                    + group().replace(" p='1'", pen) + "</p>");
            var source = CaptionXml.read(bytes);
            var before = source.cloneNode(true);
            var prepared = NativeCaptionRubyInput.prepare(bytes, source);
            Element paragraph = (Element) prepared.document().getElementsByTagName("p").item(0);
            assertEquals(1, CaptionXml.children(paragraph).size());
            assertEquals("1000", paragraph.getAttribute("t"));
            assertEquals("漢字(かんじ)", paragraph.getTextContent());
            Element synthetic = (Element) prepared.document().getElementsByTagName("pen").item(3);
            assertEquals("#123456", synthetic.getAttribute("fc"));
            assertEquals("2,3,6,4", synthetic.getAttribute("morphe-ruby"));
            assertTrue(before.isEqualNode(source));
        }
    }

    @Test
    public void annotationInheritsParagraphPenAndItsParentRole() throws Exception {
        for (String role : new String[] {"3", "4"}) {
            byte[] bytes = xml("<pen id='4' rb='" + role + "'/><pen id='5' p='4'/>",
                    "<p p='5' t='1000'>" + group().replace(" p='3'", "") + "</p>");
            var prepared = NativeCaptionRubyInput.prepare(bytes, CaptionXml.read(bytes));
            Element synthetic = (Element) prepared.document().getElementsByTagName("pen").item(5);
            assertEquals("2,3,6," + role, synthetic.getAttribute("morphe-ruby"));
        }
    }

    @Test
    public void groupsRubyAtParagraphTimeWithoutMutatingTheCapturedDocument() throws Exception {
        byte[] bytes = xml("", "<p t='1000' d='2000' w='7'>" + group() + "</p>");
        var document = CaptionXml.read(bytes);
        var before = document.cloneNode(true);
        var prepared = NativeCaptionRubyInput.prepare(bytes, document);
        assertTrue(before.isEqualNode(document));
        assertNotSame(bytes, prepared.bytes());
        Element p = (Element) prepared.document().getElementsByTagName("p").item(0);
        assertEquals("1000", p.getAttribute("t"));
        assertEquals("2000", p.getAttribute("d"));
        assertEquals("7", p.getAttribute("w"));
        var segments = CaptionXml.children(p);
        assertEquals(1, segments.size());
        Element segment = segments.get(0);
        assertFalse(segment.hasAttribute("t"));
        assertEquals("漢字(かんじ)", segment.getTextContent());
        Element pen = (Element) prepared.document().getElementsByTagName("pen").item(3);
        assertEquals(pen.getAttribute("id"), segment.getAttribute("p"));
        assertEquals("#123456", pen.getAttribute("fc"));
        assertEquals("4", pen.getAttribute("fs"));
        assertEquals("2,3,6,4", pen.getAttribute("morphe-ruby"));
    }

    @Test
    public void leavesOrdinarySegmentTimingAndInvalidGroupsUntouched() throws Exception {
        for (String body :
                new String[] {
                    "<p t='1000'><s p='3' t='100'>Text</s></p>",
                    "<p t='1000'><s p='1'>Base</s><s p='2'>(</s></p>",
                    "<p t='1000'><s p='1'>Base</s> "
                            + "<s p='2'>(</s><s p='3'>read</s><s p='2'>)</s></p>"
                }) {
            byte[] bytes = xml("", body);
            var document = CaptionXml.read(bytes);
            var prepared = NativeCaptionRubyInput.prepare(bytes, document);
            assertSame(bytes, prepared.bytes());
            assertSame(document, prepared.document());
        }
    }

    @Test
    public void syntheticPenDoesNotCapturePreviouslyUndefinedReferences() throws Exception {
        byte[] bytes = xml("", "<p p='4'><s p='5'>Plain</s>" + group() + "</p>");
        var prepared = NativeCaptionRubyInput.prepare(bytes, CaptionXml.read(bytes));
        Element pen = (Element) prepared.document().getElementsByTagName("pen").item(3);
        assertNotNull(pen);
        assertEquals("6", pen.getAttribute("id"));
    }

    @Test
    public void inheritsRubyRolesAcrossUnspecifiedMarkersAndNormalizesExtendedRoles()
            throws Exception {
        byte[] bytes =
                xml(
                        "<pen id='4' p='1' rb='10'/><pen id='5' p='3' rb='13'/>",
                        "<p t='10'>"
                                + group().replace("p='1'", "p='4'").replace("p='3'", "p='5'")
                                + "</p>");
        var prepared = NativeCaptionRubyInput.prepare(bytes, CaptionXml.read(bytes));
        Element pen = (Element) prepared.document().getElementsByTagName("pen").item(5);
        assertEquals("2,3,6,12", pen.getAttribute("morphe-ruby"));
        assertEquals("#123456", pen.getAttribute("fc"));
    }

    @Test
    public void computesOffsetsAfterTheNativeEscapedBreakTransformation() throws Exception {
        byte[] bytes = xml("", "<p t='10'>" + group().replace("漢字", "漢&lt;br/&gt;字") + "</p>");
        var prepared = NativeCaptionRubyInput.prepare(bytes, CaptionXml.read(bytes));
        Element pen = (Element) prepared.document().getElementsByTagName("pen").item(3);
        assertEquals("3,4,7,4", pen.getAttribute("morphe-ruby"));
        assertEquals(
                "漢\n字(かんじ)",
                prepared.document().getElementsByTagName("s").item(0).getTextContent());
    }

    @Test
    public void boundedSerializationFailureLeavesInputDocumentIntact() throws Exception {
        byte[] bytes =
                xml(
                        "<pen id='4' p='1' ec='" + "x".repeat(5000) + "'/>",
                        "<p t='10'>" + group().replace("p='1'", "p='4'").repeat(1000) + "</p>");
        var document = CaptionXml.read(bytes);
        assertNotNull(document);
        var original = document.cloneNode(true);
        assertThrows(
                java.io.IOException.class, () -> NativeCaptionRubyInput.prepare(bytes, document));
        assertTrue(original.isEqualNode(document));
    }

    @Test
    public void resolvesStandaloneEmphasisWithoutChangingItsSegmentTiming() throws Exception {
        byte[] bytes =
                xml(
                        "<pen id='4' rb='13'/><pen id='5' p='4' rb='10'/>",
                        "<p t='1000'><s p='5' t='500'>強調</s></p>");
        var prepared = NativeCaptionRubyInput.prepare(bytes, CaptionXml.read(bytes));
        Element pen = (Element) prepared.document().getElementsByTagName("pen").item(4);
        assertEquals("12", pen.getAttribute("morphe-emphasis"));
        Element segment = (Element) prepared.document().getElementsByTagName("s").item(0);
        assertEquals("500", segment.getAttribute("t"));
        assertEquals("強調", segment.getTextContent());
    }

    @Test
    public void doesNotRequireCloningADocumentNodeUnsupportedByAndroid() throws Exception {
        byte[] bytes = xml("", "<p t='10'>" + group() + "</p>");
        var document = CaptionXml.read(bytes);
        var androidDocument =
                (org.w3c.dom.Document)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {org.w3c.dom.Document.class},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals("cloneNode"))
                                        throw new org.w3c.dom.DOMException(
                                                org.w3c.dom.DOMException.NOT_SUPPORTED_ERR,
                                                "Cannot copy node of type 9");
                                    return method.invoke(document, arguments);
                                });
        var prepared = NativeCaptionRubyInput.prepare(bytes, androidDocument);
        assertNotSame(bytes, prepared.bytes());
    }

    @Test
    public void baseRoleGroupsTheNextFourRunsEvenWithDefaultFallbackRoles() throws Exception {
        byte[] bytes =
                xml(
                        "<pen id='4'/>",
                        "<p t='10'>"
                                + group().replace("p='2'", "p='4'").replace("p='3'", "p='4'")
                                + "</p>");
        var prepared = NativeCaptionRubyInput.prepare(bytes, CaptionXml.read(bytes));
        assertEquals(1, prepared.document().getElementsByTagName("s").getLength());
        Element pen = (Element) prepared.document().getElementsByTagName("pen").item(4);
        assertEquals("2,3,6,0", pen.getAttribute("morphe-ruby"));
    }

    @Test
    public void insertsSyntheticPensAfterAuthoredIdsInIncreasingOrder() throws Exception {
        byte[] bytes =
                ("<timedtext format='3'><head><pen id='1' rb='1'/><pen id='2' rb='2'/>"
                                + "<pen id='4' rb='4'/><pen id='10'/></head><body><p t='10'>"
                                + group().replace("p='3'", "p='4'")
                                + "</p></body></timedtext>")
                        .getBytes(StandardCharsets.UTF_8);
        var prepared = NativeCaptionRubyInput.prepare(bytes, CaptionXml.read(bytes));
        var pens = prepared.document().getElementsByTagName("pen");
        assertEquals(5, pens.getLength());
        for (int i = 0; i < pens.getLength(); i++)
            assertEquals(
                    Integer.toString(i == 0 ? 1 : i == 4 ? 10 : i + 1),
                    ((Element) pens.item(i)).getAttribute("id"));
    }

    @Test
    public void serializationRetainsSupplementaryTextAndLiteralReferenceText() throws Exception {
        String text = "A 🧑‍💻字、 &amp;#55358;";
        byte[] bytes = xml("<pen id='4' rb='9'/>", "<p t='10'><s p='4'>" + text + "</s></p>");
        var original = CaptionXml.read(bytes);
        var prepared = NativeCaptionRubyInput.prepare(bytes, original);
        var decoded = CaptionXml.read(prepared.bytes());
        assertNotNull(decoded);
        assertEquals(
                original.getElementsByTagName("s").item(0).getTextContent(),
                decoded.getElementsByTagName("s").item(0).getTextContent());
    }
}
