# Android poster parsing and review

## Context

Issue #17 calls for CameraX capture plus the Python frame and corridor algorithms.
The expanded workflow also ports room OCR and wall-aware entrance placement from
#14, and gold-star detection and attachment from #15. Issue #16 owns editing and
verified saving. Issue #28 owns corridors without painted evacuation route lines.

## Decision

Use the existing CameraX and OpenCV 4.10.0 aliases. Poster OCR uses the
bundled PP-OCRv6 small models through OpenCV DNN. The debug scan-to-orient
activity retains ML Kit and still estimates a heading from a known map. Poster capture
creates a new draft and keeps it separate from the bundled demo map.

1. Detect the blue frame at a 1500 px long side. Apply the accepted source corners
   in TL, TR, BR, BL order to a 2000 px wide destination using the Python aspect
   estimate. Keep all stage inputs in those pixel coordinates. Retry weak blue
   chroma at up to four additional Lab b* units, capped at the original upper
   cutoff. Reapply the same fill check and reject clipped frames.
2. Use the same HSV mask, morphology, component rules, graph cleanup and line
   fit as the Python corridor extractor. The standard Android AAR lacks contrib
   thinning. Use the scikit-image 0.26 two-phase lookup table and ship its BSD
   notice. Process spur and merge candidates in a stable bottom-to-top order.
   Compare topology on the exact same image before judging the full photo path.
3. Use the same PP-OCRv6 small model weights as the Python prototype. Export
   the recognizer with a fixed 1 × 3 × 48 × 320 input and verify numerical
   equivalence within 1e-4. OpenCV 4.10 imports that graph. Bundle the weights,
   character vocabulary and checksums for offline first use. The detector runs
   on overlapping 1152 × 864 px tiles to bound native memory. Networks live for
   one read and are finalized afterwards. Rectify each text
   box, normalize BGR crops to [-1, 1], and decode CTC output while preserving
   repeated digits and suffixes. Multi-number lines use character positions to
   produce separate boxes. Word-sized proposals retry unread locations with the
   same recognizer at confidence 0.90. Reject crop-only legend numbers using
   prose context and EH3's 3xxx convention. This replaces the ML Kit atlas,
   isolated-ink and overlapping-window retries and their sequence heuristic.
   Score readings separately from door placement. Port MCP_Geometric's local
   eight-neighbour cost search with its path limits and thin-door fallback.
   If a connector is longer than 1.5 times the straight distance plus 20 px,
   report it for review. Attach rooms before assigning START.
4. Apply the gold/blue contour and shape tests. A second gold range accepts
   warmer, less saturated paint while retaining the original contour checks.
   Merge the two candidate lists spatially so separate stars stay ambiguous.
   If the star is over 40 px from the graph, retry route saturation only within
   150 px of that star. Accept the recovered graph only if it is connected and
   comes within 25 px of the marker. The regular route mask stays in use
   elsewhere. Limit automatic START attachment to 40 px, stricter than Python's
   100 px limit, to withhold unsupported distant matches. Preserve room
   landmarks on split edges. The unresolved START sentinel is an empty id, which fails
   structural validation. No shared model or dependency-catalog edits are needed.
5. Pass the draft to CAPTURE → VERIFY and display it with its rectified image.
   The #16 editor receives the bitmap, graph and warnings as one ParseResult and
   calls the session verification transition after correction and user confirmation.
   The preview keeps each OCR box and its proposed entrance ID in the parse
   result. It outlines printed labels and draws purple corridors. Green leader
   lines link rooms to entrances. Amber indicates an unplaced room or a shared
   entrance that needs review. Label tags appear as the user zooms in.

## Verification thresholds

Set before inspecting Android fixture results. Require matching accept/retake
decisions, median corner error ≤ 0.5% of trace diagonal and each accepted photo
≤ 0.75%. On the same rectified image, allow up to 3 percentage points of corridor
or junction F1 loss versus the Python baseline at 15 px corridor and 40 px
junction tolerances. For room OCR on the elevator fixture, target ≥ 85% precision
and ≥ 80% recall. At least 32 of the 52 traced entrances should match by label
within 40 px. Require both START detections, star centers within 2 px of Python,
snap distances within 10 px of Python and START position errors ≤ 25 px from
the trace. Score camera photos only after projecting to the trace's warp.

## Reproduction

From the repo root with the Python tool requirements installed and the Git LFS
fixtures hydrated, generate predictions in a temporary directory.

