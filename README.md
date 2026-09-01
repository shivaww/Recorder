# BrollRender

Single-Activity Android app: pick a self-contained HTML animation (CSS keyframes, no JS logic) -> render it offscreen, frame-by-frame -> pixel-exact 16:9 MP4 (1080p30 final / 720p24 draft). Zero third-party dependencies. No screen capture, no ffmpeg, no network needed after the first render.

## Build (no PC needed)

Push to GitHub. The `apk` workflow (`.github/workflows/build.yml`) builds `app-debug.apk` with Gradle 8.9 + AGP 8.5.2 and uploads it as the `app-debug` artifact. Download it from Actions -> latest run -> Artifacts, then install on the phone (allow "install unknown apps" for your browser/files app).

The repo commits `debug.keystore`, so every rebuild is signed identically and installs over the previous version without uninstalling.

## Use

1. **PICK** - CHOOSE HTML (system file picker, `text/html`), plus resolution / fps / mode toggles (MODE acts as a preset).
2. **PREVIEW** - the app draws its own amber corner brackets, rect outline and crosshair over the detected 16:9 frame; status lines show frame size, corner deviation, animation count, fonts status, detected duration (editable, 5-600 s). START RENDER or BACK.
3. **RENDER** - progress bar, `frame N/T - r f/s - ETA`, CANCEL aborts cleanly (encoder released, partial file deleted).
4. **DONE** - file card read back with MediaMetadataRetriever: name, size, duration, resolution, plus a VERIFIED/MISMATCH line against the target. OPEN / SHARE / RENDER ANOTHER.

Output lands in `Movies/BrollRender/BrollRender_<stamp>.mp4`, with `Pictures/BrollRender/<stamp>_frame0.png` for eyeball QC (MediaStore forbids images under Movies/).

## HTML contract

- The 16:9 video frame is `<div class="fit">` (CSS `aspect-ratio: 16/9`, contains `.stage`); `#video-frame` is also supported. Everything outside it is crop chrome; `?headless=1` (plus an injected class) hides it.
- All motion is CSS animations; the timeline is scrubbed via `document.getAnimations()` + `currentTime`.
- Webfonts: Anton + IBM Plex Mono from Google Fonts - the first render needs internet, the WebView caches them afterwards.
- Page void background: `#0A0C10` (the renderer's canvas pre-fill matches it).
- The exact injected JS lives in `app/src/main/java/com/brollrender/app/JsContracts.kt` (frozen strings).

## Hard rules (by design)

- Never captures the screen: offscreen WebView, software layer, manually drawn per frame.
- Never upscales: the detected frame must match the target within 2 px or the render aborts with a specific error.
- Corner detection is DOM geometry (the page reports its own rect), never vision.
