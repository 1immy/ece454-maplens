# MapLens: project context for AI assistants

Give this file to any AI assistant before asking it to write code, tests, docs or PR text for this repo. It describes what the project is, how the repo is organized, what is already built, the rules for contributing, and where each piece of work stands. **If anything here conflicts with the code on `main`, the code wins; if it conflicts with the graded Phase I report, ask the team.**

Last updated: Oct 23, 2026.

---

## 1. The project in one paragraph

**MapLens** (ECE 454 Mobile Computing Lab, team **JJL.EXE**: Johnny Hong, Jayden Lee, Limo Kemei) is an Android app that turns a photo of a posted wall floor map into an indoor navigation system. The phone straightens the photo, extracts a corridor graph and room labels, and the user verifies it. After a short calibration walk the app tracks the user with step + gyro dead reckoning, constrained to the corridor graph by a **particle filter**, and asks for a **room-sign scan only when its own confidence drops**. No beacons, Wi-Fi survey, server, GPS or venue map. Everything runs **offline on the phone**.

- **MVP:** one floor, **Engineering Hall 3rd floor** (UW–Madison).
- **Research question:** do confidence-triggered sign scans lower position error and avoid unnecessary scans, compared with raw PDR, graph-constrained PDR and periodic scans? (Conditions A–D, section 9.)
- **Out of scope:** emergency evacuation, blind navigation, multi-floor (stretch), pocket/swinging-arm carry.

## 2. People and ownership

Owners follow the graded Phase I plan. Everyone joins field tests and cross-reviews one other module.

| Person | GitHub | Owns | Label |
|---|---|---|---|
| Jayden Lee | @JaJaBaa | Map parsing, CV, OCR, graph editor, sign scanner (`mapparse/`, `tools/`, `data/`) | `area: map` |
| Johnny Hong | @JohnnyHong0116 | Sensors, calibration, particle tracker, confidence (`tracking/`) | `area: tracking` |
| Limo Kemei | @1immy | Guidance UI, `NavigationSession`, integration, offline, optional ARCore (`app/`, `guidance/`) | `area: app` |
| Shared | | Routing (Jayden + Limo); confidence + sign reset (Jayden + Johnny); `core/model`, build, CI (all three) | `shared` |

Limo built the first map tools while repo invites were pending; Jayden owns them from here.

## 3. Schedule: review gates = GitHub milestones

Each gate passes only when its tests pass (section 10). A failed gate becomes the first task of the next window. Stretch work is dropped before any core task slips.

| Milestone | Due | Passes when |
|---|---|---|
| G0 · Floor chosen | Oct 4 | Target floor chosen, map legible, plaques readable, sensors confirmed. **Done** (EH 3rd floor) |
| G1 · Graph and route approved | Oct 18 | Verified FloorGraph saved; A* reaches every destination; graph survives restart. **Graph done; routing by label + persistence still open** |
| G2 · Baseline navigates | **Nov 1** | Step/heading errors measured at 3 speeds × 3 tilts; tracker follows one full route with the dot on the correct corridor at every checkpoint |
| G3 · Sign reset works | Nov 15 | Each confidence check fires when forced; correct nearby signs accepted, wrong/distant rejected; drift recovery end to end. **Phase II demo Nov 16** |
| G4 · Evaluation build frozen | Nov 29 | All readiness criteria met, every end-to-end scenario passes incl. airplane mode, thresholds frozen |
| G5 · Final report | Dec 13 | A–D trials (Nov 30–Dec 6) + user study done; **final demo Dec 7**; results in report |

Dependencies: verified graph → calibrated tracker → confidence scans. Guidance UI develops alongside the tracker (it can run on a fake pose stream). A–D trials start only after the Nov 29 freeze.

## 4. Repository

`https://github.com/1immy/ece454-maplens` (private). Default branch `main`, protected.

