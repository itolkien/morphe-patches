package app.morphe.patches.youtube.layout.captions;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the injected automatic-visibility methods with a controlled clock and persisted preferences. */
public class CaptionVisibilityBoundaryTest {
    @Test public void restoresProfilesAndPreservesManualToggles() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("extensions/youtube/src/main/java"))) root = root.getParent();
        Path output = Files.createTempDirectory("caption-visibility-");
        try {
            Map<String, String> sources = new LinkedHashMap<>();
            sources.put("android/os/SystemClock.java", """
                package android.os;
                public class SystemClock {
                    public static long now;
                    public static long uptimeMillis() { return now; }
                }
                """);
            sources.put("app/morphe/extension/youtube/patches/VideoInformation.java", """
                package app.morphe.extension.youtube.patches;
                public class VideoInformation {
                    public static boolean shorts;
                    public static boolean isCurrentPlayerShorts() { return shorts; }
                    public interface PlaybackController {}
                }
                """);
            sources.put("app/morphe/extension/youtube/settings/Settings.java", """
                package app.morphe.extension.youtube.settings;
                import app.morphe.extension.youtube.patches.AutoCaptionsPatch.AutoCaptionsStyle;
                public class Settings {
                    public static class Value<T> {
                        T value;
                        public Value(T value) { this.value = value; }
                        public T get() { return value; }
                        public void save(T value) { this.value = value; }
                    }
                    public static final Value<Integer> CAPTION_VISIBILITY_REGULAR = new Value<>(-1);
                    public static final Value<Integer> CAPTION_VISIBILITY_SHORTS = new Value<>(-1);
                    public static final Value<AutoCaptionsStyle> AUTO_CAPTIONS_STYLE =
                            new Value<>(AutoCaptionsStyle.BOTH_ENABLED);
                }
                """);
            sources.put("app/morphe/extension/youtube/patches/VisibilityFixture.java", """
                package app.morphe.extension.youtube.patches;
                import app.morphe.extension.youtube.settings.Settings;
                import android.os.SystemClock;
                public class VisibilityFixture {
                    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
                    public static void run() {
                        // No manual choice yet: Always show wins.
                        check(!AutoCaptionsPatch.disableAutoCaptions(true));

                        // Manual off in regular videos is remembered independently.
                        VideoInformation.shorts = false;
                        AutoCaptionsPatch.rememberVisibility(false);
                        check(Settings.CAPTION_VISIBILITY_REGULAR.get() == 0);
                        check(AutoCaptionsPatch.disableAutoCaptions(true));

                        // Manual on in Shorts does not overwrite the regular profile.
                        VideoInformation.shorts = true;
                        AutoCaptionsPatch.rememberVisibility(true);
                        check(Settings.CAPTION_VISIBILITY_REGULAR.get() == 0);
                        check(Settings.CAPTION_VISIBILITY_SHORTS.get() == 1);
                        AutoCaptionsPatch.newVideoStarted(null);
                        check(!AutoCaptionsPatch.disableAutoCaptions(false));
                        check(AutoCaptionsPatch.disableMuteAutoCaptions(true));
                        AutoCaptionsPatch.videoInformationLoaded();
                        SystemClock.now = 151;
                        check(!AutoCaptionsPatch.disableAutoCaptions(false));
                        // Repeated load notifications cannot override a manual toggle after the load guard.
                        AutoCaptionsPatch.videoInformationLoaded();
                        check(!AutoCaptionsPatch.disableAutoCaptions(false));

                        // Returning to a regular video restores its own profile.
                        VideoInformation.shorts = false;
                        AutoCaptionsPatch.newVideoStarted(null);
                        check(AutoCaptionsPatch.disableAutoCaptions(true));
                        check(!AutoCaptionsPatch.disableMuteAutoCaptions(false));
                    }
                }
                """);
            var args = new ArrayList<>(List.of("--release", "17", "-d", output.toString()));
            for (var source : sources.entrySet()) {
                Path file = output.resolve(source.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue());
                args.add(file.toString());
            }
            for (String name : List.of("AutoCaptionsPatch", "CaptionVisibilityPolicy")) {
                args.add(root.resolve("extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/"
                        + name + ".java").toString());
            }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
            try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, null)) {
                loader.loadClass("app.morphe.extension.youtube.patches.VisibilityFixture")
                        .getMethod("run").invoke(null);
            }
        } finally {
            try (var files = Files.walk(output)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
