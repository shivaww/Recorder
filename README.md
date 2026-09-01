# BrollRender

Single-Activity Android app: pick a self-contained HTML animation (CSS keyframes, no JS logic) -> render it offscreen, frame-by-frame -> pixel-exact 16:9 MP4 (480p/720p/1080p, 24/30/60 fps). Zero third-party dependencies. No screen capture, no ffmpeg, no network needed after the first render.

## Build (no PC needed)

Push to GitHub. The `apk` workflow (`.github/workflows/build.yml`) builds `app-debug.apk` with Gradle 8.9 + AGP 8.5.2 and uploads it as the `app-debug` artifact. Download it from Actions -> latest run -> Artifacts, then install on the phone (allow "install unknown apps" for your browser/files app).

The repo commits `debug.keystore`, so every rebuild is signed identically and installs over the previous version without uninstalling.

## Use

1. **PICK** - CHOOSE HTML (system file picker, `text/html`), resolution 480p/720p/1080p, FPS and MODE presets (MODE snaps resolution + fps + bitrate together).
2. **PREVIEW** - FRAMING: AUTO (amber brackets lock the DOM-detected 16:9 frame) or MANUAL (pinch/drag zoom+pan, editor-style CROP with draggable corner handles + thirds grid, FULL/CENTER presets - what is inside the brackets is exactly what gets recorded). Settings: FPS 24/30/60, BITRATE 8/16/24 Mbps, ENHANCE OFF/ON, editable duration (5-600 s).
3. **RENDER** - progress bar with `frame N/T - r f/s - ETA`; CANCEL aborts cleanly (encoder released, partial file deleted).
4. **DONE** - file card read back with MediaMetadataRetriever: name, size, duration, resolution, VERIFIED/MISMATCH line. OPEN / SHARE / RENDER ANOTHER.

Output lands in `Movies/BrollRender/BrollRender_<stamp>.mp4`, with `Pictures/BrollRender/<stamp>_frame0.png` for eyeball QC (MediaStore forbids images under Movies/).

## Manual framing - no quality loss

Zoom and crop are applied as a CSS transform on the page itself, so content re-rasterizes at the output resolution - crisp text and vectors at any zoom, never a bitmap upscale of the capture. Known limits: `position:fixed` elements and `vw/vh`-sized layout will not pan; raster images soften when zoomed. Conforming HTML (a `.fit` 16:9 frame containing `.stage`) still auto-detects; non-conforming HTML falls back to manual framing instead of aborting, initialized by an automatic content-bounds fit (contain + center) so phone-authored pages fill the frame instead of rendering small in the void. The one hard requirement: at least one CSS animation - the whole engine scrubs `document.getAnimations()`.

## ENHANCE - honest naming

A native ColorMatrix color grade (contrast ~1.12 around mid-gray + saturation 1.18) applied per frame at native speed. It is deliberately NOT an AI model - the app stays zero-dependency and nothing ever leaves the device. frame0.png is exported with the same grade so QC still matches.

## HTML contract

- The 16:9 video frame is `<div class="fit">` (CSS `aspect-ratio: 16/9`, contains `.stage`); `#video-frame` is also supported. Everything outside it is crop chrome; `?headless=1` (plus an injected class) hides it.
- All motion is CSS animations; the timeline is scrubbed via `document.getAnimations()` + `currentTime`.
- Webfonts: every family requested via the page's Google Fonts `<link>` is checked (default pairing Anton + IBM Plex Mono; deviations allowed) - the first render needs internet, the WebView caches them afterwards (a warning, not an abort, if they are missing).
- Page void background: `#0A0C10` (the renderer's canvas pre-fill matches it).
- The exact injected JS lives in `app/src/main/java/com/brollrender/app/JsContracts.kt` (frozen strings).

## Hard rules (by design)

- Never captures the screen: offscreen WebView, software layer, manually drawn per frame.
- Never upscales: AUTO framing must match the target within 2 px or fall back to manual; manual zoom/crop re-rasterizes the page instead of resampling pixels.
- Corner detection is DOM geometry (the page reports its own rect), never vision.
