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


def hsv_to_bgr(h, s, v):
    return tuple(int(c) for c in cv2.cvtColor(np.uint8([[[h, s, v]]]), cv2.COLOR_HSV2BGR)[0, 0])


def fringed_walls():
    """A corridor whose walls pick up route colour, as a photo of a screen does, and a faint
    painted line beside a wall with no stronger paint near it."""
    img = np.full((400, 1200, 3), PAPER, np.uint8)
    fringe = hsv_to_bgr(10, 90, 120)
    for x in (570, 630, 900):
        cv2.line(img, (x, 20), (x, 380), WALL, 4)
    cv2.line(img, (600, 20), (600, 380), ROUTE, 6)
    for x in (573, 627, 903):
        cv2.line(img, (x, 40), (x, 360), fringe, 2)
    return img


class WallFringeTest(unittest.TestCase):
    def test_wall_fringe_beside_paint_is_not_a_route(self):
        routes = corridors.route_mask(fringed_walls())
        self.assertGreater(routes[200, 600], 0)
        self.assertEqual(routes[200, 573], 0)
        self.assertEqual(routes[200, 627], 0)

    def test_faint_line_with_no_paint_near_it_stays(self):
        self.assertGreater(corridors.route_mask(fringed_walls())[200, 903], 0)

    def test_fringed_corridor_traces_as_one_line(self):
        img = fringed_walls()
        cv2.rectangle(img, (850, 0), (1000, 400), PAPER, -1)
        graph, _ = corridors.extract_graph(img)
        self.assertEqual(len(graph.edges), 1)
        xs = [x for x, _ in graph.edges[0][2]]
        self.assertLess(max(abs(x - 600) for x in xs), 5)


def walled_corridors():
    """A floor plan with no paint on a blue backdrop, like a directory poster: a T of corridors
    between rooms, doors and room numbers."""
    img = np.full((800, 1200, 3), (200, 170, 150), np.uint8)
    cv2.rectangle(img, (100, 100), (1100, 700), PAPER, -1)
    cv2.line(img, (100, 300), (1100, 300), WALL, 3)
    cv2.line(img, (100, 340), (580, 340), WALL, 3)
    cv2.line(img, (620, 340), (1100, 340), WALL, 3)
    for x in (580, 620):
        cv2.line(img, (x, 340), (x, 700), WALL, 3)
    cv2.rectangle(img, (100, 100), (1100, 700), WALL, 3)
    for x in range(250, 1100, 150):              # rooms too short to look like corridors
        cv2.line(img, (x, 100), (x, 300), WALL, 3)
    for x in (250, 400, 770, 920):
        cv2.line(img, (x, 340), (x, 700), WALL, 3)
    cv2.line(img, (100, 520), (580, 520), WALL, 3)
    cv2.line(img, (620, 520), (1100, 520), WALL, 3)
    for door in (170, 320, 470, 690, 840, 990):  # gaps in the corridor walls
        cv2.line(img, (door, 300), (door + 12, 300), PAPER, 5)
        cv2.line(img, (door, 340), (door + 12, 340), PAPER, 5)
    cv2.putText(img, "3650", (180, 220), cv2.FONT_HERSHEY_SIMPLEX, 1.0, WALL, 2)
    truth = {
        "schemaVersion": 1, "buildingId": "t", "floor": "1", "startNodeId": "W",
        "nodes": [
            {"id": "W", "type": "START", "x": 105, "y": 320},
            {"id": "J", "type": "JUNCTION", "x": 600, "y": 320},
            {"id": "E", "type": "DEAD_END", "x": 1095, "y": 320},
            {"id": "S", "type": "DEAD_END", "x": 600, "y": 695},
        ],
        "edges": [{"from": "W", "to": "J"}, {"from": "J", "to": "E"}, {"from": "J", "to": "S"}],
        "landmarks": [],
    }
    return img, truth


