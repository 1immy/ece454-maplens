#!/usr/bin/env python3
"""Corridor extraction reference for mapparse/PosterCorridors.kt (Gantt tasks 3 and 5).

Straightened poster -> corridor mask -> skeleton -> FloorGraph, plus a scorer
that compares any graph with a hand-traced one. ADR 0010 has the results.

Pipeline
  1. Orange/rust evacuation-route lines (HSV), plus faint paint that reaches
     them. Filled symbols (AED heart, extinguishers, arrowheads) are removed by
     a morphological opening: route lines are thin, symbols are blobs.
  2. Widen, close gaps, skeletonize, turn the skeleton into nodes and edges,
     prune short spurs, merge nearby junctions.
  3. Refine each junction to the least-squares intersection of straight line
     fits to its corridors, 20-150 px out.
  4. A poster with little paint, such as a directory map, instead traces free
     space that is narrow one way and long the other, measured between walls.
  5. Either way, link corridors the first pass missed: straight clear runs from
     a corner or an end, then walks through free space between separate pieces.
     Closed door gaps keep links out of rooms.

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
from collections import deque
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
    symbol_size: int = 11       # filled blobs at least this wide are symbols, not route lines
    route_width: int = 9        # route lines are widened before closing gaps
    long_run: int = 61          # straight runs this long stay through symbols and arrow tails
    close: int = 21
    keep_fraction: float = 0.05  # blobs under this share of the biggest are noise, not corridor
    keep_network: float = 0.25   # after linking, keep networks at least this share of the biggest
    spur_length: float = 60.0   # prune dead-end branches shorter than this
    marker_spur_length: float = 90.0  # star paint matches the route, so stubs at a star go further
    merge_radius: float = 60.0  # junctions closer than this become one
    refine_near: float = 20.0   # junction line fits use each corridor from this far out...
    refine_far: float = 150.0   # ...to this far out
    refine_max_move: float = 80.0  # ignore a refinement that moves a junction further than this
    wall_long: int = 120        # dark shapes this long are walls whatever their shape...
    wall_short: int = 30        # ...shorter ones must be at least this long and 3 times as long as wide
    seal: float = 0.75          # door gaps up to this share of a corridor width are closed
    wide: float = 1.6           # free space wider than this many corridor widths is a room
    strip_length: float = 3.5   # a corridor runs straight for at least this many widths
    strip_run: float = 1.5      # an unbroken stretch of corridor is at least this many widths
    strip_gap: float = 2.5      # a corridor keeps going across openings up to this many widths
    link_clear: float = 0.6     # a link needs this many corridor widths of clear space
    link_reach: float = 12.0    # a link joins corridors at most this many widths apart
    stub_reach: float = 4.0     # an unpainted corridor end reaches at most this many widths
    stub_min: float = 0.6       # shorter stubs are the space beside a line, not a corridor
    walk_margin: float = 0.5    # a walk between pieces keeps this many widths from any wall
    walk_reach: float = 25.0    # and is at most this many widths long
    neck_margin: float = 0.3    # widths from any wall when walking with door gaps open
    neck_reach: float = 12.0    # such a walk is at most this many widths long
    walk_piece: float = 4.0     # pieces shorter than this many widths are not worth joining
    stub_wide: float = 1.7      # a stub is at most this many widths between its walls
    facing_reach: float = 3.0   # a paint end carries on to paint it points at this many widths on;
                                # a sign or icon was drawn over the line between
    colour_reach: float = 6.0   # a paint end joins a line the photo's route colour reaches this
                                # many widths away; 4 to 8 give the same graphs on every image
    min_route_length: float = 1000.0  # less painted route than this means the poster has no paint
    fill_share: float = 0.4     # and sits mostly outside filled patches; pink rooms are filled
    paint_share: float = 0.5    # real paint is one network; a camera can push pink rooms into the
                                # route colour, but they read as scattered pieces


# --------------------------------------------------------------------------- mask

WALL_MAX_V = 110     # walls are darker than this...
WALL_MAX_S = 70      # ...and less colourful than this, which paint is not
WALL_RUN = 25        # dark runs at least this long are walls, not text
WALL_CONTRAST = 50   # or darker than this below the paper around it
WALL_GREY_S = 45     # and greyer than this, since blur also darkens and greys paint
FRINGE_REACH = 11    # route colour within this window of a wall may be fringe; a camera's colour
                     # fringe sits a few px out from a blurred wall edge
SIGN_FILL = 7        # letters on a sign are holes at most this wide
SIGN_AREA = 900      # px; signs are larger than any junction or extinguisher symbol
SIGN_SIZE = 150      # px; and no side of a sign is longer than this
SIGN_SOLID = 0.5     # and a sign fills at least this share of its box
SIGN_THICK = 20      # px; a sign is a box at least this wide each way, unlike a blurred line
SIGN_LINES = 3       # paint leaving a blob in this many places is a junction, not a sign
SIGN_MARGIN = 7      # px around a sign removed with it (as a window), and out to where leaving paint is read
SIGN_RING = 4        # px; leaving paint is read in a ring this wide beyond that
PAINT_REACH = 61     # fringe is only dropped with solid paint inside this window
LINK_ARM = 30        # px of line used to read a corner's or an end's direction
BEND_COSINE = 0.5    # a line turning more than 60 degrees has a corner
LINK_LANE = 0.35     # share of a corridor width between the lanes a link may take
LINK_FROM = 2.0      # edges shorter than this many corridor widths start no links
OPEN_RUN = 4.0       # a line straight for this many widths carries on along its hallway to a wall
STRAIGHT_COSINE = 0.9  # an end within about 25 degrees of its line is straight
AXIS_STRAIGHT = 0.94 # only a line within 20 degrees of an axis carries straight on; paint bends
                     # diagonally around symbols, and that bend is no corridor
AXIS_TAKEN = 0.7     # an existing corridor within 45 degrees of an axis takes that direction
FAINT_S = 45         # weak paint is at least this saturated...
FAINT_RUN = 31       # ...in a straight run at least this long...
FAINT_LINK = 15      # ...that reaches solid paint within this window
FILL_S = 35          # pink room fills are at least this saturated
FILL_WINDOW = 61     # px; a filled patch covers most of this window
FILL_COVER = 0.45    # share of the window that is reddish in a filled patch
PAPER_BLUE = 6       # Lab b* units bluer than paper means frame or backdrop
HOLE_AREA = 6000     # px; holes smaller than this in a corridor mask are filled
PAINT_MARGIN = 7     # px around paint that is never wall
OFF_PAPER_SIZE = 21  # frame and backdrop are at least this wide; text is not
FIRST_SEAL = 15      # door gaps closed before the corridor width is known
WIDTH_RUN = 200      # free runs this long are measured for corridor width
MIN_WIDTH = 12
MAX_WIDTH = 400

def route_mask(bgr: np.ndarray) -> np.ndarray:
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    h, s, v = cv2.split(hsv)
    red = (h < 20) | (h > 165)
    routes = (red & (s > 70) & (v > 80)).astype(np.uint8) * 255
    # Photos of a screen fringe walls with route colour. Walls are dark and colourless, unlike
    # paint. A streak on a wall is dropped only when solid paint runs beside it, so a faint
    # corridor with nothing stronger nearby survives.
    # A phone photo can leave walls lighter than WALL_MAX_V, so a wall may also be dark for its
    # surroundings.
    local = cv2.medianBlur(v, 31)
    dark = (((v < WALL_MAX_V) & (s < WALL_MAX_S)) | ((local.astype(int) - v > WALL_CONTRAST) & (s < WALL_GREY_S))).astype(np.uint8) * 255
    walls = (cv2.morphologyEx(dark, cv2.MORPH_OPEN, np.ones((1, WALL_RUN), np.uint8))
             | cv2.morphologyEx(dark, cv2.MORPH_OPEN, np.ones((WALL_RUN, 1), np.uint8)))
    walls = cv2.dilate(walls, np.ones((FRINGE_REACH, FRINGE_REACH), np.uint8))
    paint = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (PAINT_REACH, PAINT_REACH))
    near_paint = cv2.dilate(routes & ~walls, paint)
    solid = routes & ~(walls & near_paint)
    # Faint paint reads under the solid cutoff in stretches. It never counts beside a wall, where
    # it can't be told from fringe, and it must reach solid paint.
    faint = (red & (s > FAINT_S) & (v > 80)).astype(np.uint8) * 255 & ~walls
    faint = (cv2.morphologyEx(faint, cv2.MORPH_OPEN, np.ones((1, FAINT_RUN), np.uint8))
             | cv2.morphologyEx(faint, cv2.MORPH_OPEN, np.ones((FAINT_RUN, 1), np.uint8)))
    grown = cv2.dilate(solid | faint, np.ones((FAINT_LINK, FAINT_LINK), np.uint8))
    count, labels = cv2.connectedComponents(grown, connectivity=8)
    seeded = np.zeros(count, bool)
    seeded[labels[solid > 0]] = True
    seeded[0] = False
    return solid | (faint & (seeded[labels].astype(np.uint8) * 255))


def in_fills(bgr: np.ndarray) -> float:
    """Share of route colour inside filled patches. Paint is thin lines. A camera can push pink room
    fills into the route colour, and those fill most of the area around them."""
    h, s, v = cv2.split(cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV))
    reddish = (((h < 20) | (h > 160)) & (s > FILL_S) & (v > 80)).astype(np.float32)
    fills = cv2.blur(reddish, (FILL_WINDOW, FILL_WINDOW)) > FILL_COVER
    routes = route_mask(bgr) > 0
    return float((routes & fills).sum()) / max(int(routes.sum()), 1)


def clean_routes(routes: np.ndarray, p: Params) -> np.ndarray:
    """Drop filled symbols (AED heart, extinguishers, arrowheads) from the route mask."""
    disk = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (p.symbol_size, p.symbol_size))
    blobs = cv2.morphologyEx(routes, cv2.MORPH_OPEN, disk)
    clean = routes & ~cv2.dilate(blobs, np.ones((7, 7), np.uint8))
    # Long runs preserve painted route lines through junction blobs and arrow tails.
    runs = (cv2.morphologyEx(routes, cv2.MORPH_OPEN, np.ones((1, p.long_run), np.uint8))
            | cv2.morphologyEx(routes, cv2.MORPH_OPEN, np.ones((p.long_run, 1), np.uint8)))
    kept = clean | runs
    # An EXIT sign is a red box with white letters. Filled in, it is a large blob, and its edges are
    # long enough to pass as runs, so it goes entirely.
    filled = cv2.morphologyEx(routes, cv2.MORPH_CLOSE, np.ones((SIGN_FILL, SIGN_FILL), np.uint8))
    filled = cv2.morphologyEx(filled, cv2.MORPH_OPEN, disk)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(filled, connectivity=8)
    width, height = stats[:, cv2.CC_STAT_WIDTH], stats[:, cv2.CC_STAT_HEIGHT]
    # A sign is a compact box, mostly filled. Blurred paint fills in too, but runs far.
    signs = ((stats[:, cv2.CC_STAT_AREA] >= SIGN_AREA) & (np.maximum(width, height) <= SIGN_SIZE)
             & (np.minimum(width, height) >= SIGN_THICK)
             & (stats[:, cv2.CC_STAT_AREA] >= SIGN_SOLID * width * height))
    signs[0] = False
    # A route ends at a sign. Thick paint where routes cross also fills in, but routes leave it.
    reach = SIGN_MARGIN + SIGN_RING
    for i in np.nonzero(signs)[0]:
        x0, y0 = max(stats[i, 0] - reach, 0), max(stats[i, 1] - reach, 0)
        x1, y1 = stats[i, 0] + width[i] + reach, stats[i, 1] + height[i] + reach
        blob = (labels[y0:y1, x0:x1] == i).astype(np.uint8) * 255
        ring = (cv2.dilate(blob, np.ones((2 * reach + 1,) * 2, np.uint8))
                & ~cv2.dilate(blob, np.ones((2 * SIGN_MARGIN + 1,) * 2, np.uint8)) & runs[y0:y1, x0:x1])
        signs[i] = cv2.connectedComponents(ring, connectivity=8)[0] - 1 < SIGN_LINES
    return kept & ~cv2.dilate(signs[labels].astype(np.uint8) * 255, np.ones((SIGN_MARGIN,) * 2, np.uint8))


def corridor_mask(bgr: np.ndarray, p: Params | None = None) -> np.ndarray:
    p = p or Params()
    routes = clean_routes(route_mask(bgr), p)
    mask = cv2.dilate(routes, np.ones((p.route_width, p.route_width), np.uint8))
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


# --------------------------------------------------------------------------- walls and free space

def wall_mask(bgr: np.ndarray, p: Params, paint: np.ndarray | None = None) -> np.ndarray:
    """Dark, colourless, long shapes. Text and hatching dots are compact, so they stay out. Dark
    rust paint can pass as a wall in a photo, and would split its corridor in two, so on a painted
    poster the paint never counts."""
    h, s, v = cv2.split(cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV))
    local = cv2.medianBlur(v, 31)
    dark = (v < 90) | ((v < 130) & (s < 90)) | ((local.astype(int) - v > 70) & (s < 50))
    if paint is not None:
        dark &= cv2.dilate(paint, np.ones((PAINT_MARGIN, PAINT_MARGIN), np.uint8)) == 0
    count, labels, stats, _ = cv2.connectedComponentsWithStats(dark.astype(np.uint8) * 255, connectivity=8)
    longer = np.maximum(stats[:, cv2.CC_STAT_WIDTH], stats[:, cv2.CC_STAT_HEIGHT])
    shorter = np.minimum(stats[:, cv2.CC_STAT_WIDTH], stats[:, cv2.CC_STAT_HEIGHT])
    keep = (longer >= p.wall_long) | ((longer >= p.wall_short) & (longer >= 3 * shorter))
    keep[0] = False
    return keep[labels].astype(np.uint8) * 255


def off_paper(bgr: np.ndarray) -> np.ndarray:
    """The blue frame or backdrop: bluer than the poster's own paper, whatever the light."""
    _, s, v = cv2.split(cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV))
    blue = cv2.cvtColor(bgr, cv2.COLOR_BGR2LAB)[:, :, 2]
    paper = (s < 25) & (v > 150)
    if not paper.any():
        return np.zeros(blue.shape, np.uint8)
    mode = int(np.bincount(blue[paper].ravel(), minlength=256).argmax())
    bluer = (blue.astype(int) < mode - PAPER_BLUE).astype(np.uint8) * 255
    # Coloured text such as "You Are Here" is bluer too, but it lies across corridors.
    return cv2.morphologyEx(bluer, cv2.MORPH_OPEN, np.ones((OFF_PAPER_SIZE, OFF_PAPER_SIZE), np.uint8))


