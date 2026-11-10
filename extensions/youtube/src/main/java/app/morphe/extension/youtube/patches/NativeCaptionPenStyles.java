package app.morphe.extension.youtube.patches;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Makes inherited fields consumed by the supported native parser explicit. Caption events stay
 * intact; unknown parent fields are not interpreted.
 */
final class NativeCaptionPenStyles {
    private static final Set<String> FIELDS =
            Set.of("b", "i", "u", "of", "sz", "et", "ec", "fs", "fc", "fo", "bc", "bo", "hg");

    private NativeCaptionPenStyles() {}

    static byte[] prepare(byte[] input, Document document) throws IOException {
        return document != null && normalize(document) ? CaptionXml.write(document) : input;
    }

    /** Mutates one bounded copy; the caller owns serialization and publication. */
    static boolean normalize(Document document) {
        if (document == null) return false;
        Element head = null;
        for (Element child : CaptionXml.children(document.getDocumentElement())) {
            if (!child.getTagName().equals("head")) continue;
            if (head != null) return false;
            head = child;
        }
        if (head == null) return false;
        Map<Integer, Element> pens = new LinkedHashMap<>();
        Set<Integer> invalid = new HashSet<>();
        for (Element pen : CaptionXml.children(head)) {
            if (!pen.getTagName().equals("pen")) continue;
            Integer id = CaptionXml.nativeId(pen.getAttribute("id"));
            if (id != null && pens.putIfAbsent(id, pen) != null) invalid.add(id);
        }
        Map<Integer, Map<String, String>> resolved = new HashMap<>();
        boolean changed = false;
        for (var entry : pens.entrySet()) {
            Element pen = entry.getValue();
            if (!pen.hasAttribute("p")) continue;
            Map<String, String> fields = resolve(entry.getKey(), pens, resolved, invalid);
            if (fields == null) continue;
            for (var field : fields.entrySet()) {
                if (pen.getAttribute(field.getKey()).equals(field.getValue())) continue;
                pen.setAttribute(field.getKey(), field.getValue());
                changed = true;
            }
            // Native fields are now explicit. Keep the parent link for authored metadata
            // outside this adapter's field set (for example ruby and vertical combinations).
        }
        return changed;
    }

    static Map<String, String> resolve(
            int id,
            Map<Integer, Element> pens,
            Map<Integer, Map<String, String>> resolved,
            Set<Integer> invalid) {
        var chain = new ArrayDeque<Integer>();
        var visiting = new HashSet<Integer>();
        Integer current = id;
        while (current != null && !resolved.containsKey(current)) {
            if (invalid.contains(current) || !pens.containsKey(current) || !visiting.add(current)) {
                invalid.addAll(visiting);
                return null;
            }
            chain.push(current);
            Element pen = pens.get(current);
            if (!pen.hasAttribute("p")) {
                current = null;
                break;
            }
            current = CaptionXml.nativeId(pen.getAttribute("p"));
            if (current == null) {
                invalid.addAll(visiting);
                return null;
            }
        }
        Map<String, String> inherited = current == null ? Map.of() : resolved.get(current);
        while (!chain.isEmpty()) {
            int next = chain.pop();
            Element pen = pens.get(next);
            var fields = new HashMap<>(inherited);
            for (String name : FIELDS) {
                if (!pen.hasAttribute(name)) continue;
                String value = pen.getAttribute(name);
                // SRV3's unspecified font marker does not replace the parent's family.
                if (name.equals("fs")) {
                    try {
                        if (Integer.parseInt(value) == 0) continue;
                    } catch (NumberFormatException invalidFont) {
                        continue;
                    }
                }
                fields.put(name, value);
            }
            inherited = Map.copyOf(fields);
            resolved.put(next, inherited);
        }
        return resolved.get(id);
    }

}