```
ece454-maplens/
├── app/            Activity, UI, NavigationSession (single writer of position + route)   [Limo]
├── core/model/     Shared records + FloorGraph JSON. Pure Kotlin, no Android              [all]
├── mapparse/       Capture, rectify, corridor/OCR parsing, sign scanner (Android)        [Jayden]
├── tracking/       Sensors, step length k, particle filter, confidence (Android)         [Johnny]
├── routing/        A* over pixel lengths. Pure Kotlin                                    [Jayden + Limo]
├── guidance/       Arrow, map, voice, vibration outputs (Android)                        [Limo]
├── tools/          Python: labeling, rectification, corridor extraction, scoring         [Jayden]
├── data/
│   ├── fixtures/photos/ehall/   16 wall-map photos (LFS) + manifest.csv
│   ├── fixtures/rectify/        Hand-labeled map-frame corners (JSON)
│   ├── fixtures/maps/           Straightened maps (LFS) + hand-traced *.graph.json
│   └── fixtures/traces/         Sensor traces (CSV, LFS); none yet
├── docs/           architecture.md, decisions/NNNN-*.md
├── .github/        CI workflow, CODEOWNERS, PR + issue templates
├── gradle/libs.versions.toml    All dependency versions
├── README.md, CONTRIBUTING.md, project-AI-context.md (this file)
```

Package root: `edu.wisc.maplens` (`.model`, `.routing`, `.tracking`, `.mapparse`, `.guidance`).

### Build and toolchain
- Gradle **8.10.2** (wrapper), AGP **8.7.3**, Kotlin **2.0.21**, JVM target **17**. `compileSdk 35`, `minSdk 26`, `targetSdk 35`.
- Gradle 8.10 runs on **JDK 17–23** only. Use Android Studio's bundled JDK 21 (`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`).
- Lint: **ktlint** (Android Studio style, max line 100). `./gradlew ktlintFormat` fixes most issues.
- Commands: `./gradlew test` (unit tests, no phone), `./gradlew ktlintCheck`, `./gradlew assembleDebug`.
- Python tools: `python3 -m venv .venv && source .venv/bin/activate && pip install -r tools/requirements.txt` (numpy, pillow, matplotlib, opencv-python-headless, scipy, scikit-image); tests: `python -m unittest discover tools` (headless, `MPLBACKEND=Agg` in CI).
- **Git LFS** is required (`git lfs install`) for `data/fixtures/**/*.{jpg,jpeg,png,heic,csv}`; `manifest.csv` is plain git.
- The Android emulator has no real step detector or gyro: test `tracking/` on a phone or with recorded traces.

### CI (`.github/workflows/ci.yml`)
- `build`: `./gradlew ktlintCheck test assembleDebug` (JDK 17). Uploads the debug APK on pushes to `main`.
- `tools`: Python 3.12, installs `tools/requirements.txt`, runs `python -m unittest discover tools`.
- `./gradlew test` also loads **every** `data/fixtures/maps/*.graph.json` and fails if one doesn't pass `FloorGraph.validate()`.

## 5. What exists today

### On `main`
| Area | What | Notes |
|---|---|---|
| `core/model` | `FloorGraph`, `Node`, `Edge`, `Landmark`, `PixelPoint`, `NodeType`, `Route`, `MotionEvent`, `PoseEstimate`, `ConfidenceState` (+ `ConfidenceCheck`, `RecoveryAction`), `SignObservation`, `EvalCondition`; `FloorGraph.validate()`, `adjacency()`, `landmark()`; `FloorGraphJson` encode/decode | `landmark(label)` returns only the **first** match; rooms can have several doors |
| `routing` | `Router` interface, `AStarRouter` (node → node, straight-line heuristic over `lengthPx`) | Tests on a T-junction. Missing: route by **label** to the nearest door |
| `tracking` | `TrackingConfig` (all thresholds), `StepLength` (calibrate, 70/30 refine, px→m), `Tracker` + `ConfidenceMonitor` **interfaces only** | Implementations are G2/G3 work |
| `mapparse` | `MapParser`, `SignScanner` **interfaces only** | Kotlin port of `tools/` is G3 work |
| `guidance` | `GuidanceOutput`, `Instruction`, `Maneuver` | No implementation yet |
| `app` | Placeholder `MainActivity`; `NavigationSession` state machine (Capture → Verify → Calibrate → Navigate ⇄ Recover → Arrived) with tests | Not wired to UI or data yet |
| `tools/maplabel.py` | `corners` (label map-frame corners), `warp` (straighten), `trace` (click-to-trace a graph), `tidy` (route corridors through placed doors) | Close the trace window before `tidy` |
| `tools/rectify.py` | Automatic blue-frame detection + homography; `evaluate` vs labels | 15/16 accepted, median corner error 0.31% of diagonal (~8 px); returns a **retake** reason instead of a bad warp |
| Data | 16 EH3 photos (7 maps, 4 locations, 2 poster styles), 16 corner labels, straightened elevator posters (evac + directory), **hand-traced elevator evac graph: 75 nodes, 77 edges, 55 rooms, 7 exits, elevator, AED**, synthetic `t-junction.graph.json` | |

