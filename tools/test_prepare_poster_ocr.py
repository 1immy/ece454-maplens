import hashlib
import json
from pathlib import Path
import re
import unittest


class PosterOcrAssetsTest(unittest.TestCase):
    def test_bundled_assets_match_the_verified_export(self):
        folder = Path(__file__).resolve().parents[1] / "data/models/poster-ocr"
        manifest = json.loads((folder / "manifest.json").read_text())
        self.assertEqual(manifest["source"], "rapidocr==3.10.0")
        self.assertEqual(manifest["recognizerShape"], [1, 3, 48, 320])
        self.assertLessEqual(manifest["equivalenceMaxAbsoluteError"], 1e-4)
        for name, expected in manifest["sha256"].items():
            with self.subTest(asset=name):
                data = (folder / name).read_bytes()
                # CI checks out without LFS. A pointer records the same SHA-256 as its file.
                pointer = re.search(rb"^oid sha256:(\w+)$", data[:200], re.MULTILINE)
                actual = pointer[1].decode() if pointer else hashlib.sha256(data).hexdigest()
                self.assertEqual(actual, expected)


if __name__ == "__main__":
    unittest.main()
