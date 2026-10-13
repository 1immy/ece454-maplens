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

## Coming next

- Port `rectify.py` to Kotlin in `mapparse` (OpenCV Android)
- Corridor mask → skeleton → graph, scored by graph F1 against the traced fixtures
- Trace replay and drift plots for conditions A–D
