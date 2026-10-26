# Guarded caption geometry candidate v7 (26 Oct 2026)

## Why
User reports subtitles become small again during 16:9→4:3→16:9 video switching. The actual device log in `caption-video-switch.log` shows:
- Distinct video ID changes are now detected (4 reset messages).
- `stable reference=2520x1080 heightPerWidth=0.42857143` (21:9-like) is learned.
- A 4:3-ish window `978x734` is **incorrectly compressed to `978x419`** using the 0.42857143 reference.
- A fresh near-square `2184x1968` view can also have `reference=missing`, leaving native huge captions.

## Conservative test candidate
Compared to PR #7:
1. Normalization is only considered for **height/width >= 0.85** in landscape (not 4:3-ish 0.75 layouts or intermediate transitions).
2. Stable reference must be near **9:16 height/width**, within ±0.035. Reject 21:9 ratio 0.4286 and transitional ratio 0.7358. This is intentionally **16:9-oriented**, not universal video-aspect handling.
3. Actual video ID deduplication/reset from PR #7 remains unchanged.
4. If no trusted reference exists, preserve native YouTube geometry. This deliberately avoids arbitrary shrinking but does **not** solve oversized captions on a cold-start fullscreen.

## Policy sanity checks
- 2184×1968 → eligible (requires trusted 16:9-ish reference).
- 1968×1946 → eligible (requires trusted 16:9-ish reference).
- 1080×810, 978×734, 2055×1553 → **never normalize**.
- 2520×1080 and 1968×1448 references → **not trusted**.
- Test source: `CaptionAspectRatioFixPolicyTest.java`.

## Device testing pending
- [ ] Build APK with the **full Morphe patch set** (omit `--exclusive`) + experimental geometry patch; use new clone package.
- [ ] Repeat same 16:9 → 4:3 → 16:9 video sequence; compare subtitles against stock/control in normal and fullscreen.
- [ ] Inspect logs and confirm **no `978x734 videoViewport=978x419`** and no `stable reference=2520x1080`.
- [ ] Verify 16:9 unfolded fullscreen still normalizes `2184x1968` to ~`2184x1228` when a trusted 16:9 reference is available.
- [ ] Test 4:3 content in genuine near-square fullscreen and direct cold-start fullscreen. These cases are **not yet solved** by these conservative guards.

Do not merge into PR #7 until these cases pass. Maintain independent test branch `experiment/caption-geometry-guarded-v7`.
