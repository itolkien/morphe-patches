import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension

plugins {
    alias(libs.plugins.android.library)
}

abstract class VerticalSourceTask : Sync() {
    @get:OutputDirectory abstract val sourceDirectory: DirectoryProperty
}

val upstreamSources = configurations.create("upstreamSources")
val generatedSources = layout.buildDirectory.dir("generated/upstream")
val corrections = layout.projectDirectory.file("vertical-text.patch")

dependencies {
    val upstream = libs.androidx.text.vertical.get()
    add(upstreamSources.name, "${upstream.module}:${upstream.versionConstraint.requiredVersion}:sources@jar")
    implementation(libs.annotation)
}

// Keep the upstream implementation intact except for the reviewed, version-pinned hunks.
val prepareSources = tasks.register<VerticalSourceTask>("prepareVerticalSources") {
    from(provider { zipTree(upstreamSources.singleFile) })
    include("**/*.kt")
    sourceDirectory.set(generatedSources)
    into(sourceDirectory)
    inputs.file(corrections)
    doLast {
        val lines = corrections.asFile.readLines()
        var cursor = 0
        while (cursor < lines.size) {
            if (!lines[cursor].startsWith("+++ b/")) { cursor++; continue }
            val file = destinationDir.resolve(lines[cursor++].removePrefix("+++ b/"))
            val contents = file.readLines().toMutableList()
            var shift = 0
            while (cursor < lines.size && !lines[cursor].startsWith("--- ")) {
                val header = Regex("@@ -(\\d+)(?:,\\d+)? \\+\\d+(?:,\\d+)? @@.*").matchEntire(lines[cursor])
                if (header == null) { cursor++; continue }
                val at = header.groupValues[1].toInt() - 1 + shift
                cursor++
                val before = mutableListOf<String>()
                val after = mutableListOf<String>()
                while (cursor < lines.size && !lines[cursor].startsWith("@@ ") && !lines[cursor].startsWith("--- ")) {
                    val line = lines[cursor++]
                    when (line.firstOrNull()) {
                        ' ' -> { before.add(line.drop(1)); after.add(line.drop(1)) }
                        '-' -> before.add(line.drop(1))
                        '+' -> after.add(line.drop(1))
                        '\\' -> Unit
                        else -> error("Invalid vertical text patch line")
                    }
                }
                check(at >= 0 && at + before.size <= contents.size && contents.subList(at, at + before.size) == before) {
                    "Vertical text source changed: ${file.name} hunk at ${at + 1}"
                }
                contents.subList(at, at + before.size).clear()
                contents.addAll(at, after)
                shift += after.size - before.size
            }
            file.writeText(contents.joinToString("\n", postfix = "\n"))
        }
    }
}

configure<LibraryExtension> {
    namespace = "app.morphe.captiontext"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

configure<LibraryAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.kotlin!!.addGeneratedSourceDirectory(prepareSources) { it.sourceDirectory }
    }
}
