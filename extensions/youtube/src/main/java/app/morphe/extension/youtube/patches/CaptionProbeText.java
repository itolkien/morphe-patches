package app.morphe.extension.youtube.patches;

/** Pure-Java, bounded description of a native caption string for an opt-in debug log. */
public final class CaptionProbeText {
    private CaptionProbeText() {}

    public static String describe(Object value) {
        if (!(value instanceof CharSequence text)) return value == null ? "null" : "not-text";

        int newlines = 0;
        int carriageReturns = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') newlines++;
            else if (c == '\r') carriageReturns++;
        }

        // For correlation with a screenshot, print only the first 96 caption characters.
        // Escape controls so a single cue always occupies one logcat line.
        int count = Math.min(text.length(), 96);
        StringBuilder preview = new StringBuilder(count + 12);
        for (int i = 0; i < count; i++) {
            char c = text.charAt(i);
            if (c == '\n') preview.append("\\n");
            else if (c == '\r') preview.append("\\r");
            else if (c == '\t') preview.append("\\t");
            else if (Character.isISOControl(c)) preview.append('?');
            else preview.append(c);
        }
        if (text.length() > count) preview.append("...");
        return "length=" + text.length() + " nl=" + newlines
                + " cr=" + carriageReturns + " preview=\"" + preview + "\"";
    }
}
