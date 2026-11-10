package app.morphe.extension.youtube.patches;
import org.junit.Test;
import static org.junit.Assert.*;
public class CaptionVisibilityPolicyTest {
    @Test public void profilesRemainIndependentAcrossFormatSwitches() {
        var policy = new CaptionVisibilityPolicy(-1, -1);
        assertNull(policy.remembered(false));
        assertNull(policy.remembered(true));
        policy.remember(false, true);
        policy.remember(true, false);
        assertEquals(Boolean.TRUE, policy.remembered(false));
        assertEquals(Boolean.FALSE, policy.remembered(true));
        policy.remember(true, true);
        assertEquals(Boolean.TRUE, policy.remembered(false));
        assertEquals(Boolean.TRUE, policy.remembered(true));
        var restored = new CaptionVisibilityPolicy(1, 0);
        assertEquals(Boolean.TRUE, restored.remembered(false));
        assertEquals(Boolean.FALSE, restored.remembered(true));
    }
}
