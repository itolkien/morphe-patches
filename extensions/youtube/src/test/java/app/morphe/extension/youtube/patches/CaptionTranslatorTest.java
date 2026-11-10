package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

public class CaptionTranslatorTest {
    @Test
    public void batchFreezesPartialResultsBeforeDelivery() {
        var input = new ArrayList<>(Arrays.asList("eins", null));
        var batch = new CaptionTranslator.Batch(input, 5000);
        input.set(0, "replaced");
        assertEquals(Arrays.asList("eins", null), batch.texts);
        assertThrows(UnsupportedOperationException.class, () -> batch.texts.set(1, "later"));
    }
}
