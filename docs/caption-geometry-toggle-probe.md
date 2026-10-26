# Caption geometry A/B instrumentation (2026-10-26)

## Motivation
Control APK (only GmsCore support + Clone app) displays native-sized captions in normal view, but giant captions in Fold 7 unfolded landscape fullscreen. Both original v6 and PR #7 geometry patch currently show small captions across modes, despite apparently plausible `videoViewport` logs. This test isolates the cause in a **single cloned app with identical app data**.

## Changes relative to PR #7
- A runtime setting in Morphe → Captions: **Enable experimental caption geometry correction** (default ON).
- Toggle OFF bypasses **all** geometry learning/normalization in `normalizeHeight`; return the caller's original height unchanged.
- Logs the actual `float` returned by YouTube's native `nll.f(FII)F` method after the method runs (not just the inferred viewport). The value is returned **unchanged**. Log tag `MorpheCaptionFix`.
- Diagnostic branch only; do not merge as production code.

## A/B test
1. Patch YouTube 21.23.492 with `Fix caption size on near-square screens (test)`, GmsCore support and Clone app; give it a new unique cloned package.
2. Open same video with the same caption track and appearance preferences.
3. In the new clone, switch **Enable experimental caption geometry correction** OFF, test normal view then unfolded landscape fullscreen.
4. Switch it ON, test both again.
5. Save `adb logcat -d | grep MorpheCaptionFix` and note visible differences.

## Expected diagnostic record
`font result=<float> scale=<float> input=<width>x<height> effective=<width>x<height> geometry=on/off`

If **OFF = native sized normally + giant fullscreen**, and **ON = small normally + smaller fullscreen**, the geometry code has side-effects beyond the near-square input reduction or a transition state we haven't isolated. If both have the same normal size, prior cross-app differences may be app-caption settings/track selection. Compare native `font result` and exact input sizes before changing any font multiplier.

The plugin writes new debug logs only; its output has **not been verified by a device build**.