### In open PRs (check GitHub for status)
| Branch | What |
|---|---|
| `feat/corridor-extraction` | `tools/corridors.py`: route lines → mask → skeleton → graph, junction refinement, scorer. Corridor-length F1 **0.91** (tuned poster) / **0.93** (held out); junction F1 **0.80 / 0.86** |
| `data/ehall-3-stair3424` | Hand-traced stair-3424 evac poster (held-out test; corridors only, no doors) |
| `chore/team-setup` | Owners per graded plan, CODEOWNERS, CI `tools` job, ignore `*.json.bak` |

### Known gaps between the report and the code
These are deliberate simplifications so far. Changing them touches `core/model`, so all three owners have to agree (section 7).
- The report's `FloorGraph` also holds `version`, `imageRef`, `homography`, `k`, `k` uncertainty and heading offset θ0; the code holds geometry + labels only.
- The report's `Landmark` has normalized label, edge, offset and side; the code has label, node id, OCR confidence.
- The report plans **Jetpack Compose** UI and a **Room** database for persistence; neither is set up yet.
- The report's node types are junction, door, start, dead end, exit; the code adds `ELEVATOR`, `STAIRS`, `AED`.

## 6. Core contracts (`core/model`)

The only data passed between modules. No module reads another's internal state. `NavigationSession` routes them and is the **only writer** of position and route.

| Record | Producer → consumer | Holds |
|---|---|---|
| `FloorGraph` | Graph editor → router, tracker | Nodes (pixel coords on the straightened map), edges (polyline, `lengthPx`), landmarks (label → door node), `startNodeId`, `verified` |
| `Route` | Router → guidance | Ordered node ids + edge ids, `lengthPx`; `lengthSteps(k)` |
| `MotionEvent` | Sensor adapter → tracker | `Step(timestamp, headingRad, source)` or `Turn(timestamp, deltaRad)` |
| `PoseEstimate` | Tracker → confidence, router, guidance | Position, heading, edge, spread (px), top-group weight, current k |
| `ConfidenceState` | Confidence → guidance/session | HIGH/LOW, failed checks, suggested recovery |
| `SignObservation` | Sign scanner → tracker | OCR text, confidence, matched door node |

**Units:** all geometry is in **pixels of the straightened map** (origin top-left). Routing works in pixels. Meters are display-only: `meters = px / k × stride` (stride 0.7 m default).

### FloorGraph JSON (`*.graph.json`, schemaVersion 1)
```json
{
  "schemaVersion": 1, "buildingId": "engineering-hall", "floor": "3",
  "startNodeId": "S1", "verified": true,
  "source": { "image": "eh-3-elevators-evac.jpg" },
  "nodes": [ { "id": "S1", "type": "START", "x": 1440.2, "y": 862.0 } ],
  "edges": [ { "id": "e1", "from": "S1", "to": "J2", "polyline": [[1440.2, 862.0], [1388, 862]], "lengthPx": 52.2 } ],
  "landmarks": [ { "label": "3650", "nodeId": "D24", "ocrConfidence": 1.0 } ]
}
```
Edge `id`, `polyline` and `lengthPx` are optional on read (filled in). Node types: `JUNCTION, DOOR, START, DEAD_END, ELEVATOR, STAIRS, EXIT, AED`.

