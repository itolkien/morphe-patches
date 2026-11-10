package app.morphe.patches.youtube.layout.captions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** Tests the actual measurement entry route, not Android's span rendering or pixels. */
public class NativeCaptionPreferencesBoundaryTest {
    @Test public void penOnlyRowsReachPreferencesBeforeNativeMeasurement() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("extensions/youtube/src/main/java"))) root = root.getParent();
        Path classes = root.resolve("extensions/youtube/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes");
        Path android;
        Path platforms = Path.of(java.util.Objects.requireNonNull(
                System.getenv("ANDROID_HOME"), "ANDROID_HOME is required")).resolve("platforms");
        try (var paths = Files.walk(platforms, 2)) {
            android = paths.filter(path -> path.getFileName().toString().equals("android.jar")
                    && path.getParent().getFileName().toString().startsWith("android-37"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("SDK 37 platform is required"));
        }
        Path output = Files.createTempDirectory("caption-preference-route-");
        try {
            var sources = new LinkedHashMap<String, String>();
            sources.put("android/view/View.java", """
                    package android.view;
                    public class View {
                        public int getPaddingTop() { return 0; }
                        public int getPaddingBottom() { return 0; }
                        public static class MeasureSpec {
                            public static int getSize(int value) { return value; }
                        }
                    }
                    """);
            sources.put("app/morphe/extension/youtube/patches/NativeCaptionWindows.java", """
                    package app.morphe.extension.youtube.patches;
                    import java.util.*;
                    public class NativeCaptionWindows {
                        public interface Host {
                            NativeCaptionWindowData.Source patch_getWindowSource();
                        }
                        public static void onCue(Object view) { PreferenceRoute.events.add("cue"); }
                    }
                    """);
            sources.put("app/morphe/extension/youtube/patches/NativeCaptionPreferences.java", """
                    package app.morphe.extension.youtube.patches;
                    public class NativeCaptionPreferences {
                        static void prepareRows(android.view.View view, StableCaptionBlocks.Window window) {
                            PreferenceRoute.events.add("preferences");
                        }
                    }
                    """);
            sources.put("app/morphe/extension/youtube/patches/NativeCaptionWindowLayout.java", """
                    package app.morphe.extension.youtube.patches;
                    public class NativeCaptionWindowLayout {
                        static void prepare(android.view.View view, int width, int height) {
                            throw new AssertionError("No authored geometry exists for a pen-only cue");
                        }
                    }
                    """);
            sources.put("app/morphe/extension/youtube/patches/PreferenceRoute.java", """
                    package app.morphe.extension.youtube.patches;
                    import java.util.*;
                    public class PreferenceRoute extends android.view.View
                            implements NativeCaptionWindows.Host, StableCaptionBlocks.Window {
                        static final List<String> events = new ArrayList<>();
                        final List<CharSequence> rows = new ArrayList<>(List.of("pen-only source"));
                        public NativeCaptionWindowData.Source patch_getWindowSource() { return null; }
                        public Object patch_getCaptionCue() { return null; }
                        public Object patch_getCaptionMeasureRow() { return null; }
                        public int patch_getCaptionBackgroundColor() { return 0; }
                        public void patch_setCaptionMeasuredSize(int width, int height) {}
                        public List<CharSequence> patch_getFullCaptionLines() { return rows; }
                        public List<CharSequence> patch_getVisibleCaptionLines() { return rows; }
                        public static void run() {
                            var view = new PreferenceRoute();
                            NativeCaptionVertical.prepare(view, 100, 100);
                            events.add("measure");
                            if (!events.equals(List.of("cue", "preferences", "measure")))
                                throw new AssertionError("Pen-only measure route: " + events);
                        }
                    }
                    """);
            var args = new ArrayList<>(java.util.List.of("--release", "17", "-classpath",
                    classes + File.pathSeparator + android, "-d", output.toString()));
            for (var source : sources.entrySet()) {
                Path file = output.resolve(source.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue());
                args.add(file.toString());
            }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
            try (var loader = new URLClassLoader(new URL[] {
                    output.toUri().toURL(), classes.toUri().toURL(), android.toUri().toURL()},
                    getClass().getClassLoader())) {
                loader.loadClass("app.morphe.extension.youtube.patches.PreferenceRoute").getMethod("run").invoke(null);
            }
        } finally {
            try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
