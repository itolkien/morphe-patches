package app.morphe.extension.youtube.patches;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.AttributesImpl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Authored window properties carried by native cue state; no playback clock or text model. */
public final class NativeCaptionWindowData {
    public enum Kind {
        STYLE(Set.of("mh", "ju", "pd", "sd", "wfc", "wfo"), "ws"),
        POSITION(Set.of("ap", "ah", "av", "cc", "rc"), "wp");
        final Set<String> fields;
        final String parent;

        Kind(Set<String> fields, String parent) {
            this.fields = fields;
            this.parent = parent;
        }
    }

    public record Definition(Kind kind, Map<String, String> fields) {
        public Definition {
            fields = Map.copyOf(fields);
        }

        public Integer integer(String name) {
            String value = fields.get(name);
            return value == null ? null : Integer.valueOf(value);
        }
    }

    public record Source(Definition style, Definition position) {
        public boolean vertical() {
            Integer pd = style == null ? null : style.integer("pd");
            return pd != null && (pd == 2 || pd == 3);
        }

        public Integer columns() {
            return position == null ? null : position.integer(vertical() ? "rc" : "cc");
        }

        public Integer rows() {
            return position == null ? null : position.integer(vertical() ? "cc" : "rc");
        }
    }

    private NativeCaptionWindowData() {}

    static Integer background(Definition style) {
        if (style == null
                || (!style.fields().containsKey("wfc") && !style.fields().containsKey("wfo")))
            return null;
        String color = style.fields().getOrDefault("wfc", "#080808");
        if (!color.matches("#[0-9a-fA-F]{6}")) return null;
        Integer opacity = style.integer("wfo");
        // Window fill defaults to transparent; the reference's .75/.7 defaults belong to
        // the separate pen background and must not make an authored window opaque.
        int alpha = opacity == null ? 0 : Math.max(0, Math.min(255, opacity));
        return (alpha << 24) | Integer.parseInt(color.substring(1), 16);
    }

    static Definition resolve(Kind kind, Definition parent, Attributes attributes) {
        if (parent != null && parent.kind() != kind)
            throw new IllegalArgumentException("Mismatched window definition kind");
        var fields = new LinkedHashMap<String, String>();
        if (parent != null) fields.putAll(parent.fields());
        for (String name : kind.fields) {
            String value = attributes.getValue(name);
            if (value == null) continue;
            if (!name.equals("wfc")) {
                try {
                    value = Integer.toString(Integer.parseInt(value.trim()));
                } catch (NumberFormatException invalid) {
                    continue;
                }
            }
            fields.put(name, value);
        }
        return new Definition(kind, fields);
    }

    static Attributes enriched(Attributes original, Definition definition) {
        if (definition == null || definition.fields().isEmpty()) return original;
        var result = new AttributesImpl(original);
        definition
                .fields()
                .forEach(
                        (name, value) -> {
                            if (!definition.kind().fields.contains(name)) return;
                            int index = result.getIndex(name);
                            if (index < 0) result.addAttribute("", name, name, "CDATA", value);
                            else result.setValue(index, value);
                        });
        return result;
    }
}
