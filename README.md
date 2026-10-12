# MapLens

**Scan the map, navigate the building.** Infrastructure-free indoor navigation on Android from a photo of a posted floor map.

ECE 454 · Team JJL.EXE · Johnny Hong, Jayden Lee, Limo Kemei

MapLens turns one photo of an egress map into a routable corridor graph. It tracks the user with step and gyro dead reckoning constrained to that graph by a particle filter, and asks for a room-sign scan only when its own confidence drops. No beacons, Wi-Fi survey, server or venue-published maps are needed.

## Modules

| Module | What | Area |
|---|---|---|
| `core/model` | Shared records: `FloorGraph`, `Route`, `MotionEvent`, `PoseEstimate`, `ConfidenceState`, `SignObservation` (pure Kotlin) | Shared |
| `mapparse` | Capture, rectify, corridor mask, skeleton → graph, OCR, sign scanner | A · Map understanding |
| `tracking` | Steps, heading, scale k, particle filter, confidence checks | B · Localization |
| `routing` | A* over pixel lengths (pure Kotlin) | C · Navigation and app |
| `guidance` | Arrow, map, voice, vibration output | C |
| `app` | Activity, UI, `NavigationSession` state machine | C |
| `tools/` | Python notebooks and evaluation scripts | Shared |
| `data/fixtures/` | Small test maps and sensor traces (Git LFS) | Shared |

See [`docs/architecture.md`](docs/architecture.md) for the data flow and who owns what.

## Getting started

Requirements: Android Studio (Ladybug or newer), JDK 17+, [Git LFS](https://git-lfs.com).

```bash
git lfs install            # once per machine
git clone https://github.com/1immy/ece454-maplens.git
cd ece454-maplens
./gradlew test             # unit tests, no phone needed
./gradlew assembleDebug    # build the APK
```

Open the folder in Android Studio and run the `app` configuration on a phone. The emulator has no real step detector or gyro, so use a physical device for anything in `tracking`.

## Milestones

| Date | Milestone |
|---|---|
| Wk 6 | Parser + "Check the map" UI |
| Wk 8 | PDR + corridor snapping |
| Wk 10 | Guidance + sign reset |
| **Nov 16** | **Phase II demo** |
| Wk 12 | Evaluation (conditions A–D) |
| **Dec 7** | **Final presentation + live demo** |

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening your first PR.
