package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.morphe.patcher.literal
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.mutableClassDefBy
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class StyledCaptionsPatchTest {
    @Test
    fun `uploaded captions can always enter the native rich text renderer`() {
        val paths = System.getProperty("styledCaptionsApks").orEmpty()
        assumeTrue(paths.isNotBlank(), "Pass -PstyledCaptionsApks with local stock YouTube APK paths")
        for (path in paths.split(File.pathSeparator)) {
            val verify = patch {
                dependsOn(styledCaptionsPatch)
                execute {
                    val gate = Fingerprint(
                        returnType = "Z",
                        parameters = emptyList(),
                        filters = listOf(literal(45773966L)),
                    ).method
                    val instructions = gate.implementation!!.instructions.toList()
                    assertEquals(Opcode.CONST_4, instructions[0].opcode)
                    assertEquals(1, (instructions[0] as NarrowLiteralInstruction).narrowLiteral)
                    assertEquals(Opcode.RETURN, instructions[1].opcode)
                    assertEquals(
                        (instructions[0] as OneRegisterInstruction).registerA,
                        (instructions[1] as OneRegisterInstruction).registerA,
                    )
                    val nativeText = NativeCaptionSpanBuilderFingerprint()
                    assertEquals("patch_buildCaptionText", nativeText.method.name)
                    val wrappers = nativeText.classDef.methods.filter { method ->
                        method.instructions.any {
                            it.getReference<MethodReference>()?.definingClass ==
                                "Lapp/morphe/extension/youtube/patches/NativeCaptionTypography;"
                        }
                    }
                    assertEquals(2, wrappers.size, "Source wrapper and translated run builder per APK")
                            assertEquals(1, wrappers.count { it.name == "patch_renderCaptionRun" })
                            assertEquals(1, wrappers.count { method -> method.instructions.any {
                                it.getReference<MethodReference>()?.definingClass ==
                                    "Lapp/morphe/extension/youtube/patches/NativeCaptionRuns;"
                            } })
                    val window = mutableClassDefBy("Lcom/google/android/libraries/youtube/player/subtitles/ui/SubtitleWindowView;")
                    val measure = window.methods.single { it.name == "onMeasure" }
                    val calls = measure.instructions.mapNotNull { it.getReference<MethodReference>() }
                    assertEquals(1, calls.count { it.definingClass == "Lapp/morphe/extension/youtube/patches/NativeCaptionVertical;" && it.name == "prepare" },
                        "Standalone styled captions must install native measurement")
                    assertEquals(1, window.methods.count { it.name == "patch_getCaptionMeasureRow" })
                    assertEquals(1, window.methods.count { it.name == "patch_setCaptionMeasuredSize" })
                    assertEquals(0, calls.count { it.definingClass == "Lapp/morphe/extension/youtube/patches/StableCaptionBlocks;" && it.name == "prepare" },
                        "Standalone styling must not enable optional block presentation")
                    val rubyGate = mutableClassDefBy("Lapp/morphe/extension/youtube/patches/NativeCaptionRuby;")
                        .methods.single { it.name == "enabled" }
                    assertEquals(1, (rubyGate.instructions.first() as NarrowLiteralInstruction).narrowLiteral)
                    val pen = mutableClassDefBy(nativeText.method.parameterTypes[1].toString())
                    assertEquals(
                        0,
                        pen.instanceFields.count {
                            it.name in setOf("patch_ruby", "morpheAppearance", "morpheHorizontalGroup")
                        },
                        "Pen state belongs to the extension, not to the native pen class",
                    )
                    assertEquals(
                        0,
                        pen.methods.count {
                            it.name in setOf(
                                "patch_getRuby", "patch_setRuby",
                                "patch_getAppearance", "patch_setAppearance",
                                "patch_getHorizontalGroup", "patch_setHorizontalGroup",
                            )
                        },
                        "The native pen class must stay free of injected accessors",
                    )
                    assertEquals(0, nativeText.classDef.methods.count { it.name == "patch_getRubyPens" })
                    val reader = NativeCaptionPenFieldFingerprint("of").method
                    assertEquals("patch_readCaptionPen", reader.name)
                    assertEquals(
                        1,
                        reader.instructions.count {
                            it.getReference<MethodReference>()?.let { reference ->
                                reference.definingClass ==
                                    "Lapp/morphe/extension/youtube/patches/NativeCaptionRuby;" &&
                                    reference.name == "onPen"
                            } == true
                        },
                        "The reader call must key the native pen object extension-side",
                    )
                    assertEquals(1, nativeText.classDef.methods.count { it.name == "patch_getWindowDefinitions" })
                    assertEquals(1, nativeText.classDef.methods.count { it.name == "patch_getWindowStyles" })
                    assertEquals(
                        0,
                        nativeText.classDef.instanceFields.count { it.name == "patch_windowBindings" },
                        "Parser bindings belong to the extension, not to the native parser",
                    )
                    assertEquals(
                        0,
                        nativeText.classDef.methods.count {
                            it.name in setOf("patch_getWindowBindings", "patch_setWindowBindings")
                        },
                    )
                    assertEquals(
                        0,
                        window.instanceFields.count { it.name == "patch_windowBackground" },
                        "Window background state belongs to the extension",
                    )
                }
            }
            val workspace = createTempDirectory("styled-captions-").toFile()
            try {
                Patcher(PatcherConfig(File(path), workspace)).use { patcher ->
                    patcher += setOf(verify)
                    runBlocking {
                        patcher().collect { result ->
                            assertEquals(null, result.exception, result.exception?.stackTraceToString().orEmpty())
                        }
                    }
                }
            } finally {
                workspace.deleteRecursively()
            }
        }
    }
}
