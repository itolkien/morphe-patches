# Translated rolling-caption hard break experiment (2026-10-27)

**Target: YouTube 21.23.492, Turkish auto-translation, video P6pcTK0wTYA, 00:09–00:26.** Default-off opt-in patch, based on a clean fork `main`. No previous geometry/window-stack/reflow patches are included.

## Evidence from the user's live Android log

- Native YouTube subtitle sampler `apnt.c(J)` returned **one** active `apoo` cue:
  - sample time 15192ms: 148 visible characters, **2 literal hard newlines**, including `Edge,\n\nvarsayılan`
  - sample time 20150ms: 112 visible characters, **3 hard newlines**, including `Ya da belki de\n\nişlet...`
  - sample time 25349ms: 142 visible characters, **2 hard newlines**
- The actual renderer showed **one `SubtitleWindowView`**, not two concurrently visible caption windows. Its `apow` rows were generally 49px tall and placed contiguously at the native row layout positions, e.g. local tops 8 and 57. That makes an abnormal *inter-window* gap an unlikely cause for this specific sample.
- This is evidence **after native timed-text parsing but before view rendering**. It does not prove whether the upstream HTTP timed-text response or YouTube's native parser inserted the hard breaks; either way the existing parsed cue has them.
- Other later moments also showed repeated words or dangling punctuation. This patch handles hard newlines only, not translation quality, duplicated words, or timing.

## What this experiment changes

- Patch the optimized native caption sampler using a wrapper instead of potentially skipped return-site injection.
- Capture whether a parsed caption timeline is **auto-translated** from Morphe's existing parser-scoped VSS track detection; no UI-thread global state.
- Only when the opt-in setting is ON, there is **exactly one** native cue, its `apol.f` (scrolling/roll-up) flag is true, and the source strings are bounded (<=2048 chars), replace `\n`, `\r`, `\t` with spaces and collapse adjacent spaces.
- Preserve the original cue's id, sample timestamp, placement object and original span objects through `SpannableStringBuilder` copies. Both visible and measurement/full strings are normalized independently; native YouTube wraps and places the resulting text.
- Original caption tracks, native/creator subtitles, multi-cue frames, non-scrolling translated captions, and disabled mode always pass through.

## How to validate

In a separate clone of 21.23.492 built with this patch and all regular Morphe patches:

1. Turn OFF `Test: Fix auto-translated caption line breaks`. Play video P6pcTK0wTYA at 00:09–00:26 with auto-translated Turkish captions. Capture screenshot.
2. Turn the setting ON, reopen the same video/time if necessary, and capture the same timestamp.
3. Run `adb logcat -d -v time -s MorpheCaptionBreakFix:I '*:S'` to confirm `normalized translated rolling cue` is printed. If no log, do not assume the fix ran.
4. Check source words remain complete, no duplicate lines are introduced, and the original (nontranslated) captions are identical in both modes.
5. Repeat folded/unfolded and portrait/fullscreen only after the video-level A/B passes.

**Never infer device success from an MPP/Gradle build alone.** This may remove only the double blank breaks and not solve every source segment/timing problem.