```sh
baseline=$(mktemp -d)
python tools/rectify.py evaluate data/fixtures/photos/ehall/*.jpg \
    --corners data/fixtures/rectify/ehall-corners.json
for poster in eh-3-elevators-evac eh-3-stair3424-evac; do
    fixture="data/fixtures/maps/$poster"
    python tools/corridors.py extract "$fixture.jpg" -o "$baseline/$poster.corridors.json"
    python tools/corridors.py score "$baseline/$poster.corridors.json" "$fixture.graph.json"
    python tools/room_labels.py extract "$fixture.jpg" --graph "$baseline/$poster.corridors.json" \
        -o "$baseline/$poster.rooms.json" --readings "$baseline/$poster.ocr.json"
    python tools/start_marker.py evaluate "$fixture.jpg" --graph "$baseline/$poster.rooms.json" \
        --truth "$fixture.graph.json" -o "$baseline/$poster.start.json" \
        --report "$baseline/$poster.start-report.json"
done
python tools/room_labels.py score "$baseline/eh-3-elevators-evac.ocr.json" \
    "$baseline/eh-3-elevators-evac.rooms.json" data/fixtures/maps/eh-3-elevators-evac.graph.json
```

Build the fixture runner with `./gradlew :mapparse:assembleDebugAndroidTest`.
Install it on a test Android device in airplane mode with Wi-Fi off, then run
the following commands. The runner uses a fresh app sandbox for the first-use
check. Its generated predictions and measurements stay separate from the traces.

```sh
adb install -r mapparse/build/outputs/apk/androidTest/debug/mapparse-debug-androidTest.apk
adb shell pm clear edu.wisc.maplens.mapparse.test
adb shell am instrument -w \
    edu.wisc.maplens.mapparse.test/edu.wisc.maplens.mapparse.PosterParserChecks
android=$(mktemp -d)
adb exec-out run-as edu.wisc.maplens.mapparse.test tar -cf - files/poster-eval \
    | tar -xf - -C "$android"
python tools/score_android_poster.py "$android/files/poster-eval" "$baseline"
```

The same-image scores use the canonical rectified fixture directly. The scorer
also runs Python attachment with the exported Android room boxes, corridor
graph and wall pixels. On the elevator front photo it runs the whole Python
corridor, OCR and attachment pipeline on the exact exported Android bitmap.
The photo scores transform the draft back through the Android homography and into
the corresponding manual warp before comparing coordinates. Stair 3424 has
no traced room entrances, so its OCR output has no held-out room accuracy
score.

## Results

Android 36 ARM64 emulator, offline, on the final tree. The elevator poster was
used for tuning. Stair 3424 is held out for corridors and has no traced entrances.
Frame decisions match Python on all 16 photos: 15/16 accepted, median corner error
7.83 px (0.31% of the trace diagonal), maximum 12.23 px (0.41%).

| Against the trace | Python | Android |
|---|---:|---:|
| Elevator room precision / recall, rectified and front photo | 94.2% / 96.1% | 94.2% / 96.1% |
| Elevator entrances within 40 px, rectified | 38/52 | 40/52 |
| Elevator entrances within 40 px, front photo | 36/52 | 40/52 |
| Other sharp elevator shots | — | 41, 40, 40 of 52 |
| Blurred elevator shot | — | 11/52 |
| Rectified corridor F1, elevator / stair | 90.8% / 92.9% | 92.3% / 92.0% |
| Rectified junction F1, elevator / stair | 80.0% / 85.7% | 75.0% / 75.0% |
| Front-photo corridor F1, elevator / stair | — | 91.2% / 94.7% |
| Front-photo junction F1, elevator / stair | — | 80.0% / 85.7% |
| START error, elevator rectified / front | 15.98 px / — | 15.98 / 26.96 px |
| START error, stair front | — | 8.43 px |

The rectified junction scores miss the 3-point limit above, and the elevator
front-photo START misses the 25 px limit. Both are accepted. Spur pruning stops at
60 px so the painted hook to the west EXIT survives, which separates rooms 3442 and
3440. Those hooks lead to real EXIT signs that the trace does not draw. Photos are
what the app parses, and their junction scores did not drop. The START error comes
from the trace marking the corridor center while the snap lands on the painted
route. Spurs ending within 40 px of a star keep a 90 px limit, because star paint
matches the route colour.

PP-OCRv6 replaced ML Kit, which reached 92.2% recall and 37/52 entrances on the
front photo. It costs memory and time on the emulator:

| Measure | Value |
|---|---:|
| Warmed parse | 7.7–8.6 s |
| Process PSS after parsing | 120–130 MiB |
| Peak RSS over four parses of one photo | 590 MiB |
| Peak RSS over the whole fixture run | about 640 MiB |
| Universal debug APK | 204.1 MiB (was 178.4 MiB) |

Smaller tiles lowered the peak but added the false rooms 3601 and 3040 and 20–25%
latency, so the tile size stays. Networks live for one read because OpenCV's Java
`Net` frees native memory only when finalized.

## Resolution limit

Small labels need pixels. Downscaling both front photos gives:

| Source pixels across the poster | Result |
|---|---|
| ≥ 873 | Recall ≥ 94.1%, no invented rooms |
| 555–714 | Recall ≥ 94.1%, 1–2 invented rooms, suffix labels start to vanish |
| ≤ 485 | Recall ≤ 66.7%, at least 7 invented rooms |

Confidence does not separate these, since invented rooms reach 0.97. Under 900 px
the review warns and under 500 px the photo is rejected. A sharpness check adds the
same warning for motion blur. Both limits are tuned on these fixtures.

