import unittest

import numpy as np

from score_android_poster import register_graph, registration


class RegistrationTest(unittest.TestCase):
    def test_undoes_capture_resize_and_warp_before_scoring(self):
        trace = {
            "size": [400, 300],
            "corners": [[40, 60], [360, 60], [360, 240], [40, 240]],
        }
        report = {
            "sourceSize": [200, 150],
            "width": 161,
            "height": 91,
            "corners": [[30, 30], [190, 30], [190, 120], [30, 120]],
        }
        transform = registration(report, trace, (321, 181))
        projected = transform @ [80, 45, 1]
        np.testing.assert_allclose(projected[:2] / projected[2], [180, 90])

    def test_registers_nodes_and_remeasures_corridor_length(self):
        graph = {
            "nodes": [{"x": 0, "y": 0}, {"x": 3, "y": 4}],
            "edges": [{"polyline": [[0, 0], [3, 4]], "lengthPx": 5}],
        }
        register_graph(graph, np.array([[2, 0, 3], [0, 2, 4], [0, 0, 1]]))
        self.assertEqual(graph["nodes"], [{"x": 3, "y": 4}, {"x": 9, "y": 12}])
        self.assertEqual(graph["edges"][0]["polyline"], [[3, 4], [9, 12]])
        self.assertEqual(graph["edges"][0]["lengthPx"], 10)
