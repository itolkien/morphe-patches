package app.morphe.extension.youtube.patches;

import org.junit.Test;
import static org.junit.Assert.*;

public class CaptionProbeTextTest {
    @Test public void hardLineBreaksRemainVisibleInSingleLogLine() {
        String result = CaptionProbeText.describe("Birinci satır\nİkinci satır");
        assertTrue(result.contains("nl=1"));
        assertTrue(result.contains("cr=0"));
        assertTrue(result.contains("Birinci satır\\nİkinci satır"));
        assertFalse(result.contains("satır\nİkinci"));
    }

    @Test public void noBreaksAreReportedAsZero() {
        String result = CaptionProbeText.describe("Düz metin");
        assertTrue(result.contains("length=9"));
        assertTrue(result.contains("nl=0"));
    }

    @Test public void maximumPreviewIsBounded() {
        String result = CaptionProbeText.describe("x".repeat(300));
        assertTrue(result.contains("length=300"));
        assertTrue(result.contains("..."));
        assertFalse(result.contains("x".repeat(100)));
    }

    @Test public void unknownInputsAreHandled() {
        assertEquals("null", CaptionProbeText.describe(null));
        assertEquals("not-text", CaptionProbeText.describe(new Object()));
    }
}
