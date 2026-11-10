package app.morphe.extension.youtube.patches;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.CharacterStyle;
import android.text.style.MetricAffectingSpan;

import org.xml.sax.Attributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;

/** Native parser/span boundary for paragraph-timed SRV3 ruby. */
public final class NativeCaptionRuby {
    private NativeCaptionRuby() {}

    /**
     * State kept next to the native pen object instead of in injected DEX fields.
     * The native parser and renderer pass the same object to both hooks.
     */
    private static final class State {
        final NativeCaptionAppearance.Style appearance;
        final int horizontalGroup;
        final String annotation;

        State(NativeCaptionAppearance.Style appearance, int horizontalGroup, String annotation) {
            this.appearance = appearance;
            this.horizontalGroup = horizontalGroup;
            this.annotation = annotation;
        }
    }

    /** Keys are native pen objects; entries disappear with the parser table that owns them. */
    private static final Map<Object, State> pens = new WeakHashMap<>();

    /** Enabled only when the matching native parser and span hooks are installed. */
    public static boolean enabled() {
        return false;
    }

    /**
     * Reads a pen declaration. Runs immediately before the native parser stores the pen,
     * so the same instance can be keyed here without adding fields to the pen class.
     */
    public static void onPen(Object pen, Map<?, ?> table, Attributes attributes) {
        if (pen == null || attributes == null) return;
        try {
            Object inherited = null;
            String parentId = attributes.getValue("p");
            if (parentId != null) {
                try {
                    Object parent = table.get(Integer.parseInt(parentId));
                    if (parent != null && parent != pen) {
                        State state = state(parent);
                        if (state != null) inherited = state.appearance;
                    }
                } catch (NumberFormatException ignored) {
                    /* Native fallback. */
                }
            }
            int group = 0;
            String horizontal = attributes.getValue("hg");
            if (horizontal != null) {
                try {
                    group = Integer.parseInt(horizontal) == 1 ? 1 : 0;
                } catch (NumberFormatException invalid) {
                    /* Retain the native default. */
                }
            }
            String ruby = attributes.getValue("morphe-ruby");
            String emphasis = attributes.getValue("morphe-emphasis");
            State state = new State(
                    NativeCaptionAppearance.read(attributes, inherited),
                    group,
                    ruby != null ? ruby : emphasis == null ? null : "emphasis:" + emphasis);
            synchronized (pens) {
                pens.put(pen, state);
            }
        } catch (RuntimeException ignored) {
            // The native reader retains its existing handling of malformed declarations.
        }
    }

    private static State state(Object pen) {
        if (pen == null) return null;
        synchronized (pens) {
            return pens.get(pen);
        }
    }

    /** Authored ruby/emphasis annotation of a native pen, if any. */
    static String annotation(Object pen) {
        State state = state(pen);
        return state == null ? null : state.annotation;
    }

    /** Applies the state of a native pen to text built by the native formatter. */
    public static CharSequence apply(CharSequence text, Object pen) {
        State state = state(pen);
        return state == null ? text : apply(text, state);
    }

    /** Applies a translated descriptor to text already styled by the native formatter. */
    public static CharSequence applyTranslated(CharSequence text, Object pen, String annotation) {
        State state = state(pen);
        if (state == null) return applyAnnotation(text, annotation);
        return NativeCaptionVertical.applyGroup(
                applyAnnotation(
                        NativeCaptionAppearance.apply(text, state.appearance), annotation),
                state.horizontalGroup == 1);
    }

    private static CharSequence apply(CharSequence text, State state) {
        return NativeCaptionVertical.applyGroup(
                applyAnnotation(
                        NativeCaptionAppearance.apply(text, state.appearance), state.annotation),
                state.horizontalGroup == 1);
    }

    /** SRV3/YTSubConverter ruby parts: even roles sit above the base, odd roles below. */
    static boolean annotationUnder(int role) {
        return role == 5 || role == 7 || role == 11 || role == 13;
    }

