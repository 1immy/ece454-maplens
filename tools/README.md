# Tools

Python scripts that don't ship in the app: labeling, parser prototyping, evaluation.

## Setup (once)

```bash
cd ~/ece454
python3 -m venv .venv            # .venv/ is gitignored
source .venv/bin/activate
pip install -r tools/requirements.txt
python -m unittest discover tools   # 19 tests, headless
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
- Room-label OCR and "You Are Here" star detection → doors and START on the extracted graph
- Trace replay and drift plots for conditions A–D
