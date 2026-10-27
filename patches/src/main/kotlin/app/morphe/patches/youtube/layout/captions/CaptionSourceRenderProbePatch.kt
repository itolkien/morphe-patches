package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val PROBE = "Lapp/morphe/extension/youtube/patches/CaptionSourceRenderProbe;"

/** Locates the native subtitle overlay via its original font-size implementation. */
private object NativeCaptionOverlayFingerprint : Fingerprint(
    returnType = "F",
    parameters = listOf("F", "I", "I"),
    filters = listOf(
        methodCall("Ljava/lang/Math;->min(II)I"),
        fieldAccess("Landroid/util/DisplayMetrics;->scaledDensity:F"),
    ),
)

/**
 * Read-only diagnostic. The original native sampler and layout always run first.
 * Existing subtitle text, timestamps, styling, positions and YouTube player settings remain unmodified.
 */
@Suppress("unused")
val captionSourceRenderProbePatch = bytecodePatch(
    name = "Test: Trace caption source and renderer",
    description = "Read-only caption diagnostics for YouTube 21.23.492: source cue windows vs drawn rows.",
    default = false,
) {
    dependsOn(captionsPatch)
    compatibleWith(
        Compatibility(
            packageName = "com.google.android.youtube",
            name = "YouTube",
            targets = listOf(AppTarget(version = "21.23.492", minSdk = 28, isExperimental = true)),
        )
    )
    execute {
        require(packageMetadata.versionName == "21.23.492") {
            "Source/render probe supports only the audited 21.23.492 native caption ABI"
        }
        settingsMenuCaptionGroup.add(SwitchPreference("morphe_caption_source_render_probe", summary = true))

        // Native subtitle timeline sampling: apnt.c(long) returns active apoo cue windows.
        // Rename the native method and use a wrapper: optimized methods reuse p0 and skip
        // injected return instructions through branching, so return-site injection is unsafe.
        mutableClassDefBy("Lapnt;").apply {
            val nativeSample = methods.single {
                it.name == "c" &&
                    it.parameterTypes == listOf("J") &&
                    it.returnType == "Ljava/util/List;"
            }
            val access = nativeSample.accessFlags
            nativeSample.name = "morpheProbeSampleOriginal"
            methods.add(
                ImmutableMethod(
                    type, "c", listOf(ImmutableMethodParameter("J", null, null)),
                    "Ljava/util/List;", access, null, null,
                    MutableMethodImplementation(5),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            invoke-virtual/range { p0 .. p2 }, Lapnt;->morpheProbeSampleOriginal(J)Ljava/util/List;
                            move-result-object v0
                            invoke-static { v0 }, $PROBE->traceNativeFrame(Ljava/util/List;)Ljava/util/List;
                            move-result-object v0
                            return-object v0
                        """.trimIndent(),
                    )
                },
            )
        }

        // Subtitle overlay layout: observe the resulting View hierarchy on pre-draw.
        // Do not re-anchor aose/SubtitleWindowView or alter apow/StaticLayout measurements.
        val overlay = mutableClassDefBy(NativeCaptionOverlayFingerprint.originalMethod.definingClass)
        val params = listOf("Z", "I", "I", "I", "I")
        val nativeLayout = overlay.methods.single {
            it.name == "onLayout" && it.parameterTypes == params && it.returnType == "V"
        }
        val access = nativeLayout.accessFlags
        nativeLayout.name = "morpheProbeNativeOnLayout"
        overlay.methods.add(
            ImmutableMethod(
                overlay.type, "onLayout",
                params.map { ImmutableMethodParameter(it, null, null) },
                "V", access, null, null, MutableMethodImplementation(7),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-virtual/range { p0 .. p5 }, ${overlay.type}->morpheProbeNativeOnLayout(ZIIII)V
                        invoke-static { p0 }, $PROBE->observeCaptionOverlay(Landroid/view/ViewGroup;)V
                        return-void
                    """.trimIndent(),
                )
            },
        )
    }
}
