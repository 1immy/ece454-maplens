# Data

| Folder | In git? | Contents |
|---|---|---|
| `fixtures/photos/<building>/` | Yes (LFS) | Wall-map photos plus `manifest.csv` (location, map type, shot angle, original filename, capture time) |
| `fixtures/rectify/` | Yes | Hand-labeled poster corners per photo (`*-corners.json`), the rectification answer key |
| `fixtures/maps/` | Yes (LFS for images) | Rectified map images plus hand-traced ground-truth graphs (`*.graph.json`) |
| `fixtures/traces/` | Yes (LFS) | Short recorded sensor traces (`*.csv`) with checkpoint taps, for replaying the tracker |
| `raw/` | **No** (ignored) | Scratch space: unrenamed originals, HEIC files, long walks |

Photo names: `eh-<floor>-<location>-<evac|dir>-<nn>-<shot>.jpg`, e.g. `eh-3-elevators-evac-03-front.jpg`.

Run `git lfs install` once before adding anything here.

## Trace format (proposed, agree before recording)

```
timestamp_ns,type,v0,v1,v2,v3,label
```

`type` is one of `step`, `accel`, `gyro`, `game_rv`, `checkpoint`. `label` holds the checkpoint name (for example `door-3650`) on `checkpoint` rows.
