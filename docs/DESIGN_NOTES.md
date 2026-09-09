# DESIGN NOTES — BrollRender Android console

Pass 1: "mission-control console" — a pocket console for a remote GPU render farm.
Identity kept from the pre-design app: mono telemetry, amber on black, teal progress.
The pass added surface hierarchy, state encoding, and type roles — not a new identity.

## Tokens (source of truth: app/.../Console.kt)
- VOID   #0B0E11  background field
- PANEL  #151B21  card surface
- EDGE   #232C33  hairlines + ghost fills
- AMBER  #FFB454  primary action / attention
- TEAL   #3FD8C2  progress / healthy / success
- ALERT  #FF6B5E  error / destructive
- CHALK  #E6EDF3  primary text; CHALK_DIM = 62% alpha = secondary text

## Type roles
- DISPLAY: sans-serif-condensed bold, uppercase, +tracking — titles, control labels, section headers.
- DATA: monospace — every number, id, ETA, log line, code blob.
- BODY: sans-serif-medium, sentence case, 1.15 leading — helpers, guidance, empty states.

## Layout system
- Screens = vertical column, 18dp padding, inside ScrollView.
- Cards: PANEL bg, 1dp EDGE stroke, 4dp radius, 3dp left state stripe
  (Console.stateColor: teal done / amber working / alert failed / dim queued).
- Rails: Console.rail — teal progress over EDGE track.
- Buttons: mkButton — condensed uppercase; filled AMBER/VOID, ghost EDGE stroke, danger ALERT stroke.
- Signature: FarmPulseView — one segmented rail per GPU, fill = live util from the /gpu poll.
  The single memorable element; everything else stays quiet around it.

## Copy rules applied
- Name actions by what they do: "Start render", "Download video", "Cancel job", "Remove".
- Failures explain cause + fix in the interface voice (error screen, INVALID job card).
- Empty states are invitations ("No jobs on the farm yet...").
- The key-rotation warning lives at each point of failure (connection card, setup screen, error card).

## Tried / rejected
- JPEG intermediate frames: rejected — first lossy stage in a lossless pipeline; speed was
  recovered instead via 4-worker parallel capture (kaggle/renderer.py).
- New visual identity: rejected — the terminal vernacular was the soul; structure only.
- Near-black + single acid accent default: escaped via functional dual accents (TEAL vs ALERT
  carry meaning), real card structure, and a subject-derived signature instead of a gradient hero.
- Numbered markers: used only where content is a true sequence (Kaggle setup steps 1-2-3).

## Critique checklist (screenshot pass, pending)
- [ ] Hierarchy legible at arm's length: title > card > data row.
- [ ] Stripes read as state, not decoration.
- [ ] FarmPulse earns its place; cut it if it reads as ornament.
- [ ] Copy clear at a glance; no element does double duty.
- [ ] Touch targets >= 44dp; contrast holds on PANEL.
- Tune tokens in Console.kt only — every screen follows one change.

## Open
- Lossless (qp0 yuv444p) vs near-lossless (qp ~16-18) final encode — user decision; drives
  tunnel download size and time.
- Kaggle parallel-render verification: worker fps lines + resource panel paste still pending.
