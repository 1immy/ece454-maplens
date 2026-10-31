#!/usr/bin/env python3
"""Offline room OCR on a straightened poster; attach to corridors.py graph JSON.

extract MAP --graph GRAPH -o OUT [--debug overlay.png] [--readings readings.json]
evaluate MAP --graph GRAPH --truth TRACE -o OUT [--debug overlay.png]
score READINGS PRED TRACE
All coordinates and distance parameters use pixels of the exact input image.
"""
from __future__ import annotations

import argparse
import copy
import csv
import io
import json
import math
import re
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

import cv2
import numpy as np
from scipy.optimize import linear_sum_assignment

from maplabel import PREFIX, load_image


@dataclass
class Params:
    # shortcut: pixel defaults target 2000 px posters; rescale/tune for other resolutions.
    scale: float = 2.0
    crop_scale: float = 3.0
    threshold_window: int = 31
    threshold_offset: int = 12
    glyph_height: tuple[int, int] = (10, 45)
    glyph_max_width: int = 160
    glyph_area: tuple[int, int] = (12, 4000)
    word_width: tuple[int, int] = (30, 180)
    word_height: tuple[int, int] = (12, 50)
    word_gap: int = 11
    padding: int = 5
    min_confidence: float = 0.5
    duplicate_radius: float = 15.0
    duplicate_overlap: float = 0.5
    max_distance: float = 180.0
    node_snap: float = 8.0


@dataclass
class Reading:
    text: str
    box: tuple[float, float, float, float]  # x, y, width, height, original image
    confidence: float

    @property
    def center(self):
        x, y, w, h = self.box
        return x + w / 2, y + h / 2


def normalize_label(text: str) -> str | None:
    """Four digits + optional suffix. Ambiguous O/0 and I/1 need human review."""
    label = re.sub(r"[\s.-]", "", text.strip().strip(",:;()[]|")).upper()
    return label if re.fullmatch(r"[0-9]{4}[A-Z]?", label) else None


def _overlap_fraction(a, b):
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    intersection = max(0, min(ax + aw, bx + bw) - max(ax, bx)) * max(0, min(ay + ah, by + bh) - max(ay, by))
    smaller = min(aw * ah, bw * bh)
    return intersection / smaller if smaller > 0 else 0


def room_labels(readings: list[Reading], p: Params | None = None) -> list[Reading]:
    p = p or Params()
    kept = []
    for r in sorted(readings, key=lambda r: r.confidence, reverse=True):
        label = normalize_label(r.text)
        if label and r.confidence >= p.min_confidence:
            if not any(math.dist(r.center, other.center) <= p.duplicate_radius or
                       (label == other.text and _overlap_fraction(r.box, other.box) >= p.duplicate_overlap)
                       for other in kept):
                kept.append(Reading(label, r.box, r.confidence))
    return sorted(kept, key=lambda r: (r.center[1], r.center[0]))


def text_regions(bgr: np.ndarray, p: Params) -> list[tuple[int, int, int, int]]:
    """Group text-sized dark components; reject long walls and tiny hatch dots."""
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    bw = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                               cv2.THRESH_BINARY_INV, p.threshold_window, p.threshold_offset)
    _, components, stats, _ = cv2.connectedComponentsWithStats(bw)
    keep = np.array([p.glyph_height[0] <= h <= p.glyph_height[1] and w <= p.glyph_max_width
                     and p.glyph_area[0] <= area <= p.glyph_area[1] for x, y, w, h, area in stats])
    keep[0] = False
    joined = cv2.dilate(keep[components].astype(np.uint8), np.ones((3, p.word_gap), np.uint8))
    _, _, words, _ = cv2.connectedComponentsWithStats(joined)
    height, width = gray.shape
    boxes = []
    for x, y, w, h, _ in words[1:]:
        if p.word_width[0] <= w <= p.word_width[1] and p.word_height[0] <= h <= p.word_height[1] and w >= 1.5 * h:
            x0, y0 = max(0, x - p.padding), max(0, y - p.padding)
            boxes.append((x0, y0, min(width, x + w + p.padding) - x0,
                          min(height, y + h + p.padding) - y0))
    return boxes


