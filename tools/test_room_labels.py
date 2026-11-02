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
                rooms.main(["evaluate", str(image), "--graph", str(corridor), "--truth", str(reference), "--ocr", "tesseract",
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


class StrongOcrTest(unittest.TestCase):
    def test_numeric_line_splits_using_character_boxes_but_prose_stays_unsplit(self):
        line = rooms.Reading("3442 3440", (0, 0, 200, 20), .95)
        chars = [(c, .9, [[x, 0], [x + 10, 0], [x + 10, 20], [x, 20]])
                 for c, x in zip("34423440", [10, 20, 30, 40, 110, 120, 130, 140])]
        split = rooms.split_numeric_line(line, chars)
        self.assertEqual([r.text for r in rooms.room_labels(split)], ["3442", "3440"])
        self.assertEqual([r.box for r in split[1:]], [(10, 0, 40, 20), (110, 0, 40, 20)])
        prose = rooms.Reading("Outside Room 1224, 1412, 2239", (0, 0, 400, 20), .99)
        self.assertEqual(rooms.room_labels(rooms.split_numeric_line(prose, chars)), [])
        concatenated = rooms.Reading("34423440", (0, 0, 200, 20), .95)
        self.assertEqual([r.text for r in rooms.room_labels(rooms.split_numeric_line(concatenated, chars))],
                         ["3442", "3440"])
        self.assertEqual(rooms.split_numeric_line(line, []), [line])

    def test_rapidocr_empty_results_and_bundled_models_only(self):
        import types
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            (package / "models").mkdir()
            for name in ("PP-OCRv6_det_small.onnx", "PP-OCRv6_rec_small.onnx", "ch_ppocr_mobile_v2.0_cls_mobile.onnx"):
                (package / "models" / name).write_bytes(b"model")

            response = types.SimpleNamespace(boxes=None, txts=None, scores=None, word_results=())

            class Engine:
                def __init__(self, params):
                    for stage in ("Det", "Rec", "Cls"):
                        if not Path(params[f"{stage}.model_path"]).is_file():
                            raise AssertionError("a bundled local model was not selected")

                def __call__(self, image):
                    if isinstance(response, Exception):
                        raise response
                    return response

            module = types.SimpleNamespace(__file__=str(package / "__init__.py"), RapidOCR=Engine)
            with patch.dict(sys.modules, {"rapidocr": module, "onnxruntime": types.SimpleNamespace(disable_telemetry_events=lambda: None)}):
                self.assertEqual(rooms.read_rapidocr(np.full((100, 100, 3), 255, np.uint8)), [])
                response = types.SimpleNamespace(boxes=np.array([[[10, 20], [50, 20], [50, 35], [10, 35]]]),
                                                 txts=("3540B",), scores=(.9,), word_results=((),))
                self.assertEqual(rooms.read_rapidocr(np.zeros((100, 100, 3), np.uint8)),
                                 [rooms.Reading("3540B", (10, 20, 40, 15), .9)])
                response = RuntimeError("bad model")
                with self.assertRaisesRegex(ValueError, "RapidOCR failed"):
                    rooms.read_rapidocr(np.zeros((100, 100, 3), np.uint8))
                (package / "models" / "PP-OCRv6_det_small.onnx").unlink()
                with self.assertRaisesRegex(ValueError, "model"):
                    rooms.read_rapidocr(np.full((100, 100, 3), 255, np.uint8))


class WallAttachmentTest(unittest.TestCase):
    def corridor(self):
        data = graph()
        data["nodes"][1].update(x=200, y=100)
        data["nodes"][0].update(y=100)
        data["edges"][0].update(polyline=[[0, 100], [200, 100]], lengthPx=200)
        return data

    def test_wall_gap_beats_nearer_projection_through_solid_wall(self):
        walls = np.zeros((120, 220), np.uint8)
        walls[50:62, :] = 255
        walls[50:62, 65:85] = 0
        pred, audit = rooms.attach_labels(self.corridor(), [reading("3540", 100, 20)], walls=walls)
        self.assertLessEqual(audit[0]["point"][0], 85)
        self.assertGreater(len(audit[0]["connector"]), 2)
        self.assertEqual(audit[0]["point"][1], 100)
        rooms.validate_graph(pred)

    def test_no_visible_corridor_is_explicitly_rejected(self):
        walls = np.zeros((120, 220), np.uint8)
        walls[40:70, :] = 255
        pred, audit = rooms.attach_labels(self.corridor(), [reading("3540", 100, 20)], walls=walls)
        self.assertEqual(pred["landmarks"], [])
        self.assertIsNone(audit[0]["nodeId"])
        self.assertIn("wall", audit[0]["reason"])

    def test_wall_mask_removes_text_without_erasing_long_walls(self):
        image = np.full((160, 300, 3), 235, np.uint8)
        cv2.line(image, (10, 120), (290, 120), (0, 0, 0), 5)
        cv2.putText(image, "3540", (80, 75), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 0, 0), 2)
        cv2.line(image, (10, 140), (290, 140), (40, 80, 190), 5)
        walls = rooms.wall_mask(image, [rooms.Reading("3540", (75, 45, 100, 35), .9)])
        self.assertGreater(walls[120, 150], 0)
        self.assertEqual(walls[140, 150], 0)
        self.assertEqual(np.count_nonzero(walls[45:80, 75:175]), 0)

    def test_grouped_row_box_does_not_erase_walls_between_rooms(self):
        image = np.full((160, 220, 3), 235, np.uint8)
        cv2.line(image, (100, 0), (100, 159), (0, 0, 0), 5)
        readings = [rooms.Reading("3442 3440", (10, 50, 190, 30), .95),
                    rooms.Reading("3442", (10, 50, 50, 30), .95),
                    rooms.Reading("3440", (140, 50, 50, 30), .95)]
        self.assertGreater(rooms.wall_mask(image, readings)[60, 100], 0)

    def test_shared_candidate_is_flagged_without_losing_valid_multiple_labels(self):
        pred, audit = rooms.attach_labels(self.corridor(), [reading("3444", 100, 20), reading("3446", 100, 180)])
        self.assertEqual(len(pred["landmarks"]), 2)
        self.assertEqual(audit[0]["nodeId"], audit[1]["nodeId"])
        self.assertTrue(all("share" in a["reviewReason"] for a in audit))

    def test_fractional_center_at_image_border_does_not_abort_extraction(self):
        data = self.corridor()
        data["nodes"][0].update(y=80)
        data["nodes"][1].update(x=90, y=80)
        data["edges"][0].update(polyline=[[0, 80], [90, 80]], lengthPx=90)
        pred, audit = rooms.attach_labels(data, [reading("3540", 99.8, 50)], walls=np.zeros((100, 100), np.uint8))
        self.assertIsNotNone(audit[0]["nodeId"])
        rooms.validate_graph(pred)

    def test_short_admissible_connector_survives_longer_weighted_detour(self):
        walls = np.zeros((500, 750), np.uint8)
        walls[150:154, :560] = 255
        result = rooms._corridor_path((350, 50), np.array([[350, 250], [350, 300]]), walls, rooms.Params())
        self.assertIsNotNone(result)
        point, path = result
        self.assertEqual(point, [350, 250])
        self.assertLessEqual(sum(math.dist(a, b) for a, b in zip(path, path[1:])), 350)

    def test_small_wall_details_do_not_become_false_shared_doorway(self):
        image = np.full((280, 400, 3), 235, np.uint8)
        for a, b in [((50, 60), (350, 60)), ((50, 100), (50, 220)),
                     ((350, 100), (350, 220)), ((200, 100), (200, 220)),
                     ((50, 220), (350, 220))]:
            cv2.line(image, a, b, (0, 0, 0), 3)
        cv2.circle(image, (200, 60), 7, (235, 235, 235), -1)
        cv2.circle(image, (200, 60), 7, (0, 0, 0), 2)
        data = {"schemaVersion": 1, "buildingId": "test", "floor": "1", "startNodeId": "L",
                "nodes": [{"id": "L", "type": "START", "x": 20, "y": 20},
                          {"id": "R", "type": "JUNCTION", "x": 380, "y": 20},
                          {"id": "LE", "type": "DEAD_END", "x": 20, "y": 250},
                          {"id": "RE", "type": "DEAD_END", "x": 380, "y": 250}],
                "edges": [{"from": "L", "to": "R"}, {"from": "L", "to": "LE"},
                          {"from": "R", "to": "RE"}], "landmarks": []}
        labels = [reading("1001", 170, 120), reading("1002", 230, 120)]
        pred, audit = rooms.attach_labels(data, labels, walls=rooms.wall_mask(image, labels))
        self.assertEqual([a["point"][0] for a in audit], [20, 380])
        self.assertNotEqual(audit[0]["nodeId"], audit[1]["nodeId"])
        self.assertTrue(all("reviewReason" not in a for a in audit))
        rooms.validate_graph(pred)

    def test_short_wall_detail_is_kept_but_isolated_noise_is_not(self):
        image = np.full((160, 300, 3), 235, np.uint8)
        cv2.line(image, (10, 100), (290, 100), (0, 0, 0), 3)
        cv2.line(image, (150, 100), (150, 110), (0, 0, 0), 3)
        cv2.circle(image, (200, 30), 3, (0, 0, 0), -1)
        cv2.line(image, (180, 40), (280, 40), (40, 80, 190), 3)
        walls = rooms.wall_mask(image, [])
        self.assertGreater(walls[110, 150], 0)
        self.assertEqual(walls[30, 200], 0)


if __name__ == "__main__":
    unittest.main()
