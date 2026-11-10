package app.morphe.extension.youtube.patches;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;
import android.text.style.TypefaceSpan;
import android.view.View;
import android.view.accessibility.CaptioningManager;

import java.util.List;

/** Applies explicit platform caption preferences without changing source cues or native paints. */
public final class NativeCaptionPreferences {
    private NativeCaptionPreferences() {}

    /** Native formatter effects only change either text alpha or its shadow. */
    public interface PaintEffect {}

    /** Native outline span keeps its edge while its fill inherits a user-selected color. */
    public interface Outline {
        ReplacementSpan patch_withoutForeground();

        int patch_getOutlineColor();

        Integer patch_getOutlineForeground();
    }

    record Profile(
            Integer foreground, Integer background, Integer window, boolean edge, Typeface font) {
        boolean empty() {
            return foreground == null
                    && background == null
                    && window == null
                    && !edge
                    && font == null;
        }
    }

    private record Original(Spanned text, Profile profile) implements android.text.NoCopySpan {}

    /** Full-row adapter copies retain our restoration marker; arbitrary slices still must not. */
    static SpannableStringBuilder copy(CharSequence text) {
        var result = new SpannableStringBuilder(text);
        if (text instanceof Spanned spans) {
            for (Original original : spans.getSpans(0, text.length(), Original.class)) {
                result.setSpan(
                        original,
                        spans.getSpanStart(original),
                        spans.getSpanEnd(original),
                        spans.getSpanFlags(original));
            }
        }
        return result;
    }

    static Profile profile(View view) {
        var manager =
                (CaptioningManager) view.getContext().getSystemService(Context.CAPTIONING_SERVICE);
        if (manager == null) return new Profile(null, null, null, false, null);
        var style = manager.getUserStyle();
        return new Profile(
                style.hasForegroundColor() ? style.foregroundColor : null,
                style.hasBackgroundColor() ? style.backgroundColor : null,
                style.hasWindowColor() ? style.windowColor : null,
                style.hasEdgeType() || style.hasEdgeColor(),
                style.getTypeface());
    }

    static Integer window(View view, Integer authored) {
        Integer value = profile(view).window;
        return value == null ? authored : value;
    }

    static void prepareRows(View view, StableCaptionBlocks.Window window) {
        Profile profile = profile(view);
        for (var rows :
                List.of(
                        window.patch_getFullCaptionLines(),
                        window.patch_getVisibleCaptionLines())) {
            for (int i = 0; i < rows.size(); i++) rows.set(i, apply(rows.get(i), profile));
        }
    }

    static CharSequence apply(CharSequence text, Profile profile) {
        if (!(text instanceof Spanned spans) || text.length() == 0) return text;
        Original[] originals = spans.getSpans(0, text.length(), Original.class);
        if (originals.length != 0) {
            Original old = originals[0];
            if (old.profile.equals(profile)) return text;
            spans = old.text;
        }
        if (profile.empty()) return spans;
        var result = new SpannableStringBuilder(spans);
        for (CharacterStyle span : result.getSpans(0, result.length(), CharacterStyle.class)) {
            int start = result.getSpanStart(span),
                    end = result.getSpanEnd(span),
                    flags = result.getSpanFlags(span);
            if (span instanceof NativeRubySpan ruby) {
                result.removeSpan(span);
                result.setSpan(ruby.withPreferences(profile), start, end, flags);
            } else if (profile.foreground != null
                    && !profile.edge
                    && span instanceof Outline outline) {
                result.removeSpan(span);
                result.setSpan(outline.patch_withoutForeground(), start, end, flags);
            } else if ((profile.background != null && span instanceof BackgroundColorSpan)
                    || (profile.font != null && span instanceof TypefaceSpan)
                    || (profile.edge && span instanceof ReplacementSpan)
                    || overrides(span, profile)) {
                result.removeSpan(span);
            }
        }
        if (profile.foreground != null)
            result.setSpan(
                    new ForegroundColorSpan(profile.foreground),
                    0,
                    result.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (profile.font != null)
            result.setSpan(
                    new UserFont(profile.font),
                    0,
                    result.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        result.setSpan(
                new Original(spans, profile), 0, result.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return result;
    }

    private static boolean overrides(CharacterStyle span, Profile profile) {
        if (span instanceof ForegroundColorSpan) return profile.foreground != null;
        if (!(span instanceof PaintEffect)) return false;
        TextPaint probe = new TextPaint();
        probe.setTextSize(40);
        probe.setColor(0xFFFFFFFF);
        span.updateDrawState(probe);
        // A fully opaque alpha span is also safe to remove when replacing the edge.
        return probe.getColor() != 0xFFFFFFFF ? profile.foreground != null : profile.edge;
    }

    private static final class UserFont extends MetricAffectingSpan {
        private final Typeface font;

        UserFont(Typeface font) {
            this.font = font;
        }

        @Override
        public void updateMeasureState(TextPaint paint) {
            Typeface current = paint.getTypeface();
            paint.setTypeface(
                    Typeface.create(font, current == null ? Typeface.NORMAL : current.getStyle()));
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            Typeface current = paint.getTypeface();
            paint.setTypeface(
                    Typeface.create(font, current == null ? Typeface.NORMAL : current.getStyle()));
        }
    }
}
