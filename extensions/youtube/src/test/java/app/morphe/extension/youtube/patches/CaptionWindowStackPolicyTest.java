package app.morphe.extension.youtube.patches;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import app.morphe.extension.youtube.patches.CaptionWindowStackPolicy.Rect;

public class CaptionWindowStackPolicyTest {
    @Test public void separatedTranslatedSubtitleWindowsStackWithoutTextResizing() {
        List<Rect> result = CaptionWindowStackPolicy.plan(1080, 950,
                Arrays.asList(new Rect(1, 70, 810, 920, 60), new Rect(0, 100, 610, 880, 80)), 8);
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(0, result.get(0).index);
        assertEquals(1, result.get(1).index);
        assertEquals(880, result.get(0).width);
        assertEquals(80, result.get(0).height);
        assertEquals(result.get(0).bottom() + 8, result.get(1).top);
        assertEquals((1080 - 880) / 2, result.get(0).left);
        assertTrue(result.get(1).bottom() <= (int)(950 * 0.91f));
    }

    @Test public void singleSubtitleWindowKeepsNativeLayout() {
        assertNull(CaptionWindowStackPolicy.plan(1080, 950,
                Arrays.asList(new Rect(0, 10, 750, 800, 60)), 8));
    }

    @Test public void normallyAdjacentLinesStayUntouched() {
        assertNull(CaptionWindowStackPolicy.plan(1080, 950, Arrays.asList(
                new Rect(0, 70, 730, 920, 60), new Rect(1, 70, 795, 920, 60)), 8));
    }

    @Test public void tooManyConcurrentCuesStayUntouched() {
        assertNull(CaptionWindowStackPolicy.plan(1080, 950, Arrays.asList(
                new Rect(0, 20, 100, 300, 40), new Rect(1, 20, 220, 300, 40),
                new Rect(2, 20, 360, 300, 40), new Rect(3, 20, 520, 300, 40)), 8));
    }

    @Test public void tooTallLayoutStaysUntouched() {
        assertNull(CaptionWindowStackPolicy.plan(1080, 950, Arrays.asList(
                new Rect(0, 20, 100, 900, 270), new Rect(1, 20, 700, 900, 260)), 8));
    }

    @Test public void windowsWiderThanFrameStayUntouched() {
        assertNull(CaptionWindowStackPolicy.plan(1080, 950, Arrays.asList(
                new Rect(0, 20, 100, 1200, 40), new Rect(1, 20, 700, 900, 40)), 8));
    }

    @Test public void repeatedLayoutCallsAreIdempotent() {
        List<Rect> initial = Arrays.asList(
                new Rect(0, 70, 250, 900, 60), new Rect(1, 70, 660, 900, 60));
        List<Rect> normalized = CaptionWindowStackPolicy.plan(1080, 900, initial, 8);
        assertNotNull(normalized);
        assertNull(CaptionWindowStackPolicy.plan(1080, 900, normalized, 8));
    }
}
