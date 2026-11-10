package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;
import org.xml.sax.helpers.AttributesImpl;

public class NativeCaptionAppearanceTest {
    private AttributesImpl attributes(String... pairs) {
        var result = new AttributesImpl();
        for (int i = 0; i < pairs.length; i += 2)
            result.addAttribute("", pairs[i], pairs[i], "CDATA", pairs[i + 1]);
        return result;
    }

    @Test
    public void absentFieldsStayNative() {
        var style = NativeCaptionAppearance.read(attributes(), null);
        assertNull(style.background());
        assertNull(style.opacity());
        assertNull(style.size());
        assertNull(style.edge());
        assertNull(style.edgeColor());
    }

    @Test
    public void inheritedFieldsRemainIndependent() {
        var parent =
                NativeCaptionAppearance.read(
                        attributes(
                                "bc", "#FF0000", "bo", "128", "sz", "50", "et", "1", "ec",
                                "#0000FF"),
                        null);
        var child = NativeCaptionAppearance.read(attributes("bo", "0", "sz", "0"), parent);
        assertEquals(Integer.valueOf(0xFFFF0000), child.background());
        assertEquals(Integer.valueOf(0), child.opacity());
        assertEquals(Integer.valueOf(0), child.size());
        assertEquals(parent.edge(), child.edge());
        assertEquals(parent.edgeColor(), child.edgeColor());
        assertEquals(Integer.valueOf(128), parent.opacity());
    }

    @Test
    public void malformedFieldsRetainParent() {
        var parent =
                NativeCaptionAppearance.read(
                        attributes("bc", "#112233", "bo", "123", "sz", "100"), null);
        assertEquals(
                parent,
                NativeCaptionAppearance.read(
                        attributes("bc", "bad", "bo", "NaN", "sz", "99999999999"), parent));
    }

    @Test
    public void sizeScaleMatchesTheNativePenReader() {
        assertEquals(0f, NativeCaptionAppearance.nativeScale(0), 0f);
        assertEquals(.3f, NativeCaptionAppearance.nativeScale(30), .00001f);
        assertEquals(.5f, NativeCaptionAppearance.nativeScale(50), .00001f);
        assertEquals(1f, NativeCaptionAppearance.nativeScale(100), 0f);
        assertEquals(1.1875f, NativeCaptionAppearance.nativeScale(175), .00001f);
        assertEquals(1.5f, NativeCaptionAppearance.nativeScale(300), .00001f);
    }

    @Test
    public void authoredSizeScaleFollowsSrv3() {
        assertEquals(.75f, NativeCaptionAppearance.authoredScale(0), .00001f);
        assertEquals(.825f, NativeCaptionAppearance.authoredScale(30), .00001f);
        assertEquals(.875f, NativeCaptionAppearance.authoredScale(50), .00001f);
        assertEquals(1f, NativeCaptionAppearance.authoredScale(100), .00001f);
        assertEquals(1.1875f, NativeCaptionAppearance.authoredScale(175), .00001f);
        assertEquals(1.5f, NativeCaptionAppearance.authoredScale(300), .00001f);
    }

    @Test
    public void disabledRichTextKeepsOriginalObject() {
        var text = "native unstyled";
        assertSame(
                text,
                NativeCaptionAppearance.apply(
                        text, NativeCaptionAppearance.read(attributes("bc", "#FF0000"), null)));
    }
}