**Tracing conventions:** doors sit on the corridor **centerline** opposite the opening. A room with several entrances gets one door node per entrance, all with the same label. A corridor that leaves the drawing ends in a `DEAD_END` labeled `OFFMAP`. A corridor leading to an exit ends in an `EXIT` (e.g. labeled `TO-EXIT`).

### Other data formats
- **Corner labels** (`data/fixtures/rectify/*-corners.json`): outer corners of the **blue map frame** in upright (EXIF-rotated) pixels, order TL, TR, BR, BL.
- **Sensor traces** (proposed, agree before recording): CSV `timestamp_ns,type,v0,v1,v2,v3,label` with `type` ∈ `step, accel, gyro, game_rv, checkpoint`.
- **Photo names:** `eh-<floor>-<location>-<evac|dir>-<nn>-<shot>.jpg`.

## 7. Algorithms (from the graded report; implement to these specs)

### A1 · Map understanding (photo → graph) [Jayden]
1. Capture with CameraX; find the map frame; homography to a flat image **2000 px wide**; warn on glare (`tools/rectify.py` is the reference).
2. ML Kit on-device text recognition: room numbers + "You Are Here"; erase text boxes so they don't break corridors.
3. Corridor mask. The current prototype (`tools/corridors.py`) uses the **orange evacuation-route lines**, which run along almost every corridor; the report's alternative is threshold walls + seal doors + flood-fill rooms.
4. Thin to centerlines (Zhang–Suen / skeletonize); junctions = ≥3 neighbors, ends = 1; Douglas–Peucker; merge close junctions; prune spurs.
5. Attach each room number to the nearest edge as a door node; "You Are Here" becomes START.
6. **Verify:** saving is blocked until the graph is connected, each destination links to a door, labels are unique, and no edges cross without a junction.

Maps are drawn **rotated to the viewer's facing direction**, not north-up (the street name at the top tells the orientation). Evacuation posters show **part** of the floor; directory maps show all of it, but corridors and rooms are both white there (harder).

### A2 · Heading and steps [Johnny]
- Carry mode: phone held in front, screen up, portrait locked; any tilt from flat to vertical.
- Steps: Android step detector; accelerometer-peak fallback.
- Heading: **game rotation vector** (no magnetometer). `f = R·ŷ − R·ẑ`, `ψ = atan2(fx, fy)`, `θmap = ψ + θ0`.
- θ0 set at START: the user taps the arrow for the corridor they face (`θ0 = corridor bearing − ψ`); nudged toward the corridor bearing after 10+ straight steps on one corridor.

### A3 · Particle filter [Johnny]
- ~**300** particles on corridor edges: (edge, offset px, direction, own k, weight).
- **Predict:** move by own k + ~10% stride noise; at junctions choose branches that fit the heading.
- **Weigh:** `w ← w · exp(−(θmap − β)² / (2σθ²))`, σθ ≈ 20°; past a dead end or far from a scanned sign → ~0.
- **Resample** when effective particle count < N/2.
- **Estimate:** group particles on the same corridor within 2 m; report the weighted mean of the **heaviest group** (never average groups).

### A4 · Scale k [Johnny]
- Prior from corridor width (2 m corridor, 0.7 m stride).
- Calibration walk: `k = path px ÷ steps` (400 px / 16 steps = 25 px/step).
- Landmark refine: `k_new = 0.7·k_old + 0.3·k_obs` (k_obs 23 → k_new 24.4).
- Display: steps = remaining px ÷ k; meters = steps × stride ("~40 steps, ~28 m"), always shown as an estimate.

### A5 · Confidence and recovery [Johnny + Jayden]
LOW when **any** check fails (starting values; tuned on pilot routes, then frozen; all live in `TrackingConfig`):

| Check | LOW when |
|---|---|
| Spread σ | > 3 m (about one door gap) |
| Group split p | heaviest group < 0.6 of weight |
| Heading fit | mean heading vs corridor > 30° over last 10 steps |
| Turn fit | a turn > 45° matches no junction branch in the cloud |

