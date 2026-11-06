"""Prepare the bundled Android OCR assets from rapidocr==3.10.0.

Run with onnx and onnxsim installed in the build environment. These packages
are model preparation tools. Android uses the existing OpenCV dependency.
"""

import hashlib
import importlib.metadata
import json
from pathlib import Path
import shutil


def main():
    import numpy as np
    import onnx
    import onnxruntime
    from onnxsim import simplify
    import rapidocr

    if importlib.metadata.version("rapidocr") != "3.10.0":
        raise ValueError("prepare assets from rapidocr==3.10.0")
    source = Path(rapidocr.__file__).parent / "models"
    output = Path(__file__).resolve().parents[1] / "data/models/poster-ocr"
    detector = source / "PP-OCRv6_det_small.onnx"
    recognizer = source / "PP-OCRv6_rec_small.onnx"
    original = onnx.load(recognizer)
    simplified, valid = simplify(
        original, overwrite_input_shapes={original.graph.input[0].name: [1, 3, 48, 320]}
    )
    if not valid:
        raise ValueError("fixed-shape recognizer failed ONNX equivalence checks")
    options = onnxruntime.SessionOptions()
    options.enable_mem_pattern = False
    before = onnxruntime.InferenceSession(
        original.SerializeToString(), options, providers=["CPUExecutionProvider"]
    )
    after = onnxruntime.InferenceSession(
        simplified.SerializeToString(), options, providers=["CPUExecutionProvider"]
    )
    generator = np.random.default_rng(17)
    max_error = 0.0
    for _ in range(3):
        pixels = generator.uniform(-1, 1, (1, 3, 48, 320)).astype(np.float32)
        expected = before.run(None, {before.get_inputs()[0].name: pixels})[0]
        actual = after.run(None, {after.get_inputs()[0].name: pixels})[0]
        error = float(np.max(np.abs(expected - actual)))
        max_error = max(max_error, error)
        if error > 1e-4 or not np.array_equal(expected.argmax(2), actual.argmax(2)):
            raise ValueError(f"fixed-shape recognizer changed output, maximum error {error}")
    output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(detector, output / "det.onnx")
    onnx.save(simplified, output / "rec.onnx")
    characters = next(item.value for item in original.metadata_props if item.key == "character")
    (output / "characters.txt").write_text(characters, encoding="utf-8")
    manifest = {
        "source": "rapidocr==3.10.0",
        "onnx": importlib.metadata.version("onnx"),
        "onnxsim": importlib.metadata.version("onnxsim"),
        "recognizerShape": [1, 3, 48, 320],
        "equivalenceMaxAbsoluteError": max_error,
        "sha256": {
            path.name: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in [output / "det.onnx", output / "rec.onnx", output / "characters.txt"]
        },
        "sourceRecognizerSha256": hashlib.sha256(recognizer.read_bytes()).hexdigest(),
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
