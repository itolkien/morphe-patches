package app.morphe.extension.youtube.patches;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;
import android.view.View;

import androidx.text.vertical.TextOrientation;
import androidx.text.vertical.TextOrientationSpan;
import androidx.text.vertical.VerticalTextLayout;

import java.util.List;

/** Adapts vertical text inside a native row; YouTube owns the window, clock and lifecycle. */
public final class NativeCaptionVertical {
    private NativeCaptionVertical() {}

    /** Carries authored hg through native and translated runs; horizontal rows ignore it. */
    public static CharSequence applyGroup(CharSequence text, boolean vertical) {
        if (!vertical || text.length() == 0) return text;
        var result = new SpannableStringBuilder(text);
        result.setSpan(
                new TextOrientationSpan.CombineUpright(),
                0,
                result.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return result;
    }

    private record Direction(boolean rtl) {}

    static Boolean paragraphDirection(CharSequence text) {
        if (!(text instanceof Spanned spans)) return null;
        Direction[] values = spans.getSpans(0, text.length(), Direction.class);
        return values.length == 0 ? null : values[values.length - 1].rtl;
    }

    private static void direct(List<CharSequence> rows, boolean rtl) {
        for (int i = 0; i < rows.size(); i++) {
            CharSequence row = rows.get(i);
            if (row.length() == 0) continue;
            var text = NativeCaptionPreferences.copy(row);
            for (Direction old : text.getSpans(0, text.length(), Direction.class))
                text.removeSpan(old);
            text.setSpan(new Direction(rtl), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            rows.set(i, text);
        }
    }

    /** Runs after native text replacement and before YouTube measures its rows. */
    public static void prepare(View view, int widthSpec, int heightSpec) {
        if (!(view instanceof NativeCaptionWindows.Host host)
                || !(view instanceof StableCaptionBlocks.Window window)) return;
        var source = host.patch_getWindowSource();
        restore(window.patch_getFullCaptionLines());
        restore(window.patch_getVisibleCaptionLines());
        NativeCaptionWindows.onCue(view);
        NativeCaptionPreferences.prepareRows(view, window);
        // Pen-only cues still need platform preferences before native row measurement.
        if (source == null) return;
        NativeCaptionWindowLayout.prepare(view, widthSpec, heightSpec);
        Integer pd = source.style() == null ? null : source.style().integer("pd");
        if (pd != null && (pd == 0 || pd == 1)) {
            direct(window.patch_getFullCaptionLines(), pd == 1);
            direct(window.patch_getVisibleCaptionLines(), pd == 1);
        }
        if (!source.vertical()) return;
        int height =
                View.MeasureSpec.getSize(heightSpec)
                        - view.getPaddingTop()
                        - view.getPaddingBottom();
        if (window.patch_getCaptionMeasureRow() instanceof View row) {
            height -= row.getPaddingTop() + row.getPaddingBottom();
        }
        if (height <= 0) return;
        int direction = source.style().integer("pd");
        boolean leftToRight = Integer.valueOf(1).equals(source.style().integer("sd"));
        wrap(window.patch_getFullCaptionLines(), height, direction, leftToRight);
        wrap(window.patch_getVisibleCaptionLines(), height, direction, leftToRight);
    }

    static void outlines(SpannableStringBuilder text) {
        for (var span : text.getSpans(0, text.length(), NativeCaptionPreferences.Outline.class)) {
            int start = text.getSpanStart(span),
                    end = text.getSpanEnd(span),
                    flags = text.getSpanFlags(span);
            text.removeSpan(span);
            text.setSpan(
                    new androidx.text.vertical.OutlineSpan(span.patch_getOutlineColor()),
                    start,
                    end,
                    flags);
            Integer foreground = span.patch_getOutlineForeground();
            if (foreground != null)
                text.setSpan(
                        new android.text.style.ForegroundColorSpan(foreground), start, end, flags);
        }
    }

    private static void restore(List<CharSequence> rows) {
        if (rows.size() != 1 || !(rows.get(0) instanceof Spanned spans)) return;
        VerticalSpan[] previous = spans.getSpans(0, spans.length(), VerticalSpan.class);
        if (previous.length != 1) return;
        var source = previous[0].sourceRows;
        rows.clear();
        rows.addAll(source);
    }

    private static void wrap(
            List<CharSequence> rows, int height, int direction, boolean leftToRight) {
        var text = new SpannableStringBuilder();
        boolean first = true;
        for (CharSequence row : rows) {
            if (!first) text.append('\n');
            first = false;
            if (row instanceof Spanned spans) {
                VerticalSpan[] old = spans.getSpans(0, row.length(), VerticalSpan.class);
                if (old.length == 1) row = old[0].text;
            }
            text.append(NativeCaptionRuby.verticalText(row, direction));
        }
        if (text.length() == 0) return;
        if (direction == 3) {
            for (TextOrientationSpan.CombineUpright group :
                    text.getSpans(0, text.length(), TextOrientationSpan.CombineUpright.class)) {
                int start = text.getSpanStart(group),
                        end = text.getSpanEnd(group),
                        flags = text.getSpanFlags(group);
                text.removeSpan(group);
                text.setSpan(new TextOrientationSpan.CombineUpright(180f), start, end, flags);
            }
        }
        outlines(text);
        var original = new SpannedString(text);
        for (android.text.style.CharacterStyle span :
                text.getSpans(0, text.length(), android.text.style.CharacterStyle.class))
            text.removeSpan(span);
        // The paragraph is one native inline box; hard breaks become vertical columns inside it.
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) == '\n') text.replace(i, i + 1, "\u2060");
        text.setSpan(
                new VerticalSpan(original, height, direction, leftToRight, List.copyOf(rows)),
                0,
                text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        rows.clear();
        rows.add(text);
    }

    private static final class VerticalSpan extends ReplacementSpan {
        final Spanned text;
        final int height, direction;
        final List<CharSequence> sourceRows;
        final boolean leftToRight;

        final TextPaint paint = new TextPaint();
        VerticalTextLayout layout;

        VerticalSpan(
                Spanned text,
                int height,
                int direction,
                boolean leftToRight,
                List<CharSequence> sourceRows) {
            this.sourceRows = sourceRows;
            this.text = text;
            this.height = height;
            this.direction = direction;
            this.leftToRight = leftToRight;
        }

        private VerticalTextLayout layout(Paint source) {
            boolean changed = layout == null || !paint.equalsForTextMeasurement(source);
            if (source instanceof TextPaint textPaint) paint.set(textPaint);
            else {
                paint.set(new TextPaint());
                paint.set(source);
            }
            if (changed) {
                TextOrientation orientation =
                        direction == 2 ? TextOrientation.Upright : TextOrientation.Mixed;
                layout =
                        new VerticalTextLayout(
                                text,
                                0,
                                text.length(),
                                paint,
                                height,
                                orientation,
                                NativeCaptionWindows.justified(text) ? 1 : 0);
            }
            return layout;
        }

        @Override
        public int getSize(
                Paint paint,
                CharSequence ignored,
                int start,
                int end,
                Paint.FontMetricsInt metrics) {
            var vertical = layout(paint);
            if (metrics != null) {
                metrics.top = metrics.ascent = -(int) Math.ceil(vertical.getContentHeight());
                metrics.bottom = metrics.descent = 0;
                metrics.leading = 0;
            }
            return (int) Math.ceil(vertical.getWidth());
        }

        @Override
        public void draw(
                Canvas canvas,
                CharSequence ignored,
                int start,
                int end,
                float x,
                int top,
                int baseline,
                int bottom,
                Paint paint) {
            var vertical = layout(paint);
            int save = canvas.save();
            canvas.translate(x, baseline - vertical.getContentHeight());
            if (direction == 3)
                canvas.rotate(180, vertical.getWidth() / 2, vertical.getContentHeight() / 2);
            try {
                if (!leftToRight) vertical.draw(canvas, vertical.getWidth(), 0);
                else {
                    float left = 0;
                    for (int i = 0; i < vertical.getLineCount(); i++) {
                        float width = vertical.getLineWidth(i);
                        if (vertical.getLineHeight(i) > 0)
                            vertical.drawLine(canvas, i, left + width, 0);
                        left += width;
                    }
                }
            } finally {
                canvas.restoreToCount(save);
            }
        }
    }
}
