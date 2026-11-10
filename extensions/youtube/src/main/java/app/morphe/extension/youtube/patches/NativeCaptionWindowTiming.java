package app.morphe.extension.youtube.patches;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Encodes explicit window lifetimes into native paragraph events, without a playback clock. */
final class NativeCaptionWindowTiming {
    private static final class Window {
        final long start;
        long end;
        final List<Element> paragraphs = new ArrayList<>();

        Window(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private NativeCaptionWindowTiming() {}

    static boolean needed(Document document) {
        var windows = document.getElementsByTagName("w");
        for (int i = 0; i < windows.getLength(); i++) {
            Element window = (Element) windows.item(i);
            if (window.hasAttribute("t") || window.hasAttribute("d")) return true;
        }
        return false;
    }

    static boolean normalize(Document document) {
        if (!needed(document)) return false;
        Map<Integer, Window> current = new HashMap<>();
        List<Window> windows = new ArrayList<>();
        for (Element body : CaptionXml.children(document.getDocumentElement())) {
            if (!body.getTagName().equals("body")) continue;
            for (Element element : CaptionXml.children(body)) {
                Integer id = CaptionXml.nativeId(
                        element.getAttribute(element.getTagName().equals("w") ? "id" : "w"));
                if (id == null) continue;
                if (element.getTagName().equals("w")) {
                    Long start = number(element, "t", 0), duration = number(element, "d", 0);
                    if (start == null || duration == null) {
                        current.remove(id);
                        continue;
                    }
                    Window previous = current.get(id);
                    if (previous != null && element.hasAttribute("t"))
                        previous.end = Math.min(previous.end, start);
                    Window window =
                            new Window(
                                    start, duration == 0 ? Long.MAX_VALUE : add(start, duration));
                    current.put(id, window);
                    windows.add(window);
                } else if (element.getTagName().equals("p") && current.containsKey(id)) {
                    current.get(id).paragraphs.add(element);
                }
            }
        }
        boolean changed = false;
        for (Window window : windows)
            for (Element paragraph : window.paragraphs) {
                Long time = number(paragraph, "t", 0), duration = number(paragraph, "d", 0);
                if (time == null || duration == null) continue;
                long start = Math.max(time, window.start);
                long end =
                        Math.min(window.end, duration == 0 ? Long.MAX_VALUE : add(time, duration));
                if (end <= start) {
                    paragraph.getParentNode().removeChild(paragraph);
                    changed = true;
                    continue;
                }
                boolean clippedStart = start != time;
                boolean clippedEnd =
                        end != Long.MAX_VALUE && (duration == 0 || end != add(time, duration));
                if (!clippedStart && !clippedEnd) continue;
                long shift = start - time;
                if (clippedStart) paragraph.setAttribute("t", Long.toString(start));
                if (end != Long.MAX_VALUE) paragraph.setAttribute("d", Long.toString(end - start));
                for (Element segment : CaptionXml.children(paragraph)) {
                    if (!segment.getTagName().equals("s")) continue;
                    Long offset = number(segment, "t", 0);
                    if (offset == null) continue;
                    long rebased = Math.max(0, offset - shift);
                    if (end != Long.MAX_VALUE && rebased >= end - start)
                        paragraph.removeChild(segment);
                    else if (segment.hasAttribute("t") && shift != 0)
                        segment.setAttribute("t", Long.toString(rebased));
                }
                changed = true;
            }
        return changed;
    }

    private static Long number(Element element, String name, long fallback) {
        if (!element.hasAttribute(name)) return fallback;
        try {
            long value = Long.parseLong(element.getAttribute(name));
            return value >= 0 ? value : null;
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static long add(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }
}
