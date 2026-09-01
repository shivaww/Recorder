# Acceptance checklist - self-report after each on-device run

Run against any HTML file (conforming files auto-detect the frame; non-conforming files fall back to MANUAL framing). Failing loud is the point: record result + evidence. Do not skip rows - use BLOCKED when a test cannot run.

## Core (original spec)

| # | Test | Result (PASS / FAIL / BLOCKED) | Evidence (what you saw) |
|---|------|-------------------------------|-------------------------|
| 1 | 1080p30 render: DONE screen shows 1920x1080 VERIFIED, duration ~ expected, file present in Movies/BrollRender | | |
| 2 | Frame at t = 16.2 s shows the large amber "CHEATING?" word (broll1.html only - proves the animation clock is frame-accurate; BLOCKED without that fixture) | | |
| 3 | First 3 s of the MP4 contain no corner-guide brackets and no hatched margins (headless mode works) | | |
| 4 | Zero black bars; frame0.png matches the first MP4 frame - also with ENHANCE ON (grade applied to both) | | |
| 5 | Draft (720p24) completes at least 2x faster than Final | | |
| 6 | CANCEL mid-render leaves no partial file in Movies/BrollRender; an immediate second render works | | |
| 7 | Second render of the same HTML uses zero network (fonts from WebView cache - check airplane mode) | | |
| 8 | Broken HTML (no `.fit`): lands on PREVIEW with the detection note + MANUAL framing defaulted (AUTO-FITTED) - renders successfully, no crash | | |

## Customization round

| # | Test | Result | Evidence |
|---|------|--------|----------|
| 9 | 480p render: DONE shows VERIFIED 854x480 | | |
| 10 | MANUAL pinch-zoom >= 2x: rendered zoomed text is CRISP (page re-rasterized), not blurry (would mean an upscaled bitmap) | | |
| 11 | CROP handles + APPLY: the recorded area matches the applied crop rect | | |
| 12 | FULL preset: the whole page is recorded at scale 1, centered | | |
| 13 | fps 60: duration correct and motion smooth (frame count = 60 x duration) | | |
| 14 | ENHANCE ON vs OFF: first frame visibly more contrast/saturation ON; frame0.png still matches the video | | |
| 15 | No screen capture: let a notification arrive mid-render (or take a screenshot) - none of it appears in the output video | | |
| 16 | Non-conforming page opens AUTO-FITTED: content fills the amber brackets (status line shows `MANUAL · AUTO-FITTED`), matching a manual screen-recording's framing - NOT small in a dark void | | |

Every FAIL gets a one-line note: what was seen vs what was expected, and at which screen (PICK/PREVIEW/RENDER/DONE). Update this file in the repo after each run - the checklist is the definition of done.
