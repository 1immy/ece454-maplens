"""Tests for maplabel.py. Run: python -m unittest discover tools"""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).parent))
import maplabel  # noqa: E402


class GeometryTest(unittest.TestCase):
    def test_order_corners_any_click_order(self):
        shuffled = [(90, 110), (10, 5), (100, 8), (5, 100)]
        self.assertEqual(maplabel.order_corners(shuffled), [(10, 5), (100, 8), (90, 110), (5, 100)])

    def test_homography_maps_corners(self):
        src = [(0, 0), (100, 0), (100, 50), (0, 50)]
        dst = [(10, 20), (120, 5), (130, 90), (0, 70)]
        h = maplabel.homography(src, dst)
        for (x, y), (u, v) in zip(src, dst):
            p = h @ [x, y, 1]
            self.assertAlmostEqual(p[0] / p[2], u, places=6)
            self.assertAlmostEqual(p[1] / p[2], v, places=6)

    def test_warp_recovers_a_tilted_rectangle(self):
        # White 300x200 poster drawn in perspective on a black wall.
        quad = [(120, 80), (420, 120), (400, 330), (100, 300)]
        photo = Image.new("RGB", (520, 420), "black")
        ImageDraw.Draw(photo).polygon(quad, fill="white")
        flat = maplabel.warp(photo, quad, width=300)
        pixels = np.asarray(flat.convert("L"), dtype=float)
        inner = pixels[5:-5, 5:-5]
        self.assertGreater(inner.mean(), 250, "warped poster should be (almost) all white")
        w, h = flat.size
        self.assertAlmostEqual(h / w, 0.72, delta=0.08)


class GraphTest(unittest.TestCase):
    def chain(self):
        g = maplabel.Graph()
        s = g.add_node(0, 100, "START")
        j = g.add_node(0, 0, "JUNCTION")
        d = g.add_node(80, 0, "DOOR")
        g.connect(s, j)
        g.connect(j, d)
        g.labels[d] = "3650"
        return g, s, j, d

    def test_ids_are_prefixed_and_unique(self):
        g, s, j, d = self.chain()
        self.assertEqual((s, j, d), ("S1", "J2", "D3"))

    def test_only_one_start(self):
        g, s, *_ = self.chain()
        other = g.add_node(5, 5, "START")
        self.assertEqual(g.nodes[s]["type"], "JUNCTION")
        self.assertEqual(g.nodes[other]["type"], "START")

    def test_connect_ignores_duplicates_and_self_loops(self):
        g, s, j, _ = self.chain()
        self.assertFalse(g.connect(j, s))
        self.assertFalse(g.connect(s, s))
        self.assertEqual(len(g.edges), 2)

    def test_delete_removes_edges_and_labels(self):
        g, _, j, d = self.chain()
        g.delete_node(d)
        self.assertNotIn(d, g.labels)
        self.assertTrue(all(d not in e for e in g.edges))

    def test_undo(self):
        g, *_ = self.chain()
        g.checkpoint()
        g.add_node(1, 1, "JUNCTION")
        self.assertTrue(g.undo())
        self.assertEqual(len(g.nodes), 3)

    def test_problems(self):
        g, *_ = self.chain()
        self.assertEqual(g.problems(), [])
        g.add_node(500, 500, "DOOR")
        problems = g.problems()
        self.assertTrue(any("not connected" in p for p in problems))
        self.assertTrue(any("without a room label" in p for p in problems))

    def test_json_round_trip_and_counter_resumes(self):
        g, *_ = self.chain()
        data = json.loads(json.dumps(g.to_json()))
        self.assertEqual(data["schemaVersion"], 1)
        self.assertEqual(data["startNodeId"], "S1")
        self.assertEqual(data["landmarks"], [{"label": "3650", "nodeId": "D3"}])
        again = maplabel.Graph.from_json(data)
        self.assertEqual(again.to_json(), data)
        self.assertEqual(again.add_node(0, 0, "JUNCTION"), "J4")


class TracerTest(unittest.TestCase):
    """Drives the GUI with synthetic events on the headless Agg backend."""

    @classmethod
    def setUpClass(cls):
        import matplotlib
        matplotlib.use("Agg")
        cls.plt = maplabel._setup_matplotlib()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        image = Path(self.tmp.name) / "map.png"
        Image.new("RGB", (1000, 800), "white").save(image)
        self.out = Path(self.tmp.name) / "map.graph.json"
        self.t = maplabel.Tracer(str(image), str(self.out), maplabel.Graph(), self.plt)

    def tearDown(self):
        self.plt.close("all")
        self.tmp.cleanup()

    def click(self, x, y, button=1):
        self.t.on_click(SimpleNamespace(inaxes=self.t.ax, button=button, xdata=x, ydata=y))

    def key(self, *keys):
        for k in keys:
            self.t.on_key(SimpleNamespace(key=k))

    def test_trace_a_corridor_with_a_labeled_door(self):
        self.key("3")
        self.click(500, 700)              # START
        self.key("1")
        self.click(503, 300)              # junction, snaps to x=500
        self.key("2")
        self.click(700, 302)              # door, snaps to y=300, asks for label
        self.key("3", "6", "5", "0", "enter")
        self.key("escape")
        g = self.t.graph
        self.assertEqual(len(g.nodes), 3)
        self.assertEqual(len(g.edges), 2)
        self.assertEqual(g.nodes["J2"]["x"], 500)
        self.assertEqual(g.nodes["D3"]["y"], 300)
        self.assertEqual(g.labels, {"D3": "3650"})
        self.assertEqual(g.problems(), [])
        self.key("w")
        saved = json.loads(self.out.read_text())
        self.assertEqual(saved["startNodeId"], "S1")

    def test_clicking_two_existing_nodes_connects_them(self):
        self.key("escape")
        self.click(100, 100)
        self.key("escape")
        self.click(400, 100)
        self.click(100, 100)              # select A while B selected -> connects
        self.assertEqual(len(self.t.graph.edges), 1)

    def test_cut_edge_right_click_delete_and_undo(self):
        self.click(100, 100)
        self.click(400, 100)
        self.key("x")
        self.click(100, 100)
        self.assertEqual(self.t.graph.edges, [])
        self.click(400, 100, button=3)
        self.assertNotIn("J2", self.t.graph.nodes)
        self.key("u")
        self.assertIn("J2", self.t.graph.nodes)

    def test_number_keys_do_not_retype_selected_but_t_does(self):
        self.click(100, 100)
        self.key("4")
        self.assertEqual(self.t.graph.nodes["J1"]["type"], "JUNCTION")
        self.key("t")
        self.assertEqual(self.t.graph.nodes["J1"]["type"], "DEAD_END")


if __name__ == "__main__":
    unittest.main()