    /**
     * Applies a translated group whose annotation offsets have been rebased.
     */
    private static CharSequence applyAnnotation(CharSequence text, String annotation) {
        if (!(text instanceof Spanned source) || text.length() == 0 || annotation == null)
            return text;
        try {
            if (annotation.startsWith("emphasis:")) {
                int role = Integer.parseInt(annotation.substring(9));
                if (role < 9 || role > 14) return text;
                return annotated(
                        source,
                        new NativeRubySpan(
                                source,
                                text.length(),
                                text.length(),
                                text.length(),
                                false,
                                role == 12 || role == 13 ? 2 : 1));
            }
            String[] values = annotation.split(",", -1);
            if (values.length != 4) return text;
            int baseEnd = Integer.parseInt(values[0]);
            int start = Integer.parseInt(values[1]);
            int end = Integer.parseInt(values[2]);
            int role = Integer.parseInt(values[3]);
            if (baseEnd < 0 || baseEnd > start || start > end || end > text.length() || role < 0)
                return text;
            boolean under = annotationUnder(role);
            int emphasis = role >= 10 && role <= 14 ? (role == 11 || role == 13 ? 4 : 3) : 0;
            return annotated(
                    source, new NativeRubySpan(source, baseEnd, start, end, under, emphasis));
        } catch (NumberFormatException malformed) {
            return text;
        }
    }

    private static CharSequence annotated(Spanned source, NativeRubySpan annotation) {
        var result = new SpannableStringBuilder(source);
        for (CharacterStyle style : result.getSpans(0, result.length(), CharacterStyle.class))
            if (!(style instanceof MetricAffectingSpan)) result.removeSpan(style);
        // Hard breaks inside one replacement must not split the outer native paragraph.
        // The inner layout retains them; sourceText restores the authored characters.
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029')
                result.replace(i, i + 1, "\u2060");
        }
        result.setSpan(annotation, 0, result.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // A replacement masks strong characters during Android bidi analysis. A zero-width
        // guard outside its range restores the group's direction in each native paragraph.
        char guard = directionGuard(annotation.baseText());
        if (guard != 0) {
            result.insert(0, Character.toString(guard));
            result.setSpan(new DirectionGuard(), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return result;
    }

    /** Identifies guards we added, without deleting authored bidi controls. */
    private static final class DirectionGuard {}

    private static char directionGuard(CharSequence text) {
        for (int i = 0; i < text.length(); ) {
            int codePoint = Character.codePointAt(text, i);
            byte direction = Character.getDirectionality(codePoint);
            if (direction == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return '\u200e';
            if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return '\u200f';
            i += Character.charCount(codePoint);
        }
        return 0;
    }

    /** Restores authored characters/styles, including breaks masked by annotation boxes. */
    public static CharSequence sourceText(CharSequence text) {
        return restoreText(text, null);
    }

    static CharSequence verticalText(CharSequence text, int direction) {
        return restoreText(
                text,
                direction == 2
                        ? androidx.text.vertical.TextOrientation.Upright
                        : androidx.text.vertical.TextOrientation.Mixed);
    }

    private static CharSequence restoreText(
            CharSequence text, androidx.text.vertical.TextOrientation orientation) {
        if (!(text instanceof Spanned spans)) return text;
        var edits = new ArrayList<Object>();
        edits.addAll(Arrays.asList(spans.getSpans(0, text.length(), DirectionGuard.class)));
        edits.addAll(Arrays.asList(spans.getSpans(0, text.length(), NativeRubySpan.class)));
        if (edits.isEmpty()) return text;
        edits.sort(Comparator.comparingInt(spans::getSpanStart).reversed());
        var result = new SpannableStringBuilder(text);
        for (Object edit : edits) {
            int start = spans.getSpanStart(edit), end = spans.getSpanEnd(edit);
            if (edit instanceof NativeRubySpan annotation)
                result.replace(
                        start,
                        end,
                        orientation != null
                                ? annotation.verticalText(orientation)
                                : annotation.originalText());
            else result.delete(start, end);
        }
        return result;
    }
}
