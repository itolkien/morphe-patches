package app.morphe.extension.youtube.patches;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;

/** Atomically adapts source definitions while retaining authored event content and timing. */
final class NativeCaptionSourceInput {
    record Prepared(byte[] bytes, Document document) {}

    static Prepared prepare(byte[] input, Document document) throws IOException {
        return prepare(input, document, true, false);
    }

    static Prepared prepare(byte[] input, Document document, boolean authoredLayers)
            throws IOException {
        return prepare(input, document, authoredLayers, false);
    }

    /**
     * Runs the source passes in one ordered copy and one serialization. Ruby synthesis stays
     * first so its synthetic pens are visible to the pen-style and window passes.
     */
    static Prepared prepare(
            byte[] input, Document document, boolean authoredLayers, boolean ruby)
            throws IOException {
        var original = new Prepared(input, document);
        if (document == null) return original;
        boolean rubyNeeded = ruby && NativeCaptionRubyInput.needed(document);
        boolean inheritance = false;
        for (var head : CaptionXml.children(document.getDocumentElement())) {
            if (!head.getTagName().equals("head")) continue;
            for (var declaration : CaptionXml.children(head)) {
                String parent =
                        switch (declaration.getTagName()) {
                            case "pen" -> "p";
                            case "ws" -> "ws";
                            case "wp" -> "wp";
                            default -> null;
                        };
                if (parent != null && declaration.hasAttribute(parent)) {
                    inheritance = true;
                    break;
                }
            }
            if (inheritance) break;
        }
        if (!rubyNeeded
                && !inheritance
                && !mixedText(document, false)
                && !NativeCaptionWindowTiming.needed(document)
                && !NativeCaptionWindowInput.hasInline(document)
                && !(authoredLayers && NativeCaptionLayers.needed(document))) return original;
        // Both expansion passes mutate their DOM. Publish neither pass until serialization
        // succeeds, so fallback and source capture always see a consistent document/byte pair.
        Document copy = CaptionXml.read(input);
        if (copy == null) return original;
        boolean changed = rubyNeeded && NativeCaptionRubyInput.normalize(copy);
        boolean mixed = mixedText(copy, true);
        boolean windows = NativeCaptionWindowInput.expand(copy);
        windows |= NativeCaptionWindowInput.inline(copy);
        if (authoredLayers) windows |= NativeCaptionLayers.normalize(copy);
        boolean timing = NativeCaptionWindowTiming.normalize(copy);
        changed |= NativeCaptionPenStyles.normalize(copy);
        if (!(changed || windows || mixed || timing)) return original;
        return new Prepared(CaptionXml.write(copy), copy);
    }

    private static boolean mixedText(Document document, boolean normalize) {
        boolean changed = false;
        var paragraphs = document.getElementsByTagName("p");
        for (int i = 0; i < paragraphs.getLength(); i++) {
            Element paragraph = (Element) paragraphs.item(i);
            if (CaptionXml.children(paragraph).stream()
                    .noneMatch(child -> child.getTagName().equals("s"))) continue;
            for (Node node = paragraph.getFirstChild(); node != null; ) {
                Node next = node.getNextSibling();
                if ((node.getNodeType() == Node.TEXT_NODE
                                || node.getNodeType() == Node.CDATA_SECTION_NODE)
                        && !node.getNodeValue().isEmpty()) {
                    if (!normalize) return true;
                    // Untimed paragraph text starts at the paragraph time in the web reader.
                    Element segment = document.createElement("s");
                    paragraph.replaceChild(segment, node);
                    segment.appendChild(node);
                    changed = true;
                }
                node = next;
            }
        }
        return changed;
    }
}
