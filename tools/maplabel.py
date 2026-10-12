#!/usr/bin/env python3
"""MapLens labeling tool: ground truth for map rectification and graph extraction.

Commands
  corners  Click the 4 corners of the poster in each photo. Saves a corners JSON,
           the answer key for automatic rectification.
  warp     Straighten one photo using its labeled corners. Saves a flat image.
  trace    Click-to-trace a FloorGraph on a (straightened) image. Saves *.graph.json
           in the format read by FloorGraphJson (core/model).

Examples
  python tools/maplabel.py corners data/fixtures/photos/ehall/*.jpg -o data/fixtures/rectify/ehall-corners.json
  python tools/maplabel.py warp data/fixtures/photos/ehall/eh-3-elevators-evac-03-front.jpg \\
      --corners data/fixtures/rectify/ehall-corners.json -o data/fixtures/maps/eh-3-elevators-evac.jpg
  python tools/maplabel.py trace data/fixtures/maps/eh-3-elevators-evac.jpg \\
      -o data/fixtures/maps/eh-3-elevators-evac.graph.json

Run any command with -h for details. In the GUI, press h for the key list.
"""
from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps

SCHEMA_VERSION = 1

# Key -> node type. Prefix is used for readable node ids (J1, D2, ...).
NODE_TYPES = {
    "1": "JUNCTION",
    "2": "DOOR",
    "3": "START",
    "4": "DEAD_END",
    "5": "STAIRS",
    "6": "ELEVATOR",
    "7": "EXIT",
    "8": "AED",
}
PREFIX = {
    "JUNCTION": "J", "DOOR": "D", "START": "S", "DEAD_END": "X",
    "STAIRS": "T", "ELEVATOR": "L", "EXIT": "E", "AED": "A",
}
COLOR = {
    "JUNCTION": "#d62728", "DOOR": "#1f77b4", "START": "#000000", "DEAD_END": "#7f7f7f",
    "STAIRS": "#9467bd", "ELEVATOR": "#17becf", "EXIT": "#ff7f0e", "AED": "#e377c2",
}


def load_image(path: str | Path) -> Image.Image:
    """Open an image upright (applies EXIF rotation) as RGB."""
    return ImageOps.exif_transpose(Image.open(path)).convert("RGB")


# --------------------------------------------------------------------------- geometry

def order_corners(points):
    """Return 4 points as TL, TR, BR, BL regardless of click order."""
    pts = np.asarray(points, dtype=float)
    s = pts.sum(axis=1)
    d = pts[:, 1] - pts[:, 0]
    ordered = [pts[np.argmin(s)], pts[np.argmin(d)], pts[np.argmax(s)], pts[np.argmax(d)]]
    if len({tuple(p) for p in ordered}) != 4:
        raise ValueError("corners are degenerate; click four distinct corners")
    return [tuple(map(float, p)) for p in ordered]


def homography(src, dst) -> np.ndarray:
    """3x3 H such that dst ~ H @ src, from exactly 4 point pairs."""
    a, b = [], []
    for (x, y), (u, v) in zip(src, dst):
        a.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        a.append([0, 0, 0, x, y, 1, -v * x, -v * y])
        b += [u, v]
    h = np.linalg.solve(np.asarray(a, float), np.asarray(b, float))
    return np.append(h, 1.0).reshape(3, 3)


def rectified_size(quad, width: int) -> tuple[int, int]:
    """Output size keeping the aspect ratio estimated from the quad's edge lengths."""
    tl, tr, br, bl = (np.asarray(p) for p in quad)
    horizontal = (np.linalg.norm(tr - tl) + np.linalg.norm(br - bl)) / 2
    vertical = (np.linalg.norm(bl - tl) + np.linalg.norm(br - tr)) / 2
    return width, max(1, round(width * vertical / horizontal))


def warp(image: Image.Image, quad, width: int) -> Image.Image:
    """Map the TL, TR, BR, BL quad in `image` onto an upright rectangle."""
    w, h = rectified_size(quad, width)
    out_corners = [(0, 0), (w - 1, 0), (w - 1, h - 1), (0, h - 1)]
    out_to_in = homography(out_corners, quad)  # PIL wants output -> input
    coeffs = (out_to_in / out_to_in[2, 2]).flatten()[:8]
    return image.transform((w, h), Image.PERSPECTIVE, tuple(coeffs), Image.BICUBIC)


# --------------------------------------------------------------------------- graph model

