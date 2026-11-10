package app.morphe.extension.youtube.patches;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Gives co-timed authored effect copies separate native timelines at the same position. */
final class NativeCaptionLayers {
    private record Event(int position, long start, long duration) {}
    private record Layer(int position, int index) {}
    private record Assignment(Element paragraph, Layer layer) {}

    private NativeCaptionLayers() {}

    static boolean needed(Document document) {
        return !assignments(document).isEmpty();
    }

    static boolean normalize(Document document) {
        List<Assignment> assignments = assignments(document);
        if (assignments.isEmpty()) return false;
        Element head = (Element) document.getElementsByTagName("head").item(0);
        Map<Integer, Element> positions = new HashMap<>();
        var used = new HashSet<Integer>();
        for (Element element : CaptionXml.children(head)) {
            if (!element.getTagName().equals("wp")) continue;
            Integer id = CaptionXml.nativeId(element.getAttribute("id"));
            if (id == null) continue;
            positions.put(id, element);
            used.add(id);
        }
        // Undefined references must keep their native fallback, not capture a new definition.
        var paragraphs = document.getElementsByTagName("p");
        for (int i = 0; i < paragraphs.getLength(); i++)
            used.add(CaptionXml.nativeId(((Element) paragraphs.item(i)).getAttribute("wp")));
        Map<Layer, String> identities = new HashMap<>();
        int next = 1;
        for (Assignment assignment : assignments) {
            String id = identities.get(assignment.layer);
            if (id == null) {
                while (used.contains(next)) next++;
                used.add(next);
                id = Integer.toString(next++);
                identities.put(assignment.layer, id);
                Element position = (Element) positions.get(assignment.layer.position).cloneNode(true);
                position.setAttribute("id", id);
                CaptionXml.insertOrdered(head, position);
            }
            assignment.paragraph.setAttribute("wp", id);
        }
        return true;
    }

    private static List<Assignment> assignments(Document document) {
        var result = new ArrayList<Assignment>();
        if (document.getElementsByTagName("head").getLength() != 1
                || document.getElementsByTagName("body").getLength() != 1
                || document.getElementsByTagName("w").getLength() != 0) return result;
        Element head = (Element) document.getElementsByTagName("head").item(0);
        var positions = new HashSet<Integer>();
        var ambiguous = new HashSet<Integer>();
        for (Element element : CaptionXml.children(head)) {
            if (!element.getTagName().equals("wp")) continue;
            Integer id = CaptionXml.nativeId(element.getAttribute("id"));
            if (id != null && !positions.add(id)) ambiguous.add(id);
        }
        positions.removeAll(ambiguous);
        Element body = (Element) document.getElementsByTagName("body").item(0);
        Map<Event, Integer> copies = new HashMap<>();
        for (Element paragraph : CaptionXml.children(body)) {
            if (!paragraph.getTagName().equals("p")) continue;
            // Explicit windows and rolling append events own their native identity.
            if (paragraph.hasAttribute("w")
                    || paragraph.hasAttribute("a") && !paragraph.getAttribute("a").equals("0"))
                return List.of();
            Integer position = CaptionXml.nativeId(paragraph.getAttribute("wp"));
            if (!positions.contains(position)) continue;
            String start = paragraph.getAttribute("t"), duration = paragraph.getAttribute("d");
            long t, d;
            try {
                t = Long.parseLong(start);
                d = Long.parseLong(duration);
                if (t < 0 || d <= 0 || t > Long.MAX_VALUE - d) continue;
            } catch (NumberFormatException invalid) {
                continue;
            }
            var event = new Event(position, t, d);
            int index = copies.merge(event, 1, Integer::sum) - 1;
            if (index != 0) result.add(new Assignment(paragraph, new Layer(position, index)));
        }
        return result;
    }

}
