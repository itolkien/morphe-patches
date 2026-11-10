# Vertical text

Builds the AndroidX `text-vertical` source version pinned in the version catalog,
with `vertical-text.patch` applied before compilation. Each hunk must match the
upstream source exactly; a dependency update that changes it fails the build.

The corrections preserve emphasis and baseline shifts in vertical text and allow
combined horizontal glyphs to counter-rotate inside bottom-to-top captions.
YouTube continues to own caption windows, placement and playback timing.

Requires compile SDK 37 and AGP 9.1. Sources retain their Apache 2.0 headers;
the application includes the AndroidX notice in its license dialog.

Upstream: https://developer.android.com/jetpack/androidx/releases/text-vertical
