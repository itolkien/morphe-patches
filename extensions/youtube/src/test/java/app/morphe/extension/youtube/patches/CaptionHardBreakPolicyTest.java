package app.morphe.extension.youtube.patches;

import org.junit.Test;
import static org.junit.Assert.*;

public class CaptionHardBreakPolicyTest {
    @Test public void reproduceFifteenSecondCaption() {
        String input = "uygunsuz bir zamanda gelen bir güncelleme. Belki de Edge,\n\n"
                + "varsayılan web tarayıcınız olmak için karanlık bir desenleme uyguluyordur.";
        assertEquals("uygunsuz bir zamanda gelen bir güncelleme. Belki de Edge, "
                + "varsayılan web tarayıcınız olmak için karanlık bir desenleme uyguluyordur.",
                CaptionHardBreakPolicy.normalizedPlain(input));
    }
    @Test public void reproduceTwentySecondCaption() {
        assertEquals("Ya da belki de işletim sisteminiz",
                CaptionHardBreakPolicy.normalizedPlain("Ya da belki de\n\nişletim sisteminiz"));
    }
    @Test public void leaveNormalTextAlone() {
        assertFalse(CaptionHardBreakPolicy.shouldNormalize("Bir sorun yok."));
        assertEquals("Bir sorun yok.", CaptionHardBreakPolicy.normalizedPlain("Bir sorun yok."));
    }
    @Test public void flattenAllNativeHardBreaks() {
        assertEquals("bir başka gizlilik sorunuyla",
                CaptionHardBreakPolicy.normalizedPlain("bir\nbaşka gizlilik sorunuyla\n"));
        assertEquals("bu ? doğru", CaptionHardBreakPolicy.normalizedPlain("bu\n?\r\ndoğru"));
    }
    @Test public void preserveLongContentGateAndNull() {
        assertFalse(CaptionHardBreakPolicy.shouldNormalize("a".repeat(2049) + "\n"));
        assertNull(CaptionHardBreakPolicy.normalizedPlain(null));
        assertFalse(CaptionHardBreakPolicy.shouldNormalize(null));
    }
    @Test public void preserveLegitimateNonBreakWhitespace() {
        assertEquals("X\u00a0Y", CaptionHardBreakPolicy.normalizedPlain("X\u00a0Y"));
        assertEquals("One two", CaptionHardBreakPolicy.normalizedPlain(" One  two  "));
    }
}
