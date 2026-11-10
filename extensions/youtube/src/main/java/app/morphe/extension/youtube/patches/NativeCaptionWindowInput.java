package app.morphe.extension.youtube.patches;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.helpers.AttributesImpl;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Expands declared window inheritance before the native SAX reader consumes it. */
final class NativeCaptionWindowInput {
    static boolean expand(Document document) {
        if (document == null) return false;
        Element head = null;
        for (Element child : CaptionXml.children(document.getDocumentElement())) {
            if (!child.getTagName().equals("head")) continue;
            if (head != null) return false;
            head = child;
        }
        if (head == null) return false;
        boolean changed = false;
        for (var kind : NativeCaptionWindowData.Kind.values()) {
            Map<Integer, Element> declarations = new LinkedHashMap<>();
            Set<Integer> invalid = new HashSet<>();
            for (Element element : CaptionXml.children(head)) {
                if (!element.getTagName().equals(kind.parent)) continue;
                Integer id = CaptionXml.nativeId(element.getAttribute("id"));
                if (id != null && declarations.putIfAbsent(id, element) != null) invalid.add(id);
            }
            Map<Integer, NativeCaptionWindowData.Definition> resolved = new HashMap<>();
            for (var entry : declarations.entrySet()) {
                Element element = entry.getValue();
                if (!element.hasAttribute(kind.parent)) continue;
                var definition = resolve(entry.getKey(), kind, declarations, resolved, invalid);
                if (definition == null) continue;
                for (var field : definition.fields().entrySet()) {
                    if (element.getAttribute(field.getKey()).equals(field.getValue())) continue;
                    element.setAttribute(field.getKey(), field.getValue());
                    changed = true;
                }
            }
        }
        return changed;
    }

    private record InlineKey(int parent, NativeCaptionWindowData.Definition definition) {}

    static boolean hasInline(Document document) {
        for (var body : CaptionXml.children(document.getDocumentElement())) {
            if (!body.getTagName().equals("body")) continue;
            for (var element : CaptionXml.children(body)) {
                if (!inlineElement(element)) continue;
                for (var kind : NativeCaptionWindowData.Kind.values())
                    for (String field : kind.fields) if (element.hasAttribute(field)) return true;
            }
        }
        return false;
    }

    private static boolean inlineElement(Element element) {
        return element.getTagName().equals("w")
                || element.getTagName().equals("p") && !element.hasAttribute("w");
    }

    static boolean inline(Document document) {
        if (!hasInline(document)) return false;
        Element head = null;
        for (var element : CaptionXml.children(document.getDocumentElement())) {
            if (!element.getTagName().equals("head")) continue;
            if (head != null) return false;
            head = element;
        }
        if (head == null) {
            head = document.createElement("head");
            document.getDocumentElement()
                    .insertBefore(head, document.getDocumentElement().getFirstChild());
        }
        boolean changed = false;
        for (var kind : NativeCaptionWindowData.Kind.values()) {
            Map<Integer, Element> declarations = new LinkedHashMap<>();
            Set<Integer> invalid = new HashSet<>();
            for (var element : CaptionXml.children(head)) {
                if (!element.getTagName().equals(kind.parent)) continue;
                Integer value = CaptionXml.nativeId(element.getAttribute("id"));
                if (value != null && declarations.putIfAbsent(value, element) != null)
                    invalid.add(value);
            }
            Map<Integer, NativeCaptionWindowData.Definition> resolved = new HashMap<>();
            Map<InlineKey, Integer> derived = new HashMap<>();
            Set<Integer> used = new HashSet<>(declarations.keySet());
            // Undefined references must retain native fallback even after inline definitions
            // are appended. Reserve numeric aliases in both body and header parent references.
            var elements = document.getElementsByTagName("*");
            for (int i = 0; i < elements.getLength(); i++) {
                Integer reference = CaptionXml.nativeId(
                        ((Element) elements.item(i)).getAttribute(kind.parent));
                if (reference != null) used.add(reference);
            }
            int next = 1;
            for (var body : CaptionXml.children(document.getDocumentElement())) {
                if (!body.getTagName().equals("body")) continue;
                for (var element : CaptionXml.children(body)) {
                    if (!inlineElement(element)
                            || kind.fields.stream().noneMatch(element::hasAttribute)) continue;
                    Integer parent =
                            element.hasAttribute(kind.parent)
                                    ? CaptionXml.nativeId(element.getAttribute(kind.parent))
                                    : 0;
                    if (parent == null) continue;
                    var inherited =
                            declarations.containsKey(parent)
                                    ? resolve(parent, kind, declarations, resolved, invalid)
                                    : null;
                    if (element.hasAttribute(kind.parent) && inherited == null) continue;
                    var attributes = new AttributesImpl();
                    for (String field : kind.fields)
                        if (element.hasAttribute(field))
                            attributes.addAttribute(
                                    "", field, field, "CDATA", element.getAttribute(field));
                    var definition = NativeCaptionWindowData.resolve(kind, inherited, attributes);
                    var key = new InlineKey(parent, definition);
                    Integer assigned = derived.get(key);
                    if (assigned == null) {
                        while (used.contains(next)) next++;
                        assigned = next++;
                        used.add(assigned);
                        derived.put(key, assigned);
                        Element declaration = document.createElement(kind.parent);
                        declaration.setAttribute("id", assigned.toString());
                        if (declarations.containsKey(parent))
                            declaration.setAttribute(kind.parent, parent.toString());
                        definition.fields().forEach(declaration::setAttribute);
                        CaptionXml.insertOrdered(head, declaration);
                    }
                    element.setAttribute(kind.parent, assigned.toString());
                    for (String field : kind.fields) element.removeAttribute(field);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static NativeCaptionWindowData.Definition resolve(
            int id,
            NativeCaptionWindowData.Kind kind,
            Map<Integer, Element> declarations,
            Map<Integer, NativeCaptionWindowData.Definition> resolved,
            Set<Integer> invalid) {
        var chain = new ArrayDeque<Integer>();
        var visiting = new HashSet<Integer>();
        Integer current = id;
        while (current != null && !resolved.containsKey(current)) {
            if (invalid.contains(current)
                    || !visiting.add(current)
                    || !declarations.containsKey(current)) {
                invalid.addAll(visiting);
                return null;
            }
            chain.push(current);
            Element element = declarations.get(current);
            if (!element.hasAttribute(kind.parent)) {
                current = null;
                break;
            }
            current = CaptionXml.nativeId(element.getAttribute(kind.parent));
            if (current == null) {
                invalid.addAll(visiting);
                return null;
            }
        }
        var parent = current == null ? null : resolved.get(current);
        while (!chain.isEmpty()) {
            int next = chain.pop();
            var attributes = new AttributesImpl();
            var nodes = declarations.get(next).getAttributes();
            for (int i = 0; i < nodes.getLength(); i++) {
                var attribute = nodes.item(i);
                attributes.addAttribute(
                        "",
                        attribute.getNodeName(),
                        attribute.getNodeName(),
                        "CDATA",
                        attribute.getNodeValue());
            }
            parent = NativeCaptionWindowData.resolve(kind, parent, attributes);
            resolved.put(next, parent);
        }
        return resolved.get(id);
    }

}
