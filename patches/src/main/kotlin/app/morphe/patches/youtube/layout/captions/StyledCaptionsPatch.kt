package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.returnEarly
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private class StyledCaptionsFeatureFlagFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    filters = listOf(literal(45773966L)),
)

@Suppress("unused")
val styledCaptionsPatch = patch(
    name = "Styled captions",
    description = "Enables YouTube's native rich text renderer for uploaded captions. " +
            "Experimental; does not guarantee the same appearance as desktop YouTube.",
    default = false,
) {
    dependsOn(nativeCaptionTypographyPatch)
    compatibleWith(COMPATIBILITY_YOUTUBE.excluding(
        *COMPATIBILITY_YOUTUBE.targets.map { it.version }
            .filter { it !in setOf("21.23.492", "21.30.216", "21.43.523", "21.44.528") }
            .toTypedArray()
    ))
    execute {
        // The native parser otherwise returns plain text instead of styled spans, and
        // omits per-cue background overrides. Keep its timing and placement logic intact.
        StyledCaptionsFeatureFlagFingerprint().method.returnEarly(true)
    }
}
