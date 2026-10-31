"""Deterministic room-label tests; OCR executable is not required."""
import copy
import contextlib
import io
import json
import subprocess
import tempfile
from unittest.mock import patch

import cv2
import numpy as np
import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import room_labels as rooms


def graph():
    return {"schemaVersion": 1, "buildingId": "test", "floor": "3",
            "startNodeId": "S", "verified": True, "source": {"image": "map.png"},
            "nodes": [{"id": "S", "type": "START", "x": 0, "y": 0},
                      {"id": "X", "type": "DEAD_END", "x": 100, "y": 100}],
            "edges": [{"id": "e1", "from": "S", "to": "X",
                       "polyline": [[0, 0], [100, 0], [100, 100]], "lengthPx": 200}],
            "landmarks": []}


def reading(text, x, y, confidence=0.9):
    return rooms.Reading(text, (x - 10, y - 5, 20, 10), confidence)


class TextTest(unittest.TestCase):
    def test_normalize_suffix_without_silent_digit_repairs(self):
        for text, want in [(" 3540-b. ", "3540B"), ("3539 a", "3539A"),
                           ("3425", "3425"), ("35O1", None), ("3I11", None),
                           ("EXIT", None), ("12345", None), ("123", None),
                           ("Rooms 3540", None), ("3540AB", None), ("3539A|", "3539A"), ("", None)]:
            with self.subTest(text=text):
                self.assertEqual(rooms.normalize_label(text), want)

    def test_deduplicate_overlapping_ocr_passes_but_keep_separate_entrances(self):
        hits = [reading("3546", 10, 10, .8), reading("3548", 11, 10, .7),
                reading("3546", 100, 10), reading("EXIT", 40, 10),
                reading("3540B", 50, 10, .1),
                rooms.Reading("3546", (0, 5, 110, 10), .6)]
        labels = rooms.room_labels(hits)
        self.assertEqual([r.text for r in labels], ["3546", "3546"])
        self.assertEqual(len(rooms.room_labels([])), 0)


