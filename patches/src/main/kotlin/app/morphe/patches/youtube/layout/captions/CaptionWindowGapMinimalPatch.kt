package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

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

        // Wrap native onLayout rather than inserting at its return instructions:
        // optimized Android methods may reuse p0 before they return.
        val overlay = mutableClassDefBy(CaptionOverlayFingerprint.originalMethod.definingClass)
        val params = listOf("Z", "I", "I", "I", "I")
        val nativeLayout = overlay.methods.single {
            it.name == "onLayout" && it.parameterTypes == params && it.returnType == "V"
        }
        val access = nativeLayout.accessFlags
        nativeLayout.name = "morpheNativeOnLayout"
        overlay.methods.add(
            ImmutableMethod(
                overlay.type, "onLayout",
                params.map { ImmutableMethodParameter(it, null, null) },
                "V", access, null, null, MutableMethodImplementation(7),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-virtual/range { p0 .. p5 }, ${overlay.type}->morpheNativeOnLayout(ZIIII)V
                        invoke-static { p0 }, $EXTENSION->afterLayout(Landroid/view/ViewGroup;)V
                        return-void
                    """.trimIndent(),
                )
            },
        )
    }
}
