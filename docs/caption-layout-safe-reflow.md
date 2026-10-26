# Caption layout safe reflow — 2026-10-26

Status: **experimental only**, not merged and not a proven fix for the user's Z Fold 7 captions.

## What changed

Base: `experiment/caption-layout-replay`, whose 21.23.492 full-APK / Android native replay validation is documented in `docs/caption-layout-replay.md`.

The translated-caption reflow policy previously returned even one visible non-scrolling cue for rewriting into a synthetic center-bottom caption window. That changed native window identity, anchoring, styles and layout without evidence that a reflow was needed.

The new policy **passes through zero or one visible cue**, including a lone non-scrolling cue and a lone rolling cue. Original tracks, disabled settings, missing/unknown objects, malformed sample times and overlong payloads still pass through. Reflow of two or more visible translated cues remains as in the replay branch; that policy is still experimental and can lose intentional creator/speaker positioning.

Unit tests were updated for single-window pass-through, hidden/empty windows and empty frames.

## Validation performed for this commit

- Java 21: stand-alone smoke harness with policy implementation compiled and **11/11 checks passed**.
- The test exercises selection logic only; it does not run the extension or Morphe Gradle test suite.
- Source-code commit does **not** establish successful Kotlin bytecode injection, APK patching, or physical-phone behavior.
- No release APK produced in this environment. No merge or PR created.

## Next verification

1. Run `./gradlew :extensions:youtube:test :patches:build` with Java 21 and the Android SDK.
2. Rerun the **native Android replay** and check that all its assertions still pass.
3. In YouTube 21.23.492, compare original and translated captions on the same timecodes, distinguishing one-window wrapping from multiple native cue windows.
4. Check whether the user's badly split Turkish caption is a multi-window case; otherwise this patch intentionally does not alter it.
5. Test unusual 4:3, near-square cold fullscreen, video switching, manual speaker-positioned captions, PiP and RTL. Do not claim universal font-size fix from a single Fold screenshot.

## Safety

Keep `main`, draft PR #7 and the known-good `app.morphe.captionabfull` clone untouched. Test on a separate clone package, and keep the reflow patch disabled except in controlled comparisons.
