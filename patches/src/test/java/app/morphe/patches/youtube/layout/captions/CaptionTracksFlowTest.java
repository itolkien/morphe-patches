package app.morphe.patches.youtube.layout.captions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** Runs compiled selection/capture/loading code with controlled native/platform boundaries. */
public class CaptionTracksFlowTest {
    @Test public void newestCaptureSurvivesFullCapacity() throws Exception { run("captureLatest"); }
    @Test public void supersededCaptureCannotPublish() throws Exception { run("captureSuperseded"); }
    @Test public void uploadedLanguagesArriveIndependently() throws Exception { run("arrivalOrders"); }
    @Test public void timeoutStillAcceptsCurrentLateSuccess() throws Exception { run("lateSuccess"); }
    @Test public void oldCallbacksAndTimeoutCannotRefreshReplacement() throws Exception { run("staleUpload"); }
    @Test public void noUploadStillRefreshesPausedStyledPrimary() throws Exception { run("pausedPrimary"); }
    @Test public void failedLanguageKeepsSuccessfulSibling() throws Exception { run("independentFailures"); }
    @Test public void synchronousLoaderFailureStillRequestsOtherLanguage() throws Exception { run("synchronousFailure"); }
    @Test public void plainAudioReplacementInvalidatesOldUploads() throws Exception { run("sourceReplacement", false); }
    @Test public void styledAudioReplacementInvalidatesOldUploads() throws Exception { run("sourceReplacement", true); }
    @Test public void plainCatalogReplacementInvalidatesOldUploads() throws Exception { run("catalogReplacement", false); }
    @Test public void styledCatalogReplacementInvalidatesOldUploads() throws Exception { run("catalogReplacement", true); }
    @Test public void plainTimeoutStillAcceptsCurrentLateSuccess() throws Exception { run("lateSuccess", false); }

    private void run(String scenario) throws Exception {
        run(scenario, true);
    }

