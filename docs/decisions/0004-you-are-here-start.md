# 0004 · Offline evacuation star → START

## Context

Issue #15 needs a gold-star detector, graph attachment and measured distance to
traced START on both EH3 evacuation posters. PR #46 (room OCR) was open/unmerged
when this branch started from fresh main at `81d0f4c`. Keep implementations
independent. Shared model changes and missing corridor recovery (#28) are outside
this issue. All geometry uses the exact straightened image's pixels.

## Decision

Use existing OpenCV HSV segmentation for gold, close small color-mask gaps, then
filter by area, aspect, solidity, at least four deep convexity defects and nearby
dark blue disk pixels. Choose the gold bounding-box center only when exactly one
candidate survives. Both real posters show this symbol; their green tornado icon
and rust evacuation arrows need shape/context discrimination. Color alone is
insufficient. OCR/template models add dependencies and are unnecessary for these
observed symbols. No dependencies added. Parsing settings live in Python Params.

Separate detection from attachment. Project onto every nonzero polyline segment
and choose the closest point within 100 px. Reuse only nonsemantic endpoints
within 2 px. Semantic endpoint conflicts return a manual-review reason rather
than destroying door/landmark meaning or creating a zero-length connector.
Otherwise split the existing edge at START, retaining its bends/metadata and
recomputing geometric lengths. Make optional edge IDs explicit before insertion
changes decoder indexes. Retain all landmarks, source and unrelated data. Demote
previous START to DEAD_END or JUNCTION by degree; the new startNodeId points to
the sole START. Always mark output unverified. Validate references, finite geometry,
positive lengths and connectivity without rewriting existing corridor geometry.

Missing, multiple, distant or conflicting markers produce audit/overlay and exit
2 without writing a new prediction graph, removing an earlier prediction at the
requested output path to prevent stale success after a rejected rerun. Do not advertise an old/placeholder
start as a detection. The scorer requires exactly one START matching startNodeId.

## Consequences and evidence

Prediction reads only image and independently extracted corridors. Evaluation
loads the hand trace afterward. Save marker, projection, snap distance, traced
START and Euclidean START error separately. Both posters informed tuning; neither
is held out. Results are 15.98 px (elevators) and 7.46 px (stair3424), with 4.78/4.94
px snaps. The extracted route line detours around symbols, so small snap distance
does not imply small corridor-center error. No correction is fitted to the trace.
See tools/README.md for exact commands and diagnostic outputs.

The prototype targets the observed gold-on-blue style at 2000 px width; other
styles/resolutions and glare/occlusion can require a retake or manual START. No
Android/ML Kit integration, directory-dot support or corridor recovery is claimed.
An OCR-enriched graph is supported structurally through landmark/metadata tests;
real PR #46 integration must be rechecked after it merges.