def painted_u_with_unpainted_link():
    """Paint turns away at two corners. The corridor between those corners has no paint."""
    img = np.full((800, 1200, 3), PAPER, np.uint8)
    for y in (650, 710):
        cv2.line(img, (60, y), (1140, y), WALL, 3)
    for x in (270, 330, 870, 930):
        cv2.line(img, (x, 90), (x, 650), WALL, 3)
    for y in (90, 150):
        cv2.line(img, (270, y), (930, y), WALL, 3)
    route = [(100, 680), (300, 680), (300, 120), (900, 120), (900, 680), (1100, 680)]
    cv2.polylines(img, [np.array(route, np.int32)], False, ROUTE, 6)
    return img


class NoPaintTest(unittest.TestCase):
    def test_corridors_come_from_the_walls(self):
        img, truth = walled_corridors()
        graph, _ = corridors.extract_graph(img)
        data = corridors.to_floorgraph_json(graph)
        s = corridors.score(data, truth, shape=img.shape[:2])
        self.assertGreater(s["length_f1"], 0.9, s)
        self.assertEqual(s["junction_f1"], 1.0, s)

    def test_pink_rooms_are_not_paint(self):
        # A camera can push pink room fills into the route colour in scattered patches.
        img, truth = walled_corridors()
        for x0, x1 in ((100, 250), (250, 400), (400, 550), (700, 850), (850, 1000)):
            for y0, y1 in ((100, 300), (340, 520)):
                room = img[y0 + 4:y1 - 4, x0 + 4:x1 - 4]
                rows, cols = np.indices(room.shape[:2])
                room[(rows + cols) % 2 == 0] = hsv_to_bgr(172, 90, 210)
        graph, _ = corridors.extract_graph(img)
        data = corridors.to_floorgraph_json(graph)
        s = corridors.score(data, truth, shape=img.shape[:2])
        self.assertGreater(s["length_f1"], 0.9, s)

    def test_striped_pink_rooms_are_not_paint(self):
        # Striped pink joins into one network once widened, so only its filled look gives it away.
        img, truth = walled_corridors()
        for x0, x1 in ((100, 250), (250, 400), (400, 550), (700, 850), (850, 1000)):
            for y0, y1 in ((100, 300), (340, 520)):
                for y in range(y0 + 4, y1 - 4, 6):
                    img[y:y + 3, x0 + 4:x1 - 4] = hsv_to_bgr(172, 90, 210)
        self.assertGreater(corridors.in_fills(img), 0.4)
        graph, _ = corridors.extract_graph(img)
        s = corridors.score(corridors.to_floorgraph_json(graph), truth, shape=img.shape[:2])
        self.assertGreater(s["length_f1"], 0.9, s)

    def test_coloured_text_does_not_block_a_corridor(self):
        img, _ = walled_corridors()
        cv2.putText(img, "You", (700, 328), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (120, 40, 140), 2)
        self.assertEqual(corridors.off_paper(img)[320, 720], 0)

    def test_blue_frame_is_off_the_paper(self):
        img, _ = walled_corridors()
        cv2.rectangle(img, (0, 0), (1199, 799), (200, 170, 150), 40)
        outside = corridors.off_paper(img)
        self.assertGreater(outside[5, 600], 0)
        self.assertEqual(outside[320, 400], 0)


