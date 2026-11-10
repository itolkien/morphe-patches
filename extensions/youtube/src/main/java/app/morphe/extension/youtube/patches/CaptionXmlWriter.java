package app.morphe.extension.youtube.patches;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** Bounded UTF-8 output for the validated DOM, without vendor Transformer escaping. */
final class CaptionXmlWriter {
    private CaptionXmlWriter() {}

    static byte[] write(Document document) throws IOException {
        var output = new ByteArrayOutputStream();
        OutputStream bounded =
                new OutputStream() {
                    private void check(int length) throws IOException {
                        if (length > CaptionXml.MAX_BYTES - output.size())
                            throw new IOException("Expanded captions exceed input budget");
                    }

                    @Override
                    public void write(int value) throws IOException {
                        check(1);
                        output.write(value);
                    }

                    @Override
                    public void write(byte[] bytes, int start, int length) throws IOException {
                        check(length);
                        output.write(bytes, start, length);
                    }
                };
        Writer writer = new OutputStreamWriter(bounded, StandardCharsets.UTF_8);
        String version = document.getXmlVersion();
        if (version == null) version = "1.0";
        if (!version.equals("1.0") && !version.equals("1.1"))
            throw new IOException("Unknown XML version");
        boolean xml11 = version.equals("1.1");
        writer.write("<?xml version=\"" + version + "\" encoding=\"UTF-8\"?>");
        Node current = document.getFirstChild();
        while (current != null) {
            if (current instanceof Element element) {
                writer.write('<');
                writer.write(element.getTagName());
                var attributes = element.getAttributes();
                for (int i = 0; i < attributes.getLength(); i++) {
                    Node attribute = attributes.item(i);
                    writer.write(' ');
                    writer.write(attribute.getNodeName());
                    writer.write("=\"");
                    escaped(writer, attribute.getNodeValue(), true, xml11);
                    writer.write('"');
                }
                writer.write('>');
                if (current.getFirstChild() != null) {
                    current = current.getFirstChild();
                    continue;
                }
            } else {
                switch (current.getNodeType()) {
                    case Node.TEXT_NODE -> escaped(writer, current.getNodeValue(), false, xml11);
                    case Node.CDATA_SECTION_NODE -> {
                        writer.write("<![CDATA[");
                        writer.write(current.getNodeValue().replace("]]>", "]]]]><![CDATA[>"));
                        writer.write("]]>");
                    }
                    case Node.COMMENT_NODE -> {
                        writer.write("<!--");
                        writer.write(current.getNodeValue());
                        writer.write("-->");
                    }
                    case Node.PROCESSING_INSTRUCTION_NODE -> {
                        writer.write("<?");
                        writer.write(current.getNodeName());
                        writer.write(' ');
                        writer.write(current.getNodeValue());
                        writer.write("?>");
                    }
                    default ->
                            throw new IOException("Unsupported XML node " + current.getNodeType());
                }
            }
            while (current != null) {
                if (current instanceof Element element) {
                    writer.write("</");
                    writer.write(element.getTagName());
                    writer.write('>');
                }
                if (current.getNextSibling() != null) {
                    current = current.getNextSibling();
                    break;
                }
                current = current.getParentNode();
                if (current == document) {
                    current = null;
                    break;
                }
            }
        }
        writer.flush();
        return output.toByteArray();
    }

    private static void escaped(Writer writer, String value, boolean attribute, boolean xml11)
            throws IOException {
        int copied = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
                    throw new IOException("Unpaired XML surrogate");
                i++;
                continue;
            }
            if (Character.isLowSurrogate(c) || c == 0 || c == 0xfffe || c == 0xffff)
                throw new IOException("Invalid XML character");
            String replacement =
                    switch (c) {
                        case '&' -> "&amp;";
                        case '<' -> "&lt;";
                        case '>' -> "&gt;";
                        case '"' -> attribute ? "&quot;" : null;
                        case '\r' -> "&#13;";
                        case '\n' -> attribute ? "&#10;" : null;
                        case '\t' -> attribute ? "&#9;" : null;
                        default -> null;
                    };
            if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r')
                    || (xml11 && (c == 0x85 || c == 0x2028))) {
                if (!xml11) throw new IOException("Invalid XML control character");
                replacement = "&#" + (int) c + ";";
            }
            if (replacement == null) continue;
            writer.write(value, copied, i - copied);
            writer.write(replacement);
            copied = i + 1;
        }
        writer.write(value, copied, value.length() - copied);
    }
}
