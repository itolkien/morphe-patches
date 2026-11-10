package app.morphe.patches.youtube.layout.captions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every hand-written smali reference to an extension class must resolve to a declared method.
 * A drifted descriptor only fails at runtime, inside the native caption parser, as
 * {@code NoSuchMethodError}.
 */
public class CaptionInjectedDescriptorsTest {
    private static final Pattern CONSTANT =
            Pattern.compile("(?:const\\s+)?val\\s+(\\w+)\\s*=\\s*\\n?\\s*\"([^\"]*)\"");
    private static final Pattern REFERENCE =
            Pattern.compile(
                    "(\\$\\{?\\w+}?|Lapp/morphe/extension/[\\w/$]+;)->(\\w+)"
                            + "(\\([^)]*\\)(?:\\[*(?:L[^;]+;|[ZBSCIJFDV])))");
    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{?(\\w+)}?");

    private record Reference(String owner, String name, String descriptor) {}

    @Test
    public void captionHooksResolveAgainstTheBuiltExtension() throws Exception {
        Path root = repositoryRoot();
        Path captions =
                root.resolve("patches/src/main/kotlin/app/morphe/patches/youtube/layout/captions");
        List<Path> sources;
        try (Stream<Path> files = Files.list(captions)) {
            sources = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".kt"))
                    .toList();
        }
        assertTrue(sources.size() > 10, "Caption patch sources were not found");

        Map<String, Set<String>> shared = new HashMap<>();
        for (Path source : sources) collectShared(Files.readString(source), shared);

        Set<Reference> references = new LinkedHashSet<>();
        for (Path source : sources) {
            String text = Files.readString(source);
            Map<String, String> local = new HashMap<>();
            Matcher constant = CONSTANT.matcher(text);
            while (constant.find()) local.put(constant.group(1), unescape(constant.group(2)));
            Matcher reference = REFERENCE.matcher(text);
            while (reference.find()) {
                String owner = resolveOwner(reference.group(1), local, shared);
                String descriptor = resolveDescriptor(reference.group(3), local, shared);
                if (owner == null || descriptor == null) continue;
                if (!owner.startsWith("Lapp/morphe/extension/")) continue;
                references.add(new Reference(owner, reference.group(2), descriptor));
            }
        }
        assertTrue(
                references.size() > 20,
                "Descriptor scanner found too few extension references: " + references.size());

        try (URLClassLoader loader = extensionLoader(root)) {
            List<String> missing = new ArrayList<>();
            for (Reference reference : references) {
                Class<?> owner =
                        Class.forName(binaryName(reference.owner()), false, loader);
                try {
                    Method method = owner.getDeclaredMethod(reference.name(), types(reference.descriptor(), loader));
                    if (!descriptor(method.getReturnType()).equals(returnType(reference.descriptor()))) {
                        missing.add(reference + " returns " + descriptor(method.getReturnType()));
                    }
                } catch (NoSuchMethodException absent) {
                    missing.add(reference.toString());
                }
            }
            assertEquals(List.of(), missing, "Injected extension descriptors do not resolve");
        }
    }

    private static void collectShared(String text, Map<String, Set<String>> shared) {
        Matcher constant = CONSTANT.matcher(text);
        while (constant.find())
            shared.computeIfAbsent(constant.group(1), ignored -> new HashSet<>())
                    .add(unescape(constant.group(2)));
    }

    private static String resolveOwner(
            String token, Map<String, String> local, Map<String, Set<String>> shared) {
        if (token.startsWith("Lapp/morphe/extension/")) return token;
        Matcher variable = TEMPLATE.matcher(token);
        if (!variable.matches()) return null;
        String name = variable.group(1);
        if (local.containsKey(name)) return local.get(name);
        Set<String> values = shared.get(name);
        return values != null && values.size() == 1 ? values.iterator().next() : null;
    }

    private static String resolveDescriptor(
            String descriptor, Map<String, String> local, Map<String, Set<String>> shared) {
        Matcher template = TEMPLATE.matcher(descriptor);
        StringBuilder result = new StringBuilder();
        int start = 0;
        while (template.find()) {
            if (template.group(1).matches("\\d+")) continue;
            String value = local.get(template.group(1));
            if (value == null) {
                Set<String> values = shared.get(template.group(1));
                if (values == null || values.size() != 1) return null;
                value = values.iterator().next();
            }
            result.append(descriptor, start, template.start()).append(value);
            start = template.end();
        }
        result.append(descriptor.substring(start));
        return result.toString();
    }

    private static String unescape(String value) {
        return value.replace("\\$", "$");
    }

    private static URLClassLoader extensionLoader(Path root) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(
                root.resolve(
                                "extensions/youtube/build/intermediates/javac/release/"
                                        + "compileReleaseJavaWithJavac/classes")
                        .toUri()
                        .toURL());
        Path sdk = Path.of(System.getenv("ANDROID_HOME"));
        Path android;
        try (Stream<Path> paths = Files.walk(sdk.resolve("platforms"), 2)) {
            android = paths.filter(
                            path ->
                                    path.getFileName().toString().equals("android.jar")
                                            && path.getParent()
                                                    .getFileName()
                                                    .toString()
                                                    .startsWith("android-37"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("SDK 37 platform is required"));
        }
        urls.add(android.toUri().toURL());
        try (Stream<Path> paths = Files.walk(root.resolve("libraries/vertical-text/build"))) {
            paths.filter(path -> path.getFileName().toString().equals("classes.jar"))
                    .forEach(path -> {
                        try {
                            urls.add(path.toUri().toURL());
                        } catch (Exception invalid) {
                            throw new IllegalStateException(invalid);
                        }
                    });
        }
        return new URLClassLoader(urls.toArray(URL[]::new), null);
    }

    private static String binaryName(String owner) {
        return owner.substring(1, owner.length() - 1).replace('/', '.');
    }

    private static Class<?>[] types(String descriptor, ClassLoader loader) throws Exception {
        String parameters = descriptor.substring(1, descriptor.indexOf(')'));
        List<Class<?>> result = new ArrayList<>();
        for (int index = 0; index < parameters.length(); ) {
            int end = typeEnd(parameters, index);
            result.add(type(parameters.substring(index, end), loader));
            index = end;
        }
        return result.toArray(Class<?>[]::new);
    }

    private static String returnType(String descriptor) {
        return descriptor.substring(descriptor.indexOf(')') + 1);
    }

    private static int typeEnd(String value, int start) {
        int index = start;
        while (value.charAt(index) == '[') index++;
        return value.charAt(index) == 'L' ? value.indexOf(';', index) + 1 : index + 1;
    }

    private static Class<?> type(String descriptor, ClassLoader loader) throws Exception {
        return switch (descriptor) {
            case "Z" -> boolean.class;
            case "B" -> byte.class;
            case "S" -> short.class;
            case "C" -> char.class;
            case "I" -> int.class;
            case "J" -> long.class;
            case "F" -> float.class;
            case "D" -> double.class;
            case "V" -> void.class;
            default -> {
                if (descriptor.startsWith("["))
                    throw new IllegalArgumentException("Array descriptors are not injected");
                if (!descriptor.startsWith("L") || !descriptor.endsWith(";"))
                    throw new IllegalArgumentException(descriptor);
                Class<?> base =
                        Class.forName(binaryName(descriptor), false, loader);
                yield base;
            }
        };
    }

    private static String descriptor(Class<?> type) {
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == short.class) return "S";
        if (type == char.class) return "C";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type == void.class) return "V";
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("extensions/youtube/src/main/java"))) {
            root = root.getParent();
        }
        if (root == null) throw new IllegalStateException("Repository root not found");
        return root;
    }
}
