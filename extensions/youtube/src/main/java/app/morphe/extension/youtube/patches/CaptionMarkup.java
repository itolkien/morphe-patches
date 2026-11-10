package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.patches.captions.model.CaptionScene;
import app.morphe.extension.youtube.patches.CaptionTranslator.Part;
import app.morphe.extension.youtube.patches.CaptionTranslator.StyledResult;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Provider markup carries generated IDs; source styling never enters the response. */
final class CaptionMarkup {
    static final int MAX_OUTPUT_CODEPOINTS = 4096;
    private CaptionMarkup() {}

    static String encode(List<CaptionScene.Run> runs) throws IOException {
        validate(runs);
        var html = new StringBuilder();
        for (int i = 0; i < runs.size(); i++) {
            if (i > 0 && !Objects.equals(runs.get(i - 1).paragraphId(), runs.get(i).paragraphId()))
                html.append("<br/>");
            html.append("<span id=\"m")
                    .append(i)
                    .append("\">")
                    .append(
                            runs.get(i)
                                    .text()
                                    .replace("&", "&amp;")
                                    .replace("<", "&lt;")
                                    .replace(">", "&gt;")
                                    .replace("\r\n", "\n")
                                    .replace("\r", "\n")
                                    .replace("\n", "<br/>"))
                    .append("</span>");
        }
        return html.toString();
    }

    static StyledResult decode(String html, List<CaptionScene.Run> runs) throws IOException {
        validate(runs);
        if (html == null) throw new IOException("Missing styled caption response");
        String xml =
                "<timedtext format='3'><body><p>"
                        + html.replace("&nbsp;", "&#160;").replaceAll("(?i)<br\\s*/?>", "\n")
                        + "</p></body></timedtext>";
        var document = CaptionXml.read(xml.getBytes(StandardCharsets.UTF_8));
        if (document == null) throw new IOException("Malformed styled caption markup");
        Node paragraph = document.getElementsByTagName("p").item(0);
        var parts = new ArrayList<Part>();
        var seen = new HashSet<Integer>();
        var prefix = new StringBuilder();
        var between = new StringBuilder();
        CaptionScene.Run previousRun = null;
        for (Node node = paragraph.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.TEXT_NODE) {
                String text = node.getNodeValue();
                if (!text.codePoints().allMatch(CaptionMarkup::space))
                    throw new IOException("Unowned translated text");
                if (parts.isEmpty()) prefix.append(text);
                else between.append(text);
                continue;
            }
            if (!(node instanceof Element span)
                    || !span.getTagName().equals("span")
                    || span.getAttributes().getLength() != 1)
                throw new IOException("Unexpected translated markup");
            String id = span.getAttribute("id");
            if (!id.matches("m(?:0|[1-9][0-9]*)"))
                throw new IOException("Unknown translated span ID");
            int index;
            try {
                index = Integer.parseInt(id.substring(1));
            } catch (NumberFormatException error) {
                throw new IOException("Invalid translated span ID", error);
            }
            if (index >= runs.size() || !seen.add(index))
                throw new IOException("Duplicate or unknown translated span ID");
            for (Node child = span.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeType() != Node.TEXT_NODE
                        && child.getNodeType() != Node.CDATA_SECTION_NODE)
                    throw new IOException("Nested translated markup");
            }
            CaptionScene.Run run = runs.get(index);
            if (previousRun != null
                    && Objects.equals(previousRun.paragraphId(), run.paragraphId())) {
                Part previous = parts.remove(parts.size() - 1);
                parts.add(new Part(previous.id(), previous.text() + between));
            }
            between.setLength(0);
            previousRun = run;
            parts.add(new Part(run.id(), prefix + span.getTextContent()));
            prefix.setLength(0);
        }
        if (!parts.isEmpty() && !between.isEmpty()) {
            Part previous = parts.remove(parts.size() - 1);
            parts.add(new Part(previous.id(), previous.text() + between));
        }
        if (parts.size() != runs.size()) throw new IOException("Missing translated span IDs");
        var source = new LinkedHashMap<String, CaptionScene.Run>();
        for (var run : runs) source.put(run.id(), run);
        var result = new ArrayList<Part>();
        int characters = 0;
        for (Part part : parts) {
            String text = padding(source.get(part.id()).text(), part.text());
            characters += text.codePointCount(0, text.length());
            if (characters > MAX_OUTPUT_CODEPOINTS)
                throw new IOException("Oversized styled caption translation");
            result.add(new Part(part.id(), text));
        }
        if (result.stream()
                .allMatch(part -> part.text().codePoints().allMatch(CaptionMarkup::space)))
            throw new IOException("Empty styled caption translation");
        return new StyledResult(result);
    }

    private static void validate(List<CaptionScene.Run> runs) throws IOException {
        var ids = new HashSet<String>();
        for (var run : runs)
            if (!ids.add(run.id())) throw new IOException("Duplicate source span ID");
    }

    private static String padding(String source, String translated) {
        int first = start(source), last = end(source, first);
        if (first == source.length()) return source;
        int translatedFirst = start(translated), translatedLast = end(translated, translatedFirst);
        String prefix =
                first == 0 ? translated.substring(0, translatedFirst) : source.substring(0, first);
        String suffix =
                last == source.length()
                        ? translated.substring(translatedLast)
                        : source.substring(last);
        return prefix + translated.substring(translatedFirst, translatedLast) + suffix;
    }

    private static int start(String text) {
        int index = 0;
        while (index < text.length() && space(text.codePointAt(index)))
            index += Character.charCount(text.codePointAt(index));
        return index;
    }

    private static int end(String text, int first) {
        int index = text.length();
        while (index > first && space(text.codePointBefore(index)))
            index -= Character.charCount(text.codePointBefore(index));
        return index;
    }

    private static boolean space(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }
}
