"""Tests for rectify.py on synthetic photos (no LFS data needed).
Run: python -m unittest discover tools"""
import sys
import unittest
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import maplabel  # noqa: E402
import rectify  # noqa: E402

WALL = (170, 190, 205)    # warm off-white, BGR
BLUE = (205, 175, 150)    # light poster blue, BGR
PAPER = (245, 248, 248)


def warp_points(h, pts):
    p = np.c_[np.asarray(pts, float), np.ones(len(pts))] @ h.T
    return p[:, :2] / p[:, 2:]


def synthetic_poster(with_legend=True, crop=False):
    """Flat poster with a blue frame (+ a blue legend box above it), photographed in perspective."""
    flat = np.full((1200, 900, 3), PAPER, np.uint8)
    frame = [(60, 330), (840, 330), (840, 900), (60, 900)]
    cv2.rectangle(flat, frame[0], frame[2], BLUE, -1)
    cv2.rectangle(flat, (95, 365), (805, 865), PAPER, -1)            # white map area inside the frame
    for x in range(140, 780, 90):                                     # some walls and text
        cv2.line(flat, (x, 400), (x, 820), (40, 40, 40), 3)
    cv2.putText(flat, "ENGINEERING DRIVE", (300, 357), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (30, 30, 30), 2)
    if with_legend:
        cv2.rectangle(flat, (470, 180), (840, 318), BLUE, -1)        # legend box, 12 px above the frame
        cv2.rectangle(flat, (485, 195), (825, 303), PAPER, -1)

    photo = np.full((1600, 1200, 3), WALL, np.uint8)
    src = [(0, 0), (900, 0), (900, 1200), (0, 1200)]
    dst = [(260, 180), (980, 250), (950, 1420), (230, 1380)]
    h = maplabel.homography(src, dst)
    warped = cv2.warpPerspective(flat, h, (1200, 1600))
    mask = cv2.warpPerspective(np.full(flat.shape[:2], 255, np.uint8), h, (1200, 1600))
    photo[mask > 0] = warped[mask > 0]
    truth = warp_points(h, frame)
    if crop:
        photo = photo[:, 560:]                                        # cut through the frame
        truth = truth - [560, 0]
    photo = cv2.GaussianBlur(photo, (3, 3), 0)
    return photo, [tuple(p) for p in truth]


class RectifyTest(unittest.TestCase):
    def test_finds_frame_and_ignores_legend_box(self):
        photo, truth = synthetic_poster(with_legend=True)
        det, _ = rectify.detect(photo)
        self.assertIsNotNone(det.corners)
        _, pct = rectify.corner_error(det.corners, truth)
        self.assertLess(pct, 1.0, f"corner error {pct:.2f}% of diagonal")
        self.assertEqual(det.reason, "")

    def test_adaptive_threshold_follows_the_wall(self):
        b = np.full((100, 100), 135, np.uint8)
        self.assertEqual(rectify.adaptive_threshold(b), 128)          # clamped
        b[:] = 130
        self.assertEqual(rectify.adaptive_threshold(b), 123)

    def test_cropped_frame_is_flagged_for_retake(self):
        photo, _ = synthetic_poster(crop=True)
        det, _ = rectify.detect(photo)
        self.assertIsNotNone(det.corners)
        self.assertLess(det.score, rectify.MIN_FILL)
        self.assertIn("retake", det.reason)

    def test_no_blue_means_no_detection(self):
        det, _ = rectify.detect(np.full((800, 600, 3), WALL, np.uint8))
        self.assertIsNone(det.corners)


if __name__ == "__main__":
    unittest.main()
