package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Exercises the configured parser path, not just the pen classification helper. */
public class TranslatedCaptionStylesIntegrationTest {
    private static final int DEFAULT_PEN = Integer.MIN_VALUE;
    private static final String URL =
            "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&tlang=tr";
    private static final byte[] XML =
            ("<timedtext format='3'><head><pen id='1' fc='#FF6600' fo='128' sz='75'/><pen id='2'"
                            + " fo='0'/></head><body><p t='0' d='1000' p='1'>Synthetic"
                            + " caption</p></body></timedtext>")
                    .getBytes(StandardCharsets.UTF_8);

    private record Track(String vss, String url, boolean broken)
            implements AutoTranslateCaptionsPatch.CaptionTrackInterface {
        public String patch_getLanguageCode() {
            return "tr";
        }

        public String patch_getVssId() {
            return vss;
        }

        public String patch_getUrl() {
            if (broken) throw new NoSuchMethodError("native URL getter unavailable");
            return url;
        }

        public AutoTranslateCaptionsPatch.CaptionTrackInterface patch_copyTranslation(
                String language, String url, String vss, CharSequence label) {
            throw new UnsupportedOperationException();
        }
    }

    private void select(String vss, String url, boolean broken) {
        Track track = new Track(vss, url, broken);
        TranslatedCaptionPensPatch.setParsedRequest(
                (TranslatedCaptionPensPatch.CaptionRequestInterface) () -> track);
    }

    private byte[] prepare(byte[] xml, boolean enabled) throws IOException {
        try (var replay =
                TranslatedCaptionPensPatch.repairInput(new ByteArrayInputStream(xml), enabled)) {
            return replay.readAllBytes();
        }
    }

    @After
    public void clearRequest() {
        TranslatedCaptionPensPatch.setParsedRequest(null);
    }

