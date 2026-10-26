package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Regression coverage for the conservative Fold fullscreen geometry policy. */
public class CaptionAspectRatioFixPolicyTest {
    @Test public void foldNearSquareFullscreenIsEligible() {
        assertTrue(CaptionAspectRatioFix.isNearSquareLandscape(2184, 1968));
        assertTrue(CaptionAspectRatioFix.isNearSquareLandscape(1968, 1946));
    }

    @Test public void fourByThreeAndIntermediateWindowRemainNative() {
        assertFalse(CaptionAspectRatioFix.isNearSquareLandscape(1080, 810));
        assertFalse(CaptionAspectRatioFix.isNearSquareLandscape(978, 734));
        assertFalse(CaptionAspectRatioFix.isNearSquareLandscape(2055, 1553));
        assertFalse(CaptionAspectRatioFix.isNearSquareLandscape(2184, 1228));
        assertFalse(CaptionAspectRatioFix.isNearSquareLandscape(1080, 1920));
    }

    @Test public void onlySixteenByNineLikeReferenceIsTrusted() {
        assertTrue(CaptionAspectRatioFix.isTrustedReferenceAspect(9.0f / 16.0f));
        assertTrue(CaptionAspectRatioFix.isTrustedReferenceAspect(0.57157785f));
        assertFalse(CaptionAspectRatioFix.isTrustedReferenceAspect(0.42857143f));
        assertFalse(CaptionAspectRatioFix.isTrustedReferenceAspect(0.7357724f));
        assertFalse(CaptionAspectRatioFix.isTrustedReferenceAspect(0.75f));
        assertFalse(CaptionAspectRatioFix.isTrustedReferenceAspect(Float.NaN));
        assertFalse(CaptionAspectRatioFix.isTrustedReferenceAspect(Float.POSITIVE_INFINITY));
    }
}
