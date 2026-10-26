package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION = "Lapp/morphe/extension/youtube/patches/CaptionWindowGapPatch;"

/** The native YouTube caption overlay class, resolved from its font calculator. */
private object CaptionOverlayFingerprint : Fingerprint(
    returnType = "F",
    parameters = listOf("F", "I", "I"),
    filters = listOf(
        methodCall("Ljava/lang/Math;->min(II)I"),
        fieldAccess("Landroid/util/DisplayMetrics;->scaledDensity:F"),
    ),
)

/** Only change post-layout positions: native text, spans, cues and font sizes remain untouched. */
@Suppress("unused")
val captionWindowGapMinimalPatch = bytecodePatch(
    name = "Test: Stack separated caption windows",
    description = "Minimal opt-in experiment for two abnormally separated YouTube caption windows.",
    default = false,
) {
    dependsOn(captionsPatch)
    compatibleWith(Compatibility(
        packageName = "com.google.android.youtube",
        name = "YouTube",
        targets = listOf(AppTarget(version = "21.23.492", minSdk = 28, isExperimental = true)),
    ))
    execute {
        require(packageMetadata.versionName == "21.23.492") {
            "Only the audited YouTube 21.23.492 layout is supported"
        }
        settingsMenuCaptionGroup.add(
            SwitchPreference("morphe_caption_window_gap_minimal", summary = true)
        )

        // Hook once, after the player has positioned SubtitleWindowViews.
        val overlay = mutableClassDefBy(CaptionOverlayFingerprint.originalMethod.definingClass)
        val onLayout = overlay.methods.single {
            it.name == "onLayout" &&
                it.parameterTypes == listOf("Z", "I", "I", "I", "I") &&
                it.returnType == "V"
        }
        onLayout.findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
            onLayout.addInstructionsAtControlFlowLabel(
                index,
                "invoke-static { p0 }, $EXTENSION->afterLayout(Landroid/view/ViewGroup;)V",
            )
        }
    }
}
