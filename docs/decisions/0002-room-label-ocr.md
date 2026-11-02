# 0002 · Offline room-label OCR prototype

## Context

Issue #14 needs room numbers attached to the Python corridor graph before the
Android parser port. ML Kit remains the planned Android recognizer. The supplied
straightened elevator image and trace share a 2000 × 1467 pixel coordinate system.
The 55 reference landmarks contain 52 room entrances (51 distinct room labels,
including suffixes; 3546 has two doors), two OFFMAP entries and one TO-EXIT entry.

## Initial baseline decision

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


## Refinement after screenshot review (Nov 1)

Use **RapidOCR 3.10.0 / PP-OCRv6 small** as the default Python recognizer, with
ONNX Runtime on CPU. Its wheel bundles the detection, orientation and recognition
weights. Pass explicit local model paths and disable ONNX telemetry; fail if a
model is absent rather than downloading at inference time. Keep Tesseract as an
explicit legacy comparison option, not an automatic fallback. Installation and
weights are additional prototype dependencies, not changes to the planned Android
ML Kit interface. Primary documentation:
[installation](https://rapidai.github.io/RapidOCRDocs/main/en/install_usage/rapidocr/install/),
[outputs](https://rapidai.github.io/RapidOCRDocs/main/en/install_usage/rapidocr/usage/).

Preserve whole descriptive lines, rejecting them as room labels. Split only lines
made entirely of room tokens using returned character boxes. This removes the
AED legend destinations without hardcoding text positions, room numbers, floor
prefixes or reference labels. Numeric row parent boxes must not erase intervening
room-divider walls; only their individual word boxes are erased.

Replace center projection in the CLI with a local least-cost image path to an
existing corridor sample (scikit-image MCP_Geometric). Long dark wall runs cost
80 per pixel versus 1 for free image space; colored route lines are removed from
the wall mask. Accept only paths ≤350 px with ≤6 sampled wall pixels, tolerating
thin door symbols. If the cheapest weighted path exceeds a hard cap, test direct alternatives before
rejecting; more complex admissible alternatives may still need manual review.
Save the actual connector polyline for review. Plain
`attach_labels(..., walls=None)` keeps the earlier projection API for geometry-only
callers. Splitting and scoring remain separate and unchanged in meaning.

The fixed 40 px entrance scorer reports 49/51 distinct labels and 35/52 entrances,
versus the original 31/51 and 11/52. These are tuned on the same matching poster,
not held-out evidence. Shared candidate nodes are flagged in the audit and overlay;
all labels remain available for manual verification. In particular the missing
lower-left corridor still prevents correct placement of 3444/3446/3445. The user
explicitly left corridor recovery under #28; no corridor extractor, shared model,
hand trace or room-label fixture was modified. See tools/README.md for remaining
errors and reproduction.


## Preserve door-frame detail (Nov 1)

The long-run-only wall mask dropped small curved/short strokes around a circular
mark above the 3612/3614 divider. That created a cheap false passage and merged
both room proposals with 3613 at the middle corridor point. Preserve original dark
pixels within an 8 px support radius of long wall strokes. Dilation grows only the
support region; no white gap is filled. Remove colored route pixels before seed
selection so route lines cannot promote nearby hatch noise into walls.

A synthetic pair of rooms reproduces the old shared middle anchor and verifies
separate side attachments after the fix. Another test preserves short wall detail
while rejecting isolated noise next to an orange route. On the same tuned poster,
3612 error changes from 227.0 to 9.8 px and 3614 from 233.0 to 9.3 px. Entrance
matches increase from 35/52 to 38/52; no previously correct room-label placement
regresses beyond 40 px. OCR stays 49/51. Missing corridors remain #28; no shared
model or hand trace changes are needed. This still relies on local wall evidence
rather than complete room segmentation or guaranteed physical-door detection.