def _run_lengths(free: np.ndarray) -> np.ndarray:
    """Each free pixel's horizontal run length."""
    out = np.zeros(free.shape, np.int32)
    for y, row in enumerate(free > 0):
        edges = np.diff(np.concatenate([[0], row.astype(np.int8), [0]]))
        for a, b in zip(np.nonzero(edges == 1)[0], np.nonzero(edges == -1)[0]):
            out[y, a:b] = b - a
    return out


def _seal(walls: np.ndarray, size: int) -> np.ndarray:
    return (walls | cv2.morphologyEx(walls, cv2.MORPH_CLOSE, np.ones((1, size), np.uint8))
            | cv2.morphologyEx(walls, cv2.MORPH_CLOSE, np.ones((size, 1), np.uint8)))


def _corridor_width(across: np.ndarray, along: np.ndarray, free: np.ndarray, routes=None) -> int:
    """Most common width of long straight free space, measured along the paint when there is
    some. Long rooms are few; corridors repeat."""
    strip = (free > 0) & (np.maximum(across, along) >= WIDTH_RUN)
    if routes is not None:
        strip &= routes > 0
    widths = np.minimum(across, along)[strip]
    if widths.size == 0:
        return MIN_WIDTH
    counts = np.bincount(np.clip(widths, 0, MAX_WIDTH), minlength=MAX_WIDTH + 1).astype(float)
    counts[:MIN_WIDTH] = 0
    counts[-1] = 0
    # A strip w wide has w pixels per unit of length, so divide to count length.
    counts /= np.maximum(np.arange(len(counts)), 1)
    return int(np.convolve(counts, np.ones(5) / 5, "same").argmax())