class Graph:
    """Editable graph, independent of the GUI so it can be tested headless."""

    def __init__(self, building_id="engineering-hall", floor="3", source=None):
        self.building_id = building_id
        self.floor = floor
        self.source = source or {}
        self.nodes: dict[str, dict] = {}  # id -> {id, type, x, y}
        self.edges: list[tuple[str, str]] = []
        self.labels: dict[str, str] = {}  # node id -> room label
        self.counter = 1
        self._history: list[tuple] = []

    # undo ------------------------------------------------------------------
    def checkpoint(self):
        self._history.append(copy.deepcopy((self.nodes, self.edges, self.labels, self.counter)))
        del self._history[:-200]

    def undo(self) -> bool:
        if not self._history:
            return False
        self.nodes, self.edges, self.labels, self.counter = self._history.pop()
        return True

    # editing ---------------------------------------------------------------
    def add_node(self, x: float, y: float, node_type: str) -> str:
        node_id = f"{PREFIX[node_type]}{self.counter}"
        self.counter += 1
        self.nodes[node_id] = {"id": node_id, "type": node_type, "x": round(x, 1), "y": round(y, 1)}
        if node_type == "START":
            self._demote_other_starts(node_id)
        return node_id

    def set_type(self, node_id: str, node_type: str):
        self.nodes[node_id]["type"] = node_type
        if node_type == "START":
            self._demote_other_starts(node_id)

    def _demote_other_starts(self, keep: str):
        for node in self.nodes.values():
            if node["type"] == "START" and node["id"] != keep:
                node["type"] = "JUNCTION"

    def connect(self, a: str, b: str) -> bool:
        if a == b or self.has_edge(a, b):
            return False
        self.edges.append((a, b))
        return True

    def disconnect(self, a: str, b: str) -> bool:
        before = len(self.edges)
        self.edges = [e for e in self.edges if {a, b} != set(e)]
        return len(self.edges) != before

    def has_edge(self, a: str, b: str) -> bool:
        return any({a, b} == set(e) for e in self.edges)

    def delete_node(self, node_id: str):
        self.nodes.pop(node_id, None)
        self.labels.pop(node_id, None)
        self.edges = [e for e in self.edges if node_id not in e]

    def nearest(self, x: float, y: float, radius: float) -> str | None:
        best, best_d = None, radius
        for node in self.nodes.values():
            d = ((node["x"] - x) ** 2 + (node["y"] - y) ** 2) ** 0.5
            if d <= best_d:
                best, best_d = node["id"], d
        return best

    # checks ----------------------------------------------------------------
    def problems(self) -> list[str]:
        """Same structural rules as FloorGraph.validate(), plus tracing hints."""
        out = []
        starts = [n for n in self.nodes.values() if n["type"] == "START"]
        if len(starts) != 1:
            out.append(f"need exactly one START (You Are Here), found {len(starts)}")
        elif self.nodes:
            seen, stack = {starts[0]["id"]}, [starts[0]["id"]]
            adjacency: dict[str, list[str]] = {}
            for a, b in self.edges:
                adjacency.setdefault(a, []).append(b)
                adjacency.setdefault(b, []).append(a)
            while stack:
                for nxt in adjacency.get(stack.pop(), []):
                    if nxt not in seen:
                        seen.add(nxt)
                        stack.append(nxt)
            unreachable = sorted(set(self.nodes) - seen)
            if unreachable:
                out.append(f"not connected to START: {', '.join(unreachable)}")
        unlabeled = sorted(n["id"] for n in self.nodes.values()
                           if n["type"] == "DOOR" and n["id"] not in self.labels)
        if unlabeled:
            out.append(f"doors without a room label (hint): {', '.join(unlabeled)}")
        return out

    # json ------------------------------------------------------------------
    def to_json(self) -> dict:
        start = next((n["id"] for n in self.nodes.values() if n["type"] == "START"), "")
        return {
            "schemaVersion": SCHEMA_VERSION,
            "buildingId": self.building_id,
            "floor": self.floor,
            "startNodeId": start,
            "verified": True,
            "source": self.source,
            "nodes": [dict(n) for n in self.nodes.values()],
            "edges": [{"id": f"e{i + 1}", "from": a, "to": b} for i, (a, b) in enumerate(self.edges)],
            "landmarks": [{"label": label, "nodeId": nid} for nid, label in self.labels.items()],
        }

    @classmethod
    def from_json(cls, data: dict) -> "Graph":
        if data.get("schemaVersion") != SCHEMA_VERSION:
            raise ValueError(f"unsupported schemaVersion {data.get('schemaVersion')}")
        g = cls(data.get("buildingId", "engineering-hall"), data.get("floor", "3"), data.get("source"))
        for n in data["nodes"]:
            g.nodes[n["id"]] = {"id": n["id"], "type": n["type"], "x": n["x"], "y": n["y"]}
        g.edges = [(e["from"], e["to"]) for e in data["edges"]]
        g.labels = {l["nodeId"]: l["label"] for l in data.get("landmarks", [])}
        numbers = [int("".join(c for c in nid if c.isdigit()) or 0) for nid in g.nodes]
        g.counter = max(numbers, default=0) + 1
        return g


