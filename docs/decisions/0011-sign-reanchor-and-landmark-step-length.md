# 0011 · Room-sign re-anchoring and step length from landmarks

**Status:** accepted for G3 · 2026-11-11 · #19

## Context

Without GPS or Wi-Fi, dead reckoning drifts: the particle cloud spreads along long
corridors and a wrong step length k builds error with every step. The confidence monitor
(#18) says *when* position is uncertain; #19 needs a way to fix it. The proposal's answer is
a room-sign scan, gated so a misread can't teleport the user (A5). Scan-to-orient (ADR 0006)
already reads room numbers with on-device ML Kit and turns a picture column into a bearing.

## Decision

1. **Scan a sign when unsure.** The confidence chip offers "Scan a sign" when LOW (and is
   tappable any time on a live walk, like Live View's recalibrate). A full-screen camera
   reads room numbers four times a second with ML Kit's bundled recognizer; a room read in
   3 of 5 frames goes to the tracker. Each read also gives:
   - **bearing**: live map heading plus the text's angle off the picture centre (FOV from
     Camera2), as in ADR 0006;
   - **distance**: pinhole model, `f · H / h` with printed character height `H`
     (`signTextHeightMeters`, 4 cm guess). ML Kit pads small boxes, so text under 16 px
     gives no distance (about 2.3 m at 1080 px wide); the sign then anchors on the door.
     A monocular depth model was considered and left out: character height is cheap,
     exact enough close up, and needs no extra model.
2. **Tracker side (`ParticleTracker.onSighting`).** OCR is normalized (O→0, I/l→1, S→5; a
   letter after four digits is a suffix) and matched to door labels exactly, or to the one
   label a single edit away; ambiguity matches nothing. The sign is accepted only if
   particles holding `signGateMinWeight` (5 %) of the weight are within `max(3σ, 5 m)` of the
   door. Then: re-weight with a Gaussian (σ = max(1.5 m, 0.35·distance)) around where the
   camera puts the walker, re-place 30 % of the cloud there, resample. `SignObservation`
   (core/model) is unchanged; bearing and distance travel in a tracking-side `SignSighting`.
3. **Step length from landmarks.** START, the calibration turn (#12), every accepted sign and
   every confirmed turn at a junction (heading > 60° off the leg for 2 steps, estimate just
   past a junction, confident cloud) are landmarks. Between two landmarks at least 8 steps
   apart, `k_obs = graph distance / steps`; if within ±30 % of k, `k ← 0.7·k + 0.3·k_obs`
   (A4) and every particle's k is re-drawn. Switchable with `landmarkStepLength`.

## Consequences

- Replay of the EH3 walks: a sign at the 3418 checkpoint collapses the cloud to 0.5–0.8 m;
  with k 15 % too long, end error at 3446 drops from 3.2 to 2.0 m on one walk and is no
  worse on the others (k is only nudged, per A4). A sign for 3446 read at 3418 is rejected.
- On the phone (self-test on drawn plaques): rooms read up to 6 m, distances within 5 % up
  to 2 m; a letter-suffix room (3539A) only up to 3 m.
- Turn landmarks change nothing on the recorded route (one turn after calibration); they
  matter on longer, turnier routes. Thresholds are proposal values, tuned with #23.
- Not yet tested in a real corridor: the character height and the reading range need the
  evaluation walks (#21).
