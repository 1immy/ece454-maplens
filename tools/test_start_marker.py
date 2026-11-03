"""Synthetic marker detection and graph attachment tests; no LFS required."""
import copy
import contextlib
import io
import json
import tempfile
import math
import sys
import unittest
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import start_marker as sm


def star(image, center=(100, 100), gold=(25, 165, 205), blue=True):
    if blue:
        cv2.circle(image, center, 32, (90, 25, 8), -1)
    points = []
    for i in range(10):
        angle = -math.pi / 2 + i * math.pi / 5
        r = 26 if i % 2 == 0 else 11
        points.append([round(center[0] + r * math.cos(angle)),
                       round(center[1] + r * math.sin(angle))])
    cv2.fillPoly(image, [np.array(points, np.int32)], gold)


def graph():
    return {"schemaVersion": 1, "buildingId": "test", "floor": "3", "verified": True,
            "startNodeId": "A", "source": {"image": "map.jpg", "custom": [1]},
            "nodes": [{"id": "A", "type": "START", "x": 0, "y": 0},
                      {"id": "B", "type": "DOOR", "x": 100, "y": 100}],
            "edges": [{"id": "e1", "from": "A", "to": "B", "lengthPx": 200,
                       "polyline": [[0, 0], [100, 0], [100, 100]], "custom": 7}],
            "landmarks": [{"label": "3546", "nodeId": "B", "ocrConfidence": .9}]}


class DetectionTest(unittest.TestCase):
    def test_gold_star_in_blue_disk_beats_circle_rectangle_route_and_green_star(self):
        im = np.full((240, 650, 3), 235, np.uint8)
        star(im)
        cv2.circle(im, (230, 100), 32, (90, 25, 8), -1)
        cv2.circle(im, (230, 100), 24, (25, 165, 205), -1)
        cv2.rectangle(im, (300, 70), (350, 120), (25, 165, 205), -1)
        star(im, (420, 100), gold=(40, 80, 190))
        star(im, (530, 100), gold=(50, 120, 50))
        result = sm.detect_marker(im)
        self.assertEqual(result['status'], 'detected')
        self.assertLess(math.dist(result['marker'], (100, 100)), 4)
        self.assertEqual(len(result['candidates']), 1)

    def test_gold_without_blue_is_rejected_and_two_stars_are_ambiguous(self):
        im = np.full((240, 400, 3), 235, np.uint8)
        self.assertEqual(sm.detect_marker(im)['status'], 'missing')
        star(im, blue=False)
        self.assertEqual(sm.detect_marker(im)['status'], 'missing')
        star(im)
        star(im, (280, 100))
        self.assertEqual(sm.detect_marker(im)['status'], 'ambiguous')
        self.assertIsNone(sm.detect_marker(im)['marker'])


