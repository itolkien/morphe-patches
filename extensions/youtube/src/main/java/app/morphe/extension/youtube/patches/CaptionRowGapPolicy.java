package app.morphe.extension.youtube.patches;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Conservative, Android-free layout policy for rows within one native caption window.
 * Never alters content, native timing, fonts or window anchor.
 */
public final class CaptionRowGapPolicy {
    private CaptionRowGapPolicy() {}

    public static final class Row {
        public final int index, left, top, width, height;

        public Row(int index, int left, int top, int width, int height) {
            this.index = index;
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }

        public int bottom() {
            return top + height;
        }
    }

    /**
     * New row tops in input order, or null if no change is warranted.
     *
     * Refuse unknown, overlapping, or oversized layouts. Keep the topmost native row
     * fixed and pull only the rows separated by abnormally large empty space upward.
     */
    public static int[] plan(int width, int height, List<Row> input, int desiredGap) {
        if (width <= 0 || height <= 0 || input == null
                || input.size() < 2 || input.size() > 4) return null;
        int gap = Math.max(1, desiredGap);
        List<Row> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingInt((Row row) -> row.top).thenComparingInt(row -> row.index));

        boolean[] seen = new boolean[input.size()];
        for (Row row : sorted) {
            if (row.index < 0 || row.index >= input.size() || seen[row.index]) return null;
            seen[row.index] = true;
            if (row.width <= 0 || row.height <= 0 || row.left < 0 || row.top < 0
                    || (long) row.left + row.width > width || (long) row.top + row.height > height)
                return null;
        }

        // Do not touch native roll-up or overlapping rows.
        Row previous = null;
        boolean hasAbnormalGap = false;
        for (Row row : sorted) {
            if (previous != null) {
                int nativeGap = row.top - previous.bottom();
                if (nativeGap < 0) return null;
                if (nativeGap > Math.max(3 * gap,
                        Math.max(row.height, previous.height) / 2)) hasAbnormalGap = true;
            }
            previous = row;
        }
        if (!hasAbnormalGap) return null;

        int[] positions = new int[input.size()];
        Row first = sorted.get(0);
        positions[first.index] = first.top;
        int bottom = first.bottom();
        previous = first;
        for (int i = 1; i < sorted.size(); i++) {
            Row row = sorted.get(i);
            int nativeGap = row.top - previous.bottom();
            int threshold = Math.max(3 * gap, Math.max(row.height, previous.height) / 2);
            int effectiveGap = nativeGap > threshold ? gap : nativeGap;
            int newTop = bottom + effectiveGap;
            if (newTop < 0 || (long) newTop + row.height > height) return null;
            positions[row.index] = newTop;
            bottom = newTop + row.height;
            previous = row;
        }
        return positions;
    }
}
