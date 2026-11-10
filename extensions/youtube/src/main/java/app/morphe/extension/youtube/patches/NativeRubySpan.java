package app.morphe.extension.youtube.patches;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.icu.text.BreakIterator;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;

import java.util.ArrayList;
import java.util.Locale;

/** Horizontal ruby box measured and drawn inside the native caption row. */
final class NativeRubySpan extends ReplacementSpan {
    private static final TextPaint DEFAULT_TEXT_PAINT = new TextPaint();
    private final Spanned original;
    private final CharSequence base, annotation;
    private final boolean under;
    private final int baseEnd, annotationStart, annotationEnd;
    // 0: none; 1/2: base over/under; 3/4: annotation over/under.
    private final int emphasis;
    private final int[] markStarts, markEnds;
    private final TextPaint markPaint = new TextPaint();
    private final Path markPath = new Path();
    private final RectF markBounds = new RectF();
    private final LayoutState[] layouts = new LayoutState[Paint.Style.values().length];

    NativeRubySpan withPreferences(NativeCaptionPreferences.Profile profile) {
        return new NativeRubySpan(
                (Spanned) NativeCaptionPreferences.apply(original, profile),
                baseEnd,
                annotationStart,
                annotationEnd,
                under,
                emphasis);
    }

    CharSequence baseText() {
        return base;
    }

    Spanned originalText() {
        return original;
    }

