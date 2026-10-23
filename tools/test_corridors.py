"""Tests for corridors.py on synthetic posters (no LFS data needed).
Run: python -m unittest discover tools"""
import math
import sys
import unittest
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import corridors  # noqa: E402

PAPER = (235, 238, 238)
ROUTE = (40, 80, 190)    # rust orange, BGR
WALL = (40, 40, 40)
RED = (40, 40, 200)


def synthetic_poster():
    """A T of corridors with route lines, walls, a filled red symbol and some text."""
    img = np.full((800, 1200, 3), PAPER, np.uint8)
    # walls along both sides of the two corridors
    for y in (330, 410):
        cv2.line(img, (100, y), (1100, y), WALL, 4)
    cv2.line(img, (560, 410), (560, 750), WALL, 4)
    cv2.line(img, (640, 410), (640, 750), WALL, 4)
    # evacuation route: horizontal corridor and the stem of the T
    cv2.line(img, (120, 370), (1080, 370), ROUTE, 6)
    cv2.line(img, (600, 370), (600, 730), ROUTE, 6)
    # extinguisher symbol next to the route (must not become a junction)
    cv2.circle(img, (300, 385), 14, RED, -1)
    cv2.putText(img, "3650", (200, 300), cv2.FONT_HERSHEY_SIMPLEX, 1.2, WALL, 3)
    truth = {
        "schemaVersion": 1, "buildingId": "t", "floor": "1", "startNodeId": "W",
        "nodes": [
            {"id": "W", "type": "START", "x": 120, "y": 370},
            {"id": "J", "type": "JUNCTION", "x": 600, "y": 370},
            {"id": "E", "type": "DEAD_END", "x": 1080, "y": 370},
            {"id": "S", "type": "DEAD_END", "x": 600, "y": 730},
        ],
        "edges": [{"from": "W", "to": "J"}, {"from": "J", "to": "E"}, {"from": "J", "to": "S"}],
        "landmarks": [],
    }
    return img, truth


class RefineTest(unittest.TestCase):
    def test_junction_moves_to_corridor_intersection(self):
        g = corridors.PixelGraph()
        # true corner at (200, 200); the skeleton put the junction 40 px off (a symbol bent the route)
        g.nodes = {0: (230, 228), 1: (0, 200), 2: (400, 200), 3: (200, 450)}
        line = lambda a, b: [(a[0] + (b[0] - a[0]) * t / 100, a[1] + (b[1] - a[1]) * t / 100) for t in range(101)]
        g.edges = [(0, 1, [(230, 228)] + line((185, 200), (0, 200))),
                   (0, 2, [(230, 228)] + line((215, 200), (400, 200))),
                   (0, 3, [(230, 228)] + line((200, 215), (200, 450)))]
        corridors.refine_junctions(g, near=20, far=150, max_move=80)
        x, y = g.nodes[0]
        self.assertLess(math.dist((x, y), (200, 200)), 3)

    def test_parallel_corridors_leave_junction_alone(self):
        g = corridors.PixelGraph()
        g.nodes = {0: (100, 100), 1: (0, 100), 2: (300, 100), 3: (0, 104)}
        g.edges = [(0, 1, [(100 - t, 100) for t in range(101)]),
                   (0, 2, [(100 + t, 100) for t in range(201)]),
                   (0, 3, [(100 - t, 100 + t * 0.04) for t in range(101)])]
        corridors.refine_junctions(g, near=20, far=150, max_move=80)
        self.assertEqual(g.nodes[0], (100, 100))


class CorridorTest(unittest.TestCase):
    def test_extracts_the_t_junction(self):
        img, truth = synthetic_poster()
        graph, mask = corridors.extract_graph(img)
        data = corridors.to_floorgraph_json(graph)
        junctions = [n for n in data["nodes"] if n["type"] == "JUNCTION"]
        self.assertEqual(len(junctions), 1, data["nodes"])
        self.assertLess(abs(junctions[0]["x"] - 600) + abs(junctions[0]["y"] - 370), 30)
        s = corridors.score(data, truth, shape=img.shape[:2])
        self.assertGreater(s["length_f1"], 0.9, s)
        self.assertEqual(s["junction_f1"], 1.0, s)

    def test_symbols_and_text_are_not_routes(self):
        img, _ = synthetic_poster()
        routes = corridors.clean_routes(corridors.route_mask(img), corridors.Params())
        self.assertEqual(routes[385, 300], 0)        # inside the red symbol
        self.assertEqual(routes[290, 230], 0)        # inside the room number
        self.assertGreater(routes[370, 400], 0)      # on the route line

    def test_score_identical_and_shifted(self):
        _, truth = synthetic_poster()
        same = corridors.score(truth, truth, shape=(800, 1200))
        self.assertAlmostEqual(same["length_f1"], 1.0, places=3)
        self.assertEqual(same["junction_f1"], 1.0)
        shifted = {**truth, "nodes": [{**n, "y": n["y"] + 200} for n in truth["nodes"]]}
        far = corridors.score(shifted, truth, shape=(800, 1200))
        self.assertLess(far["length_f1"], 0.5)
        self.assertEqual(far["junction_f1"], 0.0)

    def test_hand_trace_stubs_do_not_count_as_junctions(self):
        _, truth = synthetic_poster()
        with_door_stub = {**truth,
                          "nodes": truth["nodes"] + [{"id": "D", "type": "DOOR", "x": 300, "y": 395}],
                          "edges": truth["edges"] + [{"from": "W", "to": "D"}]}
        # W gets degree 2 (route + 25 px door stub); the stub is pruned, W is not a junction
        self.assertEqual(len(corridors.corridor_topology(with_door_stub, stub=45)), 1)


if __name__ == "__main__":
    unittest.main()
