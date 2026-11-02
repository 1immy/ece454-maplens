#!/usr/bin/env python3
"""Offline gold-star detection and START attachment on straightened evac posters."""
from __future__ import annotations

import argparse
import copy
import json
import math
from dataclasses import asdict, dataclass
from pathlib import Path

import cv2
import numpy as np

from corridors import _polyline_length


@dataclass
class Params:
    # shortcut: pixel defaults target 2000 px posters; rescale for other resolutions.
    gold_low: tuple = (14, 90, 70)
    gold_high: tuple = (38, 255, 255)
    blue_low: tuple = (95, 80, 25)
    blue_high: tuple = (135, 255, 255)
    close_size: int = 3
    area: tuple = (250, 2000)
    aspect: tuple = (0.65, 1.5)
    solidity: tuple = (0.35, 0.80)
    min_deep_defects: int = 4
    defect_fraction: float = 0.07
    context_radius: float = 0.8
    min_blue_fraction: float = 0.15
    max_distance: float = 100.0
    reuse_radius: float = 2.0


def detect_marker(bgr: np.ndarray, p: Params | None = None) -> dict:
    p = p or Params()
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    gold = cv2.inRange(hsv, p.gold_low, p.gold_high)
    gold = cv2.morphologyEx(gold, cv2.MORPH_CLOSE, np.ones((p.close_size, p.close_size), np.uint8))
    blue = cv2.inRange(hsv, p.blue_low, p.blue_high)
    contours, _ = cv2.findContours(gold, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    candidates = []
    for contour in contours:
        area = cv2.contourArea(contour)
        if not p.area[0] <= area <= p.area[1]:
            continue
        x, y, w, h = cv2.boundingRect(contour)
        solidity = area / cv2.contourArea(cv2.convexHull(contour))
        if not (p.aspect[0] <= w / h <= p.aspect[1] and p.solidity[0] <= solidity <= p.solidity[1]):
            continue
        defects = cv2.convexityDefects(contour, cv2.convexHull(contour, returnPoints=False))
        deep = 0 if defects is None else sum(d[3] / 256 > p.defect_fraction * max(w, h) for d in defects.reshape(-1, 4))
        center = [x + (w - 1) / 2, y + (h - 1) / 2]
        radius = max(w, h) * p.context_radius
        yy, xx = np.ogrid[:bgr.shape[0], :bgr.shape[1]]
        disk = (xx - center[0]) ** 2 + (yy - center[1]) ** 2 <= radius ** 2
        blue_fraction = float(np.mean(blue[disk] > 0))
        if deep >= p.min_deep_defects and blue_fraction >= p.min_blue_fraction:
            candidates.append({'center': center, 'box': [x, y, w, h], 'area': area,
                               'solidity': solidity, 'deepDefects': int(deep), 'blueFraction': blue_fraction})
    candidates.sort(key=lambda c: c['center'])
    status = 'detected' if len(candidates) == 1 else 'missing' if not candidates else 'ambiguous'
    return {'status': status, 'marker': candidates[0]['center'] if status == 'detected' else None,
            'candidates': candidates}


def _finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def graph_geometry(data: dict):
    """Validate references/geometry before mutating a copied FloorGraph document."""
    if data.get('schemaVersion') != 1 or not all(isinstance(data.get(k), str) and data[k]
                                               for k in ('buildingId', 'floor', 'startNodeId')):
        raise ValueError('expected schemaVersion 1 and buildingId/floor/startNodeId')
    nodes = {n['id']: n for n in data['nodes']}
    if len(nodes) != len(data['nodes']) or data['startNodeId'] not in nodes:
        raise ValueError('duplicate nodes or missing start node')
    types = {'JUNCTION', 'DOOR', 'START', 'DEAD_END', 'ELEVATOR', 'STAIRS', 'EXIT', 'AED'}
    if any(not n['id'] or n['type'] not in types or not all(_finite(n[k]) for k in ('x', 'y'))
           for n in nodes.values()):
        raise ValueError('invalid node coordinates or type')
    lines, adjacency = [], {key: set() for key in nodes}
    edge_ids = set()
    for i, edge in enumerate(data['edges']):
        a, b = edge['from'], edge['to']
        if a not in nodes or b not in nodes:
            raise ValueError('edge has missing node')
        eid = edge.get('id', f'e{i + 1}')
        if eid in edge_ids:
            raise ValueError('duplicate edge id')
        edge_ids.add(eid)
        pts = edge.get('polyline', [[nodes[n]['x'], nodes[n]['y']] for n in (a, b)])
        if (len(pts) < 2 or any(len(pt) != 2 or not all(map(_finite, pt)) for pt in pts)
                or _polyline_length(pts) <= 0):
            raise ValueError('invalid edge geometry')
        if 'lengthPx' in edge and (not _finite(edge['lengthPx']) or edge['lengthPx'] <= 0):
            raise ValueError('invalid edge length')
        lines.append(pts)
        adjacency[a].add(b)
        adjacency[b].add(a)
    for lm in data.get('landmarks', []):
        confidence = lm.get('ocrConfidence', 1.)
        if (lm['nodeId'] not in nodes or not isinstance(lm['label'], str)
                or not _finite(confidence) or not 0 <= confidence <= 1):
            raise ValueError('invalid landmark')
    seen, stack = set(), [data['startNodeId']]
    while stack:
        node = stack.pop()
        if node not in seen:
            seen.add(node)
            stack.extend(adjacency[node] - seen)
    if seen != set(nodes):
        raise ValueError('graph is disconnected')
    return nodes, lines, adjacency


def _new_id(prefix, used):
    i = 1
    while f'{prefix}{i}' in used:
        i += 1
    return f'{prefix}{i}'


def attach_start(data: dict, marker, p: Params | None = None) -> tuple[dict, dict]:
    p = p or Params()
    nodes, lines, adjacency = graph_geometry(data)
    if len(marker) != 2 or not all(map(_finite, marker)):
        raise ValueError('invalid marker coordinates')
    closest = None
    for i, pts in enumerate(lines):
        for j, (a, b) in enumerate(zip(pts, pts[1:])):
            delta = np.subtract(b, a)
            norm = float(delta @ delta)
            if norm == 0:
                continue
            t = float(np.clip(np.dot(np.subtract(marker, a), delta) / norm, 0, 1))
            point = (np.asarray(a) + t * delta).tolist()
            distance = math.dist(marker, point)
            if closest is None or distance < closest[0]:
                closest = distance, i, j, point
    if closest is None:
        raise ValueError('no nonzero corridor segments')
    distance, i, j, point = closest
    if distance > p.max_distance:
        raise ValueError(f'marker distance {distance:.1f} px exceeds limit {p.max_distance:g} px')
    out = copy.deepcopy(data)
    # Materialize implicit ids before insertion changes their decoder indexes.
    for index, edge in enumerate(out['edges']):
        edge.setdefault('id', f'e{index + 1}')
    edge, pts = out['edges'][i], lines[i]
    endpoint = next((key for key in (edge['from'], edge['to'])
                     if math.dist(point, (nodes[key]['x'], nodes[key]['y'])) <= p.reuse_radius
                     and math.dist(marker, (nodes[key]['x'], nodes[key]['y'])) <= p.max_distance), None)
    if endpoint is not None:
        if (nodes[endpoint]['type'] not in {'START', 'JUNCTION', 'DEAD_END'}
                or any(lm['nodeId'] == endpoint for lm in data.get('landmarks', []))):
            raise ValueError('semantic endpoint conflict; choose START manually')
        sid = endpoint
        point = [nodes[sid]['x'], nodes[sid]['y']]
    else:
        sid = _new_id('S', nodes)
        out['nodes'].append({'id': sid, 'type': 'START', 'x': point[0], 'y': point[1]})
        used = {e.get('id', f'e{k + 1}') for k, e in enumerate(out['edges'])}
        pieces = [pts[:j + 1] + [point], [point] + pts[j + 1:]]
        replacement = []
        for k, (a, b) in enumerate(((edge['from'], sid), (sid, edge['to']))):
            line = [pt for m, pt in enumerate(pieces[k]) if m == 0 or pt != pieces[k][m - 1]]
            eid = edge.get('id', f'e{i + 1}') if k == 0 else _new_id('e', used)
            replacement.append({**edge, 'id': eid, 'from': a, 'to': b, 'polyline': line,
                                'lengthPx': _polyline_length(line)})
        out['edges'][i:i + 1] = replacement
    for node in out['nodes']:
        if node['id'] == sid:
            node['type'] = 'START'
        elif node['type'] == 'START':
            node['type'] = 'DEAD_END' if len(adjacency[node['id']]) <= 1 else 'JUNCTION'
    out['startNodeId'], out['verified'] = sid, False
    graph_geometry(out)
    return out, {'status': 'attached', 'nodeId': sid, 'point': point,
                 'snapDistancePx': math.dist(marker, point)}


def _start_point(data):
    node = next((n for n in data['nodes'] if n['id'] == data['startNodeId']), None)
    if node is None or node['type'] != 'START' or sum(n['type'] == 'START' for n in data['nodes']) != 1:
        raise ValueError('expected exactly one START matching startNodeId; placeholder is not a detection')
    point = [node['x'], node['y']]
    if not all(map(_finite, point)):
        raise ValueError('invalid START coordinates')
    return point


def score_start(pred: dict, truth: dict) -> dict:
    point, reference = _start_point(pred), _start_point(truth)
    return {'predictedStart': point, 'tracedStart': reference, 'startErrorPx': math.dist(point, reference)}


def draw_overlay(bgr, data, audit):
    out = bgr.copy()
    nodes = {n['id']: [n['x'], n['y']] for n in data['nodes']}
    for edge in data['edges']:
        pts = edge.get('polyline', [nodes[edge['from']], nodes[edge['to']]])
        cv2.polylines(out, [np.asarray(pts, np.int32)], False, (255, 0, 255), 2)
    for candidate in audit['detection']['candidates']:
        x, y, w, h = candidate['box']
        cv2.rectangle(out, (x, y), (x + w, y + h), (0, 210, 255), 2)
    marker = audit['detection']['marker']
    point = audit.get('attachment', {}).get('point')
    if marker and point:
        cv2.line(out, tuple(np.int32(marker)), tuple(np.int32(point)), (0, 210, 255), 2)
    for xy, color, shape in [(marker, (0, 210, 255), cv2.MARKER_CROSS),
                             (point, (0, 220, 0), cv2.MARKER_TILTED_CROSS),
                             (audit.get('score', {}).get('tracedStart'), (255, 220, 0), cv2.MARKER_CROSS)]:
        if xy:
            cv2.drawMarker(out, tuple(np.int32(np.round(xy))), color, shape, 22, 2)
    cv2.putText(out, 'gold: marker | green: START | cyan: trace | magenta: corridors',
                (30, 40), cv2.FONT_HERSHEY_SIMPLEX, .8, (0, 0, 0), 2)
    return out


def _write_json(path, data):
    Path(path).write_text(json.dumps(data, indent=2, allow_nan=False) + '\n')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for command in ('extract', 'evaluate'):
        cmd = sub.add_parser(command)
        cmd.add_argument('map')
        cmd.add_argument('--graph', required=True, help='existing corridor graph in the same pixel frame')
        cmd.add_argument('-o', '--output', required=True)
        cmd.add_argument('--report', required=True, help='detection/attachment audit, including failures')
        cmd.add_argument('--debug', help='diagnostic overlay image')
        cmd.add_argument('--max-distance', type=float, default=Params().max_distance)
        if command == 'evaluate':
            cmd.add_argument('--truth', required=True, help='trace read only after prediction, for scoring')
    cmd = sub.add_parser('score')
    cmd.add_argument('pred')
    cmd.add_argument('truth')
    args = parser.parse_args(argv)
    try:
        if args.command == 'score':
            print(json.dumps(score_start(json.loads(Path(args.pred).read_text()),
                                         json.loads(Path(args.truth).read_text())), indent=2))
            return 0
        p = Params(max_distance=args.max_distance)
        if not math.isfinite(p.max_distance) or p.max_distance <= 0:
            raise ValueError('max-distance must be finite and positive')
        # Do not overwrite a caller's input or diagnostics with another output.
        inputs = [args.map, args.graph] + ([args.truth] if args.command == 'evaluate' else [])
        outputs = [args.output, args.report] + ([args.debug] if args.debug else [])
        resolved = [Path(path).resolve() for path in inputs + outputs]
        if len(set(resolved)) != len(resolved):
            raise ValueError('input and output paths must be distinct')
        bgr = cv2.imread(args.map)
        if bgr is None:
            raise ValueError(f'cannot read image {args.map}')
        data = json.loads(Path(args.graph).read_text())
        graph_geometry(data)
        detection = detect_marker(bgr, p)
        audit = {'image': args.map, 'graph': args.graph, 'params': asdict(p), 'detection': detection}
        pred = None
        if detection['status'] != 'detected':
            audit['reason'] = f"{detection['status']} gold star; review map manually"
        else:
            try:
                pred, audit['attachment'] = attach_start(data, detection['marker'], p)
            except ValueError as error:
                audit['reason'] = str(error)
        if args.command == 'evaluate' and pred is not None:
            audit['score'] = score_start(pred, json.loads(Path(args.truth).read_text()))
        if args.debug and not cv2.imwrite(args.debug, draw_overlay(bgr, pred or data, audit)):
            raise ValueError(f'cannot write overlay {args.debug}')
        _write_json(args.report, audit)
        if pred is not None:
            _write_json(args.output, pred)
        else:
            Path(args.output).unlink(missing_ok=True)
        print(json.dumps(audit, indent=2))
        return 0 if pred is not None else 2
    except (OSError, ValueError, KeyError, TypeError, cv2.error) as error:
        parser.exit(2, f'start marker: {error}\n')


if __name__ == '__main__':
    raise SystemExit(main())
