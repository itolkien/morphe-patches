package app.morphe.extension.youtube.patches;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Prepares paragraph-timed ruby groups for the native SRV3 reader. */
final class NativeCaptionRubyInput {
    record Prepared(byte[] bytes, Document document) {}

    static Prepared prepare(byte[] bytes, Document document) throws IOException {
        Prepared original = new Prepared(bytes, document);
        if (document == null || !needed(document)) return original;
        // Android cannot clone Document nodes; importing them also changes supplementary-text
        // serialization on some releases. A separate bounded parse keeps the captured DOM intact.
        Document copy = CaptionXml.read(bytes);
        if (copy == null) return original;
        return normalize(copy) ? new Prepared(CaptionXml.write(copy), copy) : original;
    }

    static boolean needed(Document document) {
        if (document == null) return false;
        var declarations = document.getElementsByTagName("pen");
        for (int i = 0; i < declarations.getLength(); i++) {
            Integer role = CaptionXml.nativeId(((Element) declarations.item(i)).getAttribute("rb"));
            if (role != null && (role == 1 || role >= 9 && role <= 15 && role != 10)) return true;
        }
        return false;
    }

    /** Mutates one bounded copy; the caller owns serialization and publication. */
    static boolean normalize(Document document) {
        if (document == null) return false;
        Element head = child(document.getDocumentElement(), "head");
        Element body = child(document.getDocumentElement(), "body");
        if (head == null || body == null) return false;
        Map<Integer, Element> pens = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        for (Element pen : CaptionXml.children(head)) {
            if (!pen.getTagName().equals("pen")) continue;
            Integer id = CaptionXml.nativeId(pen.getAttribute("id"));
            if (id == null || pens.putIfAbsent(id, pen) != null) return false;
            used.add(id);
        }
        var elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            Integer reference = CaptionXml.nativeId(element.getAttribute("p"));
            if (reference != null) used.add(reference);
        }
        var roles = new HashMap<Integer, Integer>();
        var styles = new HashMap<Integer, Map<String, String>>();
        var invalid = new HashSet<Integer>();
        boolean changed = false;
        for (var entry : pens.entrySet()) {
            int emphasis = role(entry.getKey(), pens, roles);
            if (emphasis < 9 || emphasis > 14) continue;
            entry.getValue().setAttribute("morphe-emphasis", Integer.toString(emphasis));
            changed = true;
        }
        int next = 1;
        for (Element paragraph : CaptionXml.children(body)) {
            if (!paragraph.getTagName().equals("p")) continue;
            for (Node node = paragraph.getFirstChild(); node != null; ) {
                Node after = node.getNextSibling();
                if (!(node instanceof Element base)
                        || !base.getTagName().equals("s")
                        || role(effectivePen(base, paragraph, pens), pens, roles) != 1) {
                    node = after;
                    continue;
                }
                Element[] group = new Element[4];
                Node cursor = node;
                for (int i = 0; i < 4; i++) {
                    if (!(cursor instanceof Element segment)
                            || !segment.getTagName().equals("s")
                            || !CaptionXml.children(segment).isEmpty()) break;
                    group[i] = segment;
                    cursor = cursor.getNextSibling();
                }
                if (group[3] == null) {
                    node = after;
                    continue;
                }
                int annotationRole = role(effectivePen(group[2], paragraph, pens), pens, roles);
                int baseId = effectivePen(base, paragraph, pens);
                var fields = NativeCaptionPenStyles.resolve(baseId, pens, styles, invalid);
                if (fields == null) {
                    node = after;
                    continue;
                }
                while (used.contains(next)) next++;
                int synthetic = next++;
                used.add(synthetic);
                Element pen = (Element) pens.get(baseId).cloneNode(true);
                fields.forEach(pen::setAttribute);
                pen.setAttribute("id", Integer.toString(synthetic));
                String baseText = nativeText(base);
                String open = nativeText(group[1]);
                String annotation = nativeText(group[2]);
                int start = baseText.length() + open.length();
                pen.setAttribute(
                        "morphe-ruby",
                        baseText.length()
                                + ","
                                + start
                                + ","
                                + (start + annotation.length())
                                + ","
                                + annotationRole);
                CaptionXml.insertOrdered(head, pen);
                Element segment = (Element) base.cloneNode(false);
                segment.removeAttribute("t");
                segment.setAttribute("p", Integer.toString(synthetic));
                segment.setTextContent(baseText + open + annotation + nativeText(group[3]));
                paragraph.insertBefore(segment, base);
                for (Element part : group) paragraph.removeChild(part);
                changed = true;
                node = cursor;
            }
        }
        return changed;
    }

    private static String nativeText(Element segment) {
        return segment.getTextContent().replace("<br/>", "\n");
    }

    /** A missing/unresolved segment pen uses the paragraph pen, then native defaults. */
    private static Integer effectivePen(
            Element segment, Element paragraph, Map<Integer, Element> pens) {
        Integer id = CaptionXml.nativeId(segment.getAttribute("p"));
        if (pens.containsKey(id)) return id;
        id = CaptionXml.nativeId(paragraph.getAttribute("p"));
        return pens.containsKey(id) ? id : null;
    }

    private static Element child(Element parent, String name) {
        Element result = null;
        for (Element element : CaptionXml.children(parent)) {
            if (!element.getTagName().equals(name)) continue;
            if (result != null) return null;
            result = element;
        }
        return result;
    }

    private static int role(Integer id, Map<Integer, Element> pens, Map<Integer, Integer> cache) {
        var chain = new ArrayDeque<Integer>();
        var visiting = new HashSet<Integer>();
        int value = 0;
        while (id != null) {
            if (cache.containsKey(id)) {
                value = cache.get(id);
                break;
            }
            Element pen = pens.get(id);
            if (pen == null || !visiting.add(id)) break;
            chain.push(id);
            Integer explicit = CaptionXml.nativeId(pen.getAttribute("rb"));
            if (explicit != null && explicit != 0 && explicit != 10) {
                value = explicit > 10 ? explicit - 1 : explicit;
                break;
            }
            id = CaptionXml.nativeId(pen.getAttribute("p"));
        }
        while (!chain.isEmpty()) cache.put(chain.pop(), value);
        return value;
    }

}