def tesseract_words(image: np.ndarray, scale: float, origin=(0, 0), psm=11) -> list[Reading]:
    """Invoke offline English OCR; decode TSV coordinates back to image pixels."""
    image = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
    encoded = cv2.imencode(".png", image)[1].tobytes()
    try:
        proc = subprocess.run(["tesseract", "stdin", "stdout", "-l", "eng", "--psm", str(psm), "tsv"],
                              input=encoded, capture_output=True, timeout=120, check=True)
    except FileNotFoundError as e:
        raise ValueError("Tesseract not found; install tesseract with English language data") from e
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
        detail = (e.stderr or b"").decode(errors="replace").strip()
        raise ValueError(f"Tesseract failed: {detail or e}") from e
    out = []
    for row in csv.DictReader(io.StringIO(proc.stdout.decode()), delimiter="\t"):
        if row["level"] == "5" and row["text"].strip():
            x, y, w, h = (float(row[k]) / scale for k in ("left", "top", "width", "height"))
            out.append(Reading(row["text"], (x + origin[0], y + origin[1], w, h), float(row["conf"]) / 100))
    return out


def read_tesseract(bgr: np.ndarray, p: Params | None = None) -> list[Reading]:
    """Full poster + isolated word crops. Extraction never reads reference labels."""
    p = p or Params()
    out = tesseract_words(bgr, p.scale)
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    for x, y, w, h in text_regions(bgr, p):
        crop = cv2.copyMakeBorder(gray[y:y + h, x:x + w], p.padding, p.padding,
                                 p.padding, p.padding, cv2.BORDER_CONSTANT, value=255)
        out.extend(tesseract_words(crop, p.crop_scale, (x - p.padding, y - p.padding), psm=7))
    return out


def _point(node):
    return [node["x"], node["y"]]


def _length(pts):
    return sum(math.dist(a, b) for a, b in zip(pts, pts[1:]))


def _line(edge, nodes):
    pts = edge.get("polyline") or [_point(nodes[edge["from"]]), _point(nodes[edge["to"]])]
    # The corridor extractor refines nodes after skeletonizing; align edge ends with them.
    return [_point(nodes[edge["from"]]), *pts[1:-1], _point(nodes[edge["to"]])]


def validate_graph(data: dict):
    """Reject malformed geometry/references before mutating a copy of the graph."""
    try:
        if data["schemaVersion"] != 1:
            raise ValueError("unsupported schemaVersion")
        for key in ("buildingId", "floor", "startNodeId"):
            if not isinstance(data[key], str):
                raise ValueError(f"{key} must be a string")
        if "source" in data and not isinstance(data["source"], dict):
            raise ValueError("source must be an object")
        nodes = {n["id"]: n for n in data["nodes"]}
        if len(nodes) != len(data["nodes"]):
            raise ValueError("duplicate node ids")
        if data["startNodeId"] not in nodes:
            raise ValueError("start node does not exist")
        for n in nodes.values():
            if n["type"] not in PREFIX or not all(type(v) in (int, float) and math.isfinite(v) for v in _point(n)):
                raise ValueError("invalid node type or coordinates")
        adjacency = {nid: [] for nid in nodes}
        ids = set()
        for i, e in enumerate(data["edges"]):
            eid = e.get("id", f"e{i + 1}")
            if eid in ids:
                raise ValueError("duplicate edge ids")
            ids.add(eid)
            pts = e.get("polyline") or [_point(nodes[e["from"]]), _point(nodes[e["to"]])]
            if len(pts) < 2 or any(len(pt) != 2 or not all(type(v) in (int, float) and math.isfinite(v) for v in pt) for pt in pts):
                raise ValueError("invalid edge polyline")
            length = e.get("lengthPx", _length(pts))
            if type(length) not in (int, float) or not math.isfinite(length) or length <= 0 or _length(_line(e, nodes)) <= 0:
                raise ValueError("edge has non-positive or invalid length")
            adjacency[e["from"]].append(e["to"])
            adjacency[e["to"]].append(e["from"])
        for landmark in data.get("landmarks", []):
            if landmark["nodeId"] not in nodes or not isinstance(landmark["label"], str):
                raise ValueError("invalid landmark reference or label")
            confidence = landmark.get("ocrConfidence", 1.0)
            if type(confidence) not in (int, float) or not math.isfinite(confidence) or not 0 <= confidence <= 1:
                raise ValueError("ocrConfidence must be a finite number between 0 and 1")
        seen, stack = {data["startNodeId"]}, [data["startNodeId"]]
        while stack:
            for nxt in adjacency[stack.pop()]:
                if nxt not in seen:
                    seen.add(nxt)
                    stack.append(nxt)
        if seen != set(nodes):
            raise ValueError("nodes not connected to start")
    except (KeyError, TypeError, AttributeError) as e:
        raise ValueError(f"malformed graph: {e}") from e


