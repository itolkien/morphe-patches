package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exercises native callback ownership without replacing the native request or renderer. */
public class NativeCaptionOwnershipTest {
    private final Map<Field, Object> previous = new LinkedHashMap<>();

    @Before
    public void saveState() throws Exception {
        for (String name :
                List.of("video", "source", "selectedInputLanguage", "selectionDisabled",
                        "activeTranslation")) {
            Field field = field(name);
            previous.put(field, field.get(null));
        }
    }

    @After
    public void restoreState() throws Exception {
        for (var entry : previous.entrySet()) entry.getKey().set(null, entry.getValue());
    }

    private static Field field(String name) throws Exception {
        Field field = CaptionTracks.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void set(String name, Object value) throws Exception {
        field(name).set(null, value);
    }

    private static Object get(String name) throws Exception {
        return field(name).get(null);
    }

    // Loader ordering, stale delivery and per-language failures are exercised through
    // the real selection/loader entry points in CaptionTracksFlowTest (patches JVM suite).

    @Test
    @SuppressWarnings("unchecked")
    public void lateAudioVariantIsCachedWithoutBecomingActiveOutput() throws Exception {
        String url = "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en&audio_track_id=";
        var old = new UploadedCaptionTracksTest.Track("en", ".en", url + "one");
        var selected = new UploadedCaptionTracksTest.Track("en", ".en", url + "two");
        set("video", "abcdefghijk");
        set("source", CaptionTracks.sourceIdentity(selected));
        set("selectedInputLanguage", "en");
        set("selectionDisabled", false);
        Object session = get("activeTranslation");
        var type = Class.forName(CaptionTracks.class.getName() + "$Track");
        var constructor =
                type.getDeclaredConstructor(
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        boolean.class,
                        boolean.class);
        constructor.setAccessible(true);
        var parsing = (ThreadLocal<Object>) get("parsing");
        Object previousTrack = parsing.get();
        try {
            parsing.set(
                    constructor.newInstance(
                            CaptionTracks.sourceIdentity(old),
                            "en",
                            null,
                            "abcdefghijk",
                            false,
                            false));
            var state = new StableCaptionBlocks.State(false);
            CaptionTracks.observe(
                    CaptionXml.read(
                            ("<timedtext format='3'><body>"
                                            + "<p t='0' d='1000'>Old audio</p></body></timedtext>")
                                    .getBytes(StandardCharsets.UTF_8)),
                    state);
            assertNotNull(state.captured);
            assertNotNull(state.policy);
            assertNull("Old audio must not own the selected output", state.translation);
            assertSame(session, get("activeTranslation"));
        } finally {
            if (previousTrack == null) parsing.remove();
            else parsing.set(previousTrack);
        }
    }
}
