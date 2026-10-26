package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/CaptionAlignmentPatch;"

/**
 * Target the native caption window's explicit left-alignment override.
 * Do not affect cue timing, caption text or window positioning calculations.
 */
private object CaptionNativeLeftAlignmentFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/player/subtitles/ui/SubtitleWindowView;",
    name = "onMeasure",
    returnType = "V",
    parameters = listOf("I", "I"),
    filters = listOf(
        fieldAccess(
            "Landroid/text/Layout\$Alignment;->ALIGN_NORMAL:Landroid/text/Layout\$Alignment;",
            opcode = Opcode.SGET_OBJECT,
        ),
    ),
)

internal val captionAlignmentPatch = bytecodePatch(
    name = "Center left-aligned captions (experimental)",
    description = "Adds an optional setting to center captions that YouTube normally left-aligns.",
) {
    dependsOn(sharedExtensionPatch, settingsPatch)

    execute {
        settingsMenuCaptionGroup.add(
            SwitchPreference("morphe_center_left_aligned_captions", summary = true)
        )

        CaptionNativeLeftAlignmentFingerprint.let {
            val match = it.instructionMatches.single()
            val register = it.method.getInstruction<OneRegisterInstruction>(match.index).registerA
            it.method.addInstructions(
                match.index + 1,
                """
                    invoke-static { v\$register }, \$EXTENSION_CLASS->overrideAlignment(Landroid/text/Layout\$Alignment;)Landroid/text/Layout\$Alignment;
                    move-result-object v\$register
                """.trimIndent(),
            )
        }
    }
}