@dataclass
class FreeSpace:
    blocked: np.ndarray   # walls with door gaps closed, plus everything off the poster's paper
    unsealed: np.ndarray  # the same with door gaps left open
    width: int            # usual corridor width
    horizontal: np.ndarray
    vertical: np.ndarray


def free_space(bgr: np.ndarray, p: Params, routes=None) -> FreeSpace:
    walls = wall_mask(bgr, p, None if routes is None else route_mask(bgr))
    outside = off_paper(bgr)

    def measure(size):
        blocked = _seal(walls, size) | outside
        free = 255 - blocked
        horizontal = _run_lengths(free)
        vertical = _run_lengths(free.T).T
        return blocked, free, horizontal, vertical

    blocked, free, horizontal, vertical = measure(FIRST_SEAL)
    size = max(FIRST_SEAL, int(_corridor_width(horizontal, vertical, free, routes) * p.seal))
    blocked, free, horizontal, vertical = measure(size)
    width = max(MIN_WIDTH, _corridor_width(horizontal, vertical, free, routes))
    return FreeSpace(blocked, walls | outside, width, horizontal, vertical)


def strip_mask(space: FreeSpace, p: Params) -> np.ndarray:
    """Corridors without paint: free space narrow one way and long the other. A room is wide both
    ways, or too short. shortcut: axis-aligned corridors only, a diagonal wing needs the editor."""
    width, h, v = space.width, space.horizontal, space.vertical
    wide, length = int(width * p.wide), int(width * p.strip_length)
    gap = int(width * p.strip_gap) | 1
    # The opening drops door throats: long one way and narrow the other, but only for a few pixels.
    run = int(width * p.strip_run)

    def strip(narrow, kernel_open, kernel_close):
        return cv2.morphologyEx(cv2.morphologyEx(narrow.astype(np.uint8) * 255, cv2.MORPH_OPEN, kernel_open),
                                cv2.MORPH_CLOSE, kernel_close)

    # A door gap lets free space leak into a room, so judge width by the narrowest point nearby.
    tall = cv2.erode(v.astype(np.float32), np.ones((1, gap), np.uint8))
    broad = cv2.erode(h.astype(np.float32), np.ones((gap, 1), np.uint8))
    across = strip((h >= length) & (tall <= wide) & (tall >= width // 2), np.ones((1, run), np.uint8), np.ones((1, gap), np.uint8))
    down = strip((v >= length) & (broad <= wide) & (broad >= width // 2), np.ones((run, 1), np.uint8), np.ones((gap, 1), np.uint8))
    size = int(width * 0.8) | 1
    joined = cv2.morphologyEx(across | down, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (size, size)))
    return _keep_blobs(joined & ~space.blocked, p.keep_fraction)


def _keep_blobs(mask: np.ndarray, fraction: float) -> np.ndarray:
    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
    if count <= 1:
        return mask
    areas = stats[:, cv2.CC_STAT_AREA].astype(float)
    areas[0] = 0
    return (areas >= fraction * areas.max())[labels].astype(np.uint8) * 255


# --------------------------------------------------------------------------- skeleton -> graph

_NEIGHBOURS = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]


@dataclass
class PixelGraph:
    nodes: dict[int, tuple[float, float]] = field(default_factory=dict)  # id -> (x, y)
    edges: list[tuple[int, int, list[tuple[float, float]]]] = field(default_factory=list)
    width: float = 0.0  # usual corridor width, when known

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


def prune_spurs(graph: PixelGraph, min_length: float, stars=(), marker_length: float = 0.0) -> PixelGraph:
    """Repeatedly drop dead-end branches shorter than min_length. Star paint matches the route
    colour, so a stub into a star goes up to marker_length."""
    changed = True
    while changed:
        changed = False
        deg = graph.degree()
        for i, (a, b, pts) in enumerate(graph.edges):
            leaf = (deg[a] == 1) != (deg[b] == 1)
            end = graph.nodes[a if deg[a] == 1 else b]
            limit = marker_length if any(math.dist(end, star) <= 40.0 for star in stars) else min_length
            if leaf and _polyline_length(pts) < limit:
                del graph.edges[i]
                for n in (a, b):
                    if deg[n] == 1:
                        graph.nodes.pop(n, None)
                changed = True
                break
    return _drop_isolated(graph)


def merge_close_nodes(graph: PixelGraph, radius: float) -> PixelGraph:
    """Collapse short edges between junctions (skeletons split crossings in two). The junctions
    merged into one never spread over more than the radius, so merging can't run down a chain of
    short edges and pull a junction far from its corridors."""
    members = {n: [pos] for n, pos in graph.nodes.items()}
    changed = True
    while changed:
        changed = False
        for i, (a, b, pts) in enumerate(graph.edges):
            if a != b and _polyline_length(pts) < radius:
                group = members[a] + members[b]
                if max(math.dist(p, q) for p in group for q in group) > radius:
                    continue
                members[a] = group
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


def _arm(pts, from_start: bool, near: float, far: float):
    """Points of a polyline between `near` and `far` px from one of its ends."""
    seq = pts if from_start else pts[::-1]
    out, travelled = [], 0.0
    for a, b in zip(seq, seq[1:]):
        travelled += math.dist(a, b)
        if near <= travelled <= far:
            out.append(b)
    return out


def refine_junctions(graph: PixelGraph, near: float, far: float, max_move: float) -> PixelGraph:
    """Move each junction to the least-squares intersection of its corridors' lines."""
    for node, degree in graph.degree().items():
        if degree < 3:
            continue
        lines = []
        for a, b, pts in graph.edges:
            for from_start in ([True] if a == node else []) + ([False] if b == node else []):
                arm = _arm(pts, from_start, near, far)
                if len(arm) >= 8:
                    lines.append(cv2.fitLine(np.float32(arm), cv2.DIST_L2, 0, 0.01, 0.01).ravel())
        if len(lines) < 2:
            continue
        a_mat, b_vec = np.zeros((2, 2)), np.zeros(2)
        for vx, vy, x0, y0 in lines:
            normal = np.array([-vy, vx])
            proj = np.outer(normal, normal)
            a_mat += proj
            b_vec += proj @ np.array([x0, y0])
        if np.linalg.cond(a_mat) > 50:  # all corridors (nearly) parallel: no corner to find
            continue
        point = np.linalg.solve(a_mat, b_vec)
        if math.dist(point, graph.nodes[node]) <= max_move:
            graph.nodes[node] = (float(point[0]), float(point[1]))
    return graph


# --------------------------------------------------------------------------- linking

_AXES = [(1, 0), (-1, 0), (0, 1), (0, -1)]


def _arm_direction(pts) -> tuple[float, float] | None:
    """Heading of a polyline leaving its first point, from about 30 px of its length."""
    travelled = 0.0
    for a, b in zip(pts, pts[1:]):
        travelled += math.dist(a, b)
        if travelled >= LINK_ARM:
            break
    else:
        if travelled == 0:
            return None
    dx, dy = pts[0][0] - b[0], pts[0][1] - b[1]
    norm = math.hypot(dx, dy) or 1.0
    return -dx / norm, -dy / norm


def _bends(pts) -> list[int]:
    """Indices where a polyline turns sharply, sharpest first within each neighbourhood."""
    turns = []
    for i in range(LINK_ARM, len(pts) - LINK_ARM):
        ax, ay = pts[i][0] - pts[i - LINK_ARM][0], pts[i][1] - pts[i - LINK_ARM][1]
        bx, by = pts[i + LINK_ARM][0] - pts[i][0], pts[i + LINK_ARM][1] - pts[i][1]
        norm = math.hypot(ax, ay) * math.hypot(bx, by)
        cosine = (ax * bx + ay * by) / norm if norm else 1.0
        if cosine < BEND_COSINE:
            turns.append((cosine, i))
    chosen: list[int] = []
    for _, i in sorted(turns):
        if all(abs(i - j) > 2 * LINK_ARM for j in chosen):
            chosen.append(i)
    return chosen


def _line_labels(graph: PixelGraph, shape, reach: int) -> np.ndarray:
    labels = np.zeros(shape, np.int32)
    for index, (_, _, pts) in enumerate(graph.edges):
        cv2.polylines(labels, [np.asarray(pts, np.int32)], False, index + 1, reach * 2 + 1)
    return labels


def _clear_across(blocked, x, y, dx, dy, cap) -> int:
    """Free pixels across the ray at (x, y), up to cap each side."""
    height, width = blocked.shape
    total = 1
    for sign in (1, -1):
        for step in range(1, cap + 1):
            px, py = x + sign * step * dy, y + sign * step * dx
            if not (0 <= px < width and 0 <= py < height) or blocked[py, px]:
                break
            total += 1
    return total


def _cast(graph, labels, space, p, x0, y0, dx, dy, own, shortest=1, open_end=False):
    """Walk from (x0, y0) along an axis. Returns (kind, travelled, edge index) or None: a "line"
    reached through clear space, or a "wall" that ends an unpainted stretch of corridor. A stretch
    must look narrow, unless it carries on from the end of a long straight line (open_end): room
    walls along a hallway are drawn thin and broken by doors, and often don't read as walls."""
    width = space.width
    height, cols = space.blocked.shape
    clear, wide = int(width * p.link_clear), int(width * p.stub_wide)
    near = int(width * 2)
    beside, narrow, steps = 0, 0, 0
    reach = int(width * p.link_reach)
    for t in range(1, reach + 1):
        x, y = x0 + dx * t, y0 + dy * t
        if not (0 <= x < cols and 0 <= y < height) or space.blocked[y, x]:
            break
        hit = labels[y, x] - 1
        if hit >= 0 and (hit not in own or t > near):
            return ("line", t, hit) if t >= shortest and not _runs_beside(beside, steps) else None
        across = _clear_across(space.blocked, x, y, dx, dy, wide)
        if across < clear:
            return None
        steps += 1
        narrow += across <= wide
        beside += any(labels[sy, sx] - 1 not in (-1, *own)
                      for off in (int(width * 0.3), int(width * 0.7)) for side in (1, -1)
                      for sx, sy in [(x + side * off * dy, y + side * off * dx)]
                      if 0 <= sx < cols and 0 <= sy < height)
    else:
        return None
    if _runs_beside(beside, steps) or steps < width * p.stub_min or steps > width * p.stub_reach:
        return None
    if narrow < 0.7 * steps and not open_end:
        return None
    return "wall", steps, -1


def _runs_straight(graph: PixelGraph, node: int, index: int, dx: int, dy: int, width: float) -> bool:
    """Whether the line ending at node keeps to the axis for OPEN_RUN widths back from its end."""
    a, _, pts = graph.edges[index]
    x0, y0 = graph.nodes[node]
    for x, y in (pts if a == node else pts[::-1]):
        if abs((x - x0) * dy - (y - y0) * dx) > width * LINK_LANE:
            return False
        if abs((x - x0) * dx + (y - y0) * dy) >= width * OPEN_RUN:
            return True
    return False


def _runs_beside(beside: int, steps: int) -> bool:
    return steps > 0 and beside >= 0.5 * steps


def _split_edge(graph: PixelGraph, index: int, at: int) -> int:
    a, b, pts = graph.edges[index]
    node = max(graph.nodes) + 1
    graph.nodes[node] = pts[at]
    graph.edges[index] = (a, node, pts[: at + 1])
    graph.edges.append((node, b, pts[at:]))
    return node


def _attach(graph: PixelGraph, index: int, point, tolerance: float) -> tuple[int, tuple[float, float]]:
    """Node on an edge nearest the point: its existing end if close, otherwise a split."""
    a, b, pts = graph.edges[index]
    for node in (a, b):
        if math.dist(graph.nodes[node], point) <= tolerance:
            return node, graph.nodes[node]
    at = min(range(len(pts)), key=lambda i: math.dist(pts[i], point))
    if at == 0:
        return a, graph.nodes[a]
    if at == len(pts) - 1:
        return b, graph.nodes[b]
    return _split_edge(graph, index, at), pts[at]


def _clear_run(space, p, x0, y0, dx, dy, length) -> bool:
    clear, cap = int(space.width * p.link_clear), int(space.width * p.stub_wide)
    rows, cols = space.blocked.shape
    for t in range(1, length + 1):
        x, y = x0 + dx * t, y0 + dy * t
        if not (0 <= x < cols and 0 <= y < rows) or space.blocked[y, x]:
            return False
        if _clear_across(space.blocked, x, y, dx, dy, cap) < clear:
            return False
    return True


def _corner_links(graph: PixelGraph, space: FreeSpace, p: Params):
    """Two corridor ends whose lines would cross, as at a corner the strips stop short of."""
    reach = int(space.width * p.link_reach)
    ends = []
    for node, degree in graph.degree().items():
        if degree != 1:
            continue
        a, b, pts = next(e for e in graph.edges if node in e[:2])
        line = pts if a == node else pts[::-1]
        arm = _arm_direction(line)
        if not arm:
            continue
        # A line leaves the end going inward, so the corridor carries on the opposite way.
        axis = next(((dx, dy) for dx, dy in _AXES if -(dx * arm[0] + dy * arm[1]) > AXIS_TAKEN), None)
        if axis:
            ends.append((node, graph.nodes[node], axis))
    found = []
    for i, (na, a, da) in enumerate(ends):
        for nb, b, db in ends[i + 1:]:
            if da[0] * db[0] + da[1] * db[1] != 0:
                continue
            corner = (b[0], a[1]) if da[0] else (a[0], b[1])
            ta = int(round((corner[0] - a[0]) * da[0] + (corner[1] - a[1]) * da[1]))
            tb = int(round((corner[0] - b[0]) * db[0] + (corner[1] - b[1]) * db[1]))
            if not (0 < ta <= reach and 0 < tb <= reach):
                continue
            ax, ay, bx, by = int(a[0]), int(a[1]), int(b[0]), int(b[1])
            if _clear_run(space, p, ax, ay, *da, ta) and _clear_run(space, p, bx, by, *db, tb):
                found.append((ta + tb, na, nb, corner))
    return min(found, default=None)


def _straight(start, end) -> list[tuple[float, float]]:
    count = max(1, int(math.dist(start, end)))
    return [(start[0] + (end[0] - start[0]) * i / count, start[1] + (end[1] - start[1]) * i / count)
            for i in range(count + 1)]


def join_facing_ends(graph: PixelGraph, width: float, p: Params) -> PixelGraph:
    """Paint never stops and starts again on its own; a sign or icon drawn over it cuts it in two.
    A long line that ends pointing straight at the end of another, a short way on, carries on to
    it. Short hooks, such as one into an EXIT sign, point nowhere useful, so they start nothing."""
    reach, shortest = width * p.facing_reach, width * LINK_FROM
    while True:
        degree = graph.degree()
        best = None
        for index, (a, b, pts) in enumerate(graph.edges):
            if _polyline_length(pts) < shortest:
                continue
            for node, line in ((a, pts), (b, pts[::-1])):
                arm = _arm_direction(line)
                if degree[node] != 1 or not arm:
                    continue
                x0, y0 = graph.nodes[node]
                for other, (c, d, others) in enumerate(graph.edges):
                    ends = [graph.nodes[n] for n in (c, d) if degree[n] == 1]
                    if other == index or not ends:
                        continue
                    for at, (x, y) in enumerate(others):
                        gap = math.dist((x0, y0), (x, y))
                        if (0 < gap <= reach and (best is None or gap < best[0])
                                and -(arm[0] * (x - x0) + arm[1] * (y - y0)) >= STRAIGHT_COSINE * gap
                                and max(abs(x - x0), abs(y - y0)) >= AXIS_STRAIGHT * gap
                                and min(math.dist((x, y), e) for e in ends) <= width):
                            best = (gap, node, other, at)
        if best is None:
            return graph
        _, node, other, at = best
        a, b, others = graph.edges[other]
        target = a if at == 0 else b if at == len(others) - 1 else _split_edge(graph, other, at)
        graph.edges.append((node, target, _straight(graph.nodes[node], graph.nodes[target])))


def _colour_walk(routes, labels, start, own: int, tolerance: int, limit: int):
    """Shortest walk through route colour from start to a line other than own, in at most limit
    steps. Returns (steps, line index, where it met the line) or None."""
    rows, cols = routes.shape
    x0, y0 = int(round(start[0])), int(round(start[1]))
    seen, queue = set(), deque()
    for y in range(y0 - tolerance, y0 + tolerance + 1):
        for x in range(x0 - tolerance, x0 + tolerance + 1):
            if 0 <= x < cols and 0 <= y < rows and routes[y, x] and math.dist((x, y), (x0, y0)) <= tolerance:
                seen.add((x, y))
                queue.append((x, y, 0))
    while queue:
        x, y, steps = queue.popleft()
        if steps >= limit:
            continue
        for dx, dy in _NEIGHBOURS:
            nx, ny = x + dx, y + dy
            if ((nx, ny) in seen or not (0 <= nx < cols and 0 <= ny < rows) or not routes[ny, nx]
                    or labels[ny, nx] == own + 1):
                continue
            seen.add((nx, ny))
            if labels[ny, nx]:
                return steps + 1, int(labels[ny, nx]) - 1, (float(nx), float(ny))
            queue.append((nx, ny, steps + 1))
    return None


def join_by_colour(graph: PixelGraph, routes: np.ndarray, width: float, p: Params) -> PixelGraph:
    """A paint end joins the nearest line that the photo's route colour still reaches. Signs are cut
    out of the paint, and a symbol's outline can chop the line beside it into twigs that pruning
    drops, but the camera saw one line."""
    tolerance = max(4, int(width * 0.25))
    limit = int(width * p.colour_reach)
    while True:
        labels = _line_labels(graph, routes.shape, tolerance)
        degree = graph.degree()
        best = None
        for index, (a, b, pts) in enumerate(graph.edges):
            if _polyline_length(pts) < width * LINK_FROM:
                continue
            for node in (a, b):
                found = degree[node] == 1 and _colour_walk(routes, labels, graph.nodes[node], index, tolerance, limit)
                if found and (best is None or found[0] < best[0][0]):
                    best = (found, node)
        if best is None:
            return graph
        (_, hit, point), node = best
        target, _ = _attach(graph, hit, point, tolerance * 1.5)
        graph.edges.append((node, target, _straight(graph.nodes[node], graph.nodes[target])))


def _candidates(graph: PixelGraph, shortest: float):
    """Corners and ends with each axis direction that no corridor already takes. Short stubs,
    such as a hook into an EXIT sign, lead nowhere, so they start no links."""
    out = []
    touching: dict[int, list[int]] = {}
    arms: dict[int, list] = {}
    for index, (a, b, pts) in enumerate(graph.edges):
        for node, line in ((a, pts), (b, pts[::-1])):
            touching.setdefault(node, []).append(index)
            arm = _arm_direction(line)
            if arm:
                arms.setdefault(node, []).append((arm, _polyline_length(pts) >= shortest))
    for node, position in graph.nodes.items():
        if node in arms:
            out.append((position, arms[node], touching[node], node, None))
    for index, (_, _, pts) in enumerate(graph.edges):
        for at in _bends(pts):
            # Paint rounds its corners, so each arm's heading is read past the curve.
            found = [(_arm_direction(pts[at - LINK_ARM::-1]), _polyline_length(pts[: at + 1]) >= shortest),
                     (_arm_direction(pts[at + LINK_ARM:]), _polyline_length(pts[at:]) >= shortest)]
            out.append((pts[at], [arm for arm in found if arm[0]], [index], None, at))
    return out


def link_gaps(graph: PixelGraph, space: FreeSpace, p: Params) -> PixelGraph:
    """Add the corridors that paint or the strip test missed: straight, clear runs from a corner or
    an end that reach another line, or that end at a wall a short way on. A door is too narrow to
    pass, so a link can't enter a room."""
    tolerance = max(4, int(space.width * 0.25))
    lanes = int(space.width * LINK_LANE)
    rows, cols = space.blocked.shape
    used = set()
    while True:
        labels = _line_labels(graph, space.blocked.shape, tolerance)
        best = None
        corner = _corner_links(graph, space, p)
        if corner:
            _, a, b, point = corner
            middle = max(graph.nodes) + 1
            graph.nodes[middle] = (float(point[0]), float(point[1]))
            graph.edges.append((a, middle, _straight(graph.nodes[a], graph.nodes[middle])))
            graph.edges.append((b, middle, _straight(graph.nodes[b], graph.nodes[middle])))
            continue
        for position, arms, own, node, at in _candidates(graph, space.width * LINK_FROM):
            x0, y0 = int(round(position[0])), int(round(position[1]))
            dead_end = node is not None and len(arms) == 1
            for dx, dy in _AXES:
                # A corridor carries straight on. Hooks into an EXIT sign lead nowhere.
                if ((x0, y0, dx, dy) in used or any(dx * a[0] + dy * a[1] > AXIS_TAKEN for a, _ in arms)
                        or not any(long and -(dx * a[0] + dy * a[1]) > AXIS_STRAIGHT for a, long in arms)):
                    continue
                # Paint can hug a wall, so also try lanes either side of it.
                for lane in (0, lanes, -lanes):
                    lx, ly = x0 + lane * dy, y0 + lane * dx
                    if not (0 <= lx < cols and 0 <= ly < rows) or space.blocked[ly, lx]:
                        continue
                    found = _cast(graph, labels, space, p, lx, ly, dx, dy, own, 1 if dead_end else space.width,
                                  dead_end and _runs_straight(graph, node, own[0], dx, dy, space.width))
                    if found:
                        if best is None or found[1] < best[0][1]:
                            best = (found, (x0, y0), (dx, dy), node, at, own[0], (x0, y0, dx, dy))
                        break
        if best is None:
            return graph
        (kind, travelled, hit), origin, (dx, dy), node, at, own, key = best
        used.add(key)
        if kind == "line" and hit == own and at is not None:
            continue  # a corner looping back onto its own line is no new corridor
        tip = (float(origin[0] + dx * travelled), float(origin[1] + dy * travelled))
        if kind == "line":
            target, tip = _attach(graph, hit, tip, tolerance * 1.5)
        else:
            target = max(graph.nodes) + 1
            graph.nodes[target] = tip
        start = node if node is not None else _split_edge(graph, own, at)
        if start != target:
            graph.edges.append((start, target, _straight(graph.nodes[start], tip)))


def _networks(graph: PixelGraph) -> tuple[dict[int, int], dict[int, float]]:
    """Each node's network, and each network's total length."""
    parent = {n: n for n in graph.nodes}

    def find(n):
        while parent[n] != n:
            parent[n] = parent[parent[n]]
            n = parent[n]
        return n

    for a, b, _ in graph.edges:
        parent[find(a)] = find(b)
    lengths: dict[int, float] = {}
    for a, _, pts in graph.edges:
        lengths[find(a)] = lengths.get(find(a), 0.0) + _polyline_length(pts)
    return {n: find(n) for n in graph.nodes}, lengths


def drop_parallel(graph: PixelGraph, width: float) -> PixelGraph:
    """Paint can split around a symbol into two lines between the same junctions. Keep the shorter
    when the two never stray more than a corridor width apart."""
    def far(a, b):
        return max(min(math.dist(p, q) for q in b[::4]) for p in a[::4])

    while True:
        pair = None
        for i, (a, b, pts) in enumerate(graph.edges):
            for j in range(i + 1, len(graph.edges)):
                c, d, other = graph.edges[j]
                if {a, b} == {c, d} and max(far(pts, other), far(other, pts)) <= width:
                    pair = (i, j)
                    break
            if pair:
                break
        if pair is None:
            return graph
        i, j = pair
        del graph.edges[max(pair, key=lambda k: _polyline_length(graph.edges[k][2]))]


def keep_networks(graph: PixelGraph, share: float) -> PixelGraph:
    """Drop pieces that never joined the main network, unless they are large themselves."""
    network, lengths = _networks(graph)
    if not lengths:
        return graph
    limit = share * max(lengths.values())
    graph.edges = [e for e in graph.edges if lengths[network[e[0]]] >= limit]
    return _drop_isolated(graph)


def join_networks(graph: PixelGraph, space: FreeSpace, p: Params) -> PixelGraph:
    """Walk free space from the main network to a separate piece. The walk stays near the middle of
    a corridor and door gaps are closed, so it goes where a person would."""
    width = space.width
    clearance = cv2.distanceTransform(255 - space.blocked, cv2.DIST_L2, 5)
    # Closing door gaps can also pinch a narrow corridor shut, so last, walk a short way unsealed.
    open_clearance = cv2.distanceTransform(255 - space.unsealed, cv2.DIST_L2, 5)
    passes = [(clearance >= width * p.walk_margin, int(width * p.walk_reach)),
              (open_clearance >= width * p.neck_margin, int(width * p.neck_reach))]
    tolerance = max(4, int(width * 0.25))
    rows, cols = clearance.shape
    while True:
        network, lengths = _networks(graph)
        if len(lengths) < 2:
            return graph
        main = max(lengths, key=lengths.get)
        labels = _line_labels(graph, clearance.shape, tolerance)
        owner = [network[a] for a, _, _ in graph.edges]
        goal = None
        # The middle of a corridor first; open door gaps only if nothing else gets through.
        for walkable, limit in passes:
            seen = np.zeros(clearance.shape, np.uint8)
            queue = deque()
            parents: dict[tuple[int, int], tuple[int, int]] = {}
            for y, x in zip(*np.nonzero(labels)):
                if owner[labels[y, x] - 1] == main:
                    seen[y, x] = 1
                    queue.append((int(x), int(y), 0))
            while queue and goal is None:
                x, y, steps = queue.popleft()
                if steps >= limit:
                    continue
                for dx, dy in _AXES:
                    nx, ny = x + dx, y + dy
                    if not (0 <= nx < cols and 0 <= ny < rows) or seen[ny, nx]:
                        continue
                    seen[ny, nx] = 1
                    hit = labels[ny, nx]
                    if hit and lengths[owner[hit - 1]] >= width * p.walk_piece:
                        parents[(nx, ny)] = (x, y)
                        goal = (nx, ny)
                        break
                    if walkable[ny, nx]:
                        parents[(nx, ny)] = (x, y)
                        queue.append((nx, ny, steps + 1))
            if goal is not None:
                break
        if goal is None:
            return graph
        path = [goal]
        while path[-1] in parents:
            path.append(parents[path[-1]])
        far, _ = _attach(graph, labels[goal[1], goal[0]] - 1, (float(goal[0]), float(goal[1])), tolerance * 1.5)
        labels_near = _line_labels(graph, clearance.shape, tolerance)
        start = path[-1]
        if not labels_near[start[1], start[0]]:
            return graph
        near, _ = _attach(graph, labels_near[start[1], start[0]] - 1, (float(start[0]), float(start[1])), tolerance * 1.5)
        graph.edges.append((far, near, [graph.nodes[far]] + [(float(x), float(y)) for x, y in path] + [graph.nodes[near]]))
        # Overlapping lines can name the wrong piece. Stop rather than walk the same way again.
        if len(_networks(graph)[1]) >= len(lengths):
            return graph


def straighten_ends(graph: PixelGraph, length: float) -> PixelGraph:
    """Skeletons curl into the corner where a strip ends. Cut that curl so the end points along
    its corridor and can link onward."""
    degree = graph.degree()
    for index in range(len(graph.edges)):
        for forward in (True, False):
            a, b, pts = graph.edges[index]  # re-read: cutting one end changes the line
            tip, line = (a, pts) if forward else (b, pts[::-1])
            if degree[tip] != 1 or _polyline_length(line) <= 2 * length:
                continue
            travelled, cut = 0.0, 0
            for i in range(1, len(line)):
                travelled += math.dist(line[i - 1], line[i])
                if travelled >= length:
                    cut = i
                    break
            inner = _arm_direction(line[cut:])
            outer = (line[cut][0] - line[0][0], line[cut][1] - line[0][1])
            norm = math.hypot(*outer) or 1.0
            if inner and (inner[0] * outer[0] + inner[1] * outer[1]) / norm > STRAIGHT_COSINE:
                continue
            line = line[cut:]
            graph.nodes[tip] = line[0]
            pts = line if forward else line[::-1]
            graph.edges[index] = (a, b, pts)
    return graph


def fill_holes(mask: np.ndarray, area: float) -> np.ndarray:
    """Fill small holes so a wide, patchy band thins to one line, not a ladder of short loops.
    Holes around a room or a block are far larger and stay."""
    count, labels, stats, _ = cv2.connectedComponentsWithStats(255 - mask, connectivity=4)
    small = stats[:, cv2.CC_STAT_AREA] < area
    small[np.unique(np.concatenate([labels[0], labels[-1], labels[:, 0], labels[:, -1]]))] = False
    return mask | (small[labels].astype(np.uint8) * 255)


def _graph_from_mask(mask, spur, merge, p, stars=()) -> PixelGraph:
    graph = skeleton_graph(fill_holes(mask, HOLE_AREA))
    graph = prune_spurs(graph, spur, stars, p.marker_spur_length)
    graph = merge_close_nodes(graph, merge)
    graph = contract_degree_two(graph)
    return refine_junctions(graph, p.refine_near, p.refine_far, p.refine_max_move)


def extract_graph(bgr: np.ndarray, p: Params | None = None, stars=()) -> tuple[PixelGraph, np.ndarray]:
    """Painted posters trace their paint. Posters without paint trace narrow free space. Either
    way, corridors the first pass missed are linked in using the walls."""
    p = p or Params()
    mask = corridor_mask(bgr, p)
    graph = _graph_from_mask(mask, p.spur_length, p.merge_radius, p, stars)
    lengths = _networks(graph)[1].values()
    total = sum(lengths)
    painted = (total >= p.min_route_length and max(lengths) >= p.paint_share * total
               and in_fills(bgr) < p.fill_share)
    space = free_space(bgr, p, mask if painted else None)
    if not painted:
        mask = strip_mask(space, p)
        graph = _graph_from_mask(mask, space.width * 2.5, space.width * 0.8, p)
        graph = straighten_ends(graph, space.width * 1.5)
    else:
        graph = join_facing_ends(graph, space.width, p)
        graph = join_by_colour(graph, route_mask(bgr), space.width, p)
    graph = link_gaps(graph, space, p)
    graph = join_networks(graph, space, p)
    graph = drop_parallel(graph, space.width)
    graph = contract_degree_two(graph)
    graph = keep_networks(graph, p.keep_network)
    graph.width = float(space.width)
    return graph, mask


def _simplify(width: float) -> float:
    """Symbols on a line bend the trace; bumps smaller than this come out, corners stay."""
    return float(np.clip(width * 0.2, 2.0, 10.0))


def edge_polyline(start, end, pts, tolerance: float = 2.0) -> list[tuple[float, float]]:
    """Polyline from node to node that keeps to the traced line. A moved junction joins the line
    where it passes closest, so a straight corridor bends at its end and never tilts."""
    def trim(line, node):
        reach = 2 * math.dist(line[0], node)
        travelled, nearest, best = 0.0, 0, math.dist(line[0], node)
        for i in range(1, len(line)):
            travelled += math.dist(line[i - 1], line[i])
            if travelled > reach:
                break
            distance = math.dist(line[i], node)
            if distance < best:
                nearest, best = i, distance
        return [node] + list(line[nearest:])

    line = trim(list(pts), start)
    line = trim(line[::-1], end)[::-1]
    if len(line) < 2:
        return [start, end]
    simple = cv2.approxPolyDP(np.asarray(line, np.float32).reshape(-1, 1, 2), tolerance, False).reshape(-1, 2)
    simple = [(float(x), float(y)) for x, y in simple]
    simple[0], simple[-1] = start, end
    return simple


def to_floorgraph_json(graph: PixelGraph, building="engineering-hall", floor="3", source=None) -> dict:
    """FloorGraph JSON (schemaVersion 1); unverified, START left to the YAH detector."""
    deg = graph.degree()
    ids = {n: f"{'J' if deg[n] >= 3 else 'X'}{i + 1}" for i, n in enumerate(sorted(graph.nodes))}
    nodes = [{"id": ids[n], "type": "JUNCTION" if deg[n] >= 3 else "DEAD_END",
              "x": round(x, 1), "y": round(y, 1)} for n, (x, y) in sorted(graph.nodes.items())]
    edges = []
    for i, (a, b, pts) in enumerate(graph.edges):
        simple = edge_polyline(graph.nodes[a], graph.nodes[b], pts, _simplify(graph.width))
        edges.append({"id": f"e{i + 1}", "from": ids[a], "to": ids[b],
                      "polyline": [[round(x, 1), round(y, 1)] for x, y in simple],
                      "lengthPx": round(_polyline_length(simple), 1)})
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
    graph, mask = extract_graph(bgr)
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
    graph, mask = extract_graph(bgr)
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
    p.set_defaults(func=cmd_extract)
    p = sub.add_parser("score", help="compare a graph JSON with a hand-traced one")
    p.add_argument("pred")
    p.add_argument("truth")
    p.set_defaults(func=cmd_score)
    p = sub.add_parser("evaluate", help="extract from a map and score against its trace")
    p.add_argument("map")
    p.add_argument("truth")
    p.add_argument("--debug", help="overlay: green = traced, magenta = extracted")
    p.set_defaults(func=cmd_evaluate)
    args = parser.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
