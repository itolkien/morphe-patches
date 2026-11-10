package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;
import org.junit.Test;

public class NativeCaptionWindowRoutingTest {
    @Test public void styledOutputRequiresWindowSamplingEvenWhenPlainXmlWasCaptured() {
        var state=new StableCaptionBlocks.State(false);
        assertTrue(CaptionTracks.needsNativeSourceCapture(state,true));
        assertFalse(CaptionTracks.needsNativeSourceCapture(state,false));
        assertTrue(CaptionTracks.needsNativeSourceCapture(null,false));
        state.nativeSource=true;
        assertFalse(CaptionTracks.needsNativeSourceCapture(state,true));
    }
}
