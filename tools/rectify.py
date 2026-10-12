#!/usr/bin/env python3
"""Automatic map rectification prototype (Gantt task 2).

Finds the blue map frame in a wall-map photo, fits its four corners, and
straightens it with a homography. Both Engineering Hall poster styles use a
light-blue frame: the street-name border on evacuation posters and the
"3rd Floor" panel on directory maps.

Pipeline
  1. Downscale to ~1500 px on the long side.
  2. Blue mask: CIE Lab b* channel below a threshold set 7 units under the
     wall's own b* (measured on the photo border), so lighting changes don't
     matter. Blue is b* < 128; the warm wall and white paper sit above it.
  3. Remove speckle (no gap closing), fill holes (the white map inside the frame) *before*
     joining gaps, so nearby blue boxes such as the legend stay separate.
     Keep the blob with the largest convex hull: a directory panel whose map
     touches its edge is not a closed ring, but its hull is still the panel.
  4. Rough quad from the convex hull, then refine each side by fitting a line
     to the hull points near it and intersecting neighbouring lines. This
     looks past screws, glare dents and rounded corners.
  5. Scale back; warp with the same homography code as maplabel.py.

Commands
  detect    python tools/rectify.py detect PHOTO -o flat.jpg [--debug overlay.jpg]
  evaluate  python tools/rectify.py evaluate data/fixtures/photos/ehall/*.jpg \\
                --corners data/fixtures/rectify/ehall-corners.json [--debug-dir out/]

`evaluate` reports per-photo corner error against the hand labels, in pixels
and as a percentage of the labeled quad's diagonal.
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from maplabel import load_image, order_corners, warp  # noqa: E402

WORK_SIZE = 1500          # long side of the working image, px (1000 merges the legend into the frame)
MIN_FILL = 0.85           # below this the blob is not a clean quad: ask the user to retake
MIN_AREA_FRACTION = 0.03  # reject blobs smaller than this share of the image


@dataclass
class Detection:
    corners: list[tuple[float, float]] | None  # TL, TR, BR, BL in full-res px
    score: float                                # blob fill ratio vs its quad, 0..1
    reason: str = ""


def wall_reference(b: np.ndarray) -> float:
    """Median b* of the outer 6% border of the photo, which is almost always wall."""
    h, w = b.shape
    m = max(2, round(0.06 * min(h, w)))
    border = np.concatenate([b[:m].ravel(), b[-m:].ravel(), b[:, :m].ravel(), b[:, -m:].ravel()])
    return float(np.median(border))


def adaptive_threshold(b: np.ndarray) -> int:
    """Blue sits ~10 b* units below the wall; cut 7 below the wall, within sane bounds."""
    return int(np.clip(round(wall_reference(b) - 7), 121, 128))


def blue_mask(bgr_small: np.ndarray, threshold: int | None = None) -> np.ndarray:
    lab = cv2.cvtColor(bgr_small, cv2.COLOR_BGR2LAB)
    b = cv2.GaussianBlur(lab[..., 2], (5, 5), 0)
    if threshold is None:
        threshold = adaptive_threshold(b)
    return (b < threshold).astype(np.uint8) * 255


def fill_holes(mask: np.ndarray) -> np.ndarray:
    h, w = mask.shape
    flood = mask.copy()
    canvas = np.zeros((h + 2, w + 2), np.uint8)
    cv2.floodFill(flood, canvas, (0, 0), 255)
    return mask | cv2.bitwise_not(flood)


def rough_quad(hull: np.ndarray) -> np.ndarray | None:
    perimeter = cv2.arcLength(hull, True)
    for eps in np.linspace(0.01, 0.1, 19):
        approx = cv2.approxPolyDP(hull, eps * perimeter, True)
        if len(approx) == 4:
            return approx.reshape(4, 2).astype(float)
    box = cv2.boxPoints(cv2.minAreaRect(hull))
    return box.astype(float)


def _line_intersection(l1, l2):
    (vx1, vy1, x1, y1), (vx2, vy2, x2, y2) = l1, l2
    a = np.array([[vx1, -vx2], [vy1, -vy2]])
    if abs(np.linalg.det(a)) < 1e-9:
        return None
    t, _ = np.linalg.solve(a, [x2 - x1, y2 - y1])
    return x1 + t * vx1, y1 + t * vy1


def refine_quad(contour: np.ndarray, quad: np.ndarray) -> np.ndarray:
    """Fit a line per side to contour points close to that side; intersect."""
    pts = contour.reshape(-1, 2).astype(float)
    quad = np.asarray(order_corners(quad))
    lines = []
    for i in range(4):
        p, q = quad[i], quad[(i + 1) % 4]
        edge = q - p
        length = np.linalg.norm(edge)
        if length < 1:
            return quad
        u = edge / length
        n = np.array([-u[1], u[0]])
        rel = pts - p
        along = rel @ u
        across = np.abs(rel @ n)
        # middle 80% of the side, within 1.5% of the side length
        keep = (along > 0.1 * length) & (along < 0.9 * length) & (across < max(3.0, 0.015 * length))
        side = pts[keep]
        if len(side) < 10:
            lines.append((u[0], u[1], p[0], p[1]))
            continue
        vx, vy, x0, y0 = cv2.fitLine(side.astype(np.float32), cv2.DIST_HUBER, 0, 0.01, 0.01).ravel()
        lines.append((vx, vy, x0, y0))
    corners = []
    for i in range(4):
        hit = _line_intersection(lines[i - 1], lines[i])
        if hit is None:
            return quad
        corners.append(hit)
    return np.asarray(corners)


def detect(bgr: np.ndarray, threshold: int | None = None) -> tuple[Detection, dict]:
    h, w = bgr.shape[:2]
    scale = WORK_SIZE / max(h, w)
    small = cv2.resize(bgr, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA)
    debug: dict = {"small": small}

    mask = blue_mask(small, threshold)
    # Open only: closing gaps glues the legend box onto the evacuation frame.
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    filled = fill_holes(mask)
    debug["mask"] = filled

    contours, _ = cv2.findContours(filled, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    contours = [c for c in contours if cv2.contourArea(cv2.convexHull(c)) >= MIN_AREA_FRACTION * filled.size]
    if not contours:
        return Detection(None, 0.0, "no blue region large enough"), debug
    contour = max(contours, key=lambda c: cv2.contourArea(cv2.convexHull(c)))
    hull = cv2.convexHull(contour)
    quad = refine_quad(hull, rough_quad(hull))

    quad_area = cv2.contourArea(quad.astype(np.float32))
    score = float(cv2.countNonZero(cv2.drawContours(np.zeros_like(filled), [contour], -1, 255, -1) & filled) / quad_area) if quad_area > 0 else 0.0
    corners = [(x / scale, y / scale) for x, y in order_corners(quad)]
    debug["quad_small"] = quad
    score = min(score, 1.0)
    reason = ""
    if touches_border(contour, filled.shape):
        reason = "map is cut off at the edge of the photo; step back and retake"
        score = min(score, MIN_FILL - 0.01)
    elif score < MIN_FILL:
        reason = "map frame not cleanly visible; retake the photo"
    return Detection(corners, score, reason), debug


def touches_border(contour: np.ndarray, shape, margin: int = 2, fraction: float = 0.02) -> bool:
    """True if a noticeable share of the blob outline runs along the photo edge."""
    h, w = shape[:2]
    pts = contour.reshape(-1, 2)
    on_edge = (pts[:, 0] <= margin) | (pts[:, 1] <= margin) | (pts[:, 0] >= w - 1 - margin) | (pts[:, 1] >= h - 1 - margin)
    return bool(on_edge.mean() > fraction)


def corner_error(pred, truth) -> tuple[float, float]:
    """Mean corner distance in px and as % of the labeled quad's diagonal."""
    p, t = np.asarray(pred), np.asarray(truth)
    mean = float(np.linalg.norm(p - t, axis=1).mean())
    diag = float(max(np.linalg.norm(t[2] - t[0]), np.linalg.norm(t[3] - t[1])))
    return mean, 100 * mean / diag


