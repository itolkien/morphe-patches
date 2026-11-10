package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.patches.captions.model.CaptionScene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Native text groups retain their own renderer while provider markup carries only IDs. */
final class NativeCaptionRunPlan {
    interface Renderer {
        CharSequence render(String text, String annotation);
    }

    record Group(
            String id,
            List<String> parts,
            String annotation,
            Renderer renderer,
            String paragraphId) {
        Group(String id, List<String> parts, String annotation, Renderer renderer) {
            this(id, parts, annotation, renderer, "native");
        }

        Group inParagraph(String paragraph) {
            return new Group(id, parts, annotation, renderer, paragraph);
        }

        Group {
            parts = List.copyOf(parts);
            if (parts.size() != 1 && parts.size() != 4)
                throw new IllegalArgumentException("Text group");
        }
    }

    record Output(Group source, List<String> parts) {
        Output {
            parts = List.copyOf(parts);
        }
    }

    record Result(List<Output> outputs, boolean complete, long retryAfterMs) {
        Result {
            outputs = List.copyOf(outputs);
        }

        List<CharSequence> render(Set<String> visible) {
            var result = new ArrayList<CharSequence>();
            for (Output output : outputs) {
                if (!visible.contains(output.source.id)) continue;
                String annotation = output.source.annotation;
                if (output.parts.size() == 4) {
                    int base = output.parts.get(0).length();
                    int start = base + output.parts.get(1).length();
                    int end = start + output.parts.get(2).length();
                    annotation =
                            base
                                    + ","
                                    + start
                                    + ","
                                    + end
                                    + annotation.substring(annotation.lastIndexOf(','));
                }
                result.add(
                        output.source.renderer.render(String.join("", output.parts), annotation));
            }
            return List.copyOf(result);
        }

        String text() {
            return outputs.stream()
                    .map(output -> String.join("", output.parts))
                    .collect(java.util.stream.Collectors.joining());
        }
    }

    private static final Renderer PLAIN = (text, annotation) -> text;
    private static final java.util.regex.Pattern BREAKS =
            java.util.regex.Pattern.compile("\\r\\n|[\\r\\n\\u2028\\u2029]");

    final List<Group> groups;

    NativeCaptionRunPlan(List<Group> groups) {
        this.groups = List.copyOf(paragraphs(groups));
        var ids = new HashSet<String>();
        for (Group group : this.groups)
            if (!ids.add(group.id)) throw new IllegalArgumentException("Duplicate text group");
    }

    private static List<NativeCaptionRunPlan.Group> paragraphs(
            List<NativeCaptionRunPlan.Group> groups) {
        var result = new ArrayList<NativeCaptionRunPlan.Group>();
        int paragraph = 0;
        for (var group : groups) {
            if (!group.paragraphId().equals("native")) {
                result.add(group);
                continue;
            }
            if (group.annotation() != null || group.parts().size() != 1) {
                result.add(group.inParagraph("p" + paragraph));
                continue;
            }
            String text = group.parts().get(0);
            var breaks =
                    java.util.regex.Pattern.compile("\\r\\n|[\\r\\n\\u2028\\u2029]").matcher(text);
            int cursor = 0;
            boolean multiline = false;
            while (breaks.find()) {
                multiline = true;
                if (cursor < breaks.start())
                    result.add(
                            new NativeCaptionRunPlan.Group(
                                            group.id() + "l" + cursor,
                                            List.of(text.substring(cursor, breaks.start())),
                                            null,
                                            group.renderer())
                                    .inParagraph("p" + paragraph));
                result.add(
                        new NativeCaptionRunPlan.Group(
                                        group.id() + "b" + breaks.start(),
                                        List.of(breaks.group()),
                                        null,
                                        PLAIN)
                                .inParagraph("p" + paragraph));
                cursor = breaks.end();
                paragraph++;
            }
            if (!multiline) result.add(group.inParagraph("p" + paragraph));
            else if (cursor < text.length())
                result.add(
                        new NativeCaptionRunPlan.Group(
                                        group.id() + "l" + cursor,
                                        List.of(text.substring(cursor)),
                                        null,
                                        group.renderer())
                                .inParagraph("p" + paragraph));
        }
        return result;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NativeCaptionRunPlan plan && groups.equals(plan.groups);
    }

    @Override
    public int hashCode() {
        return groups.hashCode();
    }

    private static boolean paragraphBreak(Group group) {
        return group.annotation == null
                && group.parts.size() == 1
                && !group.parts.get(0).isEmpty()
                && BREAKS.matcher(group.parts.get(0)).replaceAll("").isEmpty();
    }

    boolean isSingleRun() {
        return groups.size() == 1
                && groups.get(0).parts.size() == 1
                && groups.get(0).annotation == null;
    }

    Result withSingleText(String text) {
        if (!isSingleRun()) throw new IllegalStateException("Multiple owned text groups");
        return new Result(
                List.of(new Output(groups.get(0), List.of(BREAKS.matcher(text).replaceAll(" ")))),
                true,
                0);
    }

    Result original() {
        return new Result(
                groups.stream().map(group -> new Output(group, group.parts)).toList(), true, 0);
    }

