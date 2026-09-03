# PROMPT_SPEC — app-side companion to GENERATION_PROMPT

The generation prompt (SRT + VIDEO_TYPE + the motion-graphics contract) is the
source of truth for the HTML. This file documents what BrollRender does with
that output.

## The closed loop

SRT (renamed .txt) + VIDEO_TYPE + prompt → AI emits one self-contained HTML
(beat table, manifest, file) → BrollRender: PICK (or PASTE HTML) → PREVIEW →
RENDER → DONE. No screen recording, no editor crop, no resampling blur.

## Two page modes

1. **CSS-only**: all motion via CSS keyframes. The app freezes the clock via
   `document.getAnimations()`, scrubs `currentTime` frame-by-frame.
2. **Scrub-safe JS**: page implements `window.__broll = { seek(t), duration() }`.
   The app calls `__broll.seek(tSec)` alongside the CSS scrub each frame.
   Canvas 2D, WebGL, SVG DOM manipulation, video scrubbing — all supported.
   Banned: setInterval, setTimeout, rAF, fetch, network, Audio/Video .play().
   The app flags banned APIs as a warning (they're inert, not scrubbed).

## How the app frames the file (in order)

1. `.fit` / `#video-frame` found and fills the canvas (±2 px) → AUTO, LOCKED.
2. 16:9 frame found but SMALLER → FRAME-FIT: CSS re-rasterization scales it
   to fill the video. Text/SVG at full output resolution, never bitmap upscale.
3. No frame at all → content-bounds contain-fit.
4. Any case → MANUAL framing (pinch/drag, CROP handles, FULL/CENTER presets).

## Rendering pipeline (hardware-accelerated)

- WebView: LAYER_TYPE_HARDWARE → Chromium GPU compositor.
- Enables: CSS filters, mix-blend-mode, backdrop-filter, Canvas 2D GPU accel,
  WebGL, 3D transforms.
- Per frame: seek JS (forces synchronous reflow) → web.draw() → GPU→CPU
  readback → blit to encoder surface → H.264 encode.
- No screen capture. No network needed after first render (fonts + images cached).

## Media loading

- **URL images** (primary): `https://` URLs in <img> or canvas drawImage.
  Loaded on first render (internet needed once), cached by WebView after.
- **Local files**: `file:///` paths for user's own images/video clips.
  WebView configured with allowFileAccessFromFileURLs + universal access.
- **Video scrubbing**: `<video>` elements seek via `video.currentTime = t`
  inside `__broll.seek(t)`. Never .play(). mediaPlaybackRequiresUserGesture=false.

## Fonts

Generic check: every family in the page's Google Fonts `<link>` is verified.
Any pairing works. FONTS_KICK re-inserts links + explicit document.fonts.load().
45s poll timeout = warning, not abort. First render needs internet; cached after.

## Audio pipeline

- **SFX**: `#sfx` JSON block → 8 synthesized sounds (slam boom whoosh tick
  rise ding pop glitch) → PCM mix → AAC encode → muxed on same master clock.
- **Ambience**: `#ambience` JSON block → synthesized bed (drone/pulse/air/
  tension) → fade in 1s, fade out 2s → mixed under SFX at declared gain.
- Master gain 0.45 (-7 dBFS). Loudness multiplier: low=0.5, normal=1.0, high=1.6.
- SFX VOLUME toggle in app: QUIET/NORMAL/LOUD overrides page declaration.

## Encoding

- H.264 AVC High Profile, VBR (quality-biased, bitrate = ceiling).
- I-frame interval: 1s (editor-friendly scrubbing).
- CBR fallback if device rejects VBR/High Profile.
- Resolutions: 480p / 720p / 1080p. FPS: 24 / 30 / 60.
- Bitrate options: 8 / 16 / 24 Mbps.
- Audio: AAC 44.1kHz mono 96kbps.

## TEXT scaling

App toggle: COMPACT (×0.9) / STANDARD (×1.0) / LARGE (×1.35).
Applied as CSS zoom on <body> — proportional, cqh recompute, design scales
coherently. Design for STANDARD.

## ENHANCE

Native ColorMatrix grade: contrast 1.12 around mid-gray + saturation 1.18.
Applied per frame at native speed. frame0.png exported with same grade.
Not an AI model — deterministic, zero-dependency.

## QC loop (fast)

1. Render 480p DRAFT first — fastest full-fidelity timing check.
2. Scrub-verify the manifest MEGA-SLAM timestamp: visual + sound on same frame.
3. Check "animations locked: N" > 0 (or "JS: scrub-safe __broll active").
4. Check ambience declared and audible.
5. Render FINAL 1080p30. Bottom 25% stays empty for captions.

## Paste HTML

PICK screen: PASTE HTML reads clipboard (any length), validates it looks like
HTML, writes to cache, prepares. No file-size limit.

## No file-size limit

The WebView loads from file:// on disk. HTML can be any size. Media lives at
URLs or file paths, keeping the HTML itself lean.
