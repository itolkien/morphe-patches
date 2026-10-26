# Caption layout investigation and replay implementation plan

> Execute inline with systematic debugging and test-first changes. User requests autonomous work and no merge.

Goal: retain the confirmed geometry A/B baseline, investigate translated pen/window rendering, deliver a separate experimental APK and reproducible phone-free checks.

Spec: the user's request of 26 October 2026; existing docs/caption-translated-pen-ab.md and docs/caption-geometry-toggle-probe.md.

Constraints: YouTube 21.23.492 initially; never alter the existing clones, main, PR #7 or prior experiment refs; no claim of Fold device testing. Prefer native renderer and selective changes over flattening all captions globally.

1. Establish isolated branch from 4890f9d8c, inspect all four experiments, run baseline tests. Investigate build failures and record them.
2. Trace nll/aose font sizing and apow/SubtitleWindowView measurement; trace timed-text parser, pen inheritance and cue windows. Record hypotheses versus proven facts.
3. Install a workspace-local Android AVD with software rendering. If runtime is unavailable, use an Android runtime replay alternative and record the actual blocker.
4. Write failing tests for confirmed defects or narrowly gated experimental layout transformation, then implement minimal fix and rerun relevant/full suites. Preserve original/manual tracks, mixed spans, null/empty input, cached/concurrent parsing and off switches.
5. Build MPP and full-Morphe APK under unique package app.morphe.captionlayoutlab; inspect bytecode and signature. Run emulated replay and app smoke checks where feasible.
6. Review final diff; publish only the separate experiment branch if authenticated access works. Save APK, source patch/bundle, test evidence and Turkish findings in outputs. Do not merge.

Review focus: invisible source pens; stale translated parser state; cue ordering and timestamps; inline spans/newlines; font scaling/density; multi-window captions must not be merged without a narrowly defined policy and tests.