class LinkTest(unittest.TestCase):
    def test_unpainted_corridor_joins_the_two_corners(self):
        graph, _ = corridors.extract_graph(painted_u_with_unpainted_link())
        data = corridors.to_floorgraph_json(graph)
        samples = corridors._sample(corridors._graph_polylines(data)[0])
        between = samples[(samples[:, 0] > 400) & (samples[:, 0] < 800) & (samples[:, 1] > 600)]
        self.assertGreater(len(between), 100, data["edges"])
        self.assertLess(abs(between[:, 1] - 680).max(), 15)

    def test_a_link_does_not_enter_a_room_through_a_door(self):
        img = np.full((800, 1200, 3), PAPER, np.uint8)
        for y in (330, 410):
            cv2.line(img, (100, y), (1100, y), WALL, 4)
        cv2.rectangle(img, (400, 410), (800, 700), WALL, 4)
        cv2.line(img, (590, 410), (610, 410), PAPER, 6)  # a door, opposite the stem
        cv2.line(img, (560, 330), (560, 80), WALL, 4)
        cv2.line(img, (640, 330), (640, 80), WALL, 4)
        cv2.line(img, (560, 330), (640, 330), PAPER, 6)
        cv2.line(img, (120, 370), (1080, 370), ROUTE, 6)
        cv2.line(img, (600, 370), (600, 100), ROUTE, 6)
        graph, _ = corridors.extract_graph(img)
        self.assertLess(max(y for _, _, pts in graph.edges for _, y in pts), 420)

    def test_pieces_join_through_free_space(self):
        img, _ = walled_corridors()
        p = corridors.Params()
        graph = corridors.PixelGraph()
        graph.nodes = {0: (105.0, 320.0), 1: (400.0, 320.0), 2: (800.0, 320.0), 3: (1095.0, 320.0)}
        line = lambda a, b: [(float(x), 320.0) for x in range(a, b + 1)]
        graph.edges = [(0, 1, line(105, 400)), (2, 3, line(800, 1095))]
        corridors.join_networks(graph, corridors.free_space(img, p), p)
        self.assertEqual(len(corridors._networks(graph)[1]), 1)


class FaintPaintTest(unittest.TestCase):
    def test_faint_paint_joins_solid_paint_but_not_on_its_own(self):
        img = np.full((400, 1200, 3), PAPER, np.uint8)
        faint = hsv_to_bgr(8, 55, 190)
        cv2.line(img, (100, 100), (400, 100), ROUTE, 6)
        cv2.line(img, (400, 100), (800, 100), faint, 6)
        cv2.line(img, (100, 300), (500, 300), faint, 6)
        routes = corridors.route_mask(img)
        self.assertGreater(routes[100, 600], 0)
        self.assertEqual(routes[300, 300], 0)

    def test_faint_paint_beside_a_wall_is_fringe(self):
        img = np.full((400, 1200, 3), PAPER, np.uint8)
        cv2.line(img, (100, 100), (400, 100), ROUTE, 6)
        cv2.line(img, (400, 100), (800, 100), hsv_to_bgr(8, 55, 190), 6)
        cv2.line(img, (500, 40), (500, 300), WALL, 4)
        cv2.line(img, (503, 110), (503, 290), hsv_to_bgr(8, 55, 190), 2)
        self.assertEqual(corridors.route_mask(img)[200, 503], 0)


def corridor_with_dark_paint():
    """A painted corridor 60 px wide whose rust paint is as dark as a wall in a phone photo."""
    img = np.full((600, 1200, 3), PAPER, np.uint8)
    for y in (270, 330):
        cv2.line(img, (60, y), (1140, y), WALL, 3)
    cv2.line(img, (100, 300), (1100, 300), hsv_to_bgr(10, 80, 115), 6)
    return img


class CameraArtifactTest(unittest.TestCase):
    def test_dark_paint_does_not_split_its_corridor(self):
        img = corridor_with_dark_paint()
        p = corridors.Params()
        self.assertGreater(corridors.free_space(img, p, corridors.corridor_mask(img, p)).width, 45)

    def test_exit_sign_is_not_traced(self):
        img = corridor_with_dark_paint()
        cv2.rectangle(img, (500, 340), (580, 370), RED, -1)
        cv2.putText(img, "EXIT", (505, 364), cv2.FONT_HERSHEY_SIMPLEX, 0.7, PAPER, 2)
        cv2.line(img, (540, 300), (540, 340), ROUTE, 6)
        graph, _ = corridors.extract_graph(img)
        self.assertLess(max(y for _, _, pts in graph.edges for _, y in pts), 345)

    def test_colour_fringe_beside_a_light_wall_goes(self):
        img = np.full((400, 1200, 3), PAPER, np.uint8)
        cv2.line(img, (100, 120), (1100, 120), ROUTE, 6)
        cv2.line(img, (100, 140), (1100, 140), (120, 120, 120), 4)  # lighter than WALL_MAX_V
        cv2.line(img, (100, 145), (1100, 145), hsv_to_bgr(10, 90, 150), 2)
        self.assertEqual(corridors.route_mask(img)[145, 600], 0)
        self.assertGreater(corridors.route_mask(img)[120, 600], 0)

    def test_side_by_side_lines_keep_the_shorter(self):
        graph = corridors.PixelGraph()
        graph.nodes = {0: (0.0, 0.0), 1: (300.0, 0.0)}
        straight = [(float(x), 0.0) for x in range(301)]
        bump = [(float(x), 30.0 * math.sin(math.pi * x / 300)) for x in range(301)]
        graph.edges = [(0, 1, bump), (0, 1, straight)]
        corridors.drop_parallel(graph, 45)
        self.assertEqual([pts for _, _, pts in graph.edges], [straight])


