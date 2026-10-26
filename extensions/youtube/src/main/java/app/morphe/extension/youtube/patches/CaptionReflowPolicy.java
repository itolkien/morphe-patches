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
        boolean hasScrollableWindow = false;
        for (Object item : input) {
            if (!(item instanceof Cue cue)) return null;
            if (!cue.patch_isVisible()) continue;
            CharSequence text = cue.patch_getText();
            if (text == null || text.length() == 0) continue;
            hasScrollableWindow |= cue.patch_isScrollable();
            // apoo time is the sampling instant, not the source cue start/end.
            // Native apnt.c has already removed expired text before this policy runs.
            if (time != null && time.longValue() != cue.patch_getTime()) return null;
            time = cue.patch_getTime();
            length += text.length();
            if (length > 4096) return null;
            visible.add(cue);
        }
        // A lone roll-up window should keep YouTube's native behavior. When multiple translated
        // windows are concurrently visible, however, retaining their separate anchors makes the
        // previous spoken phrase linger above the current one (as on live auto-caption streams).
        if (hasScrollableWindow && visible.size() < 2) return null;
        visible.sort(Comparator.comparingInt(Cue::patch_getY)
                .thenComparingInt(Cue::patch_getX).thenComparingInt(Cue::patch_getId));
        return visible;
    }
}
