package app.morphe.extension.youtube.patches;

import org.junit.Test;
import static org.junit.Assert.*;

public class CaptionWindowGapPatchTest {
    @Test
    public void screenshotTwoSeparatedWindowsStackAboveOriginalUpperBottom() {
        // Approximate the normalized window rectangles from the reported screenshot.
        assertArrayEquals(new int[] {675, 792}, CaptionWindowGapPatch.correctedTops(
                1312, 1181, 102, 795, 1040, 113, 102, 971, 1000, 116, 4));
    }

    @Test
    public void nativeNormalSpacingIsUnchanged() {
        assertNull(CaptionWindowGapPatch.correctedTops(
                1312, 1181, 102, 795, 1040, 113, 102, 913, 1000, 116, 4));
    }

    @Test
    public void distinctSpeakerPositionsAreUnchanged() {
        assertNull(CaptionWindowGapPatch.correctedTops(
                1312, 1181, 0, 600, 400, 110, 850, 900, 400, 110, 4));
    }

    @Test
    public void partiallyOffscreenWindowsAreUnchanged() {
        assertNull(CaptionWindowGapPatch.correctedTops(
                1312, 1181, 102, 795, 1040, 113, 102, 1110, 1000, 116, 4));
    }

    @Test
    public void excessiveHeightLeavesNoSafeStack() {
        assertNull(CaptionWindowGapPatch.correctedTops(
                1312, 1181, 102, 20, 1040, 300, 102, 700, 1000, 380, 4));
    }

    @Test
    public void alignedWindowsMayHaveSlightlyDifferentWidths() {
        assertNotNull(CaptionWindowGapPatch.correctedTops(
                1312, 1181, 102, 600, 1040, 100, 150, 900, 960, 100, 4));
    }
}
