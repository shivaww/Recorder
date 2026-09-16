# Nexon Studio

Pocket production studio: HTML motion clips -> pixel-exact 16:9 MP4s, and scripts -> Qwen3-TTS voice audio. Renders on this phone or on a Kaggle GPU farm. Zero third-party code; no screen capture, no ffmpeg.

## Design

Mission-control identity: steel panels on a blue-cast void, warm chalk ink, two semantic accents (AMBER = PIXEL, TEAL = VOICE), Chakra Petch display / Saira body / IBM Plex Mono data. The pipeline rail is the signature element — the render sequence, its state, and its captured decisions in one strip. Typefaces bundled as assets.

## Build (no PC needed)

Push to GitHub. The `apk` workflow (`.github/workflows/build.yml`) builds `app-debug.apk` with Gradle 8.9 + AGP 8.5.2 and uploads it as the `app-debug` artifact. Download it from Actions -> latest run -> Artifacts, then install on the phone (allow "install unknown apps" for your browser/files app).

The repo commits `debug.keystore`, so every rebuild is signed identically and installs over the previous version without uninstalling.

## Use

The home screen is two engine cards — **PIXEL** (video) and **VOICE** (audio), each in its engine accent — plus a machine footer (queue/farm counts) and one door to the **Machine room**: farm setup, farm jobs, overnight queue, generation prompt, all out of the creative path.

**PIXEL flow** — the pipeline rail (SOURCE · FRAME · RENDER · EXPORT, carrying each step's captured decision) persists across every screen:

1. **SOURCE** - Choose HTML (system file picker, `text/html`) or paste code.
2. **FRAME** - preview with AUTO (amber brackets lock the DOM-detected 16:9 frame) or MANUAL framing (pinch/drag zoom+pan, editor-style CROP with corner handles + thirds grid, FULL/CENTER presets). Resolution 480p/720p/1080p/4K, FPS 24/30/60, BITRATE, ENHANCE, SFX (when the page declares #sfx events) and editable duration (5-600 s) live on this screen.
3. **RENDER** - progress bar with `frame N/T - r f/s - ETA`; CANCEL aborts cleanly (encoder released, partial file deleted).
4. **EXPORT** - file card read back with MediaMetadataRetriever: name, size, duration, resolution, VERIFIED/MISMATCH line. OPEN / SHARE / RENDER ANOTHER.

Output lands in `Movies/NexonStudio/NexonStudio_<stamp>.mp4`, with `Pictures/NexonStudio/<stamp>_frame0.png` for eyeball QC (MediaStore forbids images under Movies/).

## Manual framing - no quality loss

Zoom and crop are applied as a CSS transform on the page itself, so content re-rasterizes at the output resolution - crisp text and vectors at any zoom, never a bitmap upscale of the capture. Known limits: `position:fixed` elements and `vw/vh`-sized layout will not pan; raster images soften when zoomed. Conforming HTML (a `.fit` 16:9 frame containing `.stage`) still auto-detects; non-conforming HTML falls back to manual framing instead of aborting, initialized by an automatic content-bounds fit (contain + center) so phone-authored pages fill the frame instead of rendering small in the void. The one hard requirement: at least one CSS animation - the whole engine scrubs `document.getAnimations()`.

## ENHANCE - honest naming

A color grade (contrast ~1.12 around mid-gray + saturation 1.18) applied as a root CSS filter inside the GPU draw - identical math to the previous native ColorMatrix, still at native speed. It is deliberately NOT an AI model - the app stays zero-dependency and nothing ever leaves the device. frame0.png is exported with the same grade so QC still matches.

## HTML contract

- The 16:9 video frame is `<div class="fit">` (CSS `aspect-ratio: 16/9`, contains `.stage`); `#video-frame` is also supported. Everything outside it is crop chrome; `?headless=1` (plus an injected class) hides it.
- All motion is CSS animations; the timeline is scrubbed via `document.getAnimations()` + `currentTime`.
- Webfonts: every family requested via the page's Google Fonts `<link>` is checked (default pairing Anton + IBM Plex Mono; deviations allowed). A requested font that has not loaded stops preparation instead of silently rendering narrower fallback glyphs; retry once it is available, then WebView caches it.
- Page void background: `#0A0C10` (the renderer's canvas pre-fill matches it).
- The exact injected JS lives in `app/src/main/java/com/brollrender/app/JsContracts.kt` (frozen strings).

## Hard rules (by design)

- Never captures the screen: offscreen WebView, hardware layer, drawn manually per frame - GPU-direct into the encoder surface when a per-render probe validates it, CPU readback fallback otherwise.
- Never upscales: AUTO framing must match the target within 2 px or fall back to manual; manual zoom/crop re-rasterizes the page instead of resampling pixels.
- Corner detection is DOM geometry (the page reports its own rect), never vision.

## Voice studio (Qwen3-TTS)

In-app voice generation on a Kaggle GPU notebook — same one-command pattern as the render farm:

1. New Kaggle notebook, Accelerator = GPU (T4 x2).
2. In the app: Voice studio → Start the voice server → Copy command, paste it into a Kaggle cell and run. It installs everything (flask, qwen-tts, cloudflared), starts the server plus a Cloudflare tunnel, and prints a BASE URL in a READY block.
3. Paste the BASE URL into Voice studio → Connection, save, test.
4. Three modes: **Custom** (9 preset speakers + instruct for tone/emotion), **Clone** (3–15 s reference audio + its exact transcript), **Design** (describe the voice in natural language). Paste the script — `{here complete voice over}` marks narration start — and generate.
5. The finished WAV lands in `Music/NexonStudio/` with play/share buttons.

Server files live in `voice/` (`run.sh` one-command launcher + `server.py`); Qwen3-TTS models lazy-load per mode on first request. Re-running the cell is safe: it kills the old server/tunnel and starts fresh.