# --------------------------------------------------------------------------- GUI helpers

def _setup_matplotlib():
    import matplotlib.pyplot as plt
    for key in list(plt.rcParams):
        if key.startswith("keymap."):
            plt.rcParams[key] = []  # free every key for our shortcuts
    return plt


def _toolbar_busy(fig) -> bool:
    toolbar = getattr(fig.canvas, "toolbar", None)
    return bool(toolbar is not None and getattr(toolbar, "mode", ""))


def _view_span(ax) -> float:
    x0, x1 = ax.get_xlim()
    return abs(x1 - x0)


# --------------------------------------------------------------------------- corners

CORNERS_HELP = """corners: click the 4 corners of the printed poster (any order).
  u        undo last point
  enter/n  save this image and go to the next
  s        skip this image
  q        save and quit
Use the toolbar magnifier to zoom; click it again to turn it off before clicking corners."""


def cmd_corners(args):
    out = Path(args.output)
    data = json.loads(out.read_text()) if out.exists() else {
        "schemaVersion": SCHEMA_VERSION,
        "note": "Poster corners in upright (EXIF-rotated) pixel coords, order TL, TR, BR, BL.",
        "images": {},
    }
    todo = [p for p in args.images if args.redo or Path(p).name not in data["images"]]
    if not todo:
        print("All images already labeled (use --redo to relabel).")
        return
    plt = _setup_matplotlib()
    print(CORNERS_HELP)

    def save():
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(data, indent=2) + "\n")

    for index, path in enumerate(todo, 1):
        image = load_image(path)
        fig, ax = plt.subplots(figsize=(9, 11))
        ax.imshow(image)
        ax.set_axis_off()
        points: list[tuple[float, float]] = []
        state = {"action": None}
        markers = []

        def redraw():
            for m in markers:
                m.remove()
            markers.clear()
            if points:
                xs, ys = zip(*points)
                markers.extend(ax.plot(xs, ys, "o", ms=9, mfc="none", mec="#ff00ff", mew=2))
                if len(points) == 4:
                    quad = order_corners(points)
                    qx, qy = zip(*(quad + [quad[0]]))
                    markers.extend(ax.plot(qx, qy, "-", color="#ff00ff", lw=1.5))
            ax.set_title(f"[{index}/{len(todo)}] {Path(path).name}: {len(points)}/4 corners")
            fig.canvas.draw_idle()

        def on_click(event):
            if event.inaxes is not ax or _toolbar_busy(fig) or event.button != 1:
                return
            if len(points) < 4:
                points.append((event.xdata, event.ydata))
                redraw()

        def on_key(event):
            if event.key == "u" and points:
                points.pop()
                redraw()
            elif event.key in ("enter", "n"):
                if len(points) == 4:
                    state["action"] = "next"
                    plt.close(fig)
                else:
                    print("  need 4 corners first")
            elif event.key in ("s", "q"):
                state["action"] = "skip" if event.key == "s" else "quit"
                plt.close(fig)

        fig.canvas.mpl_connect("button_press_event", on_click)
        fig.canvas.mpl_connect("key_press_event", on_key)
        redraw()
        plt.show()

        if state["action"] in ("next", None) and len(points) == 4:
            data["images"][Path(path).name] = {
                "corners": [[round(x, 1), round(y, 1)] for x, y in order_corners(points)],
                "size": list(image.size),
            }
            save()
            print(f"  saved {Path(path).name}")
        if state["action"] == "quit":
            break
    save()
    print(f"{len(data['images'])} images labeled -> {out}")


def cmd_warp(args):
    data = json.loads(Path(args.corners).read_text())
    name = Path(args.image).name
    if name not in data["images"]:
        sys.exit(f"{name} has no corners in {args.corners}; run the corners command first")
    flat = warp(load_image(args.image), data["images"][name]["corners"], args.width)
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    flat.save(out, quality=95)
    print(f"{name} -> {out} ({flat.size[0]}x{flat.size[1]})")


