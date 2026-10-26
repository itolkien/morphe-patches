package app.morphe.extension.youtube.patches;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure selection policy; native timelines still own cue timing and expiration. */
public final class CaptionReflowPolicy {
    private CaptionReflowPolicy() {}

    /** Added to native cue objects by the version-scoped experimental patch. */
    public interface Cue {
        int patch_getId();
        long patch_getTime();
        int patch_getX();
        int patch_getY();
        boolean patch_isVisible();
        boolean patch_isScrollable();
        CharSequence patch_getText();
        Object patch_withText(CharSequence text);
    }

    /** null means preserve the native frame unchanged, including unknown future layouts. */
    public static List<Cue> select(List<?> input, boolean translated, boolean enabled) {
        if (!translated || !enabled || input == null || input.size() > 8) return null;
        List<Cue> visible = new ArrayList<>();
        Long time = null;
        long length = 0;
        for (Object item : input) {
            if (!(item instanceof Cue cue)) return null;
            if (!cue.patch_isVisible()) continue;
            CharSequence text = cue.patch_getText();
            if (text == null || text.length() == 0) continue;
            // apoo time is the sampling instant, not the source cue start/end.
            // Native apnt.c has already removed expired text before this policy runs.
            if (time != null && time.longValue() != cue.patch_getTime()) return null;
            time = cue.patch_getTime();
            length += text.length();
            if (length > 4096) return null;
            visible.add(cue);
        }
        // Only combine simultaneous translated windows. A single caption window, even one with
        // a source line break, already has its own native size, placement and wrapping policy.
        // Replacing it would unnecessarily override publisher positioning and typography.
        if (visible.size() < 2) return null;
        visible.sort(Comparator.comparingInt(Cue::patch_getY)
                .thenComparingInt(Cue::patch_getX).thenComparingInt(Cue::patch_getId));
        return visible;
    }
}