- **Scan prompt** only if LOW **and** ≥ 20 steps since the last prompt **and** a verified sign is within reach.
- **Accepting a scan:** normalize OCR (upper-case, strip spaces/punctuation, O→0, I/l→1, S→5) → exact match or unique 1-edit match → **gate**: accept only if particles holding ≥ 5% of weight lie within `G = max(3σ, 5 m)` graph distance of the matched door → reweight with a Gaussian around the door (σ ≈ 1.5 m), re-inject 30% around the door, resample → refine k, reroute if off route. Ambiguous match: ask "Are you at 3650?".
- **No sign nearby — fallback ladder** (stop at the first step that restores confidence): wider position bubble + looser wording → use the next turn or dead end → ask which corridor (top two groups) → tap to re-anchor → map-only mode. Steps 3–5 are logged as manual re-anchors.
- **Never snap or freeze silently.**

### Routing [Jayden + Limo]
A* over `lengthPx` with a straight-line heuristic (admissible because edge length ≥ chord). A destination label routes to the **nearest of all doors** with that label. Turn cues come from angle changes at junctions and at bends > 30°. A* with a zero heuristic is Dijkstra; tests compare the two.

### Guidance [Limo]
Map with position dot + route, turn arrow, "N steps" countdown, short voice prompts (TextToSpeech), distinct vibration patterns for turns and arrival. Visible LOW-confidence state, scan prompts only when allowed, manual corridor selection, manual re-anchor, map-only fallback.

### Threading and control flow
Per step: SensorAdapter → `MotionEvent` → `NavigationSession` → `Tracker.predict/weigh/(resample)` → `PoseEstimate` → `Confidence.evaluate` → `ConfidenceState` → `Router.update` (reroute) → `Guidance.render`. Sign scan: `Scanner.capture → OcrMatcher.match → Tracker.applySign → Calibration.updateK → Router.reroute`. Sensor and image work run **off the UI thread** and post events to `NavigationSession`, so graph and pose never change concurrently. Missing sensor, denied camera or failed scan → visible fallback.

## 8. Contribution rules (follow these exactly)

### Branches and commits
- Never push to `main`. Start each branch from a **fresh `main`**: `git switch main && git pull && git switch -c <type>/<short-name>`.
- **Don't stack branches** on unmerged branches; squash merges break them. One branch = one PR; later pushes update the same PR.
- Branch prefixes: `feat/`, `fix/`, `eval/`, `docs/`, `chore/`, `data/`.
- **Conventional Commits**, present tense, ≤ 72-char subject: `feat(tracking): resample particles with systematic resampling`, `fix(mapparse): close door gaps before flood-fill`, `test(routing): cover unreachable destination`, `data(ehall): …`.
- Commits are authored by the team member. **Don't add AI co-author trailers or "generated by" lines** to commits or PRs; the team declares AI assistance separately.

### Pull requests
- Keep PRs small (aim for under ~400 changed lines); split big features.
- Link the issue: `Closes #N` at the top of the description.
- Needs **1 approval** from someone else + a green `build` (and `tools`) check; **Code Owners** of the touched paths are requested automatically. Resolve all conversations. **Squash merge**, then delete the branch.
- Changes to `core/model/`, `gradle/` or `.github/` need **all three** members to review.
- Fill in **every** section of the template below; don't leave the HTML comment or empty headings.

#### PR description template (filled-in shape)
```markdown
Closes #<issue>

## What

<One or two sentences on what this PR adds or changes. Then bullets for the main pieces.>

## Why

<Which gate/issue this moves forward and why it is needed now.>

## Results            <!-- include whenever there is a measurable outcome -->

| Metric | Value |
|---|---|
| … | … |

## How it was tested

- [x] Unit tests added or updated (`./gradlew test` / `python -m unittest discover tools`): <what they cover>
- [ ] Ran on a phone (model: ___ ) / replayed trace: ___   <!-- or "(n/a, reason)" -->
- [ ] Screenshots or a short clip if UI changed

## Checklist

- [ ] Touches `core/model`? Both other teammates are tagged
- [ ] New thresholds went into `TrackingConfig`, not hard-coded
- [ ] No large binaries outside `data/fixtures/` (LFS)

## Next             <!-- optional: follow-ups this PR leaves open -->
```