class AttachmentTest(unittest.TestCase):
    def test_projects_segment_interior_splits_bends_and_preserves_data(self):
        data = graph()
        original = copy.deepcopy(data)
        pred, audit = sm.attach_start(data, (110, 70))
        self.assertEqual(data, original)
        self.assertEqual(audit['point'], [100., 70.])
        self.assertEqual(audit['snapDistancePx'], 10)
        self.assertEqual(len(pred['nodes']), 3)
        self.assertEqual(len(pred['edges']), 2)
        self.assertAlmostEqual(sum(e['lengthPx'] for e in pred['edges']), 200)
        self.assertEqual(pred['edges'][0]['polyline'], [[0, 0], [100, 0], [100., 70.]])
        self.assertEqual(pred['source'], original['source'])
        self.assertEqual(pred['landmarks'], original['landmarks'])
        self.assertTrue(all(e['custom'] == 7 for e in pred['edges']))
        self.assertFalse(pred['verified'])
        self.assertEqual(pred['nodes'][0]['type'], 'DEAD_END')
        self.assertEqual(sum(n['type'] == 'START' for n in pred['nodes']), 1)
        self.assertEqual(next(n for n in pred['nodes'] if n['id'] == pred['startNodeId'])['type'], 'START')

    def test_endpoint_reuse_and_repeated_attachment_are_idempotent(self):
        pred, audit = sm.attach_start(graph(), (0, 1))
        self.assertEqual(pred['startNodeId'], 'A')
        self.assertEqual(len(pred['nodes']), 2)
        again, _ = sm.attach_start(pred, (0, 1))
        self.assertEqual(again, pred)
        self.assertEqual(audit['point'], [0, 0])

    def test_semantic_endpoint_conflict_requires_review_without_losing_door(self):
        data = graph()
        original = copy.deepcopy(data)
        with self.assertRaisesRegex(ValueError, 'semantic endpoint'):
            sm.attach_start(data, (100, 100))
        self.assertEqual(data, original)

    def test_optional_edge_fields_and_collision_free_ids(self):
        data = graph()
        data['edges'] = [{'from': 'A', 'to': 'B'}]
        data['nodes'][1]['id'] = 'S1'
        data['edges'][0]['to'] = 'S1'
        data['landmarks'][0]['nodeId'] = 'S1'
        pred, audit = sm.attach_start(data, (50, 55))
        self.assertEqual(audit['point'], [52.5, 52.5])
        self.assertEqual(len({n['id'] for n in pred['nodes']}), 3)
        self.assertEqual(len({e['id'] for e in pred['edges']}), 2)

    def test_optional_ids_remain_stable_when_an_earlier_edge_splits(self):
        data = graph()
        data['nodes'].append({'id': 'C', 'type': 'DEAD_END', 'x': 200, 'y': 100})
        data['edges'][0].pop('id')
        data['edges'].append({'from': 'B', 'to': 'C'})
        pred, _ = sm.attach_start(data, (50, 10))
        ids = [e.get('id', f'e{i + 1}') for i, e in enumerate(pred['edges'])]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(pred['edges'][-1]['id'], 'e2')

    def test_bend_split_and_new_start_demote_previous_interior_start(self):
        data, _ = sm.attach_start(graph(), (50, 0))
        old = data['startNodeId']
        pred, audit = sm.attach_start(data, (100, 0))
        self.assertEqual(audit['point'], [100, 0])
        self.assertEqual(next(n['type'] for n in pred['nodes'] if n['id'] == old), 'JUNCTION')
        self.assertAlmostEqual(sum(e['lengthPx'] for e in pred['edges']), 200)
        self.assertTrue(all(e['lengthPx'] > 0 for e in pred['edges']))

    def test_distance_rejection_no_edges_and_invalid_geometry(self):
        with self.assertRaisesRegex(ValueError, 'distance'):
            sm.attach_start(graph(), (400, 400))
        for mutate in [lambda g: g['edges'].clear(),
                       lambda g: g['edges'][0].update(to='missing'),
                       lambda g: g['nodes'][0].update(x=float('nan')),
                       lambda g: g['edges'][0].update(polyline=[[0, 0], [0, 0]]),
                       lambda g: g['nodes'].append(dict(g['nodes'][0]))]:
            data = graph()
            mutate(data)
            with self.assertRaises(ValueError):
                sm.attach_start(data, (20, 10))


