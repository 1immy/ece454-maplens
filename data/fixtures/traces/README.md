# Sensor traces

Recorded with the debug recorder (`RecorderActivity`); CSV format in `data/README.md`,
details in ADR 0002. Each `.csv` (Git LFS) has a `.json` sidecar with device, sensors,
counts, heading offset and checkpoints. Timestamps are Android boot-clock nanoseconds.

## EH3 elevators → 3418 → 3446

Map: `../maps/eh-3-elevators-evac.graph.json` (pixels on `eh-3-elevators-evac.jpg`).
Route from START (S1, the ★ by the elevators): left, down the side passage past the
stairs, then left along the bottom corridor from 3418 to 3446. 1,546 px along the graph.

- Pixel 7a, Android 14, 2026-11-02. Phone held in front, portrait, screen facing the walker.
- Aligned before Start by facing the poster and choosing "Map up"
  (`headingOffsetRad` in the JSON). Step source: Android step detector.
- The walker stopped at each door to tap Mark, so checkpoints fall in short pauses;
  the 3446 tap comes a few seconds after the last step.
- Walks 01 and 02 have no manual step counts. For 03 and 04 the walker counted steps
  between some checkpoints; those counts are in `manual-step-counts.json`.
- Walk 04 was rough: it stops at 3434, and its step counter (76) and peak (86) counts are
  well above the detector's 64, so use it only for its counted segments.

| File | Duration | Detector / counter / peak steps | Checkpoints |
|---|---|---|---|
| `eh-3-elevators-3418-3446-01` | 76.5 s | 91 / 93 / 97 | 3418, 3446 |
| `eh-3-elevators-3418-3446-02` | 75.0 s | 88 / 91 / 92 | 3418, 3425, 3446 |
| `eh-3-elevators-3418-3446-03` | 84.7 s | 96 / 99 / 101 | 3418, 3446 |
| `eh-3-elevators-3418-3434-04` | 99.8 s | 64 / 76 / 86 | 3418, 3425, 3434 |

Measured from the traces: mapped heading is within about 10° of each route leg, and
route length over detector steps gives k ≈ 17.0 and 17.6 px/step (01, 02).

### Step-count check (#12)

Android step-detector steps between checkpoint taps, against the walker's count:

| Walk | Segment | Counted | Detector | Error |
|---|---|---|---|---|
| 03 | start → 3418 | 32 | 35 | +9.4% |
| 03 | start → 3446 | 93 | 96 | +3.2% |
| 04 | 3418 → 3425 | 24 | 24 | 0% |
| 04 | 3425 → 3434 | 17 | 16 | −5.9% |
| | all counted, no overlap | 134 | 136 | +1.5% |

On short segments, a tap slightly early or late shifts the count by one step (3–6%).
These walks are normal pace with one grip only; they don't cover the 3 speeds × 3 tilts in
the plan. Counted k is 16.6 px/step over the full route, but 3418 → 3425 gives 13.0 here
(about 14 in walk 02), which suggests the graph puts those doors slightly too close together.