class ScreenPhotoTest(unittest.TestCase):
    """Phone photos of a poster on a screen: thick paint and symbols drawn over paint."""

    def test_thick_crossing_is_not_a_sign(self):
        img = np.full((800, 800, 3), PAPER, np.uint8)
        cv2.line(img, (100, 400), (700, 400), ROUTE, 8)
        cv2.line(img, (400, 100), (400, 700), ROUTE, 8)
        cv2.circle(img, (400, 400), 20, ROUTE, -1)
        routes = corridors.clean_routes(corridors.route_mask(img), corridors.Params())
        self.assertGreater(routes[400, 400], 0)

    def test_blurred_line_is_not_a_sign(self):
        img = np.full((400, 400, 3), PAPER, np.uint8)
        cv2.line(img, (200, 140), (200, 260), ROUTE, 14)
        routes = corridors.clean_routes(corridors.route_mask(img), corridors.Params())
        self.assertGreater(routes[200, 200], 0)

    def test_paint_carries_on_past_a_symbol(self):
        # A symbol over the paint cut it, the far piece hooks round the symbol, and the corridor
        # narrows there, so no link gets through.
        img = np.full((600, 1600, 3), PAPER, np.uint8)
        for y in (270, 330):
            cv2.line(img, (60, y), (1540, y), WALL, 3)
        cv2.line(img, (800, 270), (800, 290), WALL, 3)
        cv2.line(img, (800, 330), (800, 310), WALL, 3)
        cv2.line(img, (100, 300), (740, 300), ROUTE, 6)
        cv2.line(img, (1500, 300), (880, 300), ROUTE, 6)
        cv2.line(img, (880, 300), (850, 280), ROUTE, 6)
        graph, _ = corridors.extract_graph(img)
        self.assertEqual(len(corridors._networks(graph)[1]), 1)
        self.assertTrue(any(abs(x - 800) < 5 and abs(y - 300) < 15 for _, _, pts in graph.edges for x, y in pts))


def colour_gap(broken: bool):
    """Two traced lines whose paint still joins on the photo, jogging round a symbol. When broken,
    the colour stops 30 px short."""
    graph = corridors.PixelGraph()
    graph.nodes = {0: (100.0, 100.0), 1: (100.0, 300.0), 2: (130.0, 450.0), 3: (130.0, 700.0)}
    graph.edges = [(0, 1, corridors._straight(graph.nodes[0], graph.nodes[1])),
                   (2, 3, corridors._straight(graph.nodes[2], graph.nodes[3]))]
    routes = np.zeros((800, 300), np.uint8)
    for a, b in [((100, 100), (100, 330)), ((100, 330), (130, 420)), ((130, 420), (130, 700))]:
        cv2.line(routes, a, b, 255, 9)
    if broken:
        routes[360:390] = 0
    return graph, routes


class ColourJoinTest(unittest.TestCase):
    def test_paint_end_joins_where_the_colour_runs_on(self):
        graph, routes = colour_gap(broken=False)
        graph = corridors.join_by_colour(graph, routes, 40.0, corridors.Params())
        self.assertEqual(len(corridors._networks(graph)[1]), 1)

    def test_no_join_where_the_colour_stops(self):
        graph, routes = colour_gap(broken=True)
        graph = corridors.join_by_colour(graph, routes, 40.0, corridors.Params())
        self.assertEqual(len(corridors._networks(graph)[1]), 2)


