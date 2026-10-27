package app.morphe.extension.youtube.patches;

/**
 * Pure-Java paragraph policy for translated rolling captions.
 *
 * These source captions are timed in a native roll-up window: hard newlines
 * belong to source word segmentation, not translated sentence formatting.
 * Let YouTube's own renderer wrap the cleaned text at the actual viewport.
 */
public final class CaptionHardBreakPolicy {
    private CaptionHardBreakPolicy() {}

    public static boolean shouldNormalize(CharSequence text) {
        if (text == null || text.length() == 0 || text.length() > 2048) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') return true;
        }
        return false;
    }

    /** Plain-string reference implementation used by unit tests. */
    public static String normalizedPlain(CharSequence input) {
        if (input == null) return null;
        StringBuilder output = new StringBuilder(input.length());
        boolean previousSpace = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') c = ' ';
            if (c == ' ') {
                if (previousSpace || output.length() == 0) continue;
                previousSpace = true;
            } else {
                previousSpace = false;
            }
            output.append(c);
        }
        if (output.length() != 0 && output.charAt(output.length() - 1) == ' ')
            output.deleteCharAt(output.length() - 1);
        return output.toString();
    }
}