def draw_overlay(bgr: np.ndarray, pred, truth=None) -> np.ndarray:
    out = bgr.copy()
    lw = max(2, round(max(bgr.shape[:2]) / 600))
    if truth is not None:
        cv2.polylines(out, [np.asarray(truth, np.int32)], True, (0, 200, 0), lw)
    if pred is not None:
        cv2.polylines(out, [np.asarray(pred, np.int32)], True, (255, 0, 255), lw)
        for x, y in pred:
            cv2.circle(out, (int(x), int(y)), 4 * lw, (255, 0, 255), lw)
    return out


def _read_upright_bgr(path: str) -> np.ndarray:
    rgb = np.asarray(load_image(path))  # applies EXIF rotation, same frame as the labels
    return cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)


def cmd_detect(args):
    bgr = _read_upright_bgr(args.photo)
    det, _ = detect(bgr, args.threshold)
    if det.corners is None:
        sys.exit(f"no map found: {det.reason}")
    if det.reason:
        print(f"warning: {det.reason} (fill {det.score:.2f})", file=sys.stderr)
    print(json.dumps({"corners": [[round(x, 1), round(y, 1)] for x, y in det.corners], "score": round(det.score, 3)}))
    if args.output:
        flat = warp(load_image(args.photo), det.corners, args.width)
        flat.save(args.output, quality=95)
        print(f"-> {args.output} ({flat.size[0]}x{flat.size[1]})")
    if args.debug:
        cv2.imwrite(args.debug, draw_overlay(bgr, det.corners))


