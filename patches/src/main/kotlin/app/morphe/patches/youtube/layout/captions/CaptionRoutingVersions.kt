package app.morphe.patches.youtube.layout.captions

/** Native source-index routing is verified for these two YouTube layouts. */
internal fun usesNativeCaptions(version: String): Boolean =
    version == "21.20.405" || version == "21.41.525"
