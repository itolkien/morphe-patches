package app.morphe.extension.youtube.patches.captions.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CaptionTextUnits {
    private CaptionTextUnits() {}

    public record Unit(String id, String text) {}

    /** Source slices retain whitespace and never split UTF-16 surrogate pairs. */
    public static List<Unit> split(String id, String text, int budget) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(text);
        if (budget <= 0) throw new IllegalArgumentException("Caption unit budget must be positive");
        var result = new ArrayList<Unit>();
        int start = 0;
        while (start < text.length()) {
            int end = start;
            int safe = start;
            int count = 0;
            int regionalCount = 0;
            while (end < text.length() && count < budget) {
                int codePoint = text.codePointAt(end);
                end += Character.charCount(codePoint);
                count++;
                regionalCount = regional(codePoint) ? regionalCount + 1 : 0;
                if (end == text.length()
                        || boundary(codePoint, text.codePointAt(end), regionalCount)) {
                    safe = end;
                }
            }
            // A single cluster can exceed the provider budget. Split at a code point
            // boundary in that exceptional case; source reassembly still stays exact.
            if (safe > start) end = safe;
            result.add(new Unit(id + ":unit:" + result.size(), text.substring(start, end)));
            start = end;
        }
        return List.copyOf(result);
    }

    private static boolean regional(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    private static boolean boundary(int previous, int next, int regionalCount) {
        int type = Character.getType(next);
        return previous != 0x200D
                && next != 0x200D
                && !(previous == '\r' && next == '\n')
                && type != Character.NON_SPACING_MARK
                && type != Character.COMBINING_SPACING_MARK
                && type != Character.ENCLOSING_MARK
                && !(next >= 0xFE00 && next <= 0xFE0F)
                && !(next >= 0xE0100 && next <= 0xE01EF)
                && !(next >= 0x1F3FB && next <= 0x1F3FF)
                && !(regional(previous) && regional(next) && regionalCount % 2 == 1);
    }
}
