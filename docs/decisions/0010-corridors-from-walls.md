# Corridors from paint and walls

## Context

Issue #68: a real scan gave sparse, broken corridor lines. Faint paint broke into fragments, too few
junctions came out, and a moved junction tilted a whole straight corridor. Issue #28: some corridors
have no orange line, and a directory poster has none at all, so the parser rejected it. Hatching was
already tried for #28 and didn't help (ADR 0007).

Targets were set before measuring, in the #68 plan: one connected graph with no parallel duplicates
on a real scan, front-photo corridor recall at least 95% and junction F1 at least 85%, corridor
precision at least 90%, and corridor F1 down by at most 1 point.

## Decision

Paint stays the first choice where it exists. Walls fill in the rest.

1. **Faint paint.** Route colour at S > 45, in straight runs of at least 31 px, counts when it reaches
   solid paint within a 15 px window. It never counts within 11 px of a wall, where it can't be told
   from fringe (ADR 0008).
2. **Walls and free space.** Walls are dark, colourless, long shapes: at least 120 px, or at least 30 px
   and 3 times as long as wide. That leaves text and hatching dots out. The frame and backdrop are found
   by being at least 6 Lab b* units bluer than the poster's own paper, in patches at least 21 px wide, so
   coloured text doesn't block a corridor. Door gaps up to 0.75 of a corridor width are closed. Corridor
   width is the commonest width of long free runs, measured along the paint when there is some.
3. **Posters without paint.** Paint counts only when there is at least 1000 px of it, at least half
   forms one network, and under 40% of it lies in filled patches (where over 45% of a 61 px window is
   reddish). Otherwise the parser traces free space that is at most 1.6 widths across and at
   least 3.5 widths long. Its width is the narrowest within 2.5 widths along it, because a door gap
   lets free space leak into a room. Rooms are too wide or too short. Stretches under 1.5 widths,
   which are door throats, are dropped first. A photo is rejected only if it has under 1000 px of corridor either way.
4. **Links.** From a corner or an end, a corridor carries straight on, but only from a line within 20°
   of the poster's axes. A corner's lines are read 30–60 px out, past the curve paint makes at a
   corner. A link is kept if it reaches another line through clear space, or stops at a wall within 4
   widths with walls close on both sides. Room walls along a hallway are drawn thin and broken by
   doors, so a line that runs straight for 4 widths up to its end may carry on to that wall even when
   one side looks open. Hooks into EXIT signs start no links. Two ends whose lines cross are joined at
   that corner.
5. **Joining pieces.** Separate pieces are joined by a walk through free space: half a width from walls
   first, then with door gaps open for at most 12 widths, because closing gaps can pinch a narrow
   corridor shut. Pieces that stay apart and hold under 25% of the largest piece's length go.
6. **Symbols on the paint.** A route ends at a sign. A compact red blob counts as a sign only if it is
   at least 20 px each way and paint leaves it in fewer than 3 places (read in a ring 7–11 px out);
   thick paint where routes cross also fills in, but routes leave it. Paint never stops and starts
   again on its own, so a long line that ends pointing within 25° (and within 20° of an axis) at the
   end of another line, at most 3 corridor widths on, carries on to it across whatever symbol cut it.
   A paint end also joins the nearest other line that the photo's raw route colour still reaches
   within 6 widths, walking only through that colour: a symbol's outline can merge with the paint
   beside it and chop it into short twigs that pruning drops. The join is a straight line. Reaches of
   4 to 8 widths give the same graph on every image; 3 is too short for the AED by 3416.
7. **Clean lines.** Holes under 6000 px in a corridor mask are filled before thinning, so a patchy band
   gives one line and not a ladder. Junctions merged into one never spread over more than the 60 px
   merge radius. Two lines between the same junctions that stay within a corridor width of each other
   become the shorter one. A moved junction joins its line where the line passes closest, so a straight
   corridor bends at its end and never tilts. Bumps up to 0.2 of a corridor width, at most 10 px, come
   out of the polylines.

No `core/model`, `gradle` or `.github` change. Unpainted corridors look the same in `FloorGraph` as
painted ones, so the review asks for every scan's corridors to be checked against the poster. Python
also keeps long straight runs and uses the 60 px spur limit, as Kotlin did.

## What real phone scans showed

The first Pixel 7a scans (Nov 9) were messy, and none of the fixtures had shown why. Each cause below was
found on those scans:

| On the phone scan | What went wrong | Fix |
|---|---|---|
| Directory poster | The camera made the pink rooms red enough to read as paint, so the poster was traced as painted | Rule 3: paint must be mostly one network. The pink read as scattered pieces, 37% in the largest |
| Evacuation poster | Dark rust paint read as a wall, which split each corridor in two and halved its width (23 px against about 50) | A painted poster's paint never counts as wall |
| Evacuation poster | Lines into the stair room by 3424: the EXIT sign's outline was traced, because its letters left holes | A compact red blob over 900 px, under 150 px across and at least half filled, is a sign and goes |
| Evacuation poster | Stubs along walls: the lens leaves a thin orange edge a few px out from dark walls, and the walls were lighter than the old wall test | Fringe reach 7 → 11 px, and grey pixels at least 50 darker than the paper around them also count as walls near paint |
| Evacuation poster | A diagonal beside 3534: a patchy corridor thinned to a ladder, and merging ran down it | Hole filling and the merge cap in rule 7 |
| Directory poster, third scan | The pink speckle joined into one larger piece (58% of it), so the network rule alone passed it as paint again | Rule 3 also checks how much route colour sits in filled patches: 0–18% on every evacuation image, 90–92% on all three directory scans |
| Directory poster, rescan | West-wing and 3730 corridors missing: rooms open onto them through wide gaps, so the corridor looked 94–122 px wide against 26 px | Rule 3 judges width by the narrowest point nearby |
| Evacuation poster, saved scan | The 3444 corridor dropped and the south exit lost: on a screen photo the paint is thick, so the crossings by 3444 and 3542 and the south arrow filled in like EXIT signs and were removed | Rule 6: paint leaves a junction in 3 or more places |
| Evacuation poster, Nov 9 afternoon scans | The corridor between 3616 and 3602A was missing: paint turns down at the 3609 corner with a rounded bend, so the corner read 23° off the axis and nothing carried straight on | Rule 4: a corner's lines are read past the curve |
| Evacuation poster, Nov 9 afternoon scans | The 3444–3446 hallway stopped where the paint turns into the 3445 stair EXIT, short of the end of the hall; its top wall is thin and broken by doors, so the hallway looked twice as wide as a corridor | Rule 4: a straight painted line carries on to the hallway's end wall |
| Evacuation poster, Nov 9 2:31 PM, 2:39 PM and Nov 10 scans | The corridor past the AED and the EXIT sign by 3416 was missing. The heart's outline merged with the paint beside it, so pruning dropped the paint piece by piece; the EXIT sign was cut out below it; and the corridor is only 0.6 of a width there (the AED cabinet and the stair jut in), which reads as a door, so no link got through | Rule 6: a paint end joins the line the photo's route colour still reaches |
| Every evacuation image | The corridor east of 3418 broke where the paint runs behind the stair EXIT sign and the AED; the corridor narrows there, so no link could pass | Rule 6: a paint end carries on to the paint it points at |
| Evacuation poster, both scans | Lines wander across the west corridor and into the stair room by 3416: the camera reads the grey hatching as specks of route colour, and widening the mask merges them into a corridor-wide blob | Still open, see Limits |
| Evacuation poster, saved scan | A line through rooms 3612/3614: a screen photo tints thin black walls purple, so the wall isn't a wall and its colour fringe reads as paint | Still open, see Limits |

## Results

Pixel 6 emulator, airplane mode. The Android fixture suite passes. It requires one connected network on
all 15 accepted photos, including the directory posters. Given the same input, Kotlin and Python score
100% against each other on both rectified posters, the laptop scan, the first and the 2:31 PM phone
scans and two of the three directory scans. On the rest they agree on 98.7–100% of corridor length,
with one junction more or less.

**Nothing here is held out.** Every image, including the phone scans, was looked at while tuning. A
new scan of a different poster is the next honest test.

| Android against the trace | ADR 0008 | Now |
|---|---:|---:|
| Rectified corridor F1, elevator / stair | 92.5% / 92.0% | 97.6% / 96.5% |
| Rectified junction F1, elevator / stair | 75.0% / 75.0% | 88.9% / 94.1% |
| Front-photo corridor F1, elevator / stair | 91.1% / 94.0% | 96.1% / 95.5% |
| Front-photo junction F1, elevator / stair | 80.0% / 80.0% | 88.9% / 88.9% |
| Elevator entrances within 40 px, rectified / front | 40 / 41 of 52 | 42 / 42 of 52 |
| START error, elevator rectified / front | 15.98 / 26.96 px | 15.25 / 16.05 px |

