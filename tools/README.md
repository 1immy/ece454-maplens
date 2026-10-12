# Tools

Python scripts that don't ship in the app: labeling, parser prototyping, evaluation.

## Setup (once)

```bash
cd ~/ece454
python3 -m venv .venv            # .venv/ is gitignored
source .venv/bin/activate
pip install -r tools/requirements.txt
python -m unittest discover tools   # 14 tests, headless
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

**Corners:** click the four corners of the *printed sheet* (not the acrylic cover or the screws), in any order. Enter saves and moves on; `s` skips; `q` quits. Re-running skips images that are already labeled (`--redo` to relabel).

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

## Coming next

- Parser prototype (OpenCV): rectify → corridor mask → skeleton → graph
- Graph-extraction F1 and corner error against the labels above
- Trace replay and drift plots for conditions A–D
