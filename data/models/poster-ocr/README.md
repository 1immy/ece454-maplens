# Poster OCR assets

PP-OCRv6 small detector and recognizer from the models bundled in
`rapidocr==3.10.0`. The model and character hashes are in `manifest.json`.
The ONNX weights and vocabulary use Git LFS. Android bundles these files and needs no model
download on first use.

The recognizer has a fixed 1 × 3 × 48 × 320 input so OpenCV Android 4.10
can import it. Its weights and character vocabulary are preserved.
`tools/prepare_poster_ocr.py` verifies output equivalence within 1e-4 before
writing the assets. Model preparation uses `onnx` and `onnxsim`, whose exact
versions are recorded in the manifest. The Android runtime uses OpenCV.

The parser runs the detector on overlapping tiles, rectifies text boxes,
normalizes BGR crops to [-1, 1], and decodes CTC outputs. The recognizer has
40 time columns. Blank tokens separate repeated characters. Room suffixes
and separate readings of a multi-number row retain their own image bounds.
Wide text is constrained to the fixed recognizer input. Word proposals
provide another chance to read numbers grouped into long rows.

PP-OCR models are developed by PaddlePaddle. RapidOCR distributes the ONNX
models and character metadata. The Apache 2.0 license is included.

- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR)
- [RapidOCR](https://github.com/RapidAI/RapidOCR)
