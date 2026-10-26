# Caption style A/B test (26 October 2026)

## Hypothesis
The user reports extremely large **auto-translated Turkish subtitles** in ordinary and fullscreen layouts, a problem that predated the Fold caption-geometry patch. The screenshot also shows text in separate caption windows. The native YouTube `nll.f(FII)F` font-return logs collected in the **same** diagnostic APK were the same for original and translated subtitle tracks at the same geometry:
- 1080×608: 14.476191 (both)
- 2520×1080: 25.714285 (both)
- `scale=1.0` and no effective geometry change in these cases.

Therefore, the discrepancy is **not explained by the base font calculation**. It may arise in timed-text pen styles, spans, caption-window layout, app subtitle settings or track-specific metadata.

## Existing Morphe patch behavior
The already-enabled `TranslatedCaptionPensPatch` detects auto-translated tracks by `vssId` beginning with `t` and maps all pen IDs to `Integer.MIN_VALUE`, forcing the default pen. This intentionally addresses invisible text caused by source-translation segment mismatch. But it may change **font size / style** when the source pen differs from the user's default.

## Diagnostic code (does not change known-good app)
The branch is based on `experiment/caption-geometry-toggle-probe`, the code underlying the visually confirmed good full-Morphe `app.morphe.captionabfull` APK.

It adds a new preference under **Morphe → Player → Captions**:

**Preserve original styles in auto-translated captions (test)**

- **OFF (default):** current Morphe behavior; force the default pen for auto-translated tracks.
- **ON:** do not remap auto-translated pen IDs; preserve the original IDs from timed-text tracks. **This can bring back invisible/black text** on some videos, which is why this is not a production fix.
- Original/nontranslated tracks are unchanged by this switch.
- `MorpheCaptionStyle` logs **at most one pen mapping per parsed translated request** (no caption text/video ID logged).
- The existing `MorpheCaptionFix` geometry toggle and native `font result` tracing are retained unchanged.

## Controlled test
Compile and patch with the full/default Morphe patch set; do **not** use `--exclusive`. Keep the installed `app.morphe.captionabfull` unchanged. Use a new clone package `app.morphe.captionpensab`.

Use the **same video, subtitle language and layout** for both tests, especially the video in the user's oversized-Turkish screenshot:

1. **Geometry ON**, native pens setting **OFF**: load Turkish auto-translation; record screenshot and `MorpheCaptionStyle` / `MorpheCaptionFix` logs.
2. Toggle native pens setting **ON**, **force-stop and relaunch app** (to invalidate cached parsed caption styles), reload the same video and translated track; record screenshot and logs.
3. Repeat with native original-language captions to see whether the switch is confined to translations.
4. If text disappears with native pens enabled, immediately restore OFF.

If the rendered font becomes normal only with native pens enabled, investigate a selective pen-preservation strategy (font size, window, style attributes) that doesn't reintroduce missing text. If it remains oversized, prioritize `SubtitleWindowView`, `apow`, `Spanned` payload and cue-window multiplicity. **Do not assume the two issues have a common cause**; Fold fullscreen `46.857143→29.214285` change in the native base font remains independently supported.

## Status
Code committed for review; **not built or device tested**. No change to PR #7 or to the known-good clone.