# --------------------------------------------------------------------------- trace

TRACE_HELP = """trace: click to place nodes; consecutive clicks chain into corridor edges.
  left click empty   new node of the current type, connected to the selected node
                     (snaps straight to it if nearly aligned)
  left click node    select it; if another node was selected, connect the two
  right click node   delete it
  esc                deselect (start a new chain) / cancel label typing
  1-8                type for the next node you place:
                     1 junction  2 door  3 START (You Are Here)  4 dead end
                     5 stairs    6 elevator  7 exit  8 AED
  t                  give the selected node the current type
  l                  type a room label for the selected node, then enter
  x then click node  cut the edge between the selected node and that node
  backspace          delete the selected node
  u                  undo
  w                  save          q  save and quit          h  this help
Doors: put the node on the corridor centerline opposite the door. Typing 2 asks for
the room number straight away. Use the toolbar magnifier to zoom; turn it off to click."""


class Tracer:
    def __init__(self, image_path: str, output: str, graph: Graph, plt):
        self.plt = plt
        self.output = Path(output)
        self.graph = graph
        self.image = load_image(image_path)
        self.selected: str | None = None
        self.current_type = "JUNCTION"
        self.label_target: str | None = None
        self.label_buffer = ""
        self.cut_pending = False
        self.artists: list = []
        self.fig, self.ax = plt.subplots(figsize=(12, 9))
        self.ax.imshow(self.image)
        self.ax.set_axis_off()
        self.fig.canvas.mpl_connect("button_press_event", self.on_click)
        self.fig.canvas.mpl_connect("key_press_event", self.on_key)
        self.fig.canvas.mpl_connect("close_event", lambda _e: self.save())
        self.redraw()

    # sizes scale with zoom so picking feels the same at any magnification
    def pick_radius(self) -> float:
        return 0.012 * _view_span(self.ax)

    def snap(self, x: float, y: float) -> tuple[float, float]:
        if self.selected is None:
            return x, y
        sel = self.graph.nodes[self.selected]
        tol = 0.01 * _view_span(self.ax)
        if abs(x - sel["x"]) < tol:
            x = sel["x"]
        if abs(y - sel["y"]) < tol:
            y = sel["y"]
        return x, y

    # events -----------------------------------------------------------------
    def on_click(self, event):
        if event.inaxes is not self.ax or _toolbar_busy(self.fig) or self.label_target:
            return
        hit = self.graph.nearest(event.xdata, event.ydata, self.pick_radius())
        if event.button == 3:
            if hit:
                self.graph.checkpoint()
                self.graph.delete_node(hit)
                if self.selected == hit:
                    self.selected = None
            self.redraw()
            return
        if event.button != 1:
            return
        if self.cut_pending:
            self.cut_pending = False
            if hit and self.selected and self.graph.has_edge(self.selected, hit):
                self.graph.checkpoint()
                self.graph.disconnect(self.selected, hit)
            self.redraw()
            return
        if hit:
            if self.selected and self.selected != hit and not self.graph.has_edge(self.selected, hit):
                self.graph.checkpoint()
                self.graph.connect(self.selected, hit)
            self.selected = hit
        else:
            self.graph.checkpoint()
            x, y = self.snap(event.xdata, event.ydata)
            new = self.graph.add_node(x, y, self.current_type)
            if self.selected:
                self.graph.connect(self.selected, new)
            self.selected = new
            if self.current_type == "DOOR":
                self.start_label(new)
        self.redraw()

    def on_key(self, event):
        key = event.key or ""
        if self.label_target:
            if key == "enter":
                label = self.label_buffer.strip()
                if label:
                    self.graph.checkpoint()
                    self.graph.labels[self.label_target] = label
                self.label_target = None
            elif key == "escape":
                self.label_target = None
            elif key == "backspace":
                self.label_buffer = self.label_buffer[:-1]
            elif len(key) == 1 and (key.isalnum() or key in "-_."):
                self.label_buffer += key.upper()
            self.redraw()
            return
        if key in NODE_TYPES:
            self.current_type = NODE_TYPES[key]
        elif key == "t" and self.selected:
            self.graph.checkpoint()
            self.graph.set_type(self.selected, self.current_type)
            if self.current_type == "DOOR":
                self.start_label(self.selected)
        elif key == "escape":
            self.selected, self.cut_pending = None, False
        elif key == "l" and self.selected:
            self.start_label(self.selected)
        elif key == "x" and self.selected:
            self.cut_pending = True
        elif key in ("backspace", "delete") and self.selected:
            self.graph.checkpoint()
            self.graph.delete_node(self.selected)
            self.selected = None
        elif key == "u":
            if self.graph.undo() and self.selected not in self.graph.nodes:
                self.selected = None
        elif key == "w":
            self.save()
        elif key == "q":
            self.save()
            self.plt.close(self.fig)
            return
        elif key == "h":
            print(TRACE_HELP)
        self.redraw()

    def start_label(self, node_id: str):
        self.label_target = node_id
        self.label_buffer = self.graph.labels.get(node_id, "")

    # output -----------------------------------------------------------------
    def save(self):
        self.output.parent.mkdir(parents=True, exist_ok=True)
        self.output.write_text(json.dumps(self.graph.to_json(), indent=2) + "\n")
        problems = self.graph.problems()
        print(f"saved {self.output}: {len(self.graph.nodes)} nodes, {len(self.graph.edges)} edges, "
              f"{len(self.graph.labels)} labels")
        for p in problems:
            print(f"  ! {p}")

    def redraw(self):
        for artist in self.artists:
            artist.remove()
        self.artists.clear()
        g, ax = self.graph, self.ax
        for a, b in g.edges:
            na, nb = g.nodes[a], g.nodes[b]
            self.artists += ax.plot([na["x"], nb["x"]], [na["y"], nb["y"]], "-", color="#00a000", lw=2, alpha=0.8)
        for node in g.nodes.values():
            marker = "*" if node["type"] == "START" else "o"
            size = 14 if node["type"] == "START" else 7
            self.artists += ax.plot(node["x"], node["y"], marker, ms=size, color=COLOR[node["type"]], mec="white", mew=1)
            if node["id"] in g.labels:
                self.artists.append(ax.annotate(
                    g.labels[node["id"]], (node["x"], node["y"]), xytext=(5, 5), textcoords="offset points",
                    fontsize=8, color=COLOR["DOOR"], weight="bold",
                    bbox=dict(boxstyle="round,pad=0.15", fc="white", ec="none", alpha=0.8)))
        if self.selected:
            s = g.nodes[self.selected]
            self.artists += ax.plot(s["x"], s["y"], "o", ms=16, mfc="none", mec="#ff00ff", mew=2)
        status = f"type: {self.current_type}   nodes {len(g.nodes)}  edges {len(g.edges)}  labels {len(g.labels)}"
        if self.label_target:
            status = f"label for {self.label_target}: {self.label_buffer}_   (enter to save, esc to cancel)"
        elif self.cut_pending:
            status = "cut: click the node at the other end of the edge to remove"
        ax.set_title(status, fontsize=10, loc="left")
        self.fig.canvas.draw_idle()


