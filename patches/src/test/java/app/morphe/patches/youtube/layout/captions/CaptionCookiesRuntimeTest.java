package app.morphe.patches.youtube.layout.captions;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** Compiles the production helper with isolated synthetic startup preferences. */
public class CaptionCookiesRuntimeTest {
    private static final String CAPTION =
            "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en";

    @Test
    public void interleavedRequestsKeepTheirOwnCookieDecision() throws Exception {
        try (var api = new Api(true, "synthetic-cookie")) {
            var executor = Executors.newSingleThreadExecutor();
            var firstStarted = new CountDownLatch(1);
            var secondStarted = new CountDownLatch(1);
            try {
                var first = executor.submit(() -> {
                    api.begin(CAPTION);
                    firstStarted.countDown();
                    assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                    return api.decision(CAPTION);
                });
                assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
                api.begin("https://example.invalid/video");
                secondStarted.countDown();
                assertTrue(first.get(5, TimeUnit.SECONDS),
                        "A later foreign request must not disable this caption request");

                api.begin("https://example.invalid/video");
                api.begin(CAPTION);
                assertFalse(api.decision("https://example.invalid/video"),
                        "A caption request must not authorize a foreign request");
            } finally {
                secondStarted.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void onlyTrustedCaptionUrlsCanUseCookies() throws Exception {
        try (var api = new Api(true, "synthetic-cookie")) {
            for (String url : new String[] {
                    null, "not a URL", "http://www.youtube.com/api/timedtext?lang=en",
                    "https://example.invalid/?next=api/timedtext",
                    "https://youtube.com.example.invalid/api/timedtext?lang=en",
                    "https://youtube.com@evil.invalid/api/timedtext?lang=en",
                    "https://user@youtube.com/api/timedtext?lang=en",
                    "https://youtube.com:444/api/timedtext?lang=en",
                    "https://youtube.com/api/timedtext/other?lang=en",
                    "https://youtube.com/other?next=api/timedtext",
                    "https://youtube.com/api/timedtext?lang=en&lang=de"}) {
                api.begin(url);
                assertFalse(api.decision(url), String.valueOf(url));
            }
            api.begin(CAPTION);
            assertTrue(api.decision(CAPTION));
            String trusted = "https://youtube.com:443/api/timedtext?lang=en";
            api.begin(trusted);
            assertTrue(api.decision(trusted));
        }
    }

    @Test
    public void disabledOrEmptyStartupPreferencesNeverAuthorizeCookies() throws Exception {
        try (var disabled = new Api(false, "synthetic-cookie"); var empty = new Api(true, "")) {
            disabled.begin(CAPTION);
            empty.begin(CAPTION);
            assertFalse(disabled.decision(CAPTION));
            assertFalse(empty.decision(CAPTION));
        }
    }

    private static final class Api implements AutoCloseable {
        final Path output;
        final URLClassLoader loader;
        final Method requestDecision;

        Api(boolean enabled, String cookie) throws Exception {
            Path root = Path.of("").toAbsolutePath();
            while (!Files.isDirectory(root.resolve("extensions/youtube/src/main/java")))
                root = root.getParent();
            output = Files.createTempDirectory("caption-cookies-");
            Path logger = output.resolve("Logger.java");
            Files.writeString(logger, """
                    package app.morphe.extension.shared;
                    public class Logger {
                        public interface LogMessage { String buildMessageString(); }
                        public static void printDebug(LogMessage text, Exception error) {}
                    }
                    """);
            Path settings = output.resolve("Settings.java");
            Files.writeString(settings, """
                    package app.morphe.extension.youtube.settings;
                    public class Settings {
                        public static final Flag SET_CAPTION_COOKIES = new Flag(%s);
                        public static final Text CAPTION_COOKIES = new Text("%s");
                        public record Flag(boolean value) { public boolean get() { return value; } }
                        public record Text(String value) { public String get() { return value; } }
                    }
                    """.formatted(enabled, cookie));
            URL classes = root.resolve("extensions/youtube/build/intermediates/javac/release/"
                    + "compileReleaseJavaWithJavac/classes").toUri().toURL();
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "--release", "17", "-classpath", Path.of(classes.toURI()).toString(),
                    "-d", output.toString(), settings.toString(), logger.toString(),
                    root.resolve("extensions/youtube/src/main/java/app/morphe/extension/youtube/"
                            + "patches/CaptionCookiesPatch.java").toString()));
            loader = new URLClassLoader(new URL[] {output.toUri().toURL(), classes},
                    CaptionCookiesRuntimeTest.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve)
                        throws ClassNotFoundException {
                    if (name.startsWith("app.morphe.extension.youtube.settings.Settings")
                            || name.startsWith("app.morphe.extension.youtube.patches.CaptionCookiesPatch")
                            || name.startsWith("app.morphe.extension.youtube.patches.AutoTranslateCaptionsPatch")) {
                        synchronized (getClassLoadingLock(name)) {
                            Class<?> type = findLoadedClass(name);
                            if (type == null) type = findClass(name);
                            if (resolve) resolveClass(type);
                            return type;
                        }
                    }
                    return super.loadClass(name, resolve);
                }
            };
            Class<?> type = loader.loadClass("app.morphe.extension.youtube.patches.CaptionCookiesPatch");
            requestDecision = type.getMethod("shouldSetCookies", String.class);
        }

        void begin(String url) throws Exception {
            decision(url);
        }

        boolean decision(String url) throws Exception {
            return (Boolean) requestDecision.invoke(null, url);
        }

        @Override public void close() throws IOException {
            loader.close();
            try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