### Code rules
- Kotlin, Android Studio style, ktlint clean. Keep algorithm code **free of Android imports** where possible (`core/model`, `routing`, particle-filter maths) so it unit-tests on a laptop.
- **No magic numbers in tracking:** every threshold goes in `TrackingConfig`. Map-parsing parameters live in a `Params` data class (Python) or its Kotlin equivalent.
- Sensor and image work never on the UI thread. `NavigationSession` is the only writer of position and route.
- Every new behavior gets a unit test with synthetic input (see `TestGraphs`, `tools/test_*.py`). Use the real fixtures for evaluation, not as the only test.
- Don't edit or regenerate hand-labeled fixtures (`*-corners.json`, traced `*.graph.json`) unless the PR is about fixing them. They are ground truth.
- Report metrics honestly: say when a number was tuned on the same data it is reported on, and keep held-out results separate.
- Record any interface, dependency or algorithm decision as `docs/decisions/NNNN-title.md` (context, decision, consequences).

### Never commit
`local.properties`, keystores, API keys, `.venv/`, `data/raw/`, build output, large videos, `*.json.bak`.

## 9. Evaluation (what we will measure)

| Condition | Tracking |
|---|---|
| A · Raw PDR | Steps + heading integrated in 2-D, no graph |
| B · Graph PDR | A + particle tracker on the graph |
| C · Periodic scans | B + scan prompts at a fixed interval |
| D · Confidence scans | B + scan prompts only when confidence is LOW |

`EvalCondition` selects A–D **at runtime** (never a separate branch). Protocol: same route, phone, carry, calibration and scan procedure across A–D; tape-measured checkpoints as ground truth; tune C's interval and D's thresholds on **pilot** routes, evaluate on **separate** routes. User study: wall map alone vs MapLens, counterbalanced, consent collected, no face images stored.

| Area | Metrics |
|---|---|
| Map parsing | Node/edge precision/recall vs hand-verified graph (tolerance recorded); room-label exact-match accuracy; manual edits needed |
| Localization | Checkpoint error (m): distribution, route length, distance since last anchor |
| Scan burden | Scans prompted and performed, rejected observations, manual re-anchors |
| Navigation | Correct-door arrival, wrong turns, time to destination, self-rated ease and confidence |

Report error against scan count as a trade-off; within-route differences and uncertainty, no significance claims. The app must **log** checkpoint estimates, confidence events, scans, corrections and manual re-anchors.

## 10. Test plan by area (Appendix A of the report)

| Area | Must cover |
|---|---|
| A.1 Map parsing | Perspective correction from several angles; glare/blur warning; room-label OCR; 0/O, 1/l ambiguity not silently accepted; corridor extraction; door gaps and overlaid text don't split corridors; T, 4-way and dead-end junctions; room attachment; invalid graph can't be approved; manual correction saves |
| A.2 Routing | Single path; shorter of two; A* = Dijkstra cost; disconnected destination → "no route"; start = destination → arrival; re-anchor reroutes; invalid references rejected; persistence across restart |
| A.3 Steps/heading | ~20/50/100 counted steps at slow/normal/fast (error = \|detected − actual\| / actual); standing still, rotating, stop-start → no false steps; 45°/90°/180° turns; flat, tilted, near-vertical grip |
| A.4 Particle filter | Particles always on valid edges; straight corridor; wall constraint; dead end; clear turn; ambiguous junction keeps both; wrong group fades; resampling keeps strong particles; display uses strongest group; long unanchored walk logged |
| A.5 Scale | k computed correctly; repeatability; different walkers; wrong initial k converges at landmarks; distance display vs tape; building-scale cross-check (never used for navigation) |
| A.6 Confidence | Each of the 4 checks fires when forced; straight walking doesn't keep triggering LOW; cooldown: two LOW events < 20 steps apart → one prompt; no sign nearby → fallback ladder |
| A.7 Sign reset | Correct nearby sign accepted; correct but distant rejected by the gate; wrong OCR → no change; ambiguous → ask; blurred → ask again, keep estimate; user rejects → keep pose; accepted → spread drops, reroute. **Never a silent position change** |
| A.8 Guidance UI | Left/right/straight at maneuvers; arrival; distinct vibrations; updates after reroute; LOW state visible; scan prompts only when allowed; manual corridor select + re-anchor; map-only fallback |
| A.9 Interfaces | FloorGraph references valid; Route edges connected and end at destination; MotionEvent timestamps monotonic; PoseEstimate on a valid edge/offset; ConfidenceState records failed check; SignObservation label exists. State transitions incl. poor image → Retake, invalid graph stays in Verify, LOW → Recover → fix → Navigate, failed scan keeps estimate, missing sensor → visible fallback |
| A.10 Offline | Airplane mode: restart, load floor, pick destination, track, scan, guide; graph, calibration and labels survive restart |
| A.11 End-to-end | Normal route; parser correction; drift recovery; correct plaque; wrong plaque; ambiguous junction; manual recovery; offline route. Rerun at every weekly integration meeting |