def cmd_trace(args):
    out = Path(args.output)
    if out.exists():
        graph = Graph.from_json(json.loads(out.read_text()))
        print(f"resuming {out} ({len(graph.nodes)} nodes)")
    else:
        image = load_image(args.image)
        graph = Graph(args.building, args.floor, {"image": Path(args.image).name, "size": list(image.size)})
    plt = _setup_matplotlib()
    print(TRACE_HELP)
    Tracer(args.image, args.output, graph, plt)
    plt.show()


# --------------------------------------------------------------------------- main

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("corners", help="label poster corners in photos")
    p.add_argument("images", nargs="+")
    p.add_argument("-o", "--output", required=True, help="corners JSON (created or updated)")
    p.add_argument("--redo", action="store_true", help="relabel images that already have corners")
    p.set_defaults(func=cmd_corners)

    p = sub.add_parser("warp", help="straighten a photo using its labeled corners")
    p.add_argument("image")
    p.add_argument("--corners", required=True)
    p.add_argument("-o", "--output", required=True)
    p.add_argument("--width", type=int, default=2000, help="output width in px (default 2000)")
    p.set_defaults(func=cmd_warp)

    p = sub.add_parser("trace", help="click-to-trace a FloorGraph")
    p.add_argument("image")
    p.add_argument("-o", "--output", required=True, help="*.graph.json (resumed if it exists)")
    p.add_argument("--building", default="engineering-hall")
    p.add_argument("--floor", default="3")
    p.set_defaults(func=cmd_trace)

    args = parser.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