def cmd_evaluate(args):
    labels = json.loads(Path(args.corners).read_text())["images"]
    debug_dir = Path(args.debug_dir) if args.debug_dir else None
    if debug_dir:
        debug_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for path in args.photos:
        name = Path(path).name
        if name not in labels:
            continue
        truth = labels[name]["corners"]
        bgr = _read_upright_bgr(path)
        det, _ = detect(bgr, args.threshold)
        if det.corners is None:
            rows.append((name, None, None, det.score, det.reason))
        else:
            px, pct = corner_error(det.corners, truth)
            rows.append((name, px, pct, det.score, "RETAKE: " + det.reason if det.reason else ""))
        if debug_dir:
            small = cv2.resize(draw_overlay(bgr, det.corners, truth), None, fx=0.33, fy=0.33)
            cv2.imwrite(str(debug_dir / name), small)

    print(f"{'photo':42s} {'err px':>8s} {'err %diag':>9s} {'fill':>5s}")
    for name, px, pct, score, reason in rows:
        if px is None:
            print(f"{name:42s} {'FAIL':>8s} {'':>9s} {score:5.2f}  {reason}")
        else:
            print(f"{name:42s} {px:8.1f} {pct:8.2f}% {score:5.2f}  {reason}")
    ok = [r for r in rows if r[1] is not None and not r[4]]
    flagged = len(rows) - len(ok)
    if flagged:
        print(f"\n{flagged} photo(s) flagged for retake; excluded from the summary")
    if ok:
        pcts = np.array([r[2] for r in ok])
        print(f"{len(ok)}/{len(rows)} accepted | median {np.median(pcts):.2f}% | "
              f"mean {pcts.mean():.2f}% | under 1%: {(pcts < 1).sum()} | under 2%: {(pcts < 2).sum()}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("detect", help="find and straighten the map in one photo")
    p.add_argument("photo")
    p.add_argument("-o", "--output", help="write the straightened image here")
    p.add_argument("--width", type=int, default=2000)
    p.add_argument("--debug", help="write an overlay of the detected quad here")
    p.add_argument("--threshold", type=int, default=None, help="fixed Lab b* cut (default: adaptive)")
    p.set_defaults(func=cmd_detect)

    p = sub.add_parser("evaluate", help="score detection against hand-labeled corners")
    p.add_argument("photos", nargs="+")
    p.add_argument("--corners", required=True)
    p.add_argument("--debug-dir", help="write overlays (green = label, magenta = detected)")
    p.add_argument("--threshold", type=int, default=None, help="fixed Lab b* cut (default: adaptive)")
    p.set_defaults(func=cmd_evaluate)

    args = parser.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