def _nearest(point, edges, nodes):
    best = None
    for i, edge in enumerate(edges):
        pts = _line(edge, nodes)
        for j, (a, b) in enumerate(zip(pts, pts[1:])):
            delta = np.subtract(b, a)
            length2 = float(delta @ delta)
            if length2 == 0:
                continue
            t = float(np.clip(np.dot(np.subtract(point, a), delta) / length2, 0, 1))
            q = (np.asarray(a) + t * delta).tolist()
            distance = math.dist(point, q)
            if best is None or distance < best[0]:
                best = distance, i, j, q
    return best


def attach_labels(data: dict, readings: list[Reading], p: Params | None = None):
    """Project labels onto nearest corridor, splitting edges without moving rooms."""
    p = p or Params()
    validate_graph(data)
    out = copy.deepcopy(data)
    out["verified"] = False
    out.setdefault("landmarks", [])
    nodes = {n["id"]: n for n in out["nodes"]}
    used = set(nodes) | {e.get("id", f"e{i + 1}") for i, e in enumerate(out["edges"])}

    def new_id(prefix):
        i = 1
        while f"{prefix}{i}" in used:
            i += 1
        value = f"{prefix}{i}"
        used.add(value)
        return value

    for i, edge in enumerate(out["edges"]):
        edge.setdefault("id", f"e{i + 1}")
        edge["polyline"] = _line(edge, nodes)
        edge["lengthPx"] = _length(edge["polyline"])
    attachments = []
    for r in readings:
        record = {"label": r.text, "center": list(r.center), "nodeId": None, "point": None}
        attachments.append(record)
        nearest = _nearest(r.center, out["edges"], nodes)
        if nearest is None or nearest[0] > p.max_distance:
            record["reason"] = "no corridor within maximum attachment distance"
            continue
        distance, ei, si, q = nearest
        edge = out["edges"][ei]
        pts = edge["polyline"]
        # Reuse only endpoints of this edge: never jump to a nearby parallel corridor.
        nid = next((n for n in (edge["from"], edge["to"])
                    if math.dist(q, _point(nodes[n])) <= p.node_snap), None)
        if nid is None:
            nid = new_id("ocrD")
            node = {"id": nid, "type": "DOOR", "x": q[0], "y": q[1]}
            nodes[nid] = node
            out["nodes"].append(node)
            left, right = pts[:si + 1] + [q], [q] + pts[si + 1:]
            out["edges"][ei:ei + 1] = [
                {**edge, "id": new_id("ocrE"), "to": nid, "polyline": left, "lengthPx": _length(left)},
                {**edge, "id": new_id("ocrE"), "from": nid, "polyline": right, "lengthPx": _length(right)}]
        elif nodes[nid]["type"] == "DEAD_END":
            nodes[nid]["type"] = "DOOR"
        if not any(l["label"] == r.text and l["nodeId"] == nid for l in out["landmarks"]):
            out["landmarks"].append({"label": r.text, "nodeId": nid, "ocrConfidence": r.confidence})
        record.update(nodeId=nid, point=_point(nodes[nid]), distancePx=distance)
    validate_graph(out)
    return out, attachments


def score(readings: list[Reading], pred: dict, truth: dict, radius: float = 40.0) -> dict:
    """Distinct text recall and one-to-one (label, door position) matching."""
    def entrances(data):
        nodes = {n["id"]: _point(n) for n in data["nodes"]}
        return {(normalize_label(l["label"]), l["nodeId"]): nodes[l["nodeId"]]
                for l in data.get("landmarks", []) if normalize_label(l["label"])}

    pp, tt = entrances(pred), entrances(truth)
    recognized = {normalize_label(r.text) for r in readings} - {None}
    true_labels = {label for label, _ in tt}
    matched, errors = 0, []
    for label in true_labels:
        ps = [pt for (text, _), pt in pp.items() if text == label]
        ts = [pt for (text, _), pt in tt.items() if text == label]
        if not ps:
            continue
        distances = np.array([[math.dist(a, b) for b in ts] for a in ps])
        rows, cols = linear_sum_assignment(np.where(distances <= radius, distances, 1e9))
        good = distances[rows, cols][distances[rows, cols] <= radius]
        matched += len(good)
        errors.extend(good.tolist())
    text_matched = len(recognized & true_labels)
    return {"room_labels_true": len(true_labels), "room_entrances_true": len(tt),
            "text_pred": len(recognized), "text_matched": text_matched,
            "text_precision": text_matched / len(recognized) if recognized else 0,
            "text_recall": text_matched / len(true_labels) if true_labels else 0,
            "attachments_pred": len(pp), "attachments_matched": matched,
            "attachment_precision": matched / len(pp) if pp else 0,
            "attachment_recall": matched / len(tt) if tt else 0,
            "attachment_radius_px": radius,
            "matched_error_median_px": float(np.median(errors)) if errors else None,
            "missing_text": sorted(true_labels - recognized),
            "extra_text": sorted(recognized - true_labels)}