class OcrTest(unittest.TestCase):
    def test_text_regions_ignore_walls_and_preserve_word_box(self):
        img = np.full((160, 400, 3), 235, np.uint8)
        cv2.line(img, (0, 130), (399, 130), (0, 0, 0), 4)
        cv2.putText(img, "3546", (80, 80), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 0, 0), 2)
        boxes = rooms.text_regions(img, rooms.Params())
        self.assertTrue(any(x <= 100 <= x + w and y <= 70 <= y + h for x, y, w, h in boxes))
        self.assertFalse(any(y <= 130 <= y + h for x, y, w, h in boxes))
        self.assertEqual(rooms.text_regions(np.full_like(img, 255), rooms.Params()), [])

    def test_tsv_boxes_confidence_and_crop_offset_in_original_pixels(self):
        tsv = b"level\tleft\ttop\twidth\theight\tconf\ttext\n5\t20\t30\t80\t20\t87.5\t3540B\n"
        proc = subprocess.CompletedProcess([], 0, stdout=tsv, stderr=b"")
        with patch("room_labels.subprocess.run", return_value=proc):
            result = rooms.tesseract_words(np.full((100, 200, 3), 255, np.uint8),
                                           scale=2, origin=(100, 200), psm=7)
        self.assertEqual(result, [rooms.Reading("3540B", (110, 215, 40, 10), .875)])

    def test_engine_errors_are_actionable(self):
        for error in [FileNotFoundError(), subprocess.TimeoutExpired("tesseract", 120),
                      subprocess.CalledProcessError(1, "tesseract", stderr=b"missing eng data")]:
            with self.subTest(error=error), patch("room_labels.subprocess.run", side_effect=error):
                with self.assertRaisesRegex(ValueError, "Tesseract"):
                    rooms.tesseract_words(np.zeros((20, 20, 3), np.uint8), 2)

    def test_cli_unreadable_input_does_not_write_output(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stderr(io.StringIO()) as err:
            out = Path(tmp) / "out.json"
            with self.assertRaises(SystemExit) as failure:
                rooms.main(["extract", str(Path(tmp) / "missing.png"), "--graph", "unused", "-o", str(out)])
            self.assertEqual(failure.exception.code, 2)
            self.assertIn("room labels:", err.getvalue())
            self.assertFalse(out.exists())

    def test_cli_rejects_null_source_without_a_traceback(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stderr(io.StringIO()) as err:
            root = Path(tmp)
            image = root / "map.png"
            cv2.imwrite(str(image), np.full((200, 200, 3), 255, np.uint8))
            data = graph()
            data["source"] = None
            path = root / "graph.json"
            path.write_text(json.dumps(data))
            with self.assertRaises(SystemExit) as failure:
                rooms.main(["extract", str(image), "--graph", str(path), "-o", str(root / "out.json")])
            self.assertEqual(failure.exception.code, 2)
            self.assertIn("source", err.getvalue())
            self.assertFalse((root / "out.json").exists())

    def test_evaluate_writes_ocr_predictions_without_copying_reference_labels(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            root = Path(tmp)
            image = root / "map.png"
            cv2.imwrite(str(image), np.full((200, 200, 3), 255, np.uint8))
            corridor = root / "corridors.json"
            reference = root / "truth.json"
            out = root / "out.json"
            audit = root / "readings.json"
            corridor.write_text(json.dumps(graph()))
            truth = graph()
            truth["landmarks"] = [{"label": "9999", "nodeId": "X"}]
            reference.write_text(json.dumps(truth))
            with patch("room_labels.read_tesseract", return_value=[reading("3546", 30, 15)]):
                rooms.main(["evaluate", str(image), "--graph", str(corridor), "--truth", str(reference),
                            "-o", str(out), "--readings", str(audit), "--debug", str(root / "overlay.png")])
            saved = json.loads(out.read_text())
            self.assertEqual([l["label"] for l in saved["landmarks"]], ["3546"])
            self.assertEqual(json.loads(audit.read_text())["labels"][0]["text"], "3546")
            self.assertTrue((root / "overlay.png").exists())
            self.assertEqual(json.loads(reference.read_text()), truth)


class AttachmentTest(unittest.TestCase):
    def test_split_bent_edge_preserves_lengths_connectivity_and_input(self):
        data = graph()
        original = copy.deepcopy(data)
        result, attachments = rooms.attach_labels(data, [reading("3546", 30, 15),
                                                        reading("3546", 115, 70)])
        self.assertEqual(data, original)
        self.assertFalse(result["verified"])
        self.assertEqual(len(result["nodes"]), 4)
        self.assertEqual(len(result["edges"]), 3)
        self.assertEqual(len(result["landmarks"]), 2)
        self.assertEqual(len({l["nodeId"] for l in result["landmarks"]}), 2)
        self.assertEqual([a["point"] for a in attachments], [[30.0, 0.0], [100.0, 70.0]])
        self.assertAlmostEqual(sum(e["lengthPx"] for e in result["edges"]), 200)
        rooms.validate_graph(result)
        nodes = {n["id"]: [n["x"], n["y"]] for n in result["nodes"]}
        for edge in result["edges"]:
            self.assertEqual(edge["polyline"][0], nodes[edge["from"]])
            self.assertEqual(edge["polyline"][-1], nodes[edge["to"]])
            self.assertAlmostEqual(edge["lengthPx"], sum(math.dist(a, b) for a, b in
                                  zip(edge["polyline"], edge["polyline"][1:])))

    def test_endpoint_and_repeated_run_do_not_add_zero_length_edges(self):
        data, _ = rooms.attach_labels(graph(), [reading("3415", 100, 105)])
        again, _ = rooms.attach_labels(data, [reading("3415", 100, 105)])
        self.assertEqual(len(again["nodes"]), 2)
        self.assertEqual(len(again["edges"]), 1)
        self.assertEqual(len(again["landmarks"]), 1)
        self.assertEqual(again["nodes"][1]["type"], "DOOR")

    def test_no_match_or_no_edges_leaves_no_landmarks(self):
        data, attachments = rooms.attach_labels(graph(), [reading("3415", 999, 999)])
        self.assertEqual(data["landmarks"], [])
        self.assertIsNone(attachments[0]["nodeId"])
        self.assertIn("distance", attachments[0]["reason"])
        single = graph()
        single["nodes"] = single["nodes"][:1]
        single["edges"] = []
        result, _ = rooms.attach_labels(single, [reading("3415", 10, 10)])
        self.assertEqual(result["landmarks"], [])
        self.assertEqual(rooms.attach_labels(graph(), [])[1], [])

    def test_optional_polylines_and_existing_labels_are_preserved(self):
        data = graph()
        data["edges"] = [{"from": "S", "to": "X"}]
        data["landmarks"] = [{"label": "3546", "nodeId": "S", "ocrConfidence": .99},
                             {"label": "3546", "nodeId": "X", "ocrConfidence": .8}]
        result, _ = rooms.attach_labels(data, [reading("3540B", 50, 50)])
        self.assertEqual(result["landmarks"][:2], data["landmarks"])
        self.assertEqual(len(result["landmarks"]), 3)
        self.assertEqual(len(result["edges"]), 2)
        self.assertEqual(result["startNodeId"], "S")

    def test_bad_graph_rejected(self):
        for mutate in [lambda g: g.update(schemaVersion=2),
                       lambda g: g.pop("buildingId"),
                       lambda g: g["landmarks"].append({"label": "3415", "nodeId": "X", "ocrConfidence": None}),
                       lambda g: g["landmarks"].append({"label": "3415", "nodeId": "X", "ocrConfidence": float("nan")}),
                       lambda g: g["landmarks"].append({"label": "3415", "nodeId": "X", "ocrConfidence": 1.1}),
                       lambda g: g["edges"][0].update(to="missing"),
                       lambda g: g["nodes"][0].update(x=float("nan")),
                       lambda g: g["nodes"][0].update(x=False),
                       lambda g: g["edges"][0].update(lengthPx=True),
                       lambda g: g["landmarks"].append({"label": "3415", "nodeId": "X", "ocrConfidence": True}),
                       lambda g: g["nodes"].append(dict(g["nodes"][0])),
                       lambda g: g["landmarks"].append({"label": "3415", "nodeId": "missing"}),
                       lambda g: g["edges"].clear()]:
            bad = graph()
            mutate(bad)
            with self.assertRaises(ValueError):
                rooms.attach_labels(bad, [])


class ScoreTest(unittest.TestCase):
    def test_text_and_one_to_one_entrance_scores_have_separate_denominators(self):
        truth, _ = rooms.attach_labels(graph(), [reading("3546", 30, 0),
                                                reading("3546", 100, 70),
                                                reading("3540B", 80, 0)])
        truth["landmarks"].append({"label": "OFFMAP", "nodeId": "X"})
        pred, _ = rooms.attach_labels(graph(), [reading("3546", 30, 0)])
        pred["landmarks"].append(dict(pred["landmarks"][0]))
        metrics = rooms.score([reading("3546", 30, 0), reading("9999", 40, 20)], pred, truth)
        self.assertEqual(metrics["room_labels_true"], 2)
        self.assertEqual(metrics["room_entrances_true"], 3)
        self.assertEqual(metrics["text_matched"], 1)
        self.assertEqual(metrics["text_recall"], .5)
        self.assertEqual(metrics["text_precision"], .5)
        self.assertEqual(metrics["attachments_matched"], 1)
        self.assertAlmostEqual(metrics["attachment_recall"], 1 / 3)
        self.assertEqual(rooms.score([], graph(), graph())["attachment_recall"], 0)

    def test_matching_prefers_two_in_radius_pairs_over_one_closer_pair(self):
        truth = graph()
        truth["nodes"] += [{"id": "T1", "type": "DOOR", "x": 40, "y": 0},
                           {"id": "T2", "type": "DOOR", "x": 0, "y": 39}]
        truth["landmarks"] = [{"label": "3546", "nodeId": "T1"}, {"label": "3546", "nodeId": "T2"}]
        pred = graph()
        pred["nodes"] += [{"id": "P1", "type": "DOOR", "x": 0, "y": 0},
                          {"id": "P2", "type": "DOOR", "x": 79, "y": 0}]
        pred["landmarks"] = [{"label": "3546", "nodeId": "P1"}, {"label": "3546", "nodeId": "P2"}]
        self.assertEqual(rooms.score([], pred, truth)["attachments_matched"], 2)


if __name__ == "__main__":
    unittest.main()
