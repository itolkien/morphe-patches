package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;
import org.xml.sax.helpers.AttributesImpl;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

public class NativeCaptionWindowsTest {
    private static final class Window implements NativeCaptionWindows.Window {
        int nativeId = -1;

        public void patch_setNativeWindowId(int id) {
            nativeId = id;
        }
    }

    private static final class Parser implements NativeCaptionWindows.Parser {
        final Map<Integer, Window> windows = new HashMap<>();
        final Map<Integer, Object> positions = new HashMap<>();
        final Map<Integer, Object> styles = new HashMap<>();

        public Map<Integer, ?> patch_getWindowDefinitions() {
            return windows;
        }

        public Map<Integer, ?> patch_getWindowPositions() {
            return positions;
        }

        public Map<Integer, ?> patch_getWindowStyles() {
            return styles;
        }
    }

    private static void apply(Parser parser, String id) {
        var stack = new ArrayDeque<Object>();
        stack.push(parser);
        var attrs = new AttributesImpl();
        if (id != null) attrs.addAttribute("", "id", "id", "CDATA", id);
        NativeCaptionWindows.onWindow(stack, attrs);
    }

    private static final class Style implements NativeCaptionWindows.Style {
        NativeCaptionWindowData.Definition definition;

        public NativeCaptionWindowData.Definition patch_getWindowStyle() {
            return definition;
        }

        public void patch_setWindowStyle(NativeCaptionWindowData.Definition value) {
            definition = value;
        }
    }

    private static final class Position implements NativeCaptionWindows.Position {
        NativeCaptionWindowData.Definition definition;

        public NativeCaptionWindowData.Definition patch_getWindowPosition() {
            return definition;
        }

        public void patch_setWindowPosition(NativeCaptionWindowData.Definition value) {
            definition = value;
        }
    }

    private static final class Paragraph implements NativeCaptionWindows.Paragraph {
        final Style style = new Style();
        final Position position = new Position();

        public Object patch_getWindowStyle() {
            return style;
        }

        public Object patch_getWindowPosition() {
            return position;
        }
    }

    private static final class Settings implements NativeCaptionWindows.Settings {
        NativeCaptionWindowData.Source source;

        public NativeCaptionWindowData.Source patch_getWindowSource() {
            return source;
        }

        public void patch_setWindowSource(NativeCaptionWindowData.Source value) {
            source = value;
        }
    }

    @Test
    public void authoredPropertiesReachNativeSettingsWithoutInventingDefaults() {
        var paragraph = new Paragraph();
        var settings = new Settings();
        paragraph.style.definition =
                new NativeCaptionWindowData.Definition(
                        NativeCaptionWindowData.Kind.STYLE,
                        Map.of("pd", "2", "wfc", "#123456", "wfo", "0"));
        paragraph.position.definition =
                new NativeCaptionWindowData.Definition(
                        NativeCaptionWindowData.Kind.POSITION, Map.of("cc", "8", "rc", "3"));
        NativeCaptionWindows.bindSettings(settings, paragraph);
        assertNotNull(settings.source);
        assertEquals(8, (int) settings.source.rows());
        assertEquals(3, (int) settings.source.columns());
        assertEquals("0", settings.source.style().fields().get("wfo"));
        paragraph.style.definition =
                new NativeCaptionWindowData.Definition(
                        NativeCaptionWindowData.Kind.STYLE, Map.of());
        paragraph.position.definition = null;
        NativeCaptionWindows.bindSettings(settings, paragraph);
        assertNull(settings.source);
    }

    @Test
    public void sourceStyleChangesAreNotCollapsedAsEqualNativeSettings() {
        var first = new Settings();
        var second = new Settings();
        first.source =
                new NativeCaptionWindowData.Source(
                        new NativeCaptionWindowData.Definition(
                                NativeCaptionWindowData.Kind.STYLE, Map.of("pd", "2")),
                        null);
        second.source =
                new NativeCaptionWindowData.Source(
                        new NativeCaptionWindowData.Definition(
                                NativeCaptionWindowData.Kind.STYLE, Map.of("pd", "3")),
                        null);
        assertFalse(NativeCaptionWindows.sameSettings(first, second));
        second.source = first.source;
        assertTrue(NativeCaptionWindows.sameSettings(first, second));
        first.source = second.source = null;
        assertTrue(NativeCaptionWindows.sameSettings(first, second));
    }

