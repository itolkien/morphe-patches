package app.morphe.extension.youtube.patches;

import android.graphics.EmbossMaskFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.text.style.UpdateAppearance;
import android.view.View;

import org.xml.sax.Attributes;

/** Fills gaps in the native pen formatter using ordinary Android text spans. */
public final class NativeCaptionAppearance {
    private NativeCaptionAppearance() {}

    record Style(
            Integer background, Integer opacity, Integer size, Integer edge, Integer edgeColor) {}

    static Style read(Attributes attributes, Object inherited) {
        Style parent =
                inherited instanceof Style value ? value : new Style(null, null, null, null, null);
        return new Style(
                color(attributes, "bc", parent.background),
                integer(attributes, "bo", parent.opacity),
                integer(attributes, "sz", parent.size),
                integer(attributes, "et", parent.edge),
                color(attributes, "ec", parent.edgeColor));
    }

    private static Integer integer(Attributes a, String key, Integer fallback) {
        try {
            String value = a.getValue(key);
            return value == null ? fallback : Integer.valueOf(value);
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static Integer color(Attributes a, String key, Integer fallback) {
        String value = a.getValue(key);
        if (value == null || !value.matches("#[0-9a-fA-F]{6}")) return fallback;
        return 0xFF000000 | Integer.parseInt(value.substring(1), 16);
    }

    /** Native reader's sz mapping; used only to find the span it inserted. */
    static float nativeScale(int size) {
        return size < 100 ? size / 100f : .75f + size / 400f;
    }

    /** SRV3 authored size: sz=0 is 75%, sz=100 is 100%, sz=300 is 150%. */
    static float authoredScale(int size) {
        return .75f + size / 400f;
    }

    public static CharSequence apply(CharSequence text, Object appearance) {
        if (!(text instanceof Spanned)
                || text.length() == 0
                || !(appearance instanceof Style style)) return text;
        if (style.background == null
                && style.opacity == null
                && (style.size == null || style.size == 100)
                && !Integer.valueOf(1).equals(style.edge)
                && !Integer.valueOf(2).equals(style.edge)) return text;
        var result = new SpannableStringBuilder(text);
        if (style.background != null || style.opacity != null) {
            int opacity = style.opacity == null ? 191 : Math.max(0, Math.min(255, style.opacity));
            int color = style.background == null ? 0x080808 : style.background & 0xFFFFFF;
            result.setSpan(
                    new RunBackground(opacity << 24 | color),
                    0,
                    result.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (style.size != null && style.size >= 0 && style.size != 100) {
            float nativeScale = nativeScale(style.size);
            for (var span : result.getSpans(0, result.length(), RelativeSizeSpan.class)) {
                if (Math.abs(span.getSizeChange() - nativeScale) < .00001f) {
                    result.removeSpan(span);
                    break;
                }
            }
            result.setSpan(
                    new RelativeSizeSpan(authoredScale(style.size)),
                    0,
                    result.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (Integer.valueOf(1).equals(style.edge) || Integer.valueOf(2).equals(style.edge)) {
            result.setSpan(
                    new Edge(style.edge, style.edgeColor),
                    0,
                    result.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return result;
    }

    private static final class RunBackground extends BackgroundColorSpan {
        RunBackground(int color) {
            super(color);
        }
    }

    private static boolean hasBackground(CharSequence text) {
        if (!(text instanceof Spanned spans)) return false;
        if (spans.getSpans(0, text.length(), RunBackground.class).length != 0) return true;
        for (var ruby : spans.getSpans(0, text.length(), NativeRubySpan.class)) {
            if (ruby.originalText()
                            .getSpans(0, ruby.originalText().length(), RunBackground.class)
                            .length
                    != 0) return true;
        }
        return false;
    }

    static void prepareRows(StableCaptionBlocks.Window window) {
        boolean authored =
                window.patch_getFullCaptionLines().stream()
                        .anyMatch(NativeCaptionAppearance::hasBackground);
        if (!authored) return;
        for (var rows :
                java.util.List.of(
                        window.patch_getFullCaptionLines(),
                        window.patch_getVisibleCaptionLines())) {
            for (int i = 0; i < rows.size(); i++) {
                CharSequence row = rows.get(i);
                if (!(row instanceof Spanned spans)) continue;
                SpannableStringBuilder styled = null;
                for (int start = 0; start < row.length(); ) {
                    int end = spans.nextSpanTransition(start, row.length(), CharacterStyle.class);
                    boolean covered =
                            spans.getSpans(start, end, BackgroundColorSpan.class).length != 0;
                    for (var ruby : spans.getSpans(start, end, NativeRubySpan.class))
                        covered |= hasBackground(ruby.originalText());
                    if (!covered) {
                        if (styled == null) styled = NativeCaptionPreferences.copy(row);
                        styled.setSpan(
                                new DefaultBackground(),
                                start,
                                end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    start = end;
                }
                if (styled != null) rows.set(i, styled);
            }
        }
    }

    private static final class DefaultBackground extends BackgroundColorSpan {
        DefaultBackground() {
            super(0xBF080808);
        }
    }

    /** Per-run backgrounds replace the native first-pen background for the entire row. */
    public static Integer background(Object view) {
        if (!(view instanceof StableCaptionBlocks.Window window)) return null;
        if (view instanceof View nativeView) {
            Integer chosen = NativeCaptionPreferences.profile(nativeView).background();
            if (chosen != null) return chosen;
        }
        for (CharSequence row : window.patch_getFullCaptionLines()) {
            if (hasBackground(row)) return 0;
        }
        return null;
    }

    /** Reuses the platform's native shadow/emboss effects; it does not draw a separate row. */
    private static final class Edge extends CharacterStyle
            implements UpdateAppearance, NativeCaptionPreferences.PaintEffect {
        private final int type;
        private final Integer color;

        Edge(int type, Integer color) {
            this.type = type;
            this.color = color;
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            if (type == 1) {
                float offset = Math.max(1, paint.getTextSize() / 16);
                paint.setShadowLayer(
                        offset,
                        offset,
                        offset,
                        color == null ? (paint.getAlpha() << 24 | 0x222222) : color);
            } else {
                paint.setMaskFilter(new EmbossMaskFilter(new float[] {1, 1, 1}, 0, 9, 2));
                if (color != null) {
                    float offset = Math.max(1, paint.getTextSize() / 16);
                    paint.setShadowLayer(offset, offset, offset, color);
                }
            }
        }
    }
}
