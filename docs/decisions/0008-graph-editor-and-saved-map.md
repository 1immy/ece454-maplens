# Graph editor and saved map

## Context

Issue #16 lets a person correct a draft graph and save it as verified. Until now a photographed
map stopped at the read-only review (ADR 0007). Nothing persisted a graph, and the report's Room
database would need a dependency change. Section 7 of `project-AI-context.md` asks for four save
rules, while `FloorGraph.validate()` only checks references, connectivity and edge lengths.

## Decision

**Saving.** One verified map and one draft in app-private storage, each a `FloorGraphJson` file
beside its straightened JPEG. `FloorGraphJson` drops `source.image`, so the image sits next to the
graph. No Room and no new dependency. Saving writes a new folder and swaps it in with two renames,
and a crash between them still finds the old map. Multiple maps and a picker are left to #20 if a
second floor comes into scope.

**One gate.** `mapProblems` in `app` holds the rules. The editor reads it to explain what's wrong,
and `NavigationSession.onGraphVerified` applies it, so a graph can't become verified another way.
No `core/model` change. Moving these rules into `validate()` can be proposed to all three owners.

| Section 7 rule | What it does here |
|---|---|
| Connected | Blocks saving |
| Each destination links to a door | Blocks if its node is missing or can't be reached. Warns if the node is not a `DOOR`, because the parser reuses a junction within 8 px |
| Labels unique | **Changed.** A label may repeat, because a room with several entrances has one door node per entrance (3546 on the elevator poster) and routing picks the nearest. Empty labels block. One node with two labels warns |
| No crossing without a junction | Blocks, with a one-tap fix that adds the junction |

Doubtful rooms and rooms without a door warn in the save confirmation and don't block. The doubt
flag catches about 6% of right reads (ADR 0007), and rooms past the painted line wait for #28.

**START.** Never filled in automatically. It stays unset until someone places it. It may replace a
plain junction or corridor end within the parser's 2 px, never a door or exit. Moving it removes
the point that only held the old START and rejoins the corridor.

**Splitting and deleting.** Editing reuses the parser's `split`, so node ids and landmarks survive.
Deleting a point with two corridors rejoins them, so removing one door doesn't cut a corridor.

**Touch.** On the traced elevator graph the closest points are 3.5 px apart, and 10% of points have
a neighbour within 7.2 px. On a 360 dp wide phone at the 8x zoom limit, 3.5 px is about 5 dp, so
tapping alone can't separate them. A tap reaches 24 dp. If several points are in reach, the map
zooms in, and past 3x it lists them in 52 dp rows. Placing uses a fixed cross with the map moved
under it, so a finger never covers the spot. Undo replaces delete confirmations, and the panel under
the map replaces dialogs.

**Predicted numbers.** What the parser read stays on the map for the whole edit. Tapping a number
opens its photo beside the reading, and the number field starts filled with that reading, so a wrong
read can be seen and corrected in place.

**Wall fringe.** A Pixel 7a scan of a poster on a laptop screen traced two parallel corridors between
the same junctions, and doors attached to both. Route colour had appeared along dark walls: 8 thin
streaks on that scan and none on the clean fixture. Saturation can't separate them, because thin real
paint reads about the same (S 84 against 83), and a plain darkness test removes real paint (stair
poster corridor F1 0.91 to 0.36). Walls are dark and colourless, though (V < 110, S < 70, runs of at
least 25 px). `route_mask` now drops route pixels within a 7 px window of a wall only when solid paint
lies within a 61 px window, so a faint corridor with nothing stronger nearby stays. On that scan the
parallel pair is gone and the corridor follows the paint. These values were tuned on that one scan and
the fixtures, and nothing was held out. The scan still misses the east corridor, whose paint is too
faint there, and it also loses a short exit hook near the top. Both can be added by hand.

| Android against the trace | ADR 0007 | Now |
|---|---:|---:|
| Rectified corridor F1, elevator / stair | 92.3% / 92.0% | 92.5% / 92.0% |
| Rectified junction F1, elevator / stair | 75.0% / 75.0% | 75.0% / 75.0% |
| Front-photo corridor F1, elevator / stair | 91.2% / 94.7% | 91.1% / 94.0% |
| Front-photo junction F1, elevator / stair | 80.0% / 85.7% | 80.0% / 80.0% |
| Elevator entrances within 40 px, rectified / front | 40 / 40 of 52 | 40 / 41 of 52 |
| START error, elevator rectified / front, stair front | 15.98 / 26.96 / 8.43 px | unchanged |

The stair front-photo junction drop is one extra junction that matches nothing in the trace. Room
precision and recall are unchanged, and frame decisions still match on all 16 photos.

## Consequences

- A saved graph has no starting k. Navigation still starts from `Demo.STEP_LENGTH_PX` (17.3), which
  suits the elevator poster. Another poster's real k may fall outside the calibrator's 50% limit
  and be rejected. A per-graph value belongs with #12, and a small file beside `graph.json` would
  avoid a `core/model` change.
- Parser warnings such as blur are shown in the review before editing and are not kept in the draft.
- Graph corrections cannot redraw a corridor's bends, only move its ends and split it.
- Undo history lasts while the screen is open. The edits themselves are written to the draft after
  each change, so they outlast the app being killed.
- The room list holds what the parser read. A door added by hand is a destination, but not a row in
  that list.
- Corridors without painted lines remain #28.

## Verification

Emulator only (Pixel 6 AVD, API 34, and the same AVD at 360 x 640 dp). Fixture: delete a junction,
Save blocked with its reasons, relink, add a door, save, restart, route to the new door with the demo
walk. Photo: the elevator front photo through the emulator's image-file camera, parsed with START
unset, Save blocked until START was placed, saved, routed. Live tracking on a saved map and
finger comfort on a real phone are not tested.
