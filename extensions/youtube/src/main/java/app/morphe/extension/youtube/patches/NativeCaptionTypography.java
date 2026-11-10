package app.morphe.extension.youtube.patches;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;

/** Adds authored SRV3 fonts and baselines to text already styled by YouTube. */
public final class NativeCaptionTypography {
    private NativeCaptionTypography() {}

    /** Keeps the native object when no supported authored typography needs to be applied. */
    public static CharSequence apply(CharSequence text, int offset, int font) {
        if (!(text instanceof Spanned) || text.length() == 0) {
            return text;
        }
        String family = fontFamily(font);
        boolean baseline = offset == 0 || offset == 2;
        if (family == null && !baseline) return text;
        SpannableStringBuilder result = new SpannableStringBuilder(text);
        if (family != null) {
            result.setSpan(
                    new TypefaceSpan(family), 0, result.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (baseline) {
            result.setSpan(
                    new RelativeSizeSpan(.8f),
                    0,
                    result.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            result.setSpan(
                    offset == 0 ? new SubscriptSpan() : new SuperscriptSpan(),
                    0,
                    result.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return result;
    }

    private static String fontFamily(int font) {
        return switch (font) {
            case 1 -> "serif-monospace";
            case 2 -> "serif";
            case 3 -> "sans-serif-monospace";
            case 4 -> "sans-serif";
            case 5 -> "casual";
            case 6 -> "cursive";
            case 7 -> "sans-serif-smallcaps";
            default -> null;
        };
    }
}