    @Test
    public void unmappedTranslatedStylesKeepRepairWhileOriginalStylesRemainAuthored()
            throws Exception {
        byte[] input =
                ("<timedtext format='3'><head><pen id='1' fc='#FFFFFF' fo='0'/><pen id='2'"
                     + " fc='#000000' bo='0'/><pen id='3' fc='#123456' bc='#123456' bo='255'/><pen"
                     + " id='4' sz='0'/></head><body><p t='125' d='750'><s p='1' t='0'>Hidden</s><s"
                     + " p='2' t='100'>Black</s><s p='3' t='200'>Covered</s><s p='4' t='300'>Zero"
                     + " size</s></p></body></timedtext>")
                        .getBytes(StandardCharsets.UTF_8);
        select("t.en.tr", URL, false);
        assertArrayEquals(input, prepare(input, true));
        for (int id = 1; id <= 3; id++)
            assertEquals(
                    "unmapped translated pen " + id,
                    DEFAULT_PEN,
                    TranslatedCaptionPensPatch.getPenId(id));
        // sz=0 is SRV3's minimum authored size (75%), not an invisible pen.
        assertEquals(4, TranslatedCaptionPensPatch.getPenId(4));
        select(".en", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en", false);
        try (var replay =
                TranslatedCaptionPensPatch.repairInput(
                        new ByteArrayInputStream(input), true, true)) {
            assertTrue(
                    CaptionXml.read(input)
                            .getElementsByTagName("body")
                            .item(0)
                            .isEqualNode(
                                    CaptionXml.read(replay.readAllBytes())
                                            .getElementsByTagName("body")
                                            .item(0)));
        }
        for (int id = 1; id <= 4; id++)
            assertEquals("authored source pen " + id, id, TranslatedCaptionPensPatch.getPenId(id));
        assertEquals(99, TranslatedCaptionPensPatch.getPenId(99));
    }

    @Test
    public void inheritedHiddenStyleKeepsEventsAndSeparatesSourceFromUnmappedTranslation()
            throws Exception {
        byte[] input =
                ("<timedtext format='3'><head><pen id='1' fo='0'/><pen id='2' p='1'"
                     + " i='1'/></head><body><p t='125' d='750' p='2'><s t='50'>Hidden"
                     + " phase</s></p></body></timedtext>")
                        .getBytes(StandardCharsets.UTF_8);
        select("t.en.tr", URL, false);
        var output = CaptionXml.read(prepare(input, true));
        assertEquals(DEFAULT_PEN, TranslatedCaptionPensPatch.getPenId(2));
        select(".en", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en", false);
        try (var replay =
                TranslatedCaptionPensPatch.repairInput(
                        new ByteArrayInputStream(input), true, true)) {
            var source = CaptionXml.read(replay.readAllBytes());
            assertEquals(
                    "0",
                    ((org.w3c.dom.Element) source.getElementsByTagName("pen").item(1))
                            .getAttribute("fo"));
            assertEquals(2, TranslatedCaptionPensPatch.getPenId(2));
        }
        assertTrue(
                CaptionXml.read(input)
                        .getElementsByTagName("body")
                        .item(0)
                        .isEqualNode(output.getElementsByTagName("body").item(0)));
        assertEquals(
                "0",
                ((org.w3c.dom.Element) output.getElementsByTagName("pen").item(1))
                        .getAttribute("fo"));
    }

    @Test
    public void nativeSourceExpandsInheritedPensAndWindowsWithoutTranslationOption()
            throws Exception {
        byte[] input =
                ("<timedtext format='3'><head><pen id='1' fc='#123456'/><pen id='2' p='1'/><ws"
                     + " id='1' mh='1'/><ws id='2' ws='1'/><wp id='1' ah='20'/><wp id='2'"
                     + " wp='1'/></head><body><p t='30' d='500' p='2' wp='2'"
                     + " ws='2'>Source</p></body></timedtext>")
                        .getBytes(StandardCharsets.UTF_8);
        select(".en", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en", false);
        byte[] output;
        try (var replay =
                TranslatedCaptionPensPatch.repairInput(
                        new ByteArrayInputStream(input), false, true)) {
            output = replay.readAllBytes();
        }
        var parsed = CaptionXml.read(output);
        assertEquals(
                "#123456",
                ((org.w3c.dom.Element) parsed.getElementsByTagName("pen").item(1))
                        .getAttribute("fc"));
        assertEquals(
                "1",
                ((org.w3c.dom.Element) parsed.getElementsByTagName("ws").item(1))
                        .getAttribute("mh"));
        assertEquals(
                "20",
                ((org.w3c.dom.Element) parsed.getElementsByTagName("wp").item(1))
                        .getAttribute("ah"));
        assertEquals(2, TranslatedCaptionPensPatch.getPenId(2));
    }

    @Test
    public void failedStyleExpansionKeepsNativeInputAndClearsThePreviousWhitelist()
            throws Exception {
        select("t.en.tr", URL, false);
        prepare(XML, true);
        assertEquals(1, TranslatedCaptionPensPatch.getPenId(1));
        StringBuilder pens =
                new StringBuilder("<pen id='0' ec='").append("x".repeat(5000)).append("'/>");
        for (int id = 1; id <= 1000; id++) pens.append("<pen id='").append(id).append("' p='0'/>");
        byte[] input =
                ("<timedtext format='3'><head>" + pens + "</head><body/></timedtext>")
                        .getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(input, prepare(input, true));
        assertEquals(DEFAULT_PEN, TranslatedCaptionPensPatch.getPenId(1));
    }

    @Test
    public void inheritedUploadedStylesReachNativeParserWithoutChangingEvents() throws Exception {
        byte[] input =
                ("<timedtext format='3'><head><pen id='2' p='1' i='1'/>"
                                + "<pen id='1' fc='#FF6600' fo='128'/></head><body>"
                                + "<p t='125' d='750' w='4' p='2'><s t='50'>Translated text</s></p>"
                                + "</body></timedtext>")
                        .getBytes(StandardCharsets.UTF_8);
        select("t.en.tr", URL, false);
        assertArrayEquals(input, prepare(input, false));
        assertEquals(DEFAULT_PEN, TranslatedCaptionPensPatch.getPenId(2));
        byte[] output = prepare(input, true);
        assertEquals(2, TranslatedCaptionPensPatch.getPenId(2));
        var original = CaptionXml.read(input);
        var prepared = CaptionXml.read(output);
        assertTrue(
                original.getElementsByTagName("body")
                        .item(0)
                        .isEqualNode(prepared.getElementsByTagName("body").item(0)));
        var pen = (org.w3c.dom.Element) prepared.getElementsByTagName("pen").item(0);
        assertEquals("#FF6600", pen.getAttribute("fc"));
        assertEquals("128", pen.getAttribute("fo"));
        assertEquals("1", pen.getAttribute("i"));
    }

    @Test
    public void enabledUploadedTranslationPreservesVisiblePensAndEveryInputByte() throws Exception {
        select("t.en.tr", URL, false);
        assertArrayEquals(XML, prepare(XML, true));
        assertEquals(1, TranslatedCaptionPensPatch.getPenId(1));
        assertEquals(DEFAULT_PEN, TranslatedCaptionPensPatch.getPenId(2));
        assertEquals(DEFAULT_PEN, TranslatedCaptionPensPatch.getPenId(999));
    }

    @Test
    public void disabledOptionAndAutomaticSourceKeepExistingRepair() throws Exception {
        select("t.en.tr", URL, false);
        prepare(XML, false);
        assertEquals(DEFAULT_PEN, TranslatedCaptionPensPatch.getPenId(1));
        select("ta.en.tr", URL + "&kind=asr", false);
        prepare(XML, true);
        assertEquals(DEFAULT_PEN, TranslatedCaptionPensPatch.getPenId(1));
    }

    @Test
    public void unverifiedSourcesNeverAcquireTheUploadedStyleWhitelist() throws Exception {
        select("t.en.tr", URL, true);
        prepare(XML, true);
        assertEquals(DEFAULT_PEN, TranslatedCaptionPensPatch.getPenId(1));
        for (String url :
                new String[] {
                    null,
                    "https://example.org/api/timedtext?lang=en&tlang=tr",
                    URL + "&kind=asr",
                    URL.replace("tlang=tr", "tlang=de"),
                    URL + "&tlang=tr"
                }) {
            select("t.en.tr", url, false);
            prepare(XML, true);
            assertEquals(url, DEFAULT_PEN, TranslatedCaptionPensPatch.getPenId(1));
        }
    }

    @Test
    public void malformedOversizedAndFailedInputsCannotReusePriorStyles() throws Exception {
        select("t.en.tr", URL, false);
        prepare(XML, true);
        assertEquals(1, TranslatedCaptionPensPatch.getPenId(1));
        byte[] invalid = "<broken".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(invalid, prepare(invalid, true));
        assertEquals(DEFAULT_PEN, TranslatedCaptionPensPatch.getPenId(1));
        prepare(XML, true);
        byte[] oversized = new byte[CaptionXml.MAX_BYTES + 1];
        assertArrayEquals(oversized, prepare(oversized, true));
        assertEquals(DEFAULT_PEN, TranslatedCaptionPensPatch.getPenId(1));
        prepare(XML, true);
        assertThrows(
                IOException.class,
                () ->
                        TranslatedCaptionPensPatch.repairInput(
                                new java.io.InputStream() {
                                    @Override
                                    public int read() throws IOException {
                                        throw new IOException("read failed");
                                    }
                                },
                                true));
        assertEquals(DEFAULT_PEN, TranslatedCaptionPensPatch.getPenId(1));
    }

    @Test
    public void changingRequestsRestoresOriginalStylesAndClearsTranslatedStyles() throws Exception {
        select("t.en.tr", URL, false);
        prepare(XML, true);
        assertEquals(1, TranslatedCaptionPensPatch.getPenId(1));
        select(".en", URL.replace("&tlang=tr", ""), false);
        assertEquals(2, TranslatedCaptionPensPatch.getPenId(2));
        select("t.en.tr", URL, false);
        assertEquals(DEFAULT_PEN, TranslatedCaptionPensPatch.getPenId(1));
    }
}
