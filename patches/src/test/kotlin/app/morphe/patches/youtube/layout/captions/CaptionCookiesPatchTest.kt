package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Patcher
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

class CaptionCookiesPatchTest {
    @Test
    fun `each native request keeps its URL with its builder`() {
        val paths = System.getProperty("styledCaptionsApks").orEmpty()
        assumeTrue(paths.isNotBlank(), "Pass -PstyledCaptionsApks with stock YouTube APK paths")
        for (path in paths.split(File.pathSeparator)) {
            // Patcher fingerprints are process-global; each stock APK needs fresh matches.
            // The reset is public JVM API but its companion is Kotlin-internal.
            Fingerprint::class.java.getField("Companion").get(null).let { companion ->
                companion.javaClass.getMethod("clearFingerprints").invoke(companion)
            }
            val verify = patch {
                dependsOn(captionCookiesPatch)
                execute {
                    val owner = TimedTextUrlFingerprint.classDef
                    val helper = owner.methods.single { it.name == "patch_newCaptionUrlRequestBuilder" }
                    assertTrue(AccessFlags.STATIC.isSet(helper.accessFlags))
                    assertEquals(listOf(
                        "Lorg/chromium/net/CronetEngine;", "Ljava/lang/String;",
                        "Lorg/chromium/net/UrlRequest\$Callback;", "Ljava/util/concurrent/Executor;"
                    ), helper.parameterTypes.map { it.toString() })
                    assertEquals("Lorg/chromium/net/UrlRequest\$Builder;", helper.returnType)
                    val body = helper.instructions.toList()
                    val newBuilder = body.single {
                        it.getReference<MethodReference>()?.name == "newUrlRequestBuilder"
                    } as FiveRegisterInstruction
                    val decision = body.single {
                        it.getReference<MethodReference>()?.name == "shouldSetCookies"
                    } as FiveRegisterInstruction
                    // URL used by Cronet is exactly the URL used for the cookie decision.
                    assertEquals(newBuilder.registerD, decision.registerC)
                    assertEquals(4, newBuilder.registerCount)
                    assertEquals(1, decision.registerCount)
                    assertEquals(2, body.count {
                        it.getReference<MethodReference>()?.name == "addHeader"
                    })
                    assertEquals(Opcode.RETURN_OBJECT, body.last().opcode)
                    val factoryCalls = TimedTextUrlFingerprint.method.instructions.filter {
                        it.getReference<MethodReference>()?.name == helper.name
                    }
                    assertEquals(1, factoryCalls.size)
                    assertEquals(Opcode.INVOKE_STATIC, factoryCalls.single().opcode)
                    assertEquals(4, (factoryCalls.single() as FiveRegisterInstruction).registerCount)
                    assertTrue(owner.methods.flatMap { it.instructions }.none {
                        it.getReference<MethodReference>()?.name in
                            setOf("getRequireCookies", "setRequireCookies")
                    })
                }
            }
            val output = createTempDirectory("caption-cookie-hook-").toFile()
            try {
                Patcher(PatcherConfig(File(path), output)).use { patcher ->
                    patcher += setOf(verify)
                    runBlocking {
                        patcher().collect { result ->
                            assertEquals(null, result.exception, result.exception?.stackTraceToString())
                        }
                    }
                }
            } finally {
                output.deleteRecursively()
            }
        }
    }
}
