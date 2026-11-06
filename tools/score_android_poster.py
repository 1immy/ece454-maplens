"""Score exported Android poster fixture results without changing trace fixtures.

Run from the repo root with the Android poster-eval and Python baseline directories.
"""

import argparse
import json
import math
from pathlib import Path

import cv2
import numpy as np

import corridors
import maplabel
import rectify
import room_labels
import start_marker


def registration(report, trace_corners, trace_size):
    """Compose Android-warp → upright photo → trace-warp in bounded-photo pixels."""
    def rect(width, height):
        return [(0, 0), (width - 1, 0), (width - 1, height - 1), (0, height - 1)]

    scale = np.array(report['sourceSize']) / trace_corners['size']
    manual = np.asarray(trace_corners['corners']) * scale
    photo_to_trace = maplabel.homography(manual, rect(*trace_size))
    android_to_photo = maplabel.homography(
        rect(report['width'], report['height']), report['corners']
    )
    return photo_to_trace @ android_to_photo


def register_graph(graph, transform):
    """Project a generated graph into trace coordinates for scoring."""
    def project(xy):
        x, y, w = transform @ [*xy, 1]
        return [float(x / w), float(y / w)]

    for node in graph['nodes']:
        node['x'], node['y'] = project([node['x'], node['y']])
    for edge in graph['edges']:
        edge['polyline'] = [project(point) for point in edge['polyline']]
        edge['lengthPx'] = corridors._polyline_length(edge['polyline'])


def main(android: Path, baseline: Path) -> None:
    fixtures = Path('data/fixtures')
    corners = json.loads((fixtures / 'rectify/ehall-corners.json').read_text())['images']

    def read(path):
        return json.loads(path.read_text())

    print('FRAME')
    error = []
    for name, found in read(android / 'frames.json').items():
        expected = corners[name]
        if not isinstance(found, list):
            print(name, 'retake', found)
            continue
        px, pct = rectify.corner_error(found, expected['corners'])
        error.append((px, pct))
        print(name, round(px, 2), round(pct, 3))
    print('accepted', len(error), 'median', np.median(error, axis=0), 'max', np.max(error, axis=0))

    for poster, photo in (
        ('eh-3-elevators-evac', 'eh-3-elevators-evac-03-front'),
        ('eh-3-stair3424-evac', 'eh-3-stair-3424-evac-01-front'),
    ):
        trace = read(fixtures / 'maps' / f'{poster}.graph.json')
        route = read(android / f'{poster}.corridors.graph.json')
        python = read(baseline / f'{poster}.corridors.json')
        final = read(android / f'{poster}.final.graph.json')
        print(poster, 'ANDROID VS TRACE', corridors.format_score(corridors.score(route, trace)))
        print(poster, 'ANDROID VS PYTHON', corridors.format_score(corridors.score(route, python)))
        trace_size = maplabel.load_image(fixtures / 'maps' / f'{poster}.jpg').size
        ocr = read(android / f'{poster}.ocr.json')['labels']
        readings = [room_labels.Reading(**word) for word in ocr]
        room_score = room_labels.score(readings, final, trace)
        if room_score['room_entrances_true']:
            print('ROOMS', room_score)
            wall_file = android / f'{poster}.walls.bin'
            if wall_file.exists():
                # The Python attachment API expects a start ID even for a corridor-only input.
                same_route = route | {'startNodeId': route['nodes'][0]['id']}
                walls = np.fromfile(wall_file, np.uint8).reshape(trace_size[::-1])
                shared_input, _ = room_labels.attach_labels(same_route, readings, walls=walls)
                print('PYTHON ATTACHMENT ON ANDROID INPUTS',
                      room_labels.score(readings, shared_input, trace))
        else:
            print('ROOMS', len(readings), 'readings. No traced room entrances for scoring.')
        print('START', read(android / f'{poster}.start.json'))
        if final['startNodeId']:
            print(start_marker.score_start(final, trace))
        else:
            print('START unresolved. No position error is defined.')
        print('GRAPH', len(final['nodes']), len(final['edges']), len(final['landmarks']))
        print('CAPTURE')
        summary = read(android / f'{photo}.capture.json')
        pred = read(android / f'{photo}.capture.graph.json')
        print(summary)
        flat = cv2.imread(str(android / f'{photo}.flat.png'))
        marker = start_marker.detect_marker(flat)
        start = next((n for n in pred['nodes'] if n['id'] == pred['startNodeId']), None)
        print('Reference star detection on exported warp', marker['status'], marker['marker'])
        if start and marker['marker']:
            print('Snap distance in the exported warp', math.dist(
                marker['marker'], [start['x'], start['y']]
            ))
        transform = registration(summary, corners[f'{photo}.jpg'], trace_size)

        register_graph(pred, transform)
        print(corridors.format_score(corridors.score(pred, trace)))
        if pred['startNodeId']:
            print(start_marker.score_start(pred, trace))
        else:
            print('START unresolved. No position error is defined.')
        if room_score['room_entrances_true']:
            front_ocr = read(android / f'{photo}.ocr.json')['labels']
            front_words = [room_labels.Reading(**word) for word in front_ocr]
            print('FRONT ANDROID ROOMS', room_labels.score(front_words, pred, trace))
            # Hold the warp fixed to separate OCR and attachment differences from rectification.
            python_route = corridors.to_floorgraph_json(corridors.extract_graph(flat)[0])
            raw = room_labels.read_rapidocr(flat)
            python_words = room_labels.room_labels(raw)
            python_graph, _ = room_labels.attach_labels(
                python_route, python_words, walls=room_labels.wall_mask(flat, raw)
            )
            register_graph(python_graph, transform)
            print('FRONT PYTHON ROOMS', room_labels.score(python_words, python_graph, trace))

    print('ALL PHOTOS')
    poster = 'eh-3-elevators-evac'
    trace = read(fixtures / 'maps' / f'{poster}.graph.json')
    trace_size = maplabel.load_image(fixtures / 'maps' / f'{poster}.jpg').size
    for name, report in read(android / 'photos.json').items():
        if 'retake' in report:
            print(name, 'retake:', report['retake'])
            continue
        pred = read(android / f'{name}.photo.graph.json')
        print(name, f"{len(pred['landmarks'])} rooms", f"{report['elapsedMs']} ms",
              'START' if pred['startNodeId'] else 'START unresolved')
        if '-elevators-evac-' not in name:
            continue
        register_graph(pred, registration(report, corners[f'{name}.jpg'], trace_size))
        words = [room_labels.Reading(item['label'], (0, 0, 0, 0), 1) for item in pred['landmarks']]
        found = room_labels.score(words, pred, trace)
        print('  recall', round(found['text_recall'], 3),
              'entrances', found['attachments_matched'], 'extra', found['extra_text'])
        if pred['startNodeId']:
            print(' ', start_marker.score_start(pred, trace))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('android', type=Path)
    parser.add_argument('baseline', type=Path)
    args = parser.parse_args()
    main(args.android, args.baseline)
