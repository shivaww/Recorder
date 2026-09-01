# PROMPT_SPEC - app-side companion to the generation prompt

The generation prompt (SRT + the motion-graphics-designer contract) is the source of truth for the HTML. This file documents what BrollRender does with that output - no competing prompt here.

## The closed loop

SRT (renamed .txt) + prompt -> AI emits one self-contained HTML (beat table, manifest, file) -> BrollRender: PICK -> PREVIEW -> RENDER -> DONE. The old fallback in the prompt's RECORD NOTE ("screen-record N seconds, crop to the corner brackets") is obsolete - the app replaced exactly that path, without the editor-crop resampling blur.

## How the app frames the file (in order)

1. `.fit` / `#video-frame` found and it fills the canvas (+-2 px) -> AUTO, LOCKED, corners verified on the preview.
2. 16:9 frame found but SMALLER than the canvas (the black `.viewport` wrapper design) -> FRAME-FIT: the page is scaled so the frame fills the video. CSS re-rasterization - text and SVG strokes come out at full output resolution, never a resampled bitmap. Status line: MANUAL · AUTO-FITTED; still pinch/crop-adjustable.
3. No frame at all -> content-bounds contain-fit.
4. Any case -> MANUAL framing (pinch/drag, CROP handles, FULL/CENTER presets).

## Fonts

The app checks every family actually requested in the page's Google Fonts `<link>` - so "deviate for a strong episode identity" works; the FONTS manifest line stays as human QC, not a renderer requirement. First render needs internet once; the WebView caches after (airplane-mode-safe on the second run).

## Why the paused clock is safe with this prompt

- `animation-fill-mode: both/forwards` + finite timeline -> DURATION_JS lands on the declared DUR (block length rounded up), editable on PREVIEW.
- Infinite ambient loops are scrubbed but ignored for duration detection - exactly the ambient-floor rule.
- Guides that "auto-hide at 3.5s" CANNOT auto-hide under the frozen clock - the headless rule (`body.cdp .guides {display:none}`, contract #4) is what actually removes them. Keep that rule in every file.
- `pathLength="1"` stroke reveals scrub perfectly (dashoffset is a plain keyframe).

## QC loop (fast)

1. Render a 480p DRAFT first - the fastest full-fidelity timing check.
2. Scrub-verify the manifest MEGA-SLAM timestamp (e.g. @16.2s): punch-word + ring-burst exactly there. Frame-accurate at 30 fps.
3. Check "animations locked: N" > 0 (N ~ number of timed elements; N = 0 means the AI slipped JS motion in - regenerate).
4. Render FINAL 1080p30. Bottom 25% stays empty for captions layered in the editor later.

## "Do my prompt's font/element sizes need to change?" - No

Small-looking renders were a viewport-scale artifact, never a design-size problem. The prompt sizes everything in cqh - fractions of the 16:9 frame - and the app guarantees that frame maps onto the full output canvas: AUTO lock (+-2 px) when it fills the window, frame-fit when the black-wrapper design makes it smaller (CSS re-rasterization - text and SVG strokes at full output resolution). Keep the punch-word (8-15cqh) vs label (1.4-2.3cqh) budget exactly as the prompt defines it.

Optional belt-and-suspenders (NOT required - frame-fit already handles it): make .fit fill the window itself so the app's fit scale stays ~1:

    .fit { aspect-ratio:16/9; container-type:size; overflow:hidden;
           width:100vw; max-width:177.78vh; margin:0 auto; }

## SFX - declarative sound effects (supported)

The renderer can bake synthesized SFX into the MP4's audio track. To opt in, every generated file carries a DATA-ONLY manifest (not logic - the zero-JS rule is untouched):

    <script type="application/json" id="sfx">
    [{"t":16.2,"id":"slam","gain":0.9},{"t":10.4,"id":"tick","gain":0.5}]
    </script>

Rules for the generation prompt:
- t = seconds on the SAME timeline as animation-delay (the spoken-beat clock)
- one event per visual beat, max ~12 per 60s block; the mega-slam always gets "slam"
- vocabulary: slam (stamp / kinetic word landing), boom (deep impact), whoosh (enter/exit sweep), tick (small UI/HUD), rise (build-up INTO a beat - place at impactT minus 0.8), ding (reveal), pop (small object), glitch (error/reject)
- gain 0-1, default 0.7

Why manifest instead of audio elements: the render scrubs the frozen CSS clock at render speed (not real time), so live audio can never stay in sync. Declared events are synthesized app-side and muxed on the same master frame clock - sample-accurate, offline, deterministic, no audio files in the HTML.
