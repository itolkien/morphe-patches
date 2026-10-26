package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Regression tests for a delayed caption unlock from the previous video. */
public class AutoCaptionsGenerationTest {
    private static int generation() throws Exception {
        Field field = AutoCaptionsPatch.class.getDeclaredField("videoGeneration");
        field.setAccessible(true);
        return ((AtomicInteger) field.get(null)).get();
    }

    @Test public void staleVideoCallbackDoesNotUnlockNextVideo() throws Exception {
        AutoCaptionsPatch.newVideoStarted(null);
        int oldGeneration = generation();
        AutoCaptionsPatch.newVideoStarted(null);
        int currentGeneration = generation();

        assertFalse(AutoCaptionsPatch.unlockIfCurrentVideo(oldGeneration));
        assertTrue(AutoCaptionsPatch.unlockIfCurrentVideo(currentGeneration));
    }

    @Test public void currentVideoCallbackUnlocksOnce() throws Exception {
        AutoCaptionsPatch.newVideoStarted(null);
        int currentGeneration = generation();
        assertTrue(AutoCaptionsPatch.unlockIfCurrentVideo(currentGeneration));
        assertFalse(AutoCaptionsPatch.unlockIfCurrentVideo(currentGeneration));
    }
}