**Readiness for A–D (G4):** verified graph for the floor; valid routes; step/heading works in the fixed carry mode; tracker completes full routes; signs accepted/rejected correctly; prompts + cooldown as specified; one complete route in airplane mode; logging in place.

## 11. Issues

Issues are created with owner, area label, milestone and a **"Done when"** checklist; a PR closes its issue with `Closes #N`. Use the **Task** issue template for new work (area, Gantt task, done-when).

### G2 · Baseline navigates (Nov 1): current focus
| # | Issue | Owner | Critical for G2? |
|---|---|---|---|
| #8 | Router: A* to the nearest door for a room label | Limo + Jayden | Yes (`AStarRouter` exists; add label → nearest door) |
| #9 | Load a FloorGraph fixture in the app and wire NavigationSession | Limo | Yes |
| #10 | Guidance UI v1: arrow, map with route, steps to next turn (works on a fake pose stream) | Limo | Yes |
| #11 | Sensor-trace recorder (debug screen → CSV) | Johnny | Yes (first; everything in tracking replays its traces) |
| #12 | Steps, heading and calibration k | Johnny | Yes |
| #13 | Particle tracker v1 on the corridor graph | Johnny | Yes |
| #14 | Room-number OCR → door labels on the extracted graph | Jayden | No; can slip to G3 (baseline can use the hand-traced graph) |
| #15 | "You Are Here" star detection → START node | Jayden | No; can slip to G3 |
| #16 | "Check the map" graph editor screen | Jayden + Limo | No; can slip to G3 |

Later milestones hold: Kotlin port of rectify + corridors with CameraX, confidence monitor, sign scanner + re-anchor, voice/vibration + offline, recording EH3 evaluation walks, Phase II demo prep (G3); threshold pilot + QA, Writing Center draft (G4); A–D trials + user study, final report + demo, optional ARCore (G5); backlog: corridors without route lines (wall-gap analysis). G0 and G1 issues record the finished work.

## 12. How an AI assistant should work in this repo

1. **Read first:** the issue you are working on, this file, `CONTRIBUTING.md`, `docs/architecture.md`, and the module's existing code and tests. Match existing style and naming.
2. **Stay in your lane:** change only the module(s) the issue covers. If you need a change in `core/model`, stop and propose it (it needs all three owners); don't silently add fields.
3. **Follow the specs in section 7** (thresholds, formulas, fallback order). If the spec is unclear or seems wrong, say so in the PR rather than inventing new behavior.
4. **Tests with every change**, synthetic first; run `./gradlew ktlintCheck test` and `python -m unittest discover tools` before saying it works. State exactly what was and wasn't run (e.g. "not run on a phone").
5. **Output a filled PR description** using the template in section 8, with `Closes #N`, real numbers, and honest caveats.
6. **Don't** touch fixtures, CI, build files or other people's modules unless the issue says so; don't add dependencies without putting them in `gradle/libs.versions.toml` (or `tools/requirements.txt`) and explaining why.
7. Prefer small, reviewable steps over one large change.
