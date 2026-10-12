# Data

| Folder | In git? | Contents |
|---|---|---|
| `fixtures/maps/` | Yes (LFS) | A handful of egress-map photos plus hand-labeled ground-truth graphs (`*.json`) used in tests |
| `fixtures/traces/` | Yes (LFS) | Short recorded sensor traces (`*.csv`) with checkpoint taps, for replaying the tracker |
| `raw/` | **No** (ignored) | Full photo dataset and long walks. Lives in the team shared drive |

Run `git lfs install` once before adding anything here.

## Trace format (proposed, agree before recording)

```
timestamp_ns,type,v0,v1,v2,v3,label
```

`type` is one of `step`, `accel`, `gyro`, `game_rv`, `checkpoint`. `label` holds the checkpoint name (for example `door-3650`) on `checkpoint` rows.
