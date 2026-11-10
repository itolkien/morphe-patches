package app.morphe.extension.youtube.patches;

import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextDirectionHeuristics;
import android.text.style.AlignmentSpan;
import android.view.View;

import org.xml.sax.Attributes;

import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Binds authored metadata to YouTube's native caption windows and cue lifecycle. */
public final class NativeCaptionWindows {
    public interface Window {
        /** Copies the native position before changing its compiler identity. */
        void patch_setNativeWindowId(int id);
    }

    public interface Parser {
        Map<Integer, ?> patch_getWindowDefinitions();

        Map<Integer, ?> patch_getWindowPositions();

        Map<Integer, ?> patch_getWindowStyles();
    }

    public interface Style {
        NativeCaptionWindowData.Definition patch_getWindowStyle();

        void patch_setWindowStyle(NativeCaptionWindowData.Definition definition);
    }

    public interface Paragraph {
        Object patch_getWindowStyle();

        Object patch_getWindowPosition();
    }

    public interface Position {
        NativeCaptionWindowData.Definition patch_getWindowPosition();

        void patch_setWindowPosition(NativeCaptionWindowData.Definition definition);
    }

    public interface Settings {
        NativeCaptionWindowData.Source patch_getWindowSource();

        void patch_setWindowSource(NativeCaptionWindowData.Source source);
    }

    public interface Host {
        NativeCaptionWindowData.Source patch_getWindowSource();
    }

    /** View identity is stable; the native class carries no preference state. */
    private static final class BackgroundState {
        private NativeCaptionWindowData.Definition style;
        private Drawable previous, applied;
        private Integer color;
    }

    private static final Map<View, BackgroundState> backgrounds = new WeakHashMap<>();
    /** Parser identity is stable for one captured document; no native field is needed. */
    private static final Map<Object, Bindings> parserBindings = new WeakHashMap<>();

    private static final class Bindings {
        private final Map<Integer, Integer> logicalIds = new HashMap<>();
        private int next = 1;

        private int identity(int logical, Map<Integer, ?> positions) {
            Integer existing = logicalIds.get(logical);
            if (existing != null) return existing;
            // SRV3 declares positions in the header, before its body windows. Leave their
            // IDs and the implicit default window available to paragraphs without a <w>.
            while (positions.containsKey(next)) next++;
            int assigned = next++;
            logicalIds.put(logical, assigned);
            return assigned;
        }
    }

    private static final Object JUSTIFIED = new Object();

    private record AuthoredAlignment(Layout.Alignment value) implements AlignmentSpan {
        @Override
        public Layout.Alignment getAlignment() {
            return value;
        }
    }

    static boolean justified(CharSequence text) {
        return text instanceof Spanned spans && spans.getSpanStart(JUSTIFIED) >= 0;
    }

