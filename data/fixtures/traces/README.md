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
- Manual step counts were not recorded, so these traces don't measure step error.

| File | Duration | Detector / counter / peak steps | Checkpoints |
|---|---|---|---|
| `eh-3-elevators-3418-3446-01` | 76.5 s | 91 / 93 / 97 | 3418, 3446 |
| `eh-3-elevators-3418-3446-02` | 75.0 s | 88 / 91 / 92 | 3418, 3425, 3446 |

Measured from the traces: mapped heading is within about 10° of each route leg, and
route length over detector steps gives k ≈ 17.0 and 17.6 px/step.
