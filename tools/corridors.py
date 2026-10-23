#!/usr/bin/env python3
"""Corridor extraction prototype (Gantt tasks 3 and 5).

Straightened evacuation poster -> corridor mask -> skeleton -> FloorGraph,
plus a scorer that compares any graph with a hand-traced one.

Pipeline (evacuation posters)
  1. Orange/rust evacuation-route lines (HSV). Filled red/orange symbols
     (AED heart, extinguishers, arrowheads) are removed by a morphological
     opening: route lines are thin, symbols are blobs.
  2. Optional (--with-hatch): dotted "tornado shelter" hatching, for corridors
     with no route line. Off by default: it also fills shelter rooms and
     stairwells, which costs more precision than it adds recall.
  3. Widen, close gaps, keep the big connected network.
  4. Skeletonize, turn the pixel skeleton into nodes (junctions, ends) and
     edges (polylines), prune short spurs, merge nearby junctions.

Result on the traced EH 3rd-floor elevator poster (defaults tuned on that same
poster, so treat as optimistic until a second traced map is held out):
  corridor length F1 0.91 (P 0.95, R 0.87), junction F1 0.80 (6/6 found real, 6/9 traced)

Directory maps (corridors and rooms both white) need a different first step;
this module covers the evacuation-poster style only.

Commands
  extract   python tools/corridors.py extract MAP.jpg -o out.graph.json [--debug overlay.jpg]
  score     python tools/corridors.py score PRED.graph.json TRUTH.graph.json
  evaluate  python tools/corridors.py evaluate MAP.jpg TRUTH.graph.json [--debug overlay.jpg]
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass, field
from pathlib import Path

import cv2
import numpy as np
from scipy.optimize import linear_sum_assignment
from skimage.morphology import skeletonize

SCHEMA_VERSION = 1


@dataclass
class Params:
    """Tunable values, in pixels of a ~2000 px wide straightened poster."""
    wall_run: int = 25          # dark runs at least this long are walls, not dots
    dot_threshold: int = 18     # black-hat response that counts as a dot
    density_window: int = 21
    density_min: float = 0.12   # share of dot pixels that makes a region "hatched"
    symbol_size: int = 11       # filled blobs at least this wide are symbols, not route lines
    route_width: int = 9        # route lines are widened before closing gaps
    close: int = 21
    open: int = 9               # only used with the hatch mask
    keep_fraction: float = 0.25  # keep blobs at least this share of the biggest
    spur_length: float = 90.0   # prune dead-end branches shorter than this
    merge_radius: float = 30.0  # junctions closer than this become one
    use_hatch: bool = False


# --------------------------------------------------------------------------- mask

def route_mask(bgr: np.ndarray) -> np.ndarray:
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    h, s, v = cv2.split(hsv)
    return (((h < 20) | (h > 165)) & (s > 70) & (v > 80)).astype(np.uint8) * 255


def hatch_mask(bgr: np.ndarray, p: Params) -> np.ndarray:
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    dark = (gray < 110).astype(np.uint8) * 255
    horizontal = cv2.getStructuringElement(cv2.MORPH_RECT, (p.wall_run, 1))
    vertical = cv2.getStructuringElement(cv2.MORPH_RECT, (1, p.wall_run))
    walls = cv2.morphologyEx(dark, cv2.MORPH_OPEN, horizontal) | cv2.morphologyEx(dark, cv2.MORPH_OPEN, vertical)
    walls = cv2.dilate(walls, np.ones((5, 5), np.uint8))
    blackhat = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7)))
    dots = ((blackhat > p.dot_threshold) & (walls == 0)).astype(np.float32)
    density = cv2.blur(dots, (p.density_window, p.density_window))
    return (density > p.density_min).astype(np.uint8) * 255


def clean_routes(routes: np.ndarray, p: Params) -> np.ndarray:
    """Drop filled symbols (AED heart, extinguishers, arrowheads) from the route mask."""
    disk = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (p.symbol_size, p.symbol_size))
    blobs = cv2.morphologyEx(routes, cv2.MORPH_OPEN, disk)
    return routes & ~cv2.dilate(blobs, np.ones((7, 7), np.uint8))


def corridor_mask(bgr: np.ndarray, p: Params | None = None) -> np.ndarray:
    p = p or Params()
    routes = clean_routes(route_mask(bgr), p)
    mask = cv2.dilate(routes, np.ones((p.route_width, p.route_width), np.uint8))
    if p.use_hatch:
        hatch = hatch_mask(bgr, p)
        hatch = cv2.morphologyEx(hatch, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (p.open, p.open)))
        mask |= hatch
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (p.close, p.close)))
    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
    if count <= 1:
        return mask
    on_route = np.bincount(labels[routes > 0].ravel(), minlength=count)
    areas = stats[:, cv2.CC_STAT_AREA].astype(float)
    areas[0] = 0
    keep = (areas >= p.keep_fraction * areas.max()) & (on_route > 0)
    keep[0] = False
    return keep[labels].astype(np.uint8) * 255


# --------------------------------------------------------------------------- skeleton -> graph

_NEIGHBOURS = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]


@dataclass
class PixelGraph:
    nodes: dict[int, tuple[float, float]] = field(default_factory=dict)  # id -> (x, y)
    edges: list[tuple[int, int, list[tuple[float, float]]]] = field(default_factory=list)

    def degree(self) -> dict[int, int]:
        deg = {n: 0 for n in self.nodes}
        for a, b, _ in self.edges:
            deg[a] += 1
            deg[b] += 1
        return deg


def _polyline_length(pts) -> float:
    return float(sum(math.dist(a, b) for a, b in zip(pts, pts[1:])))


def skeleton_graph(mask: np.ndarray) -> PixelGraph:
    """Pixel skeleton -> graph with nodes at ends and junctions."""
    skel = skeletonize(mask > 0)
    ys, xs = np.nonzero(skel)
    on = set(zip(ys.tolist(), xs.tolist()))

    def neighbours(p):
        y, x = p
        return [(y + dy, x + dx) for dy, dx in _NEIGHBOURS if (y + dy, x + dx) in on]

    special = {p for p in on if len(neighbours(p)) != 2}
    # Group touching junction pixels into one node.
    node_of: dict[tuple[int, int], int] = {}
    graph = PixelGraph()
    for p in special:
        if p in node_of:
            continue
        nid = len(graph.nodes)
        stack, members = [p], []
        node_of[p] = nid
        while stack:
            q = stack.pop()
            members.append(q)
            for r in neighbours(q):
                if r in special and r not in node_of:
                    node_of[r] = nid
                    stack.append(r)
        my, mx = np.mean(members, axis=0)
        graph.nodes[nid] = (float(mx), float(my))

    seen_steps: set[tuple] = set()
    for start in special:
        for first in neighbours(start):
            if first in special and node_of[first] == node_of[start]:
                continue
            step = (start, first)
            if step in seen_steps:
                continue
            path = [start, first]
            prev, cur = start, first
            while cur not in special:
                nxt = [r for r in neighbours(cur) if r != prev]
                if not nxt:
                    break
                prev, cur = cur, nxt[0]
                path.append(cur)
            seen_steps.add((path[-1], path[-2]))
            seen_steps.add(step)
            if cur not in special:
                continue
            a, b = node_of[start], node_of[cur]
            pts = [(float(x), float(y)) for y, x in path]
            if a == b and len(pts) < 10:
                continue
            graph.edges.append((a, b, pts))
    return graph


def prune_spurs(graph: PixelGraph, min_length: float) -> PixelGraph:
    """Repeatedly drop dead-end branches shorter than min_length."""
    changed = True
    while changed:
        changed = False
        deg = graph.degree()
        for i, (a, b, pts) in enumerate(graph.edges):
            leaf = (deg[a] == 1) != (deg[b] == 1)
            if leaf and _polyline_length(pts) < min_length:
                del graph.edges[i]
                for n in (a, b):
                    if deg[n] == 1:
                        graph.nodes.pop(n, None)
                changed = True
                break
    return _drop_isolated(graph)


def merge_close_nodes(graph: PixelGraph, radius: float) -> PixelGraph:
    """Collapse short edges between junctions (skeletons split crossings in two)."""
    changed = True
    while changed:
        changed = False
        for i, (a, b, pts) in enumerate(graph.edges):
            if a != b and _polyline_length(pts) < radius:
                ax, ay = graph.nodes[a]
                bx, by = graph.nodes[b]
                graph.nodes[a] = ((ax + bx) / 2, (ay + by) / 2)
                del graph.edges[i]
                graph.edges = [(a if u == b else u, a if v == b else v, p) for u, v, p in graph.edges]
                graph.nodes.pop(b)
                changed = True
                break
    graph.edges = [(a, b, p) for a, b, p in graph.edges if a != b or _polyline_length(p) > radius * 4]
    return _drop_isolated(graph)


def contract_degree_two(graph: PixelGraph) -> PixelGraph:
    """Join chains through nodes with exactly two edges (left over after pruning)."""
    changed = True
    while changed:
        changed = False
        deg = graph.degree()
        for n, d in deg.items():
            if d != 2:
                continue
            touching = [i for i, (a, b, _) in enumerate(graph.edges) if n in (a, b)]
            if len(touching) != 2:
                continue
            (a1, b1, p1), (a2, b2, p2) = graph.edges[touching[0]], graph.edges[touching[1]]
            if (a1, b1) == (a2, b2) or (a1, b1) == (b2, a2):
                continue  # a loop through n; keep it
            p1 = p1 if b1 == n else p1[::-1]
            p2 = p2 if a2 == n else p2[::-1]
            u = a1 if b1 == n else b1
            v = b2 if a2 == n else a2
            for i in sorted(touching, reverse=True):
                del graph.edges[i]
            graph.edges.append((u, v, p1 + p2[1:]))
            graph.nodes.pop(n)
            changed = True
            break
    return graph


def _drop_isolated(graph: PixelGraph) -> PixelGraph:
    used = {a for a, _, _ in graph.edges} | {b for _, b, _ in graph.edges}
    graph.nodes = {k: v for k, v in graph.nodes.items() if k in used}
    return graph


def extract_graph(bgr: np.ndarray, p: Params | None = None) -> tuple[PixelGraph, np.ndarray]:
    p = p or Params()
    mask = corridor_mask(bgr, p)
    graph = skeleton_graph(mask)
    graph = prune_spurs(graph, p.spur_length)
    graph = merge_close_nodes(graph, p.merge_radius)
    graph = contract_degree_two(graph)
    return graph, mask


def to_floorgraph_json(graph: PixelGraph, building="engineering-hall", floor="3", source=None) -> dict:
    """FloorGraph JSON (schemaVersion 1); unverified, START left to the YAH detector."""
    deg = graph.degree()
    ids = {n: f"{'J' if deg[n] >= 3 else 'X'}{i + 1}" for i, n in enumerate(sorted(graph.nodes))}
    nodes = [{"id": ids[n], "type": "JUNCTION" if deg[n] >= 3 else "DEAD_END",
              "x": round(x, 1), "y": round(y, 1)} for n, (x, y) in sorted(graph.nodes.items())]
    edges = []
    for i, (a, b, pts) in enumerate(graph.edges):
        simple = cv2.approxPolyDP(np.asarray(pts, np.float32).reshape(-1, 1, 2), 2.0, False).reshape(-1, 2)
        edges.append({"id": f"e{i + 1}", "from": ids[a], "to": ids[b],
                      "polyline": [[round(float(x), 1), round(float(y), 1)] for x, y in simple],
                      "lengthPx": round(_polyline_length(pts), 1)})
    start = nodes[0]["id"] if nodes else ""
    return {"schemaVersion": SCHEMA_VERSION, "buildingId": building, "floor": floor,
            "startNodeId": start, "verified": False, "source": source or {},
            "nodes": nodes, "edges": edges, "landmarks": []}


# --------------------------------------------------------------------------- scoring

def _graph_polylines(data: dict) -> tuple[list[list[tuple[float, float]]], dict, list[tuple[str, str]]]:
    nodes = {n["id"]: (n["x"], n["y"]) for n in data["nodes"]}
    lines, pairs = [], []
    for e in data["edges"]:
        pts = e.get("polyline") or [nodes[e["from"]], nodes[e["to"]]]
        lines.append([tuple(p) for p in pts])
        pairs.append((e["from"], e["to"]))
    return lines, nodes, pairs


def _sample(lines, step=2.0):
    out = []
    for pts in lines:
        for a, b in zip(pts, pts[1:]):
            n = max(1, int(math.dist(a, b) / step))
            for t in np.linspace(0, 1, n, endpoint=False):
                out.append((a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])))
        if pts:
            out.append(pts[-1])
    return np.asarray(out, float).reshape(-1, 2)


def _within(samples: np.ndarray, lines, tol: float, shape) -> np.ndarray:
    canvas = np.zeros(shape, np.uint8)
    for pts in lines:
        cv2.polylines(canvas, [np.asarray(pts, np.int32)], False, 255, 1)
    dist = cv2.distanceTransform(255 - canvas, cv2.DIST_L2, 5)
    xs = np.clip(samples[:, 0].round().astype(int), 0, shape[1] - 1)
    ys = np.clip(samples[:, 1].round().astype(int), 0, shape[0] - 1)
    return dist[ys, xs] <= tol


def corridor_topology(data: dict, stub: float) -> dict[str, tuple[float, float]]:
    """Junctions of the corridor network: degree >= 3 after removing short stubs
    (door, elevator and AED stubs in hand traces; skeleton spurs in predictions)."""
    nodes = {n["id"]: (n["x"], n["y"]) for n in data["nodes"]}
    edges = [(e["from"], e["to"], math.dist(nodes[e["from"]], nodes[e["to"]]) if not e.get("lengthPx") else e["lengthPx"])
             for e in data["edges"]]
    changed = True
    while changed:
        changed = False
        deg: dict[str, int] = {}
        for a, b, _ in edges:
            deg[a] = deg.get(a, 0) + 1
            deg[b] = deg.get(b, 0) + 1
        for i, (a, b, length) in enumerate(edges):
            if (deg[a] == 1 or deg[b] == 1) and length < stub:
                del edges[i]
                changed = True
                break
    deg = {}
    for a, b, _ in edges:
        deg[a] = deg.get(a, 0) + 1
        deg[b] = deg.get(b, 0) + 1
    # merge junction nodes that sit within the stub distance of each other
    junctions = [nodes[n] for n, d in deg.items() if d >= 3]
    merged: list[list[tuple[float, float]]] = []
    for j in junctions:
        for group in merged:
            if math.dist(j, np.mean(group, axis=0)) < stub / 2:
                group.append(j)
                break
        else:
            merged.append([j])
    return {f"j{i}": tuple(np.mean(g, axis=0)) for i, g in enumerate(merged)}


def score(pred: dict, truth: dict, tol: float = 15.0, match_radius: float = 40.0,
          stub: float = 45.0, shape=None) -> dict:
    p_lines, _, _ = _graph_polylines(pred)
    t_lines, _, _ = _graph_polylines(truth)
    if shape is None:
        allpts = np.vstack([np.asarray(sum(p_lines + t_lines, []), float)])
        shape = (int(allpts[:, 1].max()) + 20, int(allpts[:, 0].max()) + 20)
    ps, ts = _sample(p_lines), _sample(t_lines)
    precision = float(_within(ps, t_lines, tol, shape).mean()) if len(ps) else 0.0
    recall = float(_within(ts, p_lines, tol, shape).mean()) if len(ts) else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0

    pj = list(corridor_topology(pred, stub).values())
    tj = list(corridor_topology(truth, stub).values())
    matched = 0
    if pj and tj:
        cost = np.array([[math.dist(a, b) for b in tj] for a in pj])
        rows, cols = linear_sum_assignment(cost)
        matched = int((cost[rows, cols] <= match_radius).sum())
    jp = matched / len(pj) if pj else 0.0
    jr = matched / len(tj) if tj else 0.0
    jf1 = 2 * jp * jr / (jp + jr) if jp + jr else 0.0
    return {"length_precision": precision, "length_recall": recall, "length_f1": f1,
            "junctions_pred": len(pj), "junctions_true": len(tj), "junctions_matched": matched,
            "junction_precision": jp, "junction_recall": jr, "junction_f1": jf1}


def format_score(s: dict) -> str:
    return (f"corridor length  P {s['length_precision']:.1%}  R {s['length_recall']:.1%}  F1 {s['length_f1']:.1%}\n"
            f"junctions        P {s['junction_precision']:.1%}  R {s['junction_recall']:.1%}  F1 {s['junction_f1']:.1%}"
            f"   ({s['junctions_matched']} matched; {s['junctions_pred']} found, {s['junctions_true']} traced)")


# --------------------------------------------------------------------------- debug + CLI

def draw_overlay(bgr, mask, pred: dict, truth: dict | None = None) -> np.ndarray:
    out = bgr.copy()
    out[mask > 0] = (0.6 * out[mask > 0] + np.array([0, 60, 0])).astype(np.uint8)
    if truth:
        for pts in _graph_polylines(truth)[0]:
            cv2.polylines(out, [np.asarray(pts, np.int32)], False, (0, 160, 0), 6)
    for pts in _graph_polylines(pred)[0]:
        cv2.polylines(out, [np.asarray(pts, np.int32)], False, (255, 0, 255), 3)
    for n in pred["nodes"]:
        color = (255, 0, 255) if n["type"] == "JUNCTION" else (128, 128, 128)
        cv2.circle(out, (int(n["x"]), int(n["y"])), 9, color, -1)
    return out


def cmd_extract(args):
    bgr = cv2.imread(args.map)
    graph, mask = extract_graph(bgr, Params(use_hatch=args.with_hatch))
    data = to_floorgraph_json(graph, source={"image": Path(args.map).name, "extractor": "corridors.py"})
    Path(args.output).write_text(json.dumps(data, indent=2) + "\n")
    print(f"{len(data['nodes'])} nodes, {len(data['edges'])} edges -> {args.output}")
    if args.debug:
        cv2.imwrite(args.debug, draw_overlay(bgr, mask, data))


def cmd_score(args):
    pred = json.loads(Path(args.pred).read_text())
    truth = json.loads(Path(args.truth).read_text())
    print(format_score(score(pred, truth)))


def cmd_evaluate(args):
    bgr = cv2.imread(args.map)
    truth = json.loads(Path(args.truth).read_text())
    graph, mask = extract_graph(bgr, Params(use_hatch=args.with_hatch))
    pred = to_floorgraph_json(graph)
    print(f"extracted {len(pred['nodes'])} nodes, {len(pred['edges'])} edges")
    print(format_score(score(pred, truth, shape=bgr.shape[:2])))
    if args.debug:
        cv2.imwrite(args.debug, draw_overlay(bgr, mask, pred, truth))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("extract", help="straightened evacuation poster -> graph JSON")
    p.add_argument("map")
    p.add_argument("-o", "--output", required=True)
    p.add_argument("--debug")
    p.add_argument("--with-hatch", action="store_true", help="also use dotted corridor hatching")
    p.set_defaults(func=cmd_extract)
    p = sub.add_parser("score", help="compare a graph JSON with a hand-traced one")
    p.add_argument("pred")
    p.add_argument("truth")
    p.set_defaults(func=cmd_score)
    p = sub.add_parser("evaluate", help="extract from a map and score against its trace")
    p.add_argument("map")
    p.add_argument("truth")
    p.add_argument("--debug", help="overlay: green = traced, magenta = extracted")
    p.add_argument("--with-hatch", action="store_true", help="also use dotted corridor hatching")
    p.set_defaults(func=cmd_evaluate)
    args = parser.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
