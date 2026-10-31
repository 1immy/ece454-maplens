# 0002 · Offline room-label OCR prototype

## Context

Issue #14 needs room numbers attached to the Python corridor graph before the
Android parser port. ML Kit remains the planned Android recognizer. The supplied
straightened elevator image and trace share a 2000 × 1467 pixel coordinate system.
The 55 reference landmarks contain 52 room entrances (51 distinct room labels,
including suffixes; 3546 has two doors), two OFFMAP entries and one TO-EXIT entry.

## Decision

Use Tesseract 5 English OCR via its command-line TSV interface, without a Python
wrapper dependency. Install the executable and English data separately; runtime
requires no network. Its word text, box and confidence form the small `Reading`
boundary that ML Kit can later supply. See the primary documentation for
[TSV and page segmentation](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage)
and [image quality](https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html).

Use sparse full-image OCR at 2× plus single-line OCR at 3× on text-sized component
crops. Adaptive thresholding groups candidate words and removes walls/hatch dots;
OCR reads the original grayscale crop with a white border. Keep four digits plus
an optional letter, strip spacing/separator punctuation and reject uncertain
O/0 or I/1 substitutions. Keep the highest-confidence reading within 15 px or with ≥50% overlap of the
smaller same-label box;
separate occurrences of one number survive. These parsing parameters live in
`Params` and were tuned on the elevator poster, not on held-out room data.

Project each accepted word center onto the nearest corridor polyline within
180 px, splitting the edge and recalculating geometric lengths. Reuse an edge
endpoint within 8 px; preserve existing landmarks and node types (a DEAD_END may
become a DOOR). Graph outputs remain unverified and retain the input coordinate
system. No reference label or coordinate is used to generate a prediction.

## Consequences and measurement

Recognition reports exact distinct-label precision and recall. Attachment matches
(label, position) one-to-one within 40 px of a traced entrance, maximizing valid
matches before minimizing distance. Duplicate predicted landmarks cannot inflate
matches. This separates text recognition from room-center/door displacement and
missing corridor edges. The reference lacks text boxes, so text recall alone does
not prove localization. Legend room mentions and labels absent from the trace
also count against reference text precision.

A single printed label cannot reveal both doors of room 3546; the prototype
preserves multiple supplied/detected entrances but does not invent unseen ones.
Nearest projection can choose the wrong side of a large room or the wrong
corridor. It does not detect physical openings. Keep these as visible review
items; wall/door inference and missing-route corridors remain later work.
The four-digit grammar and pixel defaults target the current 2000 px posters.
The stair-3424 corridor trace has no complete room annotations, so no held-out
OCR accuracy is claimed. See tools/README.md for reproduction and measured results.
