/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4149
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/** Bounded, fail-closed srv3 reader shared by source capture and translated-caption repair. */
public final class CaptionXml {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Pattern ENCODING =
            Pattern.compile(
                    "^\\s*<\\?xml\\s+[^?]*\\bencoding\\s*=\\s*(['\"])([^'\"]+)\\1",
                    Pattern.CASE_INSENSITIVE);

    private CaptionXml() {}

    /** Native SRV3 identity; absence/invalid input is distinct from the valid default ID zero. */
    static Integer nativeId(String text) {
        try {
            int id = Integer.parseInt(text);
            return id < 0 ? null : id;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element) elements.add((Element) node);
        return elements;
    }

    /**
     * Inserts a synthetic declaration so same-tag IDs stay in increasing order. ytt.ytt requires
     * that order in {@code <head>}; appending a low ID after sparse authored IDs violates it.
     */
    static void insertOrdered(Element head, Element declaration) {
        Integer id = nativeId(declaration.getAttribute("id"));
        Element last = null;
        for (Element child : children(head)) {
            if (!child.getTagName().equals(declaration.getTagName())) continue;
            last = child;
            Integer childId = nativeId(child.getAttribute("id"));
            if (id != null && childId != null && childId > id) {
                head.insertBefore(declaration, child);
                return;
            }
        }
        if (last == null) head.appendChild(declaration);
        else head.insertBefore(declaration, last.getNextSibling());
    }

    public static Document read(byte[] input) {
        if (input == null || input.length == 0 || input.length > MAX_BYTES) return null;
        try {
            String source =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(input))
                            .toString();
            if (source.startsWith("\uFEFF")) source = source.substring(1);
            Matcher encoding = ENCODING.matcher(source);
            //noinspection ConstantConditions
            if (encoding.find()
                    && !encoding.group(2).equalsIgnoreCase("UTF-8")
                    && !encoding.group(2).equalsIgnoreCase("UTF8")) return null;
            if (source.indexOf('\0') >= 0
                    || source.contains("<!DOCTYPE")
                    || source.contains("<!ENTITY")) return null;
            var builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            builder.setEntityResolver(
                    (publicId, systemId) -> {
                        throw new SAXException("External caption entity");
                    });
            builder.setErrorHandler(
                    new DefaultHandler() {
                        @Override
                        public void error(SAXParseException error) throws SAXException {
                            throw error;
                        }

                        @Override
                        public void fatalError(SAXParseException error) throws SAXException {
                            throw error;
                        }
                    });
            // Parse the same validated characters; the declaration cannot reinterpret bytes.
            Document document = builder.parse(new InputSource(new StringReader(source)));
            Element root = document.getDocumentElement();
            return root.getTagName().equals("timedtext") && root.getAttribute("format").equals("3")
                    ? document
                    : null;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Unsupported caption document", ex);
            return null;
        }
    }

    static byte[] write(Document document) throws IOException {
        return CaptionXmlWriter.write(document);
    }
}
