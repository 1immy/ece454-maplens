# Tools

Python scripts that don't ship in the app: labeling, parser prototyping, evaluation.

## Setup (once)

```bash
cd ~/ece454
python3 -m venv .venv            # .venv/ is gitignored
source .venv/bin/activate
pip install -r tools/requirements.txt
python -m unittest discover tools   # headless
```

## `maplabel.py`: ground-truth labeling

Three steps, each with a small GUI (matplotlib window). Press **h** inside a window for its keys.

```bash
# 1. Poster corners for every photo (rectification answer key, committed)
python tools/maplabel.py corners data/fixtures/photos/ehall/*.jpg \
    -o data/fixtures/rectify/ehall-corners.json

# 2. Straighten the front shot you will trace
python tools/maplabel.py warp data/fixtures/photos/ehall/eh-3-elevators-evac-03-front.jpg \
    --corners data/fixtures/rectify/ehall-corners.json \
    -o data/fixtures/maps/eh-3-elevators-evac.jpg

# 3. Trace the corridor graph on the straightened image
python tools/maplabel.py trace data/fixtures/maps/eh-3-elevators-evac.jpg \
    -o data/fixtures/maps/eh-3-elevators-evac.graph.json
```

**Corners:** click the four **outer corners of the blue map frame** in any order: the street-name border on evacuation posters, the "3rd Floor" panel (not its black shadow) on directory maps. Where a screw or glare hides a corner, click where the two edges would meet. **Enter**/**s** saves and moves on, **k** skips, **q** saves and quits. Every save merges into the file on disk, so a stale window can't wipe other labels. Re-running skips images that are already labeled (`--redo` to relabel).

**Trace:**

| Input | Action |
|---|---|
| Left click empty space | New node of the current type, joined to the selected node (snaps straight if nearly aligned) |
| Left click a node | Select it; if another node was selected, join them |
| Right click a node | Delete it |
| `1`–`8` | Type for the next node: junction, door, START, dead end, stairs, elevator, exit, AED |
| `t` | Give the selected node the current type |
| `l` | Type a room label for the selected node, Enter to save |
| `x`, then click a node | Remove the edge between the selected node and that node |
| `Esc` | Deselect (start a new chain) |
| `u` / `w` / `q` | Undo / save / save and quit |

**Tidy:** after placing doors, close the trace window and run
`python tools/maplabel.py tidy data/fixtures/maps/eh-3-elevators-evac.graph.json`.
It routes every corridor edge through any node sitting on it (doors placed on a corridor, floating door chains, unconnected nodes) and drops duplicate edges. The previous file is kept as `*.json.bak`.

A door asks for its room number as soon as you place it. Re-running `trace` on an existing `.graph.json` resumes it. Each save prints anything `FloorGraph.validate()` would reject, and `./gradlew :core:model:test` checks every fixture again.

Tip: zoom with the toolbar magnifier, then click the magnifier again to turn it off before placing nodes.

## `rectify.py`: automatic map rectification

Finds the blue map frame, fits its corners and straightens the photo. No clicks.

```bash
# One photo
python tools/rectify.py detect data/fixtures/photos/ehall/eh-3-elevators-evac-04-down.jpg -o flat.jpg

# Score against the hand labels (green = label, magenta = detected in the overlays)
python tools/rectify.py evaluate data/fixtures/photos/ehall/*.jpg \
    --corners data/fixtures/rectify/ehall-corners.json --debug-dir /tmp/rectify-debug
```

How it works: Lab **b\*** channel below the wall's own b\* − 7 → blue mask → fill holes → largest convex blob → quad from the hull, each side refined by a line fit → homography. Photos where the map is cut off or not cleanly visible come back with a **retake** reason instead of a bad warp.

**Engineering Hall 3rd floor (16 photos):** 15 accepted, median corner error **0.31 %** of the diagonal (~8 px at 3024×4032), worst 0.41 %. The cropped `stair-3542-dir` shot is correctly flagged for retake.

Known limit: under strong perspective the output aspect ratio is estimated from the photo, so it can be a few percent off (fine for tracing; the graph step will register against the poster's known shape).

## `corridors.py`: corridor extraction and graph scoring

Straightened evacuation poster → corridor graph, plus a scorer that compares any graph with a hand trace.

```bash
# Extract and score against the trace (green = traced, magenta = extracted)
python tools/corridors.py evaluate data/fixtures/maps/eh-3-elevators-evac.jpg \
    data/fixtures/maps/eh-3-elevators-evac.graph.json --debug /tmp/corridors.jpg

# Just extract
python tools/corridors.py extract data/fixtures/maps/eh-3-elevators-evac.jpg -o /tmp/auto.graph.json
```

How it works: orange evacuation-route lines (HSV) → drop filled symbols (AED heart, extinguishers, arrowheads) with a morphological opening → widen, close gaps, keep the main network → skeletonize → junction/end nodes and polyline edges → prune spurs < 90 px, merge junctions < 60 px apart → move each junction to the intersection of straight-line fits to its corridors (route lines bend around symbols near corners). `--with-hatch` also uses the dotted corridor hatching; it is off by default because it fills shelter rooms and stairwells.

**Metrics** (`score`): corridor-length precision/recall/F1 (share of each graph's length within 15 px of the other) and junction F1 (degree ≥ 3 after removing stubs < 45 px, Hungarian matching within 40 px).

| Poster | Corridor length F1 | Junction F1 |
|---|---|---|
| Elevator evac (tuned on) | 0.91 (P 0.95, R 0.87) | 0.80 (6/6 found real, 9 traced) |
| Stair-3424 evac (held out) | 0.93 (P 0.96, R 0.90) | 0.86 (6/6 found real, 8 traced) |

Misses are corridors with **no route line** (3609 → 3605, "To EXIT", short stubs). Restricting the hatching to narrow bands next to the routes was tested and did not help (it recovers stubs but adds as many false junctions).

## Coming next

- Port `rectify.py` to Kotlin in `mapparse` (OpenCV Android)
- Corridors without route lines: wall-gap analysis (hatching alone is not enough)
- Port room-label OCR and START detection to Android
- Trace replay and drift plots for conditions A–D

## `start_marker.py`: gold star → START (#15)

Offline Python prototype for straightened evacuation posters. Uses existing
OpenCV/NumPy dependencies: gold HSV segmentation, a small gap close, star
concavity/solidity checks, and a dark blue disk context check. Exactly one
candidate is required. No OCR, model download or reference trace is used in
prediction. Parameters live in `start_marker.Params` and target 2000 px maps.

From the root, activate the Python environment described above (or source the
local `start-maplens.sh` when present), then run:

```bash
mkdir -p work/issue-15
for poster in eh-3-elevators-evac eh-3-stair3424-evac; do
    python tools/corridors.py extract "data/fixtures/maps/$poster.jpg" \
        -o "work/issue-15/$poster.corridors.graph.json"
    python tools/start_marker.py evaluate "data/fixtures/maps/$poster.jpg" \
        --graph "work/issue-15/$poster.corridors.graph.json" \
        --truth "data/fixtures/maps/$poster.graph.json" \
        -o "work/issue-15/$poster.start.graph.json" \
        --report "work/issue-15/$poster.report.json" \
        --debug "work/issue-15/$poster.overlay.png"
done
```

For prediction alone use `extract` and omit `--truth`. `evaluate` creates the
prediction before reading the trace. Independently rescore saved output with:

```bash
python tools/start_marker.py score work/issue-15/eh-3-elevators-evac.start.graph.json \
    data/fixtures/maps/eh-3-elevators-evac.graph.json
MPLBACKEND=Agg python -m unittest discover tools
./gradlew ktlintCheck test
```

Use the exact same straightened image/pixel frame as the input graph. Projection
searches every segment of every edge polyline, including interiors and bends.
The default snap limit is 100 px (`--max-distance`). Safe endpoints within 2 px
are reused; DOOR/EXIT/STAIRS/ELEVATOR/AED or landmark endpoint conflicts require
manual review. Otherwise insert START and split its edge, preserving bends and
recomputing geometric lengths. Graph/node/edge metadata and landmarks survive;
implicit edge IDs are made explicit before insertion changes their array index.
Previous START nodes become DEAD_END (degree ≤1) or JUNCTION. Outputs are always
`verified: false`. Existing extractor geometry is preserved, including any
polyline endpoint discrepancy with refined junction nodes.

Missing/multiple candidates, excessive distance and semantic endpoint conflicts
exit with code 2, saving an audit/review reason and optional overlay, without
writing a prediction graph. A rejected detection/attachment also removes any
earlier prediction at the requested output path, so it cannot be mistaken for
the new result. Check exit status and audit before using outputs. Invalid
input graphs fail explicitly. A placeholder `startNodeId` cannot be scored as a
successful START. Overlays show **gold** marker/box, **green** projected START,
**cyan** traced START (evaluation only), **magenta** existing corridors.

Measured on independently extracted corridor inputs from `main` at `81d0f4c`:

| Poster | Detected marker (px) | Projected START (px) | Traced START (px) | Snap (px) | START error (px) |
|---|---|---|---|---|---|
| elevators evac | (1541.0, 836.0) | (1541.48, 840.75) | (1540.4, 856.7) | 4.78 | **15.98** |
| stair3424 evac | (1084.0, 1256.5) | (1084.25, 1261.44) | (1084.3, 1268.9) | 4.94 | **7.46** |

Both posters informed threshold selection: these are **tuning results**, with
one detected and attached marker per poster, not held-out accuracy. Each output
has 11 nodes and 12 edges and passes existing Kotlin `FloorGraphJson.decode()`
and `FloorGraph.validate()`. Reference traces were read only for scoring, and
hand fixtures were unchanged. The route-line extractor bends around the star;
its closest polyline point differs from the traced corridor center. No corridor
correction or extension was applied. Missing corridor recovery stays under #28.
Directory pink dots, severe glare/occlusion, other resolutions/poster styles,
Android integration and phone tests remain outside this prototype.

## `room_labels.py`: offline room OCR and corridor attachment (#14)

Install `pip install -r tools/requirements.txt`. Default OCR is **RapidOCR 3.10.0**
with its bundled PP-OCRv6 small detection/recognition models and ONNX Runtime CPU.
The adapter selects those local model files explicitly and fails clearly if they
are missing; extraction never downloads models or sends images to a service.
Installation needs network access; inference runs offline. ML Kit on Android
remains a later port. `--ocr tesseract` retains the older recognizer for comparison
and requires Tesseract 5 plus English data (`brew install tesseract` on macOS).

Run from the project root with the **same straightened image** for both stages.
Coordinates from a differently warped camera photo are not interchangeable.

```bash
mkdir -p work/room-label-ocr
python tools/corridors.py extract data/fixtures/maps/eh-3-elevators-evac.jpg \
    -o work/room-label-ocr/corridors.graph.json
python tools/room_labels.py evaluate data/fixtures/maps/eh-3-elevators-evac.jpg \
    --graph work/room-label-ocr/corridors.graph.json \
    --truth data/fixtures/maps/eh-3-elevators-evac.graph.json \
    -o work/room-label-ocr/labeled.graph.json \
    --readings work/room-label-ocr/readings.json --debug work/room-label-ocr/overlay.png
```

Without a reference trace, use `extract` instead of `evaluate` and omit `--truth`.
`extract` never reads ground truth. To rescore saved output without rerunning OCR:

```bash
python tools/room_labels.py score work/room-label-ocr/readings.json \
    work/room-label-ocr/labeled.graph.json data/fixtures/maps/eh-3-elevators-evac.graph.json
```

Only entire four-digit room labels with an optional letter suffix are accepted.
Pure rows such as `3442 3440 3438` (or concatenated digits) split using actual OCR
character boxes. Sentences such as `Outside Room 1224, 1412, 2239` stay descriptive
text, so their numbers do not become destinations. Suffixes such as 3540B and
3556A survive. O/0 and I/1 substitutions are not silently repaired. The audit JSON
retains the original line and derived word boxes, confidence, connector paths and
reasons for rejection/review. `--ocr tesseract` is the legacy word-based recognizer;
it does not provide the same description/row grouping as the default model.

Attachment uses visible walls after removing text and colored evacuation routes.
It keeps long wall strokes and restores original short/curved ink within 8 px of
those strokes. The enlarged support region only selects original dark pixels: it
does not thicken walls or close white openings. This prevents erased circular
wall details from becoming false doorways.
A local image path searches for a nearby point on the **existing** corridor graph,
penalizing wall pixels and rejecting paths crossing more than 6 wall pixels or
longer than 350 px (`--max-distance`). This allows bending toward an opening
rather than projecting the text center through a wall. It remains an image
heuristic, not a physical-door detector. Corridor edges are split with valid
references and lengths; existing landmarks/multiple entrances are preserved.
No new corridor geometry is invented. All pixel settings live in `Params` and
target 2000 px posters. Output remains `verified: false`.

Overlay: **magenta** corridors, **green** proposed attachments, **orange** shared
candidate nodes requiring review, **red** rejected readings. Green means attached,
not verified correct. Connector polylines show the image path used for attachment.
Shared candidates retain every label; they are not silently discarded or approved.

Measured on the matching 2000 × 1467 elevator image/trace pair (tuning data):

| Metric | Original Tesseract + projection | Refined RapidOCR + wall-aware path |
|---|---|---|
| Exact distinct room-number recall | 31/51 = 60.8% | 49/51 = 96.1% |
| Exact distinct-number precision against trace | 31/45 = 68.9% | 49/52 = 94.2% |
| Correct entrance recall (exact label, ≤40 px, one-to-one) | 11/52 = 21.2% | 38/52 = 73.1% |
| Correct entrance precision against trace | 11/33 = 33.3% | 38/52 = 73.1% |
| Median error of matched entrances | 16.8 px | 8.7 px |

The trace's 55 landmarks are **52 room entrances / 51 distinct room numbers**,
plus two OFFMAP and one TO-EXIT. Suffixes count separately; both 3546 entrances
count in attachment recall. The refined run has 52 accepted occurrences and 52
proposed attachments (56 nodes, 57 edges, 52 landmarks). Three readings absent
from the trace—3416, 3556A and 3546A—are visibly printed but lack reference door
annotations. Confirm the small 3546A/3545A text manually; it was not relabeled to
match an expectation. Two reference strings remain unread: 3560 and 3622 are
partially cropped/obscured in this image; no digits are invented.

3612 and 3614 now attach to their existing left/right side openings (9.8/9.3 px
reference error), rather than sharing the middle point. Preserving short wall
details raises correct entrance matches from 35/52 to 38/52 without losing a
previously correct label placement on this tuned poster.
3605 attaches to its existing left corridor (~8.8 px reference error). 3540,
3540B, 3556 and one 3546 entrance also match within 40 px. 3445 is recognized, but
3444/3446/3445 still share a distant proposed anchor near the incomplete lower-left
corridor: orange review markers, not successful entrance matches. Recovery of that
missing corridor remains **#28**, explicitly outside this refinement. A single
printed 3546 label still cannot identify both entrances automatically.
Against the trace, 14 predicted associations and 14 reference entrances remain
unmatched (replacement corrections overlap these counts). No manual corrections
or fixture edits were applied. No held-out room score is claimed: stair-3424 has
corridors but incomplete labels.

Tests use synthetic images/graphs and mock the OCR boundary, so the unit suite
requires no executable, model inference or network. Real fixture evaluation is
separate; the refined model was also exercised with Python network connections
blocked. Core-model JSON decoding/validation is checked separately before handoff.
