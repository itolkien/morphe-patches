# Auto-translated caption window layout — offline diagnostic candidate

Date: 2026-10-26. **NOT MERGE-READY; not built into a YouTube APK; not tested on Android.**

## Reproduced evidence and scope

- User screenshot: Turkish auto-translated captions on YouTube 21.23.492 display as two long blocks far apart vertically, one at the video's lower edge and another in the lower black region. The user reports the **font now appears normal**, but the line and box arrangement is abnormal.
- This translation problem **predates** the Fold near-square fullscreen font issue. Do not assume one root cause.
- In the same geometry (1080×608 and 2520×1080), earlier native font-size logs for original and translated tracks were identical, so the visual issue can occur after \`nll.f(FII)F\`.
- User's native pen A/B \`MorpheCaptionStyle: translated track pen mapping=native originalPenId=0\` confirms the style toggle runs. **It does not prove it fixed size**; the user did not establish a stable, identical-frame pixel-size comparison.
- Decompiled \`aose.kq()\` creates a distinct \`SubtitleWindowView\` per \`apoo.a\` window ID, while \`aose.k()\` positions it from the per-window \`apol.b/c/d\` anchor flags and percentages. \`SubtitleWindowView.onMeasure()\` wraps text *inside* each separate view. This gives a plausible mechanism for extreme separation, without proving whether the source is original YouTube, its translation output, or a Morphe patch.

## Proposed *reversible* experiment

Branch \`experiment/caption-window-stack-offline\`, based on \`experiment/caption-translated-pens-ab\`, itself based on the **user-confirmed working** geometry/font diagnostic branch. Neither PR #7 nor \`main\` has been changed.

- Opt-in patch: \`Stack separated translated caption windows (test)\`, default **off**.
- Opt-in in-app setting: **Stack separated translated subtitle windows (test)**, default **off**.
- New Kotlin hook to record the selected caption track's VSS ID after \`PreferredCaptionLanguagePatch.onSetSubtitleTrack\` substitutes a preferred selection. Only tracks whose VSS ID begins with \`t\` can be moved.
- New Kotlin hook after native \`aose.onLayout\` uses an Android adapter to obtain visible caption window rectangles.
- Pure-Java \`CaptionWindowStackPolicy\` handles **2–3** windows only when a real vertical gap is greater than half the tallest window; it keeps window order and measured widths/heights, centers the stack, and caps its bottom at 91% of frame height.
- Otherwise native layout is unchanged: single windows, adjacent windows, 4+ windows, oversized boxes, original tracks, missing geometry and disabled setting.
- No caption text is logged or changed. Font-size calculation, native pens and Fold geometry logic are preserved exactly.

**Limitations:** This only fixes visibly excessive *inter-window spacing* if the translated track really produces separate views. It does **not** fix line breaks embedded in the translated text, incorrectly translated text, cue timing, wrong window widths, or font-size differences. It is a limited hypothesis, not a proven production fix. On-screen verification is mandatory before any merge.

## Tests actually executed in this assistant's Linux container

Container provided OpenJDK 21 and Java source files, but no Android SDK / adb / emulator / \`/dev/kvm\`. Container networking could not resolve github.com; GitHub writes used the connected GitHub integration.

- \`javac\` compiled the pure-Java policy.
- 12 deterministic policy checks **passed** (including no-op behavior, oversized input, original order and idempotence).
- With fake Android \`View\`, \`ViewGroup\`, \`Resources\`, \`TypedValue\`, \`Settings\` and track types, \`javac\` compiled \`CaptionWindowStackPatch\` and **6 adapter checks passed**: original track unchanged; disabled setting unchanged; translated windows stacked; stable repeated layout; track deselection restores native behavior.
- JUnit policy test source committed under \`extensions/youtube/src/test/java/\`, but Gradle/JUnit execution in this environment was unavailable. Kotlin bytecode patch and actual Android APK **have not been compiled**.

## Remaining work before claiming a fix

1. Validate the Kotlin bytecode insertion order and patch fingerprint against the exact YouTube 21.23.492 APK in a real Android SDK.
2. Verify the native track detector follows *actual* selected translation, particularly preferred-language and video-switch transitions. Reject other player backends if ambiguous.
3. Compare translated vs original cue windows' counts, bounds, source newline count and text measurements in a real Android renderer; a screenshot alone cannot prove the original source windows are distinct in all cases.
4. Validate actual visual results without changes to valid creator-positioned multi-window captions or other languages.
5. Preserve the known-good \`app.morphe.captionabfull\` clone and keep all experimental toggles off unless testing them.

## Local verification outside Android

On machines with JUnit available:
\`\`\`
./gradlew :extensions:youtube:test --tests '*CaptionWindowStackPolicyTest'
\`\`\`
(The exact Gradle test task name may vary; check \`./gradlew tasks\`.)

To test the pure-Java algorithm without Gradle, compile \`CaptionWindowStackPolicy.java\` with \`javac\` and run the independent standalone Java assertions (delivered separately with this investigation).