| Target, front photos | Elevator | Stair 3424 | Met? |
|---|---:|---:|---|
| Corridor recall ≥ 95% | 95.5% | 95.3% | Yes |
| Junction F1 ≥ 85% | 88.9% | 88.9% | Yes |
| Corridor precision ≥ 90% | 96.7% | 95.7% | Yes |
| Corridor F1 down ≤ 1 point | +5.0 | +1.5 | Yes |

Rule 6 closed the break by the stair EXIT; rule 4's hallway ends and rounded corners added the rest.
Room reading is unchanged at 94.2% precision and 96.1% recall. Frame decisions still match on all 16
photos.

The phone scans have no trace of their own, so they were scored in Python against the elevator trace
after registering each scan to the rectified poster with SIFT matches and a homography. The target set
before measuring was corridor F1 ≥ 90% on the screen scans. Four of the six phone scans meet it; the
two oldest don't.

| Elevator evacuation, Python, corridor F1 / junction F1 | Before rule 6 | Before the colour join | Now |
|---|---:|---:|---:|
| Saved phone scan, Nov 9 morning (screen) | 77.1 / 44.4% | 84.6 / 63.2% | 87.5 / 70.0% |
| First phone scan (screen) | 85.7 / 66.7% | 87.2 / 66.7% | 87.2 / 66.7% |
| Phone scan, Nov 9 1:41 PM (screen) | — | 90.4 / 76.2% | 90.4 / 76.2% |
| Phone scan, Nov 9 2:31 PM (screen) | — | 89.9 / 63.2% | 91.0 / 80.0% |
| Phone scan, Nov 9 2:39 PM (screen) | — | 91.1 / 80.0% | 91.1 / 80.0% |
| Phone scan, Nov 10 2:03 AM (screen) | — | 90.0 / 70.6% | 90.8 / 66.7% |
| Laptop scan (screen) | 92.1 / 82.4% | 93.6 / 82.4% | 93.8 / 88.9% |
| Paper photos (left, blur, front, down, glare), corridor F1 | 96.9, 91.4, 95.2, 95.5, 93.9% | 97.4, 91.9, 95.6, 95.3, 93.8% | 98.0, 91.9, 95.6, 95.3, 93.8% |

## Limits

- Directory posters have no hand traces, so they are checked by eye. Some corridors are still missing,
  such as the south cross-corridor on the elevator poster, where room walls are drawn too open to read as a
  corridor. They can be added in the #16 editor; #78 tracks a better method (room segmentation).
- Photos of a poster on a screen stay weaker than photos of paper (corridor F1 87–91% on the six phone
  scans, against 94–98% on the paper photos apart from the deliberately blurred one). Grey hatching
  reads as specks of route colour that pull a line off the paint, and thin walls tinted purple aren't
  walls. Four fixes were tried and dropped, each because it cost a paper photo or a check: dropping specks under 15 px (lost the south exit stub and room
  3332's entrance on the rectified poster), treating tinted dark lines as walls (−1 to −7 points on three
  images), moving lines to the middle between walls (−4 to −5 points everywhere), and dropping edges with
  wide free space beside them (true and false edges overlap). For the AED corridor, three general
  fixes were tried before the colour join and dropped: measuring a spur from its end to the nearest
  junction (kept hooks into EXIT signs, −5.5 points on the rectified poster), never sealing a gap
  that paint crosses (no change), and letting a ray pass any gap paint crosses (the ray missed the
  paint by 20 px where it curves round the heart; −0.5 and −0.7 points on two images).
- Links and strips only run along the poster's axes, so a diagonal wing needs the editor.
- A poster with no paint and no coloured backdrop isn't tested. Strips of paper outside the building
  could be measured as corridors.
- Directory posters have no gold star, so START waits for the editor.
- The last walk can pass through a door if that is the only way between two pieces within 12 widths.
- The Nov 9 phone scans are of a screen. A Pixel 7a photo of the paper poster is still the missing test.

## Reproduction

As in ADR 0007. The directory poster's corridors are written to
`eh-3-elevators-dir.corridors.graph.json` beside the other results.
