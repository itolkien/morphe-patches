package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;
import org.xml.sax.helpers.AttributesImpl;

import java.util.HashMap;
import java.util.Map;

public class NativeCaptionWindowDataTest {
    private AttributesImpl attributes(String... pairs) {
        var result = new AttributesImpl();
        for (int i = 0; i < pairs.length; i += 2)
            result.addAttribute("", pairs[i], pairs[i], "CDATA", pairs[i + 1]);
        return result;
    }

    @Test
    public void inheritsKnownFieldsAndKeepsExplicitZeroOverrides() {
        var parent =
                new NativeCaptionWindowData.Definition(
                        NativeCaptionWindowData.Kind.STYLE,
                        Map.of("pd", "2", "sd", "1", "ju", "2", "wfc", "#123456", "wfo", "128"));
        var child =
                NativeCaptionWindowData.resolve(
                        NativeCaptionWindowData.Kind.STYLE,
                        parent,
                        attributes("pd", "0", "wfo", "0"));
        assertEquals("0", child.fields().get("pd"));
        assertEquals("0", child.fields().get("wfo"));
        assertEquals("#123456", child.fields().get("wfc"));
        assertEquals("1", child.fields().get("sd"));
        assertEquals("2", parent.fields().get("pd"));
    }

    @Test
    public void enrichesAttributesWithoutChangingOriginalOrDroppingUnknownFields() {
        var attrs = attributes("id", "7", "future", "retained");
        var data =
                new NativeCaptionWindowData.Definition(
                        NativeCaptionWindowData.Kind.POSITION,
                        Map.of("cc", "12", "rc", "2", "ah", "50"));
        var result = NativeCaptionWindowData.enriched(attrs, data);
        assertNull(attrs.getValue("cc"));
        assertEquals("12", result.getValue("cc"));
        assertEquals("retained", result.getValue("future"));
        assertEquals("7", result.getValue("id"));
    }

    @Test
    public void invalidNumbersDoNotEraseInheritedValues() {
        var parent =
                new NativeCaptionWindowData.Definition(
                        NativeCaptionWindowData.Kind.STYLE, Map.of("pd", "2"));
        var result =
                NativeCaptionWindowData.resolve(
                        NativeCaptionWindowData.Kind.STYLE,
                        parent,
                        attributes("pd", "bad", "sd", " 1 ", "future", "unused"));
        assertEquals("2", result.fields().get("pd"));
        assertEquals("1", result.fields().get("sd"));
        assertFalse(result.fields().containsKey("future"));
    }

    @Test
    public void definitionsOwnTheirFields() {
        var fields = new HashMap<>(Map.of("pd", "2"));
        var definition =
                new NativeCaptionWindowData.Definition(NativeCaptionWindowData.Kind.STYLE, fields);
        fields.put("pd", "0");
        assertEquals(2, (int) definition.integer("pd"));
        assertThrows(UnsupportedOperationException.class, () -> definition.fields().put("pd", "0"));
    }

    @Test
    public void verticalExtentsSwapAxesAndKeepZeroDistinctFromUnspecified() {
        var style =
                new NativeCaptionWindowData.Definition(
                        NativeCaptionWindowData.Kind.STYLE, Map.of("pd", "2"));
        var position =
                new NativeCaptionWindowData.Definition(
                        NativeCaptionWindowData.Kind.POSITION, Map.of("cc", "2", "rc", "0"));
        var vertical = new NativeCaptionWindowData.Source(style, position);
        assertTrue(vertical.vertical());
        assertEquals(0, (int) vertical.columns());
        assertEquals(2, (int) vertical.rows());
        var horizontal = new NativeCaptionWindowData.Source(null, position);
        assertFalse(horizontal.vertical());
        assertEquals(2, (int) horizontal.columns());
        assertEquals(0, (int) horizontal.rows());
        assertNull(new NativeCaptionWindowData.Source(style, null).rows());
    }
}
