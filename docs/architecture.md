# Architecture

## Data flow

```
                 setup, once per floor
 ┌───────────────┐   ┌──────────────┐   ┌───────────────────┐
 │ Capture+parse │──▶│ Graph editor │──▶│ Calibrate + route │
 │   (Area A)    │   │   (Area A)   │   │ k, A* (Areas B/C) │
 └───────────────┘   └──────┬───────┘   └────────┬──────────┘
                      FloorGraph          calibration │ Route
                 every step   ▼                  ▼    ▼
 ┌───────────────┐   ┌──────────────────┐   ┌──────────────┐
 │ Phone sensors │──▶│ Particle tracker │──▶│ Guidance UI  │
 │   (Area B)    │   │    (Area B)      │   │  (Area C)    │
 └───────────────┘   └───┬─────────▲────┘   └──────────────┘
          MotionEvent    LOW│      │re-anchor (SignObservation, Area A)
                       ┌────▼──────┴──┐
                       │  Confidence  │
                       │   (Area B)   │
                       └──────────────┘
```

Control flow (owned by `NavigationSession` in `app`):

```
Capture → Verify → Calibrate → Navigate ⇄ Recover
                                   └──▶ Arrived
```

## Contracts (`core/model`)

| Record | Producer → consumer |
|---|---|
| `FloorGraph` | Graph editor → router, tracker |
| `Route` | Router → guidance |
| `MotionEvent` | Sensors → tracker |
| `PoseEstimate` | Tracker → confidence, guidance |
| `ConfidenceState` | Confidence → guidance / session |
| `SignObservation` | Sign scanner → tracker |

All coordinates are pixels on the rectified map. Meters are display-only (k px/step × 0.7 m stride).

## Areas and ownership

| Area | Modules | Gantt tasks | Owner |
|---|---|---|---|
| A · Map understanding | `mapparse`, `tools/`, `data/` | 1–5, 11 | Jayden Lee (@JaJaBaa) |
| B · Localization | `tracking` | 7–10, 12 | Johnny Hong (@JohnnyHong0116) |
| C · Navigation and app | `app`, `guidance`, `routing` (with Jayden) | 6, 13, 16, 17 | Limo Kemei (@1immy) |
| Shared | `core/model`, `tools`, `data` | 1, 14, 18–21 | All |

Owners follow the graded Phase I plan (Jayden: map, CV, OCR, graph; Johnny: sensors, tracking, confidence; Limo: UI, guidance, integration, optional ARCore). Routing is shared by Jayden and Limo; confidence + sign reset by Jayden and Johnny. Limo built the first map tools (`tools/`) while repo access was being set up; Jayden takes them over from here. Everyone joins field tests and cross-reviews one other module.

## Evaluation hooks

`EvalCondition` (A: raw PDR, B: + map, C: periodic scans, D: confidence-triggered) is a runtime setting on `NavigationSession`, never a separate branch, so all four run from the same build and the same recorded traces.
