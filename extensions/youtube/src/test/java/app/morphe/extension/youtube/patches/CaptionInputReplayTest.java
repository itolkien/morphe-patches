package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class CaptionInputReplayTest {

    @Test
    public void preservesEveryInputByteIncludingNewlines() throws Exception {
        byte[] bytes = "<p>A\n\nB\n</p>".getBytes("UTF-8");
        CaptionInputReplay.Copy copy =
                CaptionInputReplay.copy(new ByteArrayInputStream(bytes), 1024);
        assertTrue(copy.complete);
        assertArrayEquals(bytes, copy.bytes);
        assertArrayEquals(bytes, read(copy.input));
    }

    @Test
    public void oversizedInputStillReachesParserUnchanged() throws Exception {
        byte[] bytes = new byte[8192];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        CaptionInputReplay.Copy copy = CaptionInputReplay.copy(new ByteArrayInputStream(bytes), 31);
        assertFalse(copy.complete);
        assertTrue(copy.bytes.length <= 32);
        assertArrayEquals(bytes, read(copy.input));
    }

    @Test
    public void preservesEmptyAndExactLimitInput() throws Exception {
        assertTrue(CaptionInputReplay.copy(new ByteArrayInputStream(new byte[0]), 10).complete);
        byte[] bytes = new byte[10];
        CaptionInputReplay.Copy copy = CaptionInputReplay.copy(new ByteArrayInputStream(bytes), 10);
        assertTrue(copy.complete);
        assertArrayEquals(bytes, read(copy.input));
    }

    @Test(expected = IOException.class)
    public void preservesReadFailures() throws Exception {
        CaptionInputReplay.copy(
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("test failure");
                    }
                },
                1024);
    }

    @Test
    public void completeReplayKeepsSourceOpenUntilExplicitClose() throws Exception {
        assertParserOwnedClose(1024, true);
    }

    @Test
    public void oversizedReplayKeepsSourceOpenUntilExplicitClose() throws Exception {
        assertParserOwnedClose(3, false);
    }

    @Test
    public void replayReadAndCloseFailuresStillCloseSourceExactlyOnce() throws Exception {
        int[] closeCalls = {0};
        InputStream original = new InputStream() {
            private boolean prefixRead;

            @Override
            public int read() throws IOException {
                throw new IOException("read failure");
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                if (prefixRead) throw new IOException("read failure");
                prefixRead = true;
                for (int i = 0; i < length; i++) bytes[offset + i] = 1;
                return length;
            }

            @Override
            public void close() throws IOException {
                closeCalls[0]++;
                throw new IOException("close failure");
            }
        };
        InputStream replay = CaptionInputReplay.copy(original, 3).input;
        try {
            read(replay);
            fail("Expected source read failure");
        } catch (IOException expected) {
            assertEquals("read failure", expected.getMessage());
        }
        assertEquals(0, closeCalls[0]);
        try {
            replay.close();
            fail("Expected source close failure");
        } catch (IOException expected) {
            assertEquals("close failure", expected.getMessage());
        }
        assertEquals(1, closeCalls[0]);
        replay.close();
        assertEquals(1, closeCalls[0]);
    }

    private static void assertParserOwnedClose(int limit, boolean complete) throws Exception {
        byte[] source = "caption bytes".getBytes("UTF-8");
        int[] closeCalls = {0};
        InputStream original = new ByteArrayInputStream(source) {
            @Override
            public void close() {
                closeCalls[0]++;
            }
        };
        CaptionInputReplay.Copy copy = CaptionInputReplay.copy(original, limit);
        assertEquals(complete, copy.complete);
        assertArrayEquals(source, read(copy.input));
        assertEquals("Reading through EOF must not close the parser-owned source", 0, closeCalls[0]);
        copy.input.close();
        assertEquals(1, closeCalls[0]);
        copy.input.close();
        assertEquals("Repeated close must not close the source again", 1, closeCalls[0]);
    }

    @Test
    public void sharedTrackRoutingRepairsInputAndRetainsParserOwnedClose() throws Exception {
        byte[] source =
                ("<timedtext format='3'><head><ws id='1' mh='2'/></head>"
                                + "<body><w t='0' id='1' ws='1'/>"
                                + "<p t='0' d='2000' w='1'>one</p>"
                                + "<p t='1000' d='1000' w='1' a='1'>\n</p>"
                                + "<p t='1500' d='2000' w='1' a='1'>\n</p>"
                                + "<p t='2000' d='4000' w='1'>two</p></body></timedtext>")
                        .getBytes("UTF-8");
        int[] closeCalls = {0};
        InputStream original =
                new ByteArrayInputStream(source) {
                    @Override
                    public void close() {
                        closeCalls[0]++;
                    }
                };
        Track track = new Track();
        TranslatedCaptionPensPatch.setParsedRequest(
                (TranslatedCaptionPensPatch.CaptionRequestInterface) () -> track);
        assertEquals(1, track.vssReads);
        InputStream repaired = TranslatedCaptionPensPatch.repairInput(original);
        assertEquals(2, CaptionXml.read(read(repaired)).getElementsByTagName("p").getLength());
        assertEquals(0, closeCalls[0]);
        repaired.close();
        assertEquals(1, closeCalls[0]);
        repaired.close();
        assertEquals(1, closeCalls[0]);
        // A non-caption request must clear the previous translated decoder state.
        TranslatedCaptionPensPatch.setParsedRequest(null);
        InputStream next = new ByteArrayInputStream(source);
        assertSame(next, TranslatedCaptionPensPatch.repairInput(next));
    }

    @Test
    public void failedSourceGetterClearsRepairAndPreservesTranslatedStyle() throws Exception {
        Track track = new Track();
        TranslatedCaptionPensPatch.CaptionRequestInterface request = () -> track;
        try {
            TranslatedCaptionPensPatch.setParsedRequest(request);
            track.urlUnavailable = true;
            TranslatedCaptionPensPatch.setParsedRequest(request);
            assertEquals(Integer.MIN_VALUE, TranslatedCaptionPensPatch.getPenId(7));
            InputStream input = new ByteArrayInputStream(new byte[] {1});
            assertSame(input, TranslatedCaptionPensPatch.repairInput(input));
        } finally {
            TranslatedCaptionPensPatch.setParsedRequest(null);
        }
    }

    private static final class Track implements AutoTranslateCaptionsPatch.CaptionTrackInterface {
        int vssReads;
        boolean urlUnavailable;
        @Override
        public String patch_getLanguageCode() {
            return "de";
        }

        @Override
        public String patch_getVssId() {
            vssReads++;
            return "ta.en.de";
        }

        @Override
        public String patch_getUrl() {
            if (urlUnavailable) throw new NoSuchMethodError("URL getter unavailable");
            return "https://www.youtube.com/api/timedtext?kind=asr&lang=en&tlang=de";
        }

        @Override
        public AutoTranslateCaptionsPatch.CaptionTrackInterface patch_copyTranslation(
                String languageCode, String url, String vssId, CharSequence label) {
            throw new UnsupportedOperationException();
        }
    }

    private static byte[] read(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[113];
        int n;
        while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        return output.toByteArray();
    }
}