    private record Unit(String id, String text, Group group, int part) {}

    /** Hard breaks are source layout; only the text on either side enters the provider. */
    private static List<Unit> units(Group group, int part) {
        String text = group.parts.get(part), id = group.id + "." + part;
        if (paragraphBreak(group)) return List.of(new Unit(null, text, group, part));
        var matcher = BREAKS.matcher(text);
        if (!matcher.find()) return List.of(new Unit(id, text, group, part));
        var result = new ArrayList<Unit>();
        int cursor = 0, line = 0;
        do {
            result.add(
                    new Unit(
                            id + ":l" + line++,
                            text.substring(cursor, matcher.start()),
                            group,
                            part));
            result.add(new Unit(null, matcher.group(), group, part));
            cursor = matcher.end();
        } while (matcher.find());
        result.add(new Unit(id + ":l" + line, text.substring(cursor), group, part));
        return result;
    }

    private static boolean intactGroups(
            List<CaptionScene.Run> source,
            List<CaptionScene.Run> translated,
            Map<String, Unit> owners) {
        var positions = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < translated.size(); i++) positions.put(translated.get(i).id(), i);
        var starts = new java.util.HashMap<Group, Integer>();
        var offsets = new java.util.HashMap<Group, Integer>();
        for (var run : source) {
            Group group = owners.get(run.id()).group;
            if (group.annotation == null) continue;
            Integer position = positions.get(run.id());
            if (position == null) return false;
            starts.putIfAbsent(group, position);
            int offset = offsets.merge(group, 1, Integer::sum) - 1;
            if (position != starts.get(group) + offset) return false;
        }
        return true;
    }

    Result translate(
            String source,
            String target,
            CaptionTranslator translator,
            BooleanSupplier active)
            throws IOException {
        var runs = new ArrayList<CaptionScene.Run>();
        var owners = new LinkedHashMap<String, Unit>();
        var layouts = new LinkedHashMap<Group, List<List<Unit>>>();
        for (Group group : groups) {
            var parts = new ArrayList<List<Unit>>();
            for (int i = 0; i < group.parts.size(); i++) {
                var units = units(group, i);
                parts.add(units);
                Map<String, String> pen = Map.of();
                if (group.parts.size() == 4) {
                    String role =
                            i == 0
                                    ? "1"
                                    : i == 2
                                            ? group.annotation.substring(
                                                    group.annotation.lastIndexOf(',') + 1)
                                            : "2";
                    pen = Map.of("rb", role);
                }
                for (Unit unit : units) {
                    if (unit.id == null) continue;
                    runs.add(
                            new CaptionScene.Run(
                                    unit.id,
                                    unit.text,
                                    0,
                                    pen,
                                    Map.of(),
                                    group.paragraphId,
                                    Long.MAX_VALUE));
                    owners.put(unit.id, unit);
                }
            }
            layouts.put(group, parts);
        }
        var scene =
                new CaptionScene(
                        List.of(
                                new CaptionScene.Window(
                                        "native",
                                        0,
                                        Long.MAX_VALUE,
                                        Map.of(),
                                        Map.of(),
                                        Map.of(),
                                        runs)));
        var translated =
                CaptionSceneTranslator.translate(scene, source, target, translator, active);
        List<CaptionScene.Run> ordered = translated.scene().windows().get(0).runs();
        boolean complete = translated.complete();
        long retry = translated.retryAfterMs();
        if (!intactGroups(runs, ordered, owners)) {
            // IDs alone do not authorize splitting an annotation box across other text.
            var fallback =
                    CaptionSceneTranslator.translateLongBatch(
                            source,
                            target,
                            runs.stream().map(CaptionScene.Run::text).toList(),
                            translator,
                            active);
            var safe = new ArrayList<CaptionScene.Run>();
            complete = true;
            for (int i = 0; i < runs.size(); i++) {
                String text = fallback.texts.get(i);
                complete &= text != null;
                safe.add(text == null ? runs.get(i) : runs.get(i).withText(text));
            }
            ordered = safe;
            retry = Math.max(retry, fallback.retryAfterMs);
        }
        var values = new LinkedHashMap<String, String>();
        var groupOrder = new java.util.LinkedHashSet<Group>();
        for (var run : ordered) {
            values.put(run.id(), BREAKS.matcher(run.text()).replaceAll(" "));
            groupOrder.add(owners.get(run.id()).group);
        }
        var paragraphs = new LinkedHashMap<String, List<Output>>();
        for (Group group : groups)
            paragraphs.computeIfAbsent(group.paragraphId, ignored -> new ArrayList<>());
        for (Group group : groupOrder) {
            var parts = new ArrayList<String>();
            for (var layout : layouts.get(group)) {
                var text = new StringBuilder();
                for (Unit unit : layout)
                    text.append(unit.id == null ? unit.text : values.get(unit.id));
                parts.add(text.toString());
            }
            paragraphs.get(group.paragraphId).add(new Output(group, parts));
        }
        for (Group group : groups)
            if (paragraphBreak(group))
                paragraphs.get(group.paragraphId).add(new Output(group, group.parts));
        return new Result(
                paragraphs.values().stream().flatMap(List::stream).toList(), complete, retry);
    }
}