class CliTest(unittest.TestCase):
    def test_extract_evaluate_and_rescore_use_predictions_not_truth(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            root = Path(tmp)
            im = np.full((240, 240, 3), 235, np.uint8)
            star(im)
            image = root / 'map.png'
            cv2.imwrite(str(image), im)
            data = graph()
            data['nodes'][1].update(x=200, y=120)
            data['edges'][0]['polyline'] = [[0, 0], [0, 120], [200, 120]]
            data['edges'][0]['lengthPx'] = 320
            source = root / 'input.json'
            source.write_text(json.dumps(data))
            truth = copy.deepcopy(data)
            truth['nodes'][0].update(x=90, y=120)
            ref = root / 'truth.json'
            ref.write_text(json.dumps(truth))
            output, report, debug = [root / n for n in ['out.json', 'report.json', 'overlay.png']]
            args = ['evaluate', str(image), '--graph', str(source), '--truth', str(ref),
                    '-o', str(output), '--report', str(report), '--debug', str(debug)]
            self.assertEqual(sm.main(args), 0)
            pred = json.loads(output.read_text())
            audit = json.loads(report.read_text())
            self.assertEqual(audit['attachment']['point'][1], 120)
            self.assertGreater(audit['score']['startErrorPx'], 5)
            self.assertLess(audit['score']['startErrorPx'], 15)
            self.assertEqual(audit['score']['tracedStart'], [90, 120])
            self.assertEqual(sm.score_start(pred, truth), audit['score'])
            self.assertEqual(sm.main(['score', str(output), str(ref)]), 0)
            self.assertIsNotNone(cv2.imread(str(debug)))
            self.assertEqual(json.loads(source.read_text()), data)
            self.assertEqual(json.loads(ref.read_text()), truth)
            self.assertEqual(sm.main(['extract', str(image), '--graph', str(source),
                                     '-o', str(output), '--report', str(report)]), 0)
            self.assertNotIn('score', json.loads(report.read_text()))

    def test_failure_writes_audit_overlay_but_no_placeholder_graph(self):
        for mode in ('missing', 'ambiguous', 'distant'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                im = np.full((500, 500, 3), 235, np.uint8)
                if mode == 'ambiguous':
                    star(im)
                    star(im, (280, 100))
                if mode == 'distant':
                    star(im, (350, 350))
                image = root / 'map.png'
                cv2.imwrite(str(image), im)
                source = root / 'input.json'
                source.write_text(json.dumps(graph()))
                output, report, debug = [root / n for n in ['out.json', 'audit.json', 'overlay.png']]
                with contextlib.redirect_stdout(io.StringIO()):
                    status = sm.main(['extract', str(image), '--graph', str(source), '-o', str(output),
                                      '--report', str(report), '--debug', str(debug)])
                self.assertEqual(status, 2)
                self.assertFalse(output.exists())
                self.assertIn('reason', json.loads(report.read_text()))
                self.assertTrue(debug.exists())

    def test_rejected_rerun_removes_stale_prediction_graph(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            root = Path(tmp)
            image, source, output, report = [root / p for p in ['map.png', 'input.json', 'out.json', 'audit.json']]
            im = np.full((240, 240, 3), 235, np.uint8)
            star(im)
            cv2.imwrite(str(image), im)
            source.write_text(json.dumps(graph()))
            args = ['extract', str(image), '--graph', str(source), '-o', str(output), '--report', str(report)]
            self.assertEqual(sm.main(args), 0)
            self.assertTrue(output.exists())
            cv2.imwrite(str(image), np.full_like(im, 235))
            self.assertEqual(sm.main(args), 2)
            self.assertFalse(output.exists())
            self.assertEqual(json.loads(report.read_text())['detection']['status'], 'missing')

    def test_output_alias_cannot_overwrite_input_graph(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stderr(io.StringIO()):
            source = Path(tmp) / 'input.json'
            original = json.dumps(graph())
            source.write_text(original)
            with self.assertRaises(SystemExit):
                sm.main(['extract', 'map.png', '--graph', str(source), '-o', str(source),
                         '--report', str(Path(tmp) / 'report.json')])
            self.assertEqual(source.read_text(), original)

    def test_placeholder_cannot_be_scored_as_detected_start(self):
        placeholder = graph()
        placeholder['nodes'][0]['type'] = 'DEAD_END'
        with self.assertRaisesRegex(ValueError, 'START'):
            sm.score_start(placeholder, graph())


if __name__ == '__main__':
    unittest.main()
