package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Map;

public class NativeCaptionWindowBackgroundTest {
    private Integer background(Map<String, String> fields) {
        return NativeCaptionWindowData.background(
                new NativeCaptionWindowData.Definition(NativeCaptionWindowData.Kind.STYLE, fields));
    }

    @Test
    public void unspecifiedWindowFillKeepsNativeBackground() {
        assertNull(NativeCaptionWindowData.background(null));
        assertNull(background(Map.of()));
        assertNull(background(Map.of("ju", "2")));
    }

    @Test
    public void declaredColorAndOpacityProduceAuthoredArgb() {
        assertEquals(
                Integer.valueOf(0x40123456), background(Map.of("wfc", "#123456", "wfo", "64")));
    }

    @Test
    public void windowFillDefaultsToTransparentIndependentlyOfPenBackground() {
        assertEquals(Integer.valueOf(0x00123456), background(Map.of("wfc", "#123456")));
    }

    @Test
    public void declaredOpacityUsesReferenceDefaultWindowColor() {
        assertEquals(Integer.valueOf(0x80080808), background(Map.of("wfo", "128")));
        assertEquals(Integer.valueOf(0x00080808), background(Map.of("wfo", "0")));
    }

    @Test
    public void opacityClampsLikeCssAlpha() {
        assertEquals(Integer.valueOf(0xff080808), background(Map.of("wfo", "511")));
        assertEquals(Integer.valueOf(0x00080808), background(Map.of("wfo", "-1")));
    }

    @Test
    public void malformedColorsDoNotReplaceNativeBackground() {
        for (String color : new String[] {"bad", "#12345678", "#+00000", "#GG0000"})
            assertNull(background(Map.of("wfc", color, "wfo", "128")));
    }
}
