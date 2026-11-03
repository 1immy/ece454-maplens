# 0006 · Scan to orient: automatic map direction, offline

**Status:** proposed · 2026-11-01 · needs team review (touches #12, #17, #19)

## Context

At START the user taps the corridor they face, which sets the heading offset θ0
(`θmap = ψ + θ0`, A2 in `project-AI-context.md`). Johnny wants this automatic,
and it has to work in a basement with no Wi-Fi, no cellular data and no GPS.

The phone's sensors cannot provide θ0 by themselves:

- **Game rotation vector** (our heading source) has an arbitrary horizontal zero.
  It measures turning well, but not which way is "map up".
- **Magnetometer** gives absolute north, but steel and reinforced concrete bend
  the field indoors. One MIT study measured an
  [average 45° compass error inside buildings](https://www.media.mit.edu/speech/papers/2011/positioning.systems.pdf),
  and [reinforcing steel causes anomalies](https://isprs-archives.copernicus.org/articles/XLIII-B1-2022/213/2022/isprs-archives-XLIII-B1-2022-213-2022.pdf).
  Basements are likely worse. The posted maps are also not north-up, so north
  alone would not give a map direction anyway.
- **ARCore** motion tracking runs offline but still has an arbitrary yaw.

So an absolute direction has to come from **matching something the camera sees to
the map**. Google Maps' indoor Live View does this by matching camera images
against Google's own imagery of that building
([VPS + Street View + ML](https://tomsguide.com/news/google-maps-now-offers-live-ar-directions-indoors-everything-you-need-to-know)).
We have no pre-collected imagery of each building, and it would need a server.
What we do have is the floor graph: corridor directions, junction shapes and the
room number of every door.

## Proposal

The user stands still and turns once on the spot while the phone takes a few
guided photos, like a panorama. Each photo is stamped with the game rotation
vector, so its direction in the phone's own frame (ψ) is known. On-device
analysis finds features in each photo, and a solver finds the θ0 that best lines
those features up with the graph.

### 1 · Guided capture (four or more photos)

- The screen shows a target dot for the next direction (0°, 90°, 180°, 270°
  relative to the first photo). As the user turns, the dot moves with the gyro.
  When the reticle is on the dot and the phone is steady, the photo is taken
  automatically. This is the same guidance as Photo Sphere and panorama modes.
- Four photos is the minimum. Six to eight overlapping photos give better corridor
  coverage for little extra time (about 10–15 s). A continuous video sweep gives
  more data but blurs more in dim light and costs more to process, so stills
  come first.
- Each photo is saved with its quaternion, gravity vector, timestamp and the
  camera's horizontal field of view.

### 2 · On-device features (no network)

| Feature | How | What it gives | Library (already in `libs.versions.toml`) |
|---|---|---|---|
| **Room numbers** | OCR, then the same normalization and match as sign scanning (A5) | Bearing to a known door: `ψ_frame + pixel offset × FOV`. With the user's position known, **one readable door fixes θ0 completely** | ML Kit text recognition, **bundled** artifact `com.google.mlkit:text-recognition` (model ships inside the APK, about 4 MB per script per ABI; [docs](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)) |
| **Corridor axis** | Line segments, then vanishing points. Gravity from the accelerometer fixes the horizon, so horizontal vanishing points are a 1-D search ([Kim and Manduchi](https://cloudfront.escholarship.org/dist/prd/content/qt2238w2k7/qt2238w2k7.pdf); [Elloumi et al.](https://link.springer.com/article/10.1007/s11554-014-0419-9)) | Corridor direction to a few degrees, but **modulo 90°** (Manhattan world: walls are at right angles) | OpenCV (LSD or Hough) |
| **Corridor openings** | A vanishing point inside the frame with long converging lines means "looking down a corridor"; a wall fills the frame otherwise | Which of the four axis directions are open, to match against the corridors that meet at the user's node (dead end 1, straight 2, T 3, cross 4) | OpenCV; optional depth model later |

An ML depth model (for example a small monocular depth network converted to
TFLite) could score openness more robustly, but it adds size and work. It is
phase 4, used only if the classical method fails on our walks.

### 3 · Solver (pure Kotlin, `tracking/`)

Score every candidate θ0 on a 1° grid, 0–359°:

- **Axis evidence:** each graph edge bearing near the user's node vs each
  measured corridor axis, compared modulo 90°.
- **Opening evidence:** the set of open directions should match the incident edges
  at the user's node. This removes most of the 4-way ambiguity: a dead end or a T
  has no symmetric twin.
- **Door evidence:** the measured bearing to each recognized door vs its bearing on
  the graph. This is sharp and breaks any remaining symmetry.
- Optional weak **magnetic prior**, only when the field strength looks
  undisturbed. Off by default.

Output: best θ0, its score margin over the runner-up, and the runner-ups.
Each observation adds `ln(floor + (1 − floor)·exp(−e²/2σ²))` for bearing error
`e` (a wall adds the complement), so one bad reading costs at most `ln(floor)`.
Defaults: σ 10° door, 5° axis, 20° opening; floor 0.05; CLEAR needs a margin of
2.3 (best about 10× as likely as the runner-up), which one clean door gives. Never
apply silently:

- **Clear winner:** call `HeadingEstimator.align` and show "Facing ↑ map up (auto)".
- **Close second** (for example a straight corridor with no readable sign):
  show both arrows for one tap, or seed the particle tracker with both and let
  the first turn decide. That matches the "never snap or freeze silently" rule.
- **Nothing usable** (dark, blank walls): fall back to today's manual tap.

**Position is required.** The solver needs to know roughly where the user
stands. At START this is the "You Are Here" node (#15). If that is missing, the
recognized doors also give position: two readable doors pin it down.

### 4 · Free shortcut at a poster

The evacuation posters are drawn rotated to the viewer's facing direction (#3,
#4). If the user photographs the poster (#17 capture), the phone is pointing at
the wall at that moment, so "map up" equals the phone's current heading. That
is θ0 with no scan at all. It works only at a poster, and only if the poster
follows that convention, so check this per building.

## Plan

| Phase | Deliverable | Needs |
|---|---|---|
| 1 | **Done.** `tracking/OrientationSolver.kt` + `OrientationSolverTest` (11 synthetic cases) and `OrientationSolverEh3Test` (all 75 EH3 nodes: never confidently wrong; a neighbouring door settles 65/65): graph + node + observations → θ0 candidates and CLEAR / AMBIGUOUS / NO_EVIDENCE. Tunables in `TrackingConfig.orient*` | `core/model` only |
| 2 | **Built.** Debug `OrientScanActivity` (open from the recorder): CameraX preview, gyro-guided target dot, auto photo when aimed, upright and steady (gates in `TrackingConfig.orientCapture*`). Saves photos + pose + `scan.json` under app files `scans/` | CameraX (debug only) |
| 3 | **Built, untuned.** `ScanAnalysis.kt`: camera pose from the quaternion, pixel → bearing, room-number matching with OCR look-alikes, and a pure-Kotlin vanishing-point finder (blur, Sobel, structure-tensor line test, horizon voting; no OpenCV). OCR is ML Kit's bundled model, so it runs offline | ML Kit bundled (debug only) |
| 4 | Plug into START flow; optional depth model if openings fail | #9 NavigationSession, team sign-off |

**Measure:** θ0 error against the poster-aligned or checkpoint-derived truth;
the share of scans with a clear winner; capture time; and success in dim light.
Target to be agreed by the team; a starting suggestion is ≤ 10° error on 80 % of
scans, with a correct fallback on the rest.

## Open questions for the team

1. Owner and issue. It overlaps #12 (heading, Johnny), #17 (CameraX, JaJaBaa)
   and #19 (sign OCR, Johnny + JaJaBaa). Suggest a new G3 or G4 issue.
2. Does θ0 get stored in `FloorGraph` (the report lists a heading offset) or stay
   session-only? A `core/model` change needs both teammates' approval (ADR 0001).
3. Is a turn-on-the-spot scan acceptable in the user study, or only when
   confidence is LOW?
4. APK size budget for bundled OCR plus OpenCV (both are already planned for
   `mapparse`).