    /** Same authored group expressed using the platform vertical-layout spans. */
    CharSequence verticalText(androidx.text.vertical.TextOrientation orientation) {
        var base = new SpannableStringBuilder(original.subSequence(0, baseEnd));
        var annotation =
                new SpannableStringBuilder(original.subSequence(annotationStart, annotationEnd));
        NativeCaptionVertical.outlines(base);
        NativeCaptionVertical.outlines(annotation);
        if (emphasis != 0) {
            var marked = emphasis <= 2 ? base : annotation;
            if (marked.length() != 0)
                marked.setSpan(
                        new androidx.text.vertical.EmphasisSpan(
                                androidx.text.vertical.EmphasisStyle.Circle,
                                true,
                                emphasis == 2 || emphasis == 4
                                        ? androidx.text.vertical.AnnotationPosition.After
                                        : androidx.text.vertical.AnnotationPosition.Before,
                                .5f),
                        0,
                        marked.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (base.length() != 0 && annotation.length() != 0)
            base.setSpan(
                    new androidx.text.vertical.RubySpan(
                            annotation,
                            under
                                    ? androidx.text.vertical.AnnotationPosition.After
                                    : androidx.text.vertical.AnnotationPosition.Before,
                            orientation,
                            .5f),
                    0,
                    base.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return base;
    }

    NativeRubySpan(
            Spanned source,
            int baseEnd,
            int annotationStart,
            int annotationEnd,
            boolean under,
            int emphasis) {
        this.original = new SpannedString(source);
        this.baseEnd = baseEnd;
        this.annotationStart = annotationStart;
        this.annotationEnd = annotationEnd;
        this.base = appearance(source.subSequence(0, baseEnd));
        this.annotation = appearance(source.subSequence(annotationStart, annotationEnd));
        this.under = under;
        this.emphasis = emphasis;
        String target = (emphasis <= 2 ? base : annotation).toString();
        var starts = new ArrayList<Integer>();
        var ends = new ArrayList<Integer>();
        if (emphasis != 0) {
            BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
            iterator.setText(target);
            for (int start = iterator.first(), end = iterator.next();
                    end != BreakIterator.DONE;
                    start = end, end = iterator.next()) {
                if (emphasizable(target, start, end)) {
                    starts.add(start);
                    ends.add(end);
                }
            }
        }
        markStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        markEnds = ends.stream().mapToInt(Integer::intValue).toArray();
    }

    private static boolean emphasizable(String text, int start, int end) {
        for (int i = start; i < end; ) {
            int cp = text.codePointAt(i), type = Character.getType(cp);
            if (!Character.isWhitespace(cp)
                    && !Character.isSpaceChar(cp)
                    && type != Character.CONTROL
                    && type != Character.FORMAT
                    && type != Character.CONNECTOR_PUNCTUATION
                    && type != Character.DASH_PUNCTUATION
                    && type != Character.START_PUNCTUATION
                    && type != Character.END_PUNCTUATION
                    && type != Character.INITIAL_QUOTE_PUNCTUATION
                    && type != Character.FINAL_QUOTE_PUNCTUATION
                    && type != Character.OTHER_PUNCTUATION) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private int markHeight(LayoutState state) {
        if (markStarts.length == 0) return 0;
        TextPaint paint = emphasis <= 2 ? state.basePaint : state.annotationPaint;
        return (int) Math.ceil(paint.getFontSpacing() * .5f);
    }

    private LayoutState layout(Paint paint) {
        int index = paint.getStyle().ordinal();
        LayoutState state = layouts[index];
        if (state == null || !state.key.equalsForTextMeasurement(paint)) {
            state = new LayoutState(paint, text(base, paint), text(annotation, paint));
            layouts[index] = state;
        }
        // Native foreground/edge paint can change without changing text measurements.
        copyPaint(state.basePaint, paint);
        copyPaint(state.annotationPaint, paint);
        state.annotationPaint.setTextSize(paint.getTextSize() * .5f);
        state.basePaint.baselineShift = state.annotationPaint.baselineShift = 0;
        return state;
    }

    private static void copyPaint(TextPaint target, Paint source) {
        if (source instanceof TextPaint textPaint) target.set(textPaint);
        else {
            // Paint.set(Paint) leaves TextPaint-specific fields untouched. Clear any state
            // retained from the preceding native TextPaint before copying a plain Paint.
            target.set(DEFAULT_TEXT_PAINT);
            target.set(source);
        }
    }

    private static final class LayoutState {
        final TextPaint key, basePaint, annotationPaint;
        final StaticLayout baseLayout, annotationLayout;
        final CharSequence baseText, annotationText;
        final int width, baseWidth, annotationWidth;

        LayoutState(Paint paint, CharSequence baseText, CharSequence annotationText) {
            this.baseText = baseText;
            this.annotationText = annotationText;
            key = new TextPaint(paint);
            basePaint = new TextPaint(paint);
            annotationPaint = new TextPaint(paint);
            copyPaint(key, paint);
            copyPaint(basePaint, paint);
            copyPaint(annotationPaint, paint);
            annotationPaint.setTextSize(paint.getTextSize() * .5f);
            basePaint.baselineShift = annotationPaint.baselineShift = 0;
            baseWidth = Math.max(1, (int) Math.ceil(Layout.getDesiredWidth(baseText, basePaint)));
            annotationWidth =
                    Math.max(
                            1,
                            (int)
                                    Math.ceil(
                                            Layout.getDesiredWidth(
                                                    annotationText, annotationPaint)));
            width = Math.max(baseWidth, annotationWidth);
            baseLayout =
                    StaticLayout.Builder.obtain(
                                    baseText, 0, baseText.length(), basePaint, baseWidth)
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setIncludePad(false)
                            .build();
            annotationLayout =
                    StaticLayout.Builder.obtain(
                                    annotationText,
                                    0,
                                    annotationText.length(),
                                    annotationPaint,
                                    annotationWidth)
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setIncludePad(false)
                            .build();
        }
    }

    private CharSequence appearance(CharSequence styled) {
        SpannableStringBuilder result = new SpannableStringBuilder(styled);
        for (MetricAffectingSpan metric :
                result.getSpans(0, result.length(), MetricAffectingSpan.class)) {
            if (!(metric instanceof NativeCaptionPreferences.Outline)) result.removeSpan(metric);
        }
        for (int i = result.length() - 1; i >= 0; i--) {
            char c = result.charAt(i);
            if (c == '\r') {
                if (i + 1 < result.length() && result.charAt(i + 1) == '\n')
                    result.delete(i, i + 1);
                else result.replace(i, i + 1, "\n");
            } else if (c == '\u2028' || c == '\u2029') result.replace(i, i + 1, "\n");
        }
        return new SpannedString(result);
    }

    private CharSequence text(CharSequence styled, Paint paint) {
        if (paint.getStyle() != Paint.Style.STROKE) return styled;
        SpannableStringBuilder outline = new SpannableStringBuilder(styled);
        for (ForegroundColorSpan color :
                outline.getSpans(0, outline.length(), ForegroundColorSpan.class)) {
            outline.removeSpan(color);
        }
        return outline;
    }

    @Override
    public synchronized int getSize(
            Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        LayoutState state = layout(paint);
        if (fm != null) {
            int mark = markHeight(state);
            int extra =
                    annotation.length() == 0
                            ? 0
                            : state.annotationLayout.getHeight() + (emphasis >= 3 ? mark : 0);
            fm.ascent =
                    -state.baseLayout.getLineBaseline(0)
                            - (under ? 0 : extra)
                            - (emphasis == 1 ? mark : 0);
            fm.descent =
                    state.baseLayout.getHeight()
                            - state.baseLayout.getLineBaseline(0)
                            + (under ? extra : 0)
                            + (emphasis == 2 ? mark : 0);
            fm.top = fm.ascent;
            fm.bottom = fm.descent;
        }
        return state.width;
    }

    @Override
    public synchronized void draw(
            Canvas canvas,
            CharSequence text,
            int start,
            int end,
            float x,
            int top,
            int y,
            int bottom,
            Paint paint) {
        LayoutState state = layout(paint);
        float baseTop = y - state.baseLayout.getLineBaseline(0);
        canvas.save();
        canvas.translate(x + (state.width - state.baseWidth) / 2f, baseTop);
        state.baseLayout.draw(canvas);
        canvas.restore();
        if (emphasis == 1 || emphasis == 2) {
            drawMarks(
                    canvas,
                    state.baseLayout,
                    state.baseText,
                    state.basePaint,
                    x + (state.width - state.baseWidth) / 2f,
                    baseTop,
                    emphasis == 2,
                    markHeight(state));
        }
        if (annotation.length() != 0) {
            float annotationTop =
                    under
                            ? baseTop + state.baseLayout.getHeight()
                            : baseTop - state.annotationLayout.getHeight();
            canvas.save();
            canvas.translate(x + (state.width - state.annotationWidth) / 2f, annotationTop);
            state.annotationLayout.draw(canvas);
            canvas.restore();
            if (emphasis == 3 || emphasis == 4) {
                drawMarks(
                        canvas,
                        state.annotationLayout,
                        state.annotationText,
                        state.annotationPaint,
                        x + (state.width - state.annotationWidth) / 2f,
                        annotationTop,
                        emphasis == 4,
                        markHeight(state));
            }
        }
    }

    private void drawMarks(
            Canvas canvas,
            StaticLayout layout,
            CharSequence text,
            TextPaint paint,
            float x,
            float top,
            boolean below,
            int height) {
        TextPaint mark = markPaint;
        Path path = markPath;
        RectF bounds = markBounds;
        for (int i = 0; i < markStarts.length; i++) {
            int start = markStarts[i], end = markEnds[i];
            mark.set(paint);
            if (text instanceof Spanned spans)
                for (CharacterStyle style : spans.getSpans(start, end, CharacterStyle.class))
                    style.updateDrawState(mark);
            mark.setTextSize(paint.getTextSize() * .5f);
            mark.setUnderlineText(false);
            mark.setStrikeThruText(false);
            path.reset();
            layout.getSelectionPath(start, end, path);
            path.computeBounds(bounds, true);
            float y = below ? bounds.bottom : bounds.top - height;
            canvas.drawText(
                    "●",
                    x + bounds.centerX() - mark.measureText("●") / 2f,
                    top + y - mark.ascent(),
                    mark);
        }
    }
}