    @Test
    public void sourceOnlyChangesContributeToNativeSettingsHash() {
        var first = new Settings();
        var second = new Settings();
        assertEquals(7, NativeCaptionWindows.settingsHash(7, first));
        first.source =
                new NativeCaptionWindowData.Source(
                        new NativeCaptionWindowData.Definition(
                                NativeCaptionWindowData.Kind.STYLE, Map.of("pd", "2")),
                        null);
        second.source =
                new NativeCaptionWindowData.Source(
                        new NativeCaptionWindowData.Definition(
                                NativeCaptionWindowData.Kind.STYLE, Map.of("pd", "3")),
                        null);
        assertNotEquals(
                NativeCaptionWindows.settingsHash(7, first),
                NativeCaptionWindows.settingsHash(7, second));
        second.source = first.source;
        assertEquals(
                NativeCaptionWindows.settingsHash(7, first),
                NativeCaptionWindows.settingsHash(7, second));
    }

    @Test
    public void positionCallbackPreservesZeroAndDeclaredGeometry() {
        var parser = new Parser();
        var position = new Position();
        parser.positions.put(7, position);
        var stack = new ArrayDeque<Object>();
        stack.push(parser);
        var attrs = new AttributesImpl();
        for (String[] pair : new String[][] {{"id", "7"}, {"cc", "8"}, {"rc", "0"}, {"ah", "25"}})
            attrs.addAttribute("", pair[0], pair[0], "CDATA", pair[1]);
        NativeCaptionWindows.onPosition(stack, attrs);
        assertNotNull(position.definition);
        assertEquals("0", position.definition.fields().get("rc"));
        assertEquals("8", position.definition.fields().get("cc"));
        assertEquals("25", position.definition.fields().get("ah"));
    }

    @Test
    public void declaredJustificationSelectsNativeAlignmentWithoutReplacingDefault() {
        var paragraph = new Paragraph();
        var style = paragraph.style;
        assertEquals(0, NativeCaptionWindows.alignment(paragraph));
        int[] expected = {1, 3, 2};
        for (int ju = 0; ju < 3; ju++) {
            style.definition =
                    new NativeCaptionWindowData.Definition(
                            NativeCaptionWindowData.Kind.STYLE, Map.of("ju", Integer.toString(ju)));
            assertEquals(expected[ju], NativeCaptionWindows.alignment(paragraph));
        }
        assertEquals(0, NativeCaptionWindows.alignment(new Object()));
    }

    @Test
    public void styleCallbackKeepsAuthoredMetadataOnNativeDefinition() {
        var parser = new Parser();
        var style = new Style();
        parser.styles.put(7, style);
        var stack = new ArrayDeque<Object>();
        stack.push(parser);
        var attrs = new AttributesImpl();
        for (String[] pair : new String[][] {{"id", "7"}, {"ju", "0"}, {"wfo", "0"}, {"pd", "2"}})
            attrs.addAttribute("", pair[0], pair[0], "CDATA", pair[1]);
        NativeCaptionWindows.onStyle(stack, attrs);
        assertNotNull(style.definition);
        assertEquals("0", style.definition.fields().get("ju"));
        assertEquals("0", style.definition.fields().get("wfo"));
        assertEquals("2", style.definition.fields().get("pd"));
    }

    @Test
    public void logicalWindowsAvoidPositionAndDefaultWindowIdentities() {
        var parser = new Parser();
        parser.positions.put(1, new Object());
        parser.positions.put(3, new Object());
        parser.windows.put(5, new Window());
        parser.windows.put(6, new Window());
        apply(parser, "5");
        apply(parser, "6");
        int first = parser.windows.get(5).nativeId, second = parser.windows.get(6).nativeId;
        assertTrue(first > 0 && second > 0);
        assertNotEquals(first, second);
        assertFalse(parser.positions.containsKey(first));
        assertFalse(parser.positions.containsKey(second));
    }

    @Test
    public void redefinedWindowRetainsIdentityAndDifferentParsersOwnTheirBindings() {
        var parser = new Parser();
        parser.windows.put(5, new Window());
        apply(parser, "5");
        int original = parser.windows.get(5).nativeId;
        parser.windows.put(5, new Window());
        apply(parser, "5");
        assertEquals(original, parser.windows.get(5).nativeId);
        var other = new Parser();
        other.windows.put(6, new Window());
        apply(other, "6");
        // Each parser starts its own logical sequence instead of sharing native state.
        assertEquals(original, other.windows.get(6).nativeId);
    }

    @Test
    public void absentAndMalformedIdsUseNativeZeroDefault() {
        var parser = new Parser();
        parser.windows.put(0, new Window());
        apply(parser, null);
        assertTrue(parser.windows.get(0).nativeId > 0);
        int original = parser.windows.get(0).nativeId;
        apply(parser, "bad");
        assertEquals(original, parser.windows.get(0).nativeId);
    }

    @Test
    public void missingDefinitionsAndForeignParserLeaveStateUntouched() {
        var parser = new Parser();
        apply(parser, "999");
        var stack = new ArrayDeque<Object>();
        stack.push(new Object());
        NativeCaptionWindows.onWindow(stack, new AttributesImpl());
    }
}
