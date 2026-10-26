# Near-square caption geometry experiment (v6 baseline + video-reset candidate)

This branch preserves the tested **v6** experiment for later refinement. It is **not ready for upstream merge**.

## Problem and approach

On Galaxy Z Fold 7's unfolded landscape, fullscreen caption sizes may differ dramatically from normal player mode. Earlier experimental v3/v4 variants selected YouTube's fixed 14/16 sp path, which made captions too small. v5 attempted learned geometry but recorded a transient transition aspect ratio.

v6 leaves YouTube's `nll.f(FII)F` selector and original `aose.f(FII)F` font formula intact. It learns a stable caption viewport height/width ratio for at least **550 ms**, then adjusts only the **height input** passed to the existing sizing path in near-square landscape windows. It does **not** hardcode a target font size, device model, density or video aspect ratio.

- Fingerprint locates selector using its parent size calculator.
- `normalizeHeight(View, float, int, int)` is called at the start of that selector.
- Near-square means landscape with height >= 75% of width.
- Per-view stable ratio with weak references; 20-second fallback to a recent stable ratio.\n- **New unverified change:** the video lifecycle hook clears both per-view state and recent fallback when a new video begins; this prevents inheriting the previous video's aspect ratio. This change has not been re-tested in the APK.
- Without a known stable ratio, original height is left unchanged.
- Diagnostic tag: `MorpheCaptionFix`.

## Device observations (2026-10-26)

- Galaxy Z Fold 7, YouTube **21.23.492**, cloned Morphe test APK.
- Before fullscreen: `stable reference=1528x859 heightPerWidth=0.5621728`
- Fullscreen: `near-square input=2184x1968 videoViewport=2184x1228 learnedAspect=0.5621728 source=sameViewStable scale=1.0 mode=originalYouTubeCalculation`
- User reports subtitle size **appears correct** in the latest v6 test.
- Other observed references: `1797x1314`, `2520x1080`; source/transition filtering remains a possible concern.

## Before upstream PR / merge

- [ ] Verify normal, inner-screen landscape fullscreen, cover screen and picture-in-picture.
- [ ] Test 16:9, 4:3, vertical, letterboxed, and unusual aspect-ratio videos.
- [ ] Test cold-start directly in fullscreen (no learned reference) and orientation transitions.
- [ ] Check videos with different caption styling and YouTube caption-size preferences.
- [ ] Verify supported YouTube versions; currently only 21.23.492 is reported tested.
- [ ] Verify new video-start hook fires on YouTube 21.23.492 and does not disable valid same-video normal→fullscreen reference sharing.\n- [ ] Check no stale cross-video or cross-view ratio leaks, and ensure the reference viewport really corresponds to the displayed media.
- [ ] Evaluate logs, allocations, synchronization, and simplify for release.
- [ ] Confirm clean Gradle/MPP build and APK validation for the eventual final commit.

Do not merge experimental patch into `Captions` or enable it by default without further testing.

## Local build

Enable `Fix caption size on near-square screens (test)` alongside the required `GmsCore support` and `Clone app` patches for a separate test APK.

## Related independent experiments

- Draft PR #8: Auto Captions delayed-callback generation guard (reliability).
- Draft PR #9: Opt-in centering of captions YouTube left-aligns (alignment).

Both remain separate from this geometry PR; neither changes baseline v6 behaviour unless explicitly built and installed.