The emulator's image-file camera stops at 1280 × 960, which gives 772 px across the
poster. Tight detector boxes clip blurred glyphs there: 3613 read as `36513` and 3419
as `34/15`, yet both read at 0.99 with more margin. Detector words under 0.99
confidence are now read again at 6, 10 and 14 px margins, and a room number at least
two reads agree on (each ≥ 0.75) replaces the first read. A suffix read stands,
because voting turned 3603A into 3603. Readings that do not start with 3 are dropped.
This assumes the third floor.

| OCR labels, 43 images, all used for tuning | Before | After |
|---|---:|---:|
| Emulator capture, correct / false | 45 / 3 | 49 / 1 |
| ≥ 950 px, 14 images, correct / false | 804 / 5 | 804 / 1 |
| 700–770 px, 14 images, correct / false | 784 / 10 | 787 / 5 |
| 550 px, 7 images, correct / false | 358 / 13 | 357 / 14 |
| Blurred shot at 5 sizes, correct / false | 113 / 43 | 126 / 30 |
| Average OCR time, sharp 950–1300 px | 6.9 s | 8.0 s |

All scores on the 3200 px photos are unchanged, except the blurred shot's recall
(37.3% to 47.1%) and entrances (11 to 13 of 52).

3556A and 3546A stay unread on that capture. Every crop, interpolation and sharpening
tried reads them as 3555A and 3545A, and 3623 splits evenly with 3523. Printing a
wrong room is worse than a gap, so these go to the #16 editor. They read correctly at
951 px across the poster. Capture prefers the closest size above 3200 × 2400, capped
at 4096 px. A physical phone has not been tested.

## Speed and progress

On `04-down` at 3200 px, alternating old and new logic in one run on the emulator:

| Measure | Before | After |
|---|---:|---:|
| Recognizer calls per parse | 178 | 156 |
| Parse time, average of five runs each | 37.9 s | 33.6 s (11% less) |
| Rooms found | 57 | 57 |

The emulator was throttled in that run, so only the ratio applies. Unthrottled warmed
parses took about 10 s, split as reading 50%, finding text 20%, placement 13% and the
word-region recheck 12%. A word is read once if an overlapping tile already gave a
certain room number, and a vote stops once two reads agree. Labels did not change on
19 test images. The camera preview also stops during parsing.

Dropping every duplicate box, or the word-region recheck, lost real rooms such as 3601
and 3602A across the test images, so both stay. The review shows the parser's own
stage and counts, and nothing advances on a timer.

## Showing doubt in the review

Labels this small cannot all be read at low resolution, so the review says which reads
are doubtful instead of hiding them. On 19 test images, flagging reads under 0.97
confidence caught all 3 wrong low-resolution reads and 8 of 9 wrong reads on the blurred
shot, and also flagged about 6% of right reads on sharp and low-resolution images.

Recheck reads under 0.90 were discarded before. They now appear as possible rooms,
marked with a dashed box, and stay out of the graph and the wall mask. This shows 3556A
and 3602A on the emulator capture. Across those images they gave 5 right and 2 wrong
on sharp and low-resolution images, and 2 right and 1 wrong on the blurred shot. Removing
wall lines before the recheck added reads and false rooms, and still missed 3546A.

At full zoom-out a label is about 17 × 9 dp, too small to show or tap, so the screen
lists rooms in rows of at least 72 dp, each with the photo of its label beside the number
read from it. Rooms that need a look get their own tab. The map opens with nothing
marked, and each small box shows the number read. Picking a room marks it without moving
the map. Tapping beside it, picking it again, changing tab or Back clears the mark. One
button below the map zooms to that room or returns to the full map, and a reviewed map
cannot be dragged or zoomed out of view. Correction stays with the #16 editor.

## Known misses

- 3444, 3445, 3446 and 3417 lie past the end of the painted line. This is #28, and
  the shared-entrance warning marks them.
- 3546, 3610, 3616 and 3609 reach another visible opening instead of the traced door.
- 3441 and 3424 reach the corridor through gaps left by removing route-coloured
  symbols. Treating those symbols as walls broke 3662 and 3332, so it was dropped.
- 3560 and 3622 are unread by both pipelines.
- Stair 3542 has no automatic START: its star stands 77.5 px from the route, past
  the 40 px limit. Allowing 100 px did not resolve it. The blurred shot is also
  withheld.

The capture activity uses ComponentActivity, which only ML Kit provides at compile
time. Moving ML Kit back to debug-only needs an `androidx.activity` catalog alias,
with all three owners' approval. CI also checks out without LFS, so it cannot run
the OCR models.

## Consequences

The review shows corridors, room proposals, the resolved START when available, and
warnings. It holds the draft in VERIFY. The #16 editor must connect correction,
human confirmation, and verified saving before a photographed map can enter
navigation. Corridors missing painted evacuation lines remain #28. Physical-phone
capture, runtime, and memory measurements remain outstanding.