    private void run(String scenario, boolean preserveStyles) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("extensions/youtube/src/main/java"))) root = root.getParent();
        Path classes = root.resolve("extensions/youtube/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes");
        Path output = Files.createTempDirectory("caption-flow-");
        try {
            var sources = new LinkedHashMap<String, String>();
            sources.put("app/morphe/extension/shared/settings/BooleanSetting.java", """
                    package app.morphe.extension.shared.settings;
                    public class BooleanSetting {
                        private Boolean value;
                        public BooleanSetting(boolean value) { this.value = value; }
                        public Boolean get() { return value; }
                        public void save(Object value) { this.value = (Boolean) value; }
                    }
                    """);
            sources.put("app/morphe/extension/shared/settings/StringSetting.java", """
                    package app.morphe.extension.shared.settings;
                    public class StringSetting {
                        private String value;
                        public StringSetting(String value) { this.value = value; }
                        public String get() { return value; }
                        public void save(Object value) { this.value = (String) value; }
                    }
                    """);
            sources.put("app/morphe/extension/youtube/settings/Settings.java", """
                    package app.morphe.extension.youtube.settings;
                    import app.morphe.extension.shared.settings.*;
                    public class Settings {
                        public static final BooleanSetting DUAL_CAPTIONS_ENABLED = new BooleanSetting(true);
                        public static final StringSetting SECONDARY_CAPTION_LANGUAGE = new StringSetting("de");
                        public static final StringSetting PREFERRED_CAPTION_LANGUAGE = new StringSetting("en");
                    }
                    """);
            sources.put("app/morphe/extension/shared/Logger.java", """
                    package app.morphe.extension.shared;
                    public class Logger {
                        public interface LogMessage { String buildMessageString(); }
                        public static void printDebug(LogMessage message) {}
                        public static void printDebug(LogMessage message, Exception error) {}
                        public static void printInfo(LogMessage message) {}
                        public static void printInfo(LogMessage message, Exception error) {}
                        public static void printException(LogMessage message, Throwable error) {
                            throw new AssertionError(message.buildMessageString(), error);
                        }
                    }
                    """);
            sources.put("app/morphe/extension/shared/Utils.java", """
                    package app.morphe.extension.shared;
                    import java.util.*;
                    public class Utils {
                        public static final Deque<Runnable> workers = new ArrayDeque<>(), main = new ArrayDeque<>();
                        public static final List<Runnable> delayed = new ArrayList<>();
                        public static void runOnBackgroundThread(Runnable task) { workers.add(task); }
                        public static void runOnMainThread(Runnable task) { main.add(task); }
                        public static void runOnMainThreadNowOrLater(Runnable task) { main.add(task); }
                        public static void runOnMainThreadDelayed(Runnable task, long delay) {
                            if (delay != 5000) throw new AssertionError("Unexpected upload timeout");
                            delayed.add(task);
                        }
                        public static boolean isCurrentlyOnMainThread() { return true; }
                        public static void showToastShort(String message) {}
                        public static void drain() {
                            while (!workers.isEmpty() || !main.isEmpty()) {
                                while (!workers.isEmpty()) workers.remove().run();
                                while (!main.isEmpty()) main.remove().run();
                            }
                        }
                    }
                    """);
            sources.put("app/morphe/extension/shared/StringRef.java", """
                    package app.morphe.extension.shared;
                    public class StringRef { public static String str(String value) { return value; } }
                    """);
            sources.put("app/morphe/extension/shared/requests/Requester.java", """
                    package app.morphe.extension.shared.requests;
                    public class Requester {
                        public static java.util.Locale getAppLocale() { return java.util.Locale.ENGLISH; }
                        public static java.net.HttpURLConnection openConnection(String url) {
                            throw new AssertionError("Flow test must not start provider network work");
                        }
                    }
                    """);
            sources.put("app/morphe/extension/youtube/patches/VideoInformation.java", """
                    package app.morphe.extension.youtube.patches;
                    public class VideoInformation {
                        public static long getVideoTime() { return 100; }
                        public static float getPlaybackSpeed() { return 1.25f; }
                    }
                    """);
            sources.put("app/morphe/extension/youtube/patches/CaptionBlockTranslator.java", """
                    package app.morphe.extension.youtube.patches;
                    import java.util.List;
                    public class CaptionBlockTranslator implements CaptionTranslator {
                        static final CaptionBlockTranslator DEFAULT = new CaptionBlockTranslator();
                        void cancelInactive() {}
                        public List<String> translate(String source, String target, List<String> texts) {
                            throw new AssertionError("Flow fixture must not invoke provider HTTP");
                        }
                    }
                    """);
            sources.put("app/morphe/extension/youtube/patches/TranslatedCaptionPensPatch.java", """
                    package app.morphe.extension.youtube.patches;
                    public class TranslatedCaptionPensPatch {
                        public interface CaptionRequestInterface { Object patch_getCaptionTrack(); }
                        public static boolean styles = true;
                        public static boolean preserveTranslatedStyles() { return styles; }
                    }
                    """);
            sources.put("app/morphe/extension/youtube/patches/NativeCaptionRuns.java", """
                    package app.morphe.extension.youtube.patches;
                    public class NativeCaptionRuns {
                        public static NativeCaptionRunPlan capture(CharSequence text) { return null; }
                    }
                    """);
            sources.put("app/morphe/extension/youtube/patches/NativeCaptionRuby.java", """
                    package app.morphe.extension.youtube.patches;
                    public class NativeCaptionRuby {
                        public static CharSequence sourceText(CharSequence text) { return text; }
                    }
                    """);
            sources.put("app/morphe/extension/youtube/patches/StableCaptionBlocks.java", """
                    package app.morphe.extension.youtube.patches;
                    import java.util.*;
                    public class StableCaptionBlocks {
                        static boolean isEnabled() { return true; }
                        static void requestLayout(State state) {}
                        static void refreshTranslation(State state, CaptionSession session) {}
                        public interface Timeline {
                            List<?> patch_getOriginalCaptions(long time);
                            State patch_getBlockState();
                            void patch_setBlockState(State state);
                            Timeline patch_mergeWith(Timeline other);
                            List<Long> patch_getCaptionEventTimes();
                        }
                        public interface Cue {
                            int patch_getCaptionId();
                            boolean patch_isCaptionVisible();
                            CharSequence patch_getOriginalCaptionFull();
                        }
                        public static class Frame {}
                        public static class State {
                            final boolean automatic;
                            NativeCaptionPair nativePair;
                            boolean nativeSource;
                            StableCaptionBlockPolicy policy;
                            CaptionSession translation;
                            CaptionTracks.Captured captured;
                            State(boolean automatic) { this.automatic = automatic; }
                        }
                    }
                    """);
            try (var fixture = getClass().getResourceAsStream("/captions/CaptionFlowFixture.java")) {
                sources.put("app/morphe/extension/youtube/patches/CaptionFlowFixture.java",
                        new String(fixture.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            var args = new ArrayList<>(java.util.List.of(
                    "--release", "17", "-classpath", classes.toString(), "-d", output.toString()));
            for (Map.Entry<String, String> source : sources.entrySet()) {
                Path file = output.resolve(source.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue());
                args.add(file.toString());
            }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                    null, null, null, args.toArray(String[]::new)));
            try (var loader = new URLClassLoader(new java.net.URL[] {
                    output.toUri().toURL(), classes.toUri().toURL()}, getClass().getClassLoader())) {
                loader.loadClass("app.morphe.extension.youtube.patches.TranslatedCaptionPensPatch")
                        .getField("styles").setBoolean(null, preserveStyles);
                loader.loadClass("app.morphe.extension.youtube.patches.CaptionFlowFixture")
                        .getMethod(scenario).invoke(null);
            }
        } finally {
            try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
