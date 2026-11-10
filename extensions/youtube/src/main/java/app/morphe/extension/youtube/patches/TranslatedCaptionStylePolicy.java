package app.morphe.extension.youtube.patches;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Selects readable, self-contained pens without rewriting caption events. */
final class TranslatedCaptionStylePolicy {
    private TranslatedCaptionStylePolicy() {}

    static Set<Integer> visiblePens(Document document) {
        if (document == null) return Set.of();
        Element head = null;
        for (Element child : CaptionXml.children(document.getDocumentElement())) {
            if (!child.getTagName().equals("head")) continue;
            if (head != null) return Set.of();
            head = child;
        }
        if (head == null) return Set.of();
        Map<Integer, Element> pens = new LinkedHashMap<>();
        Set<Integer> invalid = new HashSet<>(), visible = new HashSet<>();
        for (Element pen : CaptionXml.children(head)) {
            if (!pen.getTagName().equals("pen")) continue;
            try {
                int id = Integer.parseInt(pen.getAttribute("id"));
                if (id < 0) continue;
                if (pens.putIfAbsent(id, pen) != null) invalid.add(id);
            } catch (NumberFormatException ignored) {
                // Invalid IDs cannot be selected by the native reader.
            }
        }
        Map<Integer, Map<String, String>> resolved = new HashMap<>();
        for (int id : pens.keySet()) {
            var fields = NativeCaptionPenStyles.resolve(id, pens, resolved, invalid);
            if (fields == null) continue;
            try {
                int opacity = number(fields, "fo", 255);
                if (opacity < 1 || opacity > 255) continue;
                // ytt.ytt: sz cannot be negative; 0 is the minimum at a real 75%.
                if (number(fields, "sz", 100) < 0) continue;
                int foreground = color(fields, "fc", "#FFFFFF");
                int background = color(fields, "bc", "#000000");
                int backgroundOpacity = number(fields, "bo", 255);
                if (backgroundOpacity < 0 || backgroundOpacity > 255) continue;
                if (backgroundOpacity == 255 && foreground == background) continue;
                if (foreground == 0 && (background == 0 || backgroundOpacity != 255)) continue;
                visible.add(id);
            } catch (IllegalArgumentException ignored) {
                // Unsupported pen metadata must not bypass the existing translation repair.
            }
        }
        return Set.copyOf(visible);
    }

    private static int number(Map<String, String> fields, String attribute, int fallback) {
        String value = fields.get(attribute);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static int color(Map<String, String> fields, String attribute, String fallback) {
        String value = fields.getOrDefault(attribute, fallback);
        if (!value.matches("#[0-9a-fA-F]{6}"))
            throw new IllegalArgumentException("Unsupported color");
        return Integer.parseInt(value.substring(1), 16);
    }
}