def painted_loop():
    """Paint turns down at a rounded corner where an unpainted corridor carries straight on, and a
    painted hallway stops short of its end wall. The hallway's top wall is missing, as on a scan."""
    img = np.full((1100, 1300, 3), PAPER, np.uint8)
    cv2.line(img, (100, 300), (705, 300), ROUTE, 6)
    cv2.ellipse(img, (705, 325), (25, 25), 0, 270, 360, ROUTE, 6)
    for a, b in [((730, 325), (730, 900)), ((500, 900), (1100, 900)), ((1100, 300), (1100, 900))]:
        cv2.line(img, a, b, ROUTE, 6)
    for a, b in [((60, 270), (1130, 270)), ((60, 330), (700, 330)), ((760, 330), (1070, 330)),
                 ((700, 330), (700, 870)), ((760, 330), (760, 870)), ((1070, 330), (1070, 870)),
                 ((1130, 270), (1130, 930)), ((760, 870), (1070, 870)), ((300, 930), (1130, 930)),
                 ((300, 870), (300, 930)), ((60, 270), (60, 330))]:
        cv2.line(img, a, b, WALL, 3)
    return img


class HallwayTest(unittest.TestCase):
    def test_unpainted_corridor_carries_on_past_a_rounded_corner(self):
        graph, _ = corridors.extract_graph(painted_loop())
        self.assertTrue(any(800 < x < 1040 and abs(y - 300) < 15 for _, _, pts in graph.edges for x, y in pts))

    def test_painted_hallway_runs_to_its_end_wall(self):
        graph, _ = corridors.extract_graph(painted_loop())
        self.assertLess(min(x for _, _, pts in graph.edges for x, y in pts if abs(y - 900) < 15), 320)


class LadderTest(unittest.TestCase):
    def test_patchy_band_traces_as_one_line(self):
        # Paint with fringe both sides closes into a band full of small holes: a ladder.
        mask = np.zeros((800, 400), np.uint8)
        for x in (170, 200, 230):
            cv2.line(mask, (x, 50), (x, 750), 255, 9)
        for y in range(50, 751, 35):
            cv2.line(mask, (170, y), (230, y), 255, 9)
        p = corridors.Params()
        graph = corridors._graph_from_mask(mask, p.spur_length, p.merge_radius, p)
        xs = [x for _, _, pts in graph.edges for x, _ in pts]
        ys = [y for _, _, pts in graph.edges for _, y in pts]
        self.assertLess(max(abs(x - 200) for x in xs), 15)
        self.assertGreater(max(ys) - min(ys), 600)

    def test_merging_stops_at_the_radius(self):
        graph = corridors.PixelGraph()
        graph.nodes = {i: (100.0, 100.0 + 40 * i) for i in range(10)}
        graph.edges = [(i, i + 1, [(100.0, 100.0 + 40 * i + t) for t in range(41)]) for i in range(9)]
        corridors.merge_close_nodes(graph, 60)
        ys = sorted(y for _, y in graph.nodes.values())
        self.assertGreater(ys[-1] - ys[0], 250)


class PolylineTest(unittest.TestCase):
    def test_moved_junction_does_not_tilt_a_straight_corridor(self):
        pts = [(float(x), 100.0) for x in range(0, 1001)]
        line = corridors.edge_polyline((10.0, 130.0), (1000.0, 100.0), pts)
        self.assertEqual(line[0], (10.0, 130.0))
        self.assertLess(max(abs(y - 100) for _, y in line[1:]), 2)

    def test_moved_junction_keeps_the_corner(self):
        pts = [(float(x), 100.0) for x in range(0, 301)] + [(300.0, float(y)) for y in range(101, 400)]
        line = corridors.edge_polyline((0.0, 140.0), (300.0, 399.0), pts)
        self.assertTrue(any(math.dist(p, (300, 100)) < 4 for p in line), line)


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
