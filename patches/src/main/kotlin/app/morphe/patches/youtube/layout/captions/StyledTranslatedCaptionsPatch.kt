package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.returnEarly
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

@Suppress("unused")
val styledTranslatedCaptionsPatch = patch(
    name = "Styled translated captions",
    description = "Experimentally preserves visible pen styles in auto-translations of uploaded captions. " +
            "Word-specific effects may not match translated words.",
    default = false,
) {
    dependsOn(captionsPatch, styledCaptionsPatch)
    compatibleWith(COMPATIBILITY_YOUTUBE.excluding(
        *COMPATIBILITY_YOUTUBE.targets.map { it.version }
            .filter { it !in setOf("21.23.492", "21.30.216", "21.43.523", "21.44.528") }
            .toTypedArray()
    ))
    execute {
        mutableClassDefBy("Lapp/morphe/extension/youtube/patches/TranslatedCaptionPensPatch;")
            .methods.single { it.name == "preserveTranslatedStyles" }
            .returnEarly(true)
    }
}
