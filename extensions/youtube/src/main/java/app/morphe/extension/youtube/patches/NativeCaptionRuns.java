package app.morphe.extension.youtube.patches;

import android.text.SpannableStringBuilder;
import android.text.Spanned;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Carries native text-group identity through full/visible native timeline samples. */
public final class NativeCaptionRuns {
    public interface Builder {
        Builder patch_createCaptionRenderer();

        CharSequence patch_renderCaptionRun(String text, Object pen, String annotation);
    }

    private record Marker(NativeCaptionRunPlan.Group group) {}

    private static final AtomicLong ids = new AtomicLong();
    private static final java.util.Map<Builder, Builder> renderers = new java.util.WeakHashMap<>();

    /** Retained groups share native configuration without retaining source parser tables. */
    private static Builder rendererFor(Builder source) {
        synchronized (renderers) {
            return renderers.computeIfAbsent(source, Builder::patch_createCaptionRenderer);
        }
    }

    private static final NativeCaptionRunPlan.Renderer PLAIN = (text, annotation) -> text;

    private NativeCaptionRuns() {}

    /** Injection point after the native builder has applied source pen styles. */
    public static CharSequence mark(CharSequence text, Object builder, Object value) {
        if (!CaptionTracks.captureNativeRunStyles()
                || !TranslatedCaptionPensPatch.isOriginalStyleSource()) return text;
        if (!(text instanceof Spanned)
                || text.length() == 0
                || !(builder instanceof Builder nativeBuilder)
                || value == null) return text;
        Builder renderer = rendererFor(nativeBuilder);
        String source = NativeCaptionRuby.sourceText(text).toString();
        String annotation = NativeCaptionRuby.annotation(value);
        List<String> parts = List.of(source);
        if (annotation != null && !annotation.startsWith("emphasis:")) {
            try {
                String[] fields = annotation.split(",", -1);
                int base = Integer.parseInt(fields[0]),
                        start = Integer.parseInt(fields[1]),
                        end = Integer.parseInt(fields[2]);
                if (fields.length == 4
                        && base >= 0
                        && base <= start
                        && start <= end
                        && end <= source.length())
                    parts =
                            List.of(
                                    source.substring(0, base),
                                    source.substring(base, start),
                                    source.substring(start, end),
                                    source.substring(end));
            } catch (NumberFormatException | IndexOutOfBoundsException malformed) {
                // Retain native handling when an annotation descriptor is unavailable.
            }
        }
        var group =
                new NativeCaptionRunPlan.Group(
                        "r" + ids.incrementAndGet(),
                        parts,
                        annotation,
                        (translated, descriptor) ->
                                renderer.patch_renderCaptionRun(translated, value, descriptor));
        var result = new SpannableStringBuilder(text);
        result.setSpan(new Marker(group), 0, result.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return result;
    }

    @Nullable
    public static NativeCaptionRunPlan capture(CharSequence text) {
        if (!(text instanceof Spanned spans)) return null;
        Marker[] markers = spans.getSpans(0, text.length(), Marker.class);
        if (markers.length == 0) return null;
        Arrays.sort(markers, Comparator.comparingInt(spans::getSpanStart));
        var groups = new ArrayList<NativeCaptionRunPlan.Group>();
        int cursor = 0;
        String previous = "start";
        for (Marker marker : markers) {
            int start = spans.getSpanStart(marker), end = spans.getSpanEnd(marker);
            if (start < cursor || end <= start) return null;
            if (start > cursor)
                groups.add(gap(previous, text.subSequence(cursor, start).toString()));
            groups.add(marker.group);
            previous = marker.group.id();
            cursor = end;
        }
        if (cursor < text.length())
            groups.add(gap(previous, text.subSequence(cursor, text.length()).toString()));
        return new NativeCaptionRunPlan(groups);
    }

    private static NativeCaptionRunPlan.Group gap(String previous, String text) {
        return new NativeCaptionRunPlan.Group("gap:" + previous, List.of(text), null, PLAIN);
    }

    public static CharSequence render(
            NativeCaptionRunPlan.Result translated, List<CharSequence> visible) {
        Set<String> active = new HashSet<>();
        for (CharSequence line : visible) {
            NativeCaptionRunPlan plan = capture(line);
            if (plan != null) for (var group : plan.groups) active.add(group.id());
        }
        var result = new SpannableStringBuilder();
        for (CharSequence part : translated.render(active)) result.append(part);
        return result;
    }
}
