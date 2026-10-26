package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val WINDOW_STACK_CLASS =
    "Lapp/morphe/extension/youtube/patches/CaptionWindowStackPatch;"

/** Locate the native aose.f(float, int, int) font computation to identify aose itself. */
private object CaptionOverlayClassFingerprint : Fingerprint(
    returnType = "F",
    parameters = listOf("F", "I", "I"),
    filters = listOf(
        methodCall("Ljava/lang/Math;->min(II)I"),
        fieldAccess("Landroid/util/DisplayMetrics;->scaledDensity:F"),
    ),
)

/**
 * Experimental translated-cue layout correction. Opt-in, explicitly disabled by default.
 * Only stacks far-apart subtitle windows after native layout; no text/size mutation.
 */
@Suppress("unused")
val captionWindowStackFixPatch = bytecodePatch(
    name = "Stack separated translated caption windows (test)",
    description = "Opt-in correction for excessive gaps between simultaneous translated caption windows.",
    default = false,
) {
    dependsOn(sharedExtensionPatch, settingsPatch, preferredCaptionLanguagePatch)
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        settingsMenuCaptionGroup.add(
            SwitchPreference("morphe_stack_translated_caption_windows_test", summary = true)
        )

        // The preferred-caption dependency already inserts at the beginning:
        // invoke-static onSetSubtitleTrack(...), move-result-object p1, check-cast p1.
        // Insert *after* that override, so we identify the actually selected track.
        SetSubtitleTrackFingerprint.method.addInstruction(
            3,
            "invoke-static { p1 }, $WINDOW_STACK_CLASS->onTrackSelected(Ljava/lang/Object;)V"
        )

        // Run only when the native aose.onLayout has finished positioning windows.
        // No mutation of any font-size, cue or original YouTube model method.
        val overlayClass = mutableClassDefBy(CaptionOverlayClassFingerprint.originalMethod.definingClass)
        val layoutMethod = overlayClass.methods.single { it.name == "onLayout" && it.parameterTypes.size == 5 }
        layoutMethod.findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
            layoutMethod.addInstructionsAtControlFlowLabel(
                index,
                "invoke-static { p0 }, $WINDOW_STACK_CLASS->afterNativeLayout(Landroid/view/ViewGroup;)V"
            )
        }
        println("Translated caption windows: installed opt-in post-layout stacking")
    }
}
