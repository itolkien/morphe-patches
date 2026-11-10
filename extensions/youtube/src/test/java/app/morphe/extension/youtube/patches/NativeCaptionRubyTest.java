package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NativeCaptionRubyTest {
    @Test
    public void srv3RubyRolesFourSixAreAboveAndFiveSevenAreBelow() {
        assertFalse(NativeCaptionRuby.annotationUnder(4));
        assertTrue(NativeCaptionRuby.annotationUnder(5));
        assertFalse(NativeCaptionRuby.annotationUnder(6));
        assertTrue(NativeCaptionRuby.annotationUnder(7));
    }

    @Test
    public void emphasisRubyRolesKeepTheSameAlternatingPosition() {
        assertFalse(NativeCaptionRuby.annotationUnder(10));
        assertTrue(NativeCaptionRuby.annotationUnder(11));
        assertFalse(NativeCaptionRuby.annotationUnder(12));
        assertTrue(NativeCaptionRuby.annotationUnder(13));
    }
}
