# Validate footfalls before moving the map position

## Context

The Android detector reported 38 footfalls during a stationary Pixel 7a hand-shake
recording. The accelerometer peak fallback reported 39. A footfall candidate alone
is insufficient evidence for translating the user's map position. A four-step
startup buffer reduces some false starts and makes the first pose update jump.

## Decision

For either candidate source, check up to 800 ms of samples at or before its
footfall timestamp. Use the game rotation vector to express acceleration in the
gravity frame. Check gyroscope RMS, vertical and horizontal motion, and the rise
and fall of the smoothed vertical signal. Excess vertical energy or rapid
reversals reject up/down shaking. Stale or insufficient sensor evidence also
rejects a candidate. The minimum interval between accepted footfalls rejects
duplicates and implausibly fast bursts.

A single swing or tap of a phone held in place can pass every check, so one
footfall never moves the user on its own. A walk starts once three footfalls pass
in a row, each within 1.3 s of the one before; then all three are released
together, and while the walk lasts each footfall within 1.5 s of the last passes
at once. A footfall that fails the checks breaks a run that has not started yet.
This costs about two footfalls (about 1 s) at the start of each walk, never during
one, and `PuckMotion` walks off the three-step gap at up to 2.5x walking speed
instead of sliding.

Feed accepted events to the existing corridor particle tracker on the same
callback. The display does not animate between tracker poses: easing to each
pose (180 ms, then a cadence-length transition) made the dot stop and start,
and late or batched footfalls made it jump. Instead, like route-snapped map
pucks (Mapbox Navigation's matched location and key points), `PuckMotion`
moves the dot every frame along the route at walking speed (cadence from
footfall intervals times k). A tracker pose only corrects that speed: the dot
catches up when behind and slows when ahead, never moving back while walking.
It predicts at most one step past the last pose. When the phone stops bobbing
(`WalkingGate.moving`, vertical RMS over 450 ms) it stops within about half a
second and eases onto the last pose. Corrections over four steps slide over in
about 0.2 s. Off the route, the tracker pose is shown directly. The algorithm
uses the existing sensors and map.

Small footfalls may pass with a lower vertical amplitude and smaller signed
excursions when rotation and horizontal sway are modest. The original minimum
amplitude and vertical share apply outside that quiet range. Fast oscillation,
large motion and stale data still reject candidates.

## Consequences and evidence

On timestamp-ordered replay, the compiled gate accepted 0/38 Android candidates
and 0/39 accelerometer peaks in the stationary recording. The four existing EH3
walks retained 63/64, 90/91, 87/88 and 96/96 Android steps, 336/339 total. The first
candidate passed in each walk. In the existing 20-step Pixel recording, Android
reported 19 candidates. Passing candidates increased from 13 to 17 on
arrival-order replay. The remaining two were less than 300 ms after an accepted
candidate. Synthetic tests cover light walking, sideways handling, a single
bump, fast vertical oscillation, a pause, and missing or stale data.

In a 75 s recording of a phone gently swung while standing, 19 single footfalls
passed the per-footfall checks, 2-7 s apart; with the rhythm rule none pass.
A recorded real walk with turns kept 63 of 64 steps, and the stationary shake
recording still passes none.

The 800 ms window is a lookback. Android's detector delivery and display frames
still contribute to end-to-end latency. A phone-only motion classifier can miss
gentle steps or accept hand movements that closely resemble walking. If a later
version can use a foreground camera, map-aligned visual-inertial odometry can
add a separate measurement of displacement relative to the surroundings.

References: [Android sensor types](https://source.android.com/docs/core/interaction/sensors/sensor-types#step_detector),
[Robust Step Counting for Inertial Navigation with Mobile Phones](https://doi.org/10.3390/s18093157),
[ARCore motion tracking](https://developers.google.com/ar/develop/fundamentals#motion_tracking).
