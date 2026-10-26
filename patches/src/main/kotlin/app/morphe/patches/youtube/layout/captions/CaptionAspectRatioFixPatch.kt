package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val FIX_CLASS =
    "Lapp/morphe/extension/youtube/patches/CaptionAspectRatioFix;"

/** Resolve the original size formula implemented in aose.f(FII)F. */
private object LegacyCaptionSizeFingerprint : Fingerprint(
    returnType = "F",
    parameters = listOf("F", "I", "I"),
    filters = listOf(
        methodCall("Ljava/lang/Math;->min(II)I"),
        fieldAccess("Landroid/util/DisplayMetrics;->scaledDensity:F"),
    ),
)

/** Resolve nll.f(FII)F, the override that dispatches to super.f(FII)F. */
private fun captionSizeSelectorFingerprint(parentMethod: MethodReference) = Fingerprint(
    returnType = "F",
    parameters = listOf("F", "I", "I"),
    filters = listOf(
        methodCall(
            definingClass = "this",
            parameters = emptyList(),
            returnType = "Z",
            opcode = Opcode.INVOKE_DIRECT,
        ),
        methodCall(
            name = parentMethod.name,
            parameters = listOf("F", "I", "I"),
            returnType = "F",
            opcodes = listOf(Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE),
        ),
    ),
)

/**
 * Experimental geometry normalization for subtitle sizing on near-square fullscreen windows.
 *
 * Preserves YouTube's existing mode selector, font-size computation, and rendering.
 * Changes only the measured height passed into the original size calculator when
 * a landscape video viewport aspect has previously been observed for this View.
 */
@Suppress("unused")
val captionAspectRatioFixPatch = bytecodePatch(
    name = "Fix caption size on near-square screens (test)",
    description = "Preserve YouTube's dynamic subtitle sizing using the observed video viewport aspect ratio.",
    default = false,
) {
    dependsOn(sharedExtensionPatch, settingsPatch, videoInformationPatch)
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        settingsMenuCaptionGroup.add(SwitchPreference("morphe_caption_geometry_debug_enabled", summary = true))

        // Avoid carrying a learned caption viewport ratio into the next video.
        onCreateHook(FIX_CLASS, "newVideoStarted")

        val method = captionSizeSelectorFingerprint(LegacyCaptionSizeFingerprint.originalMethod).method
        // Diagnostic only: observe the actual return value of YouTube's font-size selector.
        // Iterate backwards so inserting instructions cannot invalidate earlier indices.
        val returnIndices = method.implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN }
            .map { it.index }
        check(returnIndices.isNotEmpty()) { "Caption selector has no float return" }
        returnIndices.asReversed().forEach { index ->
            val register = method.getInstruction<OneRegisterInstruction>(index).registerA
            method.addInstructions(
                index,
                """
                    invoke-static/range { v$register .. v$register }, $FIX_CLASS->traceResult(F)F
                    move-result v$register
                """.trimIndent(),
            )
        }
        // p0 = subtitle container View, p1 = YouTube's original font scale,
        // p2/p3 = calculated video-window width and height. Replacing p3 only
        // affects the inherited size formula (the compact branch ignores it).
        method.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p3 }, $FIX_CLASS->normalizeHeight(Landroid/view/View;FII)I
                move-result p3
            """.trimIndent(),
        )
        println("Caption Geometry Fix: hooked input of ${method.definingClass}->${method.name}(FII)F")
    }
}
