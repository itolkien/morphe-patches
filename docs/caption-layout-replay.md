# Translated caption layout replay experiment — 26 October 2026

Status: experimental, separate from main and Draft PR #7. Base: `4890f9d8c` (`experiment/caption-translated-pens-ab`). Only YouTube 21.23.492 is supported by the new layout patch. No Samsung/Fold device validation is claimed.

## Investigation

- `nll.f(FII)F` keeps its original dynamic/compact mode selector. In the dynamic path `aose.f` uses min(width,height)/scaledDensity, then the native landscape factor 0.0625 and minimum 13sp. On density 2.625, 1968px gives 46.857143sp and 1227px gives 29.214285sp. Geometry A/B implementation is unchanged from the user-confirmed baseline; it still needs a learned stable viewport and is not a general cold-fullscreen solution.
- `apnv.c(int)` is the shared timed-text pen lookup. `apny.g` is `sz`; `apnv.h` converts it to a RelativeSizeSpan. The existing Morphe remapping to a missing pen ID selects size 100 and loses source size/emphasis. The screenshot/log observations support investigating this independently from base font geometry; the exact offending timed-text response was not available.
- `apnt.c(long)` samples a native timeline to produce `apoo` cue windows. `apoo.d` is visible text and `apoo.e` is full text used for measurement. Both may contain spans and hard newlines. `anvi.F` splits them into separate row lists; `SubtitleWindowView` measures the full-text rows but draws the visible rows with `apow`/StaticLayout. Native anchors place different windows independently.
- `apoo.b` is the sampling instant, **not** source start/end time. Only currently active text is joined; the native timeline still owns start/end/expiry. The policy's equal-time guard rejects accidentally mixed snapshots, not overlapping source intervals.
- Universal Captions selects or creates a translated track and falls back to native YouTube selection; its language/track priority code is unchanged. This experiment works after track parsing and does not add bilingual rendering.

## Optional workaround

Select the new default-off patch **Reflow auto-translated caption windows (test)**. In an APK that includes it, **Reflow auto-translated caption windows (test)** is ON under Morphe → Player → Captions. Restart after changing it to invalidate cached parsed pen styles.

1. Preserve the native pen's relative size and safe bold/italic/underline; use default colors/opacity/background. The explicit native-pen A/B toggle remains independently usable when reflow is OFF.
2. Capture translated-track provenance on each parsed native timeline. Cached original/translated tracks do not rely on UI-thread global state.
3. At each timeline sample, sort active visible windows by vertical/horizontal anchor, then stable ID. Join their current visible text, replace hard newlines with spaces, and measure that same visible text in one centered-bottom native window. Keep safe emphasis/relative-size spans.
4. Original tracks, OFF mode, scrolling/roll-up windows, unknown ABI objects, mixed snapshots, >8 windows or >4096 characters retain native layout. Hidden/empty text cannot create ghost content. No source timeline intervals or input cue objects are modified.

This is a reversible layout **workaround**, not proof that YouTube's server segmentation or every translation is fixed. Greedy native wrapping is retained; semantic/balanced line breaking, manual translation speaker-position preservation, RTL visual order, actual Fold transitions, 4:3 and cold fullscreen remain unverified. Translation windows can represent distinct speakers; enabling reflow intentionally gives up their original placement.

## Build and test

Use Java 21 (host default Java 27 caused Android jlink incompatibility). With cached dependencies, arbitrary nonempty `GITHUB_ACTOR`/`GITHUB_TOKEN` placeholders avoid nullable Gradle repository credentials; online private dependency resolution requires real authorized credentials.

```
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME=/path/to/sdk ./gradlew :extensions:youtube:test :patches:build
```

The full/default Morphe patch set must be used: do not pass `--exclusive`. Explicitly enable geometry, reflow, Captions, GmsCore support and Clone app; give Clone app a fresh package name `app.morphe.captionlayoutlab`. Keep every baseline clone installed separately.