def draw_overlay(bgr, readings, attachments, graph):
    out = bgr.copy()
    nodes = {n["id"]: n for n in graph["nodes"]}
    for e in graph["edges"]:
        cv2.polylines(out, [np.int32(_line(e, nodes))], False, (200, 80, 200), 2)
    for r, a in zip(readings, attachments):
        x, y, w, h = map(round, r.box)
        color = (0, 160, 0) if a["nodeId"] else (0, 0, 220)
        cv2.rectangle(out, (x, y), (x + w, y + h), color, 2)
        cv2.putText(out, r.text, (x, y - 4), cv2.FONT_HERSHEY_SIMPLEX, .5, color, 1)
        if a["point"]:
            q = tuple(round(v) for v in a["point"])
            cv2.line(out, tuple(round(v) for v in r.center), q, color, 1)
            cv2.circle(out, q, 5, color, -1)
    return out


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    for command in ("extract", "evaluate"):
        cli = sub.add_parser(command)
        cli.add_argument("map")
        cli.add_argument("--graph", required=True, help="corridors.py graph in this image's pixel coordinates")
        cli.add_argument("-o", "--output", required=True)
        cli.add_argument("--debug")
        cli.add_argument("--readings", help="OCR boxes, confidence and attachment audit JSON")
        cli.add_argument("--max-distance", type=float, default=Params().max_distance)
        if command == "evaluate":
            cli.add_argument("--truth", required=True, help="matching hand trace; used only after extraction")
    cli = sub.add_parser("score")
    cli.add_argument("readings")
    cli.add_argument("pred")
    cli.add_argument("truth")
    args = parser.parse_args(argv)
    try:
        if args.command == "score":
            saved = json.loads(Path(args.readings).read_text())
            readings = [Reading(**r) for r in saved["labels"]]
            pred = json.loads(Path(args.pred).read_text())
        else:
            if not math.isfinite(args.max_distance) or args.max_distance <= 0:
                raise ValueError("max-distance must be positive and finite")
            inputs = [args.map, args.graph] + ([args.truth] if args.command == "evaluate" else [])
            outputs = [v for v in (args.output, args.debug, args.readings) if v]
            if len({Path(v).resolve() for v in inputs + outputs}) != len(inputs + outputs):
                raise ValueError("input and output paths must be distinct")
            bgr = cv2.cvtColor(np.asarray(load_image(args.map)), cv2.COLOR_RGB2BGR)
            data = json.loads(Path(args.graph).read_text())
            validate_graph(data)
            if data.get("source", {}).get("size") not in (None, list((bgr.shape[1], bgr.shape[0]))):
                raise ValueError("graph source size does not match image; use the exact straightened map")
            p = Params(max_distance=args.max_distance)
            raw = read_tesseract(bgr, p)
            readings = room_labels(raw, p)
            pred, attachments = attach_labels(data, readings, p)
            Path(args.output).write_text(json.dumps(pred, indent=2) + "\n")
            if args.readings:
                Path(args.readings).write_text(json.dumps({"raw": [asdict(r) for r in raw],
                    "labels": [asdict(r) for r in readings], "attachments": attachments}, indent=2) + "\n")
            if args.debug and not cv2.imwrite(args.debug, draw_overlay(bgr, readings, attachments, pred)):
                raise ValueError(f"could not write overlay: {args.debug}")
            print(f"{len(readings)} room readings, {sum(a['nodeId'] is not None for a in attachments)} attached -> {args.output}")
        if args.command in ("score", "evaluate"):
            truth = json.loads(Path(args.truth).read_text())
            validate_graph(pred)
            validate_graph(truth)
            print(json.dumps(score(readings, pred, truth), indent=2))
    except (OSError, ValueError, KeyError, TypeError, cv2.error) as e:
        parser.exit(2, f"room labels: {e}\n")


if __name__ == "__main__":
    main()
