package app.morphe.extension.youtube.patches;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class CaptionRowGapPolicyTest {
    static CaptionRowGapPolicy.Row row(int index, int top, int height) {
        return new CaptionRowGapPolicy.Row(index, 0, top, 500, height);
    }

    @Test public void noChangeToNormalRows() {
        assertNull(CaptionRowGapPolicy.plan(1080, 608,
                List.of(row(0, 0, 45), row(1, 48, 45), row(2, 96, 45)), 4));
    }

    @Test public void compactOnlyExcessiveGaps() {
        assertArrayEquals(new int[] {10, 54, 98},
                CaptionRowGapPolicy.plan(1080, 608,
                        List.of(row(0, 10, 40), row(1, 150, 40), row(2, 194, 40)), 4));
    }

    @Test public void noChangeToSingleRowOrEmptyWindows() {
        assertNull(CaptionRowGapPolicy.plan(1080, 608, List.of(row(0, 200, 40)), 4));
        assertNull(CaptionRowGapPolicy.plan(1080, 608, List.of(), 4));
        assertNull(CaptionRowGapPolicy.plan(1080, 608, null, 4));
    }

    @Test public void passThroughOverlappingRollupText() {
        assertNull(CaptionRowGapPolicy.plan(1080, 608,
                List.of(row(0, 10, 60), row(1, 55, 60)), 4));
    }

    @Test public void passThroughOutOfBoundsOrMalformedRows() {
        assertNull(CaptionRowGapPolicy.plan(1080, 608,
                List.of(row(0, 10, 60), row(1, 580, 60)), 4));
        assertNull(CaptionRowGapPolicy.plan(1080, 608,
                List.of(row(0, 10, 60), row(0, 150, 60)), 4));
        assertNull(CaptionRowGapPolicy.plan(1080, 608,
                List.of(new CaptionRowGapPolicy.Row(0, -1, 10, 500, 40), row(1, 200, 40)), 4));
    }

    @Test public void preserveNativeRowOrderEvenWhenChildArrayIsUnsorted() {
        var second = row(0, 160, 40);
        var first = row(1, 10, 40);
        assertArrayEquals(new int[] {54, 10},
                CaptionRowGapPolicy.plan(1080, 608, List.of(second, first), 4));
    }

    @Test public void preserveLayoutWhenNoCapacityForBottomText() {
        assertNull(CaptionRowGapPolicy.plan(1080, 608,
                List.of(row(0, 10, 300), row(1, 450, 200)), 4));
    }
}