`tests/caption-replay/` builds a test-only instrumentation APK using SDK tools, signed with the same test key as the target. The build script assumes the disposable `captionlab` test-key password and alias `morphe`. It never contains or distributes the actual signing key. Install only on a disposable AVD:

```
adb -P 5047 -s emulator-5580 install youtube-caption-layout-lab.apk
adb -P 5047 -s emulator-5580 install -t caption-replay-tests.apk
adb -P 5047 -s emulator-5580 shell am instrument -w app.morphe.captionreplay/app.morphe.captionreplay.CaptionReplay
```

The replay uses real patched native classes and Android text/layout. Its Turkish text, spans, source intervals and window metadata are controlled fixtures, not a captured live YouTube response. It checks off/original pass-through, one-window reflow, span safety, timeline provenance and native expiry, native view bounds, source size/emphasis copies and the learned geometry/native font calculation. It renders fixture before/after PNGs into the clone's external files directory.

## Review decisions

A separate static reviewer found no concrete smali register/constructor ABI issue. Its safe-emphasis finding was fixed by copying native c/d/e as well as g. Its source-time finding clarified the intended policy: overlap is deliberately merged at a common sample; actual native source start/end intervals must still expire normally. Native timeline expiry checks were added to the Android replay instead of pretending apoo.b stores source intervals.

## Evidence

See the delivered report and evidence archive for actual executed tests and Android outcomes. Writing a replay test does not imply it ran. The initial restricted process could not see /dev/kvm and a software-only boot hit a system-server watchdog timeout. Host KVM was subsequently discovered and verified; the final AVD uses KVM, 2184×1968 physical pixels and density420 (scaledDensity2.625). Its successful accelerated boot took about21 seconds.


## Native integration bug caught by Android replay

A return-site hook initially failed because the native loop's branches jumped directly to RETURN_OBJECT, skipping inserted instructions. Replacing the return exposed a second problem: optimized `apnt.c` reuses its `p0` register as an integer. The final implementation renames the unmodified native sampler to `morpheSampleOriginal` and adds a small `c(J)` wrapper. The wrapper obtains the native active frame, reads provenance from its own live receiver and applies reflow. The native overlap/expiry tests were red before this fix and green afterward.

The srv3 fixture is processed by YouTube's own `adwc` XML dispatcher, `apnv`/`apny` pen parser and `apnt` timeline. A controlled native flag snapshot enables the span-renderer experiment45773966. Test-only allocation avoids creating live reactive/network configuration subscriptions; it is not a claim about production server feature flags. Source colors/opacity/size/emphasis and independent window expiration are checked after this complete native parsing path.


## Executed final validation

- `:extensions:youtube:test`: 53 tests,0 failures.
- `:patches:test`: 6 executed tests passed;5 APK/device-input checks skipped because their external inputs were not supplied.
- Full MPP build succeeded. Full/default Morphe APK applied107 patches; package `app.morphe.captionlayoutlab`, version21.23.492.
- Final instrumented Android11/API30 AVD run: **44 assertions passed**. This includes actual Hilt `InternalMainActivity` context, actual `nll.f` selector and injected geometry hook, cold fullscreen pass-through, native XML dispatch and srv3 styling/timing, native views and span safety.
- SDK APK signature verification passed (v2); the APK installed on the AVD. Official MicroG-RE7.2.2 also installed, and the clone's actual Activity launched. A live video/translated track was not confirmed; an initial loading/modal WebView prevented treating that smoke check as playback validation.
- No physical phone was used. No real Samsung Fold transition, original problematic live timed-text response, roll-up translation reflow, RTL reading order or supported-version matrix was validated.

Final replay output and PNGs are in the delivery evidence archive. The screenshots are synthetic native-renderer fixtures at an unmodified base font size; they isolate layout, rather than demonstrating video playback or a Fold screenshot.
