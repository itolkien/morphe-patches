package app.morphe.extension.youtube.patches;

import android.text.Layout;
import android.text.TextPaint;
import android.view.View;

import androidx.text.vertical.TextOrientation;
import androidx.text.vertical.VerticalTextLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Applies declared roll-up extents to the native window, retaining its clock and placement. */
public final class NativeCaptionWindowLayout {
    private static final Map<View, State> states = new WeakHashMap<>();

    private static final class State {
        Object cue;
        NativeCaptionWindowData.Definition style, position;
        List<CharSequence> full, visible;
        int widthSpec, heightSpec;
        float sticky;
        TextPaint paint;
        boolean vertical;
    }

    private NativeCaptionWindowLayout() {}

    /** Restore only our previous geometry transformation before the native text adapter runs. */
    public static void restore(View view) {
        State state = states.get(view);
        if (state == null
                || !(view instanceof NativeCaptionWindows.Host host)
                || !(view instanceof StableCaptionBlocks.Window window)) return;
        var source = host.patch_getWindowSource();
        if (source == null
                || source.style() != state.style
                || source.position() != state.position) {
            states.remove(view);
            return;
        }
        if (window.patch_getCaptionCue() == state.cue && state.full != null) {
            replace(window.patch_getFullCaptionLines(), state.full);
            replace(window.patch_getVisibleCaptionLines(), state.visible);
        }
        state.full = state.visible = null;
    }

    static void prepare(View view, int widthSpec, int heightSpec) {
        if (!(view instanceof NativeCaptionWindows.Host host)
                || !(view instanceof StableCaptionBlocks.Window window)) return;
        var source = host.patch_getWindowSource();
        if (source == null
                || source.style() == null
                || !Integer.valueOf(2).equals(source.style().integer("mh"))) {
            states.remove(view);
            return;
        }
        State state = states.computeIfAbsent(view, ignored -> new State());
        if (state.style != source.style()
                || state.position != source.position()
                || state.widthSpec != widthSpec
                || state.heightSpec != heightSpec) {
            state.sticky = 0;
            state.paint = null;
        }
        state.style = source.style();
        state.position = source.position();
        state.vertical = source.vertical();
        state.cue = window.patch_getCaptionCue();
        state.widthSpec = widthSpec;
        state.heightSpec = heightSpec;
        List<CharSequence> full = window.patch_getFullCaptionLines(),
                visible = window.patch_getVisibleCaptionLines();
        state.full = new ArrayList<>(full);
        state.visible = new ArrayList<>(visible);
        Integer count = source.rows();
        if (count == null || count < 0) return;
        int active = visible.size();
        while (active > 0 && visible.get(active - 1).length() == 0) active--;
        int start = Math.min(Math.max(0, active - count), Math.min(full.size(), visible.size()));
        int end = (int) Math.min(full.size(), (long) start + count);
        replace(full, state.full.subList(Math.min(start, full.size()), end));
        replace(
                visible,
                state.visible.subList(
                        Math.min(start, visible.size()), Math.min(end, visible.size())));
    }

    public static void afterMeasure(View view) {
        State state = states.get(view);
        if (state == null
                || !(view instanceof StableCaptionBlocks.Window window)
                || !(view instanceof NativeCaptionWindows.Host host)) return;
        var source = host.patch_getWindowSource();
        if (source == null || source.style() != state.style || source.position() != state.position)
            return;
        if (!(window.patch_getCaptionMeasureRow() instanceof StableCaptionBlocks.Row row)) return;
        var layout = row.patch_getCaptionLayout();
        if (layout == null) return;
        TextPaint paint = layout.getPaint();
        if (state.paint == null || !state.paint.equalsForTextMeasurement(paint)) {
            state.sticky = 0;
            state.paint = new TextPaint(paint);
        }
        Integer cells = source.columns();
        float minimum = 0;
        if (cells != null && cells > 0) {
            float cell =
                    source.vertical()
                            ? new VerticalTextLayout(
                                            "–",
                                            0,
                                            1,
                                            paint,
                                            Math.max(1, View.MeasureSpec.getSize(state.heightSpec)),
                                            Integer.valueOf(2).equals(source.style().integer("pd"))
                                                    ? TextOrientation.Upright
                                                    : TextOrientation.Mixed)
                                    .getContentHeight()
                            : Layout.getDesiredWidth("–", paint);
            minimum = cell * cells;
        }
        int width = view.getMeasuredWidth(), height = view.getMeasuredHeight();
        if (!source.vertical()) width = CaptionViewportPatch.captionTextWidth(width);
        int padding =
                source.vertical()
                        ? view.getPaddingTop() + view.getPaddingBottom()
                        : view.getPaddingLeft() + view.getPaddingRight();
        if (row instanceof View nativeRow)
            padding +=
                    source.vertical()
                            ? nativeRow.getPaddingTop() + nativeRow.getPaddingBottom()
                            : nativeRow.getPaddingLeft() + nativeRow.getPaddingRight();
        state.sticky =
                Math.max(
                        state.sticky,
                        Math.max(source.vertical() ? height : width, minimum + padding));
        int spec = source.vertical() ? state.heightSpec : state.widthSpec;
        if (View.MeasureSpec.getMode(spec) == View.MeasureSpec.UNSPECIFIED) {
            View root = view.getRootView();
            int available = source.vertical() ? root.getMeasuredHeight() : root.getMeasuredWidth();
            available = Math.max(available, source.vertical() ? height : width);
            state.sticky = Math.min(state.sticky, available);
        }
        int extent = (int) Math.min(Integer.MAX_VALUE, Math.ceil(state.sticky));
        if (source.vertical()) height = View.resolveSize(extent, state.heightSpec);
        else width = View.resolveSize(extent, state.widthSpec);
        window.patch_setCaptionMeasuredSize(width, height);
    }

    private static void replace(List<CharSequence> target, List<CharSequence> values) {
        var copy = new ArrayList<>(values);
        target.clear();
        target.addAll(copy);
    }
}