    static void prepareRows(
            StableCaptionBlocks.Window window, NativeCaptionWindowData.Source source) {
        NativeCaptionAppearance.prepareRows(window);
        var style = source == null ? null : source.style();
        Integer alignment = style == null ? null : style.integer("ju");
        if (alignment == null) return;
        if (alignment == 3) {
            for (List<CharSequence> rows :
                    List.of(
                            window.patch_getFullCaptionLines(),
                            window.patch_getVisibleCaptionLines())) {
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i).length() == 0) continue;
                    var text = NativeCaptionPreferences.copy(rows.get(i));
                    text.setSpan(JUSTIFIED, 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    rows.set(i, text);
                }
            }
        } else if (Integer.valueOf(2).equals(style.integer("mh"))
                && alignment >= 0
                && alignment <= 2) {
            alignRows(window.patch_getVisibleCaptionLines(), alignment, style.integer("pd"));
            alignRows(window.patch_getFullCaptionLines(), alignment, style.integer("pd"));
        }
    }

    private NativeCaptionWindows() {}

    /** Follows the native cue setter; source fill never owns placement or player controls. */
    public static void onCue(Object window) {
        if (!(window instanceof View view)
                || !(window instanceof Host host)
                || !(window instanceof StableCaptionBlocks.Window rows)) return;
        var source = host.patch_getWindowSource();
        var style = source == null ? null : source.style();
        prepareRows(rows, source);
        BackgroundState state = backgrounds.get(view);
        Drawable current = view.getBackground();
        Integer color =
                NativeCaptionPreferences.window(view, NativeCaptionWindowData.background(style));
        if (state != null
                && state.style == style
                && current == state.applied
                && java.util.Objects.equals(color, state.color)) return;
        if (color == null) {
            if (state != null) {
                if (current == state.applied) changeBackground(view, state.previous, null);
                backgrounds.remove(view);
            }
            return;
        }
        if (state == null) {
            state = new BackgroundState();
            state.previous = current;
            backgrounds.put(view, state);
        } else if (current != state.applied) {
            state.previous = current;
        }
        state.style = style;
        if (current == state.applied && color.equals(state.color)) return;
        changeBackground(view, null, color);
        state.applied = view.getBackground();
        state.color = color;
    }

    private static void alignRows(List<CharSequence> rows, int alignment, Integer direction) {
        for (int i = 0; i < rows.size(); i++) {
            CharSequence text = rows.get(i);
            if (text.length() == 0) continue;
            boolean rtl =
                    direction != null && (direction == 0 || direction == 1)
                            ? direction == 1
                            : TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(text, 0, text.length());
            Layout.Alignment value =
                    alignment == 2
                            ? Layout.Alignment.ALIGN_CENTER
                            : ((alignment == 0) != rtl
                                    ? Layout.Alignment.ALIGN_NORMAL
                                    : Layout.Alignment.ALIGN_OPPOSITE);
            var styled = NativeCaptionPreferences.copy(text);
            for (AuthoredAlignment old :
                    styled.getSpans(0, styled.length(), AuthoredAlignment.class))
                styled.removeSpan(old);
            styled.setSpan(
                    new AuthoredAlignment(value),
                    0,
                    styled.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            rows.set(i, styled);
        }
    }

    private static void changeBackground(View view, Drawable drawable, Integer color) {
        int left = view.getPaddingLeft(), top = view.getPaddingTop();
        int right = view.getPaddingRight(), bottom = view.getPaddingBottom();
        if (color == null) view.setBackground(drawable);
        else {
            // Detach before calling YouTube's native override: View.setBackgroundColor(0)
            // can otherwise mutate the saved ColorDrawable instead of replacing it.
            view.setBackground(null);
            if ((color >>> 24) != 0) view.setBackgroundColor(color);
        }
        if (left != view.getPaddingLeft()
                || top != view.getPaddingTop()
                || right != view.getPaddingRight()
                || bottom != view.getPaddingBottom()) view.setPadding(left, top, right, bottom);
    }

    public static void onPosition(Deque<?> stack, Attributes attributes) {
        if (!(stack.peekFirst() instanceof Parser parser)) return;
        if (!(parser.patch_getWindowPositions().get(id(attributes)) instanceof Position position))
            return;
        position.patch_setWindowPosition(
                NativeCaptionWindowData.resolve(
                        NativeCaptionWindowData.Kind.POSITION, null, attributes));
    }

    public static void bindSettings(Object settings, Object paragraph) {
        if (!(settings instanceof Settings target)) return;
        NativeCaptionWindowData.Definition style = null, position = null;
        if (paragraph instanceof Paragraph data) {
            if (data.patch_getWindowStyle() instanceof Style value)
                style = value.patch_getWindowStyle();
            if (data.patch_getWindowPosition() instanceof Position value)
                position = value.patch_getWindowPosition();
        }
        if (style != null && style.fields().isEmpty()) style = null;
        if (position != null && position.fields().isEmpty()) position = null;
        target.patch_setWindowSource(
                style == null && position == null
                        ? null
                        : new NativeCaptionWindowData.Source(style, position));
    }

    public static boolean sameSettings(Object first, Object second) {
        return first == second
                || first instanceof Settings a
                        && second instanceof Settings b
                        && Objects.equals(a.patch_getWindowSource(), b.patch_getWindowSource());
    }

    public static int settingsHash(int nativeHash, Object settings) {
        var source = settings instanceof Settings value ? value.patch_getWindowSource() : null;
        return source == null ? nativeHash : 31 * nativeHash + source.hashCode();
    }

    public static void onStyle(Deque<?> stack, Attributes attributes) {
        if (!(stack.peekFirst() instanceof Parser parser)) return;
        if (!(parser.patch_getWindowStyles().get(id(attributes)) instanceof Style style)) return;
        style.patch_setWindowStyle(
                NativeCaptionWindowData.resolve(
                        NativeCaptionWindowData.Kind.STYLE, null, attributes));
    }

    public static int alignment(Object paragraph) {
        if (!(paragraph instanceof Paragraph data)
                || !(data.patch_getWindowStyle() instanceof Style style)) return 0;
        var definition = style.patch_getWindowStyle();
        Integer justification = definition == null ? null : definition.integer("ju");
        if (justification == null) return 0;
        return switch (justification) {
            case 0 -> 1; // Native LEFT.
            case 1 -> 3; // Native RIGHT.
            case 2 -> 2; // Native CENTER.
            default -> 0;
        };
    }

    public static void onWindow(Deque<?> stack, Attributes attributes) {
        if (!(stack.peekFirst() instanceof Parser parser)) return;
        int id = id(attributes);
        if (!(parser.patch_getWindowDefinitions().get(id) instanceof Window window)) return;
        Bindings bindings;
        synchronized (parserBindings) {
            bindings = parserBindings.computeIfAbsent(parser, ignored -> new Bindings());
        }
        window.patch_setNativeWindowId(bindings.identity(id, parser.patch_getWindowPositions()));
    }

    private static int id(Attributes attributes) {
        try {
            return Integer.parseInt(attributes.getValue("id"));
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }
}
