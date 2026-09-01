# Acceptance checklist - self-report after each on-device run

Run against a conforming HTML file (the spec's `broll1.html` if you have it; otherwise any file matching the HTML contract in README). Failing loud is the point: record result + evidence. Do not skip rows - use BLOCKED when a test cannot run.

| # | Test | Result (PASS / FAIL / BLOCKED) | Evidence (what you saw) |
|---|------|-------------------------------|-------------------------|
| 1 | 1080p30 render: DONE screen shows 1920x1080 VERIFIED, duration ~ expected, file present in Movies/BrollRender | | |
| 2 | Frame at t = 16.2 s shows the large amber "CHEATING?" word (broll1.html only - proves the animation clock is frame-accurate; BLOCKED without that fixture) | | |
| 3 | First 3 s of the MP4 contain no corner-guide brackets and no hatched margins (headless mode works) | | |
| 4 | Zero black bars; frame0.png matches the first MP4 frame | | |
| 5 | Draft (720p24) completes at least 2x faster than Final | | |
| 6 | CANCEL mid-render leaves no partial file in Movies/BrollRender; an immediate second render works | | |
| 7 | Second render of the same HTML uses zero network (fonts served from the WebView cache - check airplane mode) | | |
| 8 | Deliberately broken HTML (no `.fit`) shows the FRAME_NOT_FOUND error on the error screen - no crash | | |

Every FAIL gets a one-line note: what was seen vs what was expected, and at which screen (PICK/PREVIEW/RENDER/DONE). Update this file in the repo after each run - the checklist is the definition of done.
