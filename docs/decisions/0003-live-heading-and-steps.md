# Live heading and step processing

## Scope

Increment for #12 on the existing recorder branch. No shared model, tracker,
NavigationSession, dependency, or map-parser changes. Distance/stride calibration
and particle tracking remain separate work. No checkpoint UI or EH3 walk added.

## Research and choice

- Android's [game rotation vector](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position#sensors-pos-gamerotvector)
  fuses gyro/accelerometer without a magnetometer. It has an arbitrary horizontal
  reference and yaw drift; it is not a north bearing. Indoor magnetic disturbances
  are a reason to retain the project's specified relative heading plus manual
  corridor alignment, rather than replace it with a magnetic compass.
- Android's [sensor coordinate system](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview#sensors-coords)
  stays fixed to the device's natural orientation. Account for display rotation.
- The [step counter](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion#sensors-motion-stepcounter)
  can lag up to 10 seconds; the detector is expected below two seconds. AOSP states
  that [sampling period does not affect step-detector reporting](https://source.android.com/docs/core/interaction/sensors/sensor-types#step_detector).
  Registering FASTEST cannot fix a manufacturer's detector latency or sensitivity.
- The existing non-batched 50 Hz continuous-sensor registration already requests
  delivery as samples become available. Retain it; raising frequency before
  measuring errors and power is not justified. See [SensorManager](https://developer.android.com/reference/android/hardware/SensorManager#registerListener(android.hardware.SensorEventListener,android.hardware.Sensor,int,android.os.Handler)).

## Heading

Normalize the quaternion, use its device-to-world rotation, and apply the report's
forward projection `f = R * screenUp - R * deviceZ`. Screen-up maps to device
`+Y, -X, -Y, +X` for display rotations 0, 90, 180, 270. Then
`psi = atan2(f.x, f.y)`. This avoids Euler pitch singularities in the intended
screen-facing carry range from flat to upright. Map bearing is clockwise
`atan2(dy, dx)` on the y-down image: 0 right, pi/2 down. Explicit alignment sets
`offset = selectedMapBearing - psi`; no north-up map assumption is made.

Use a time-based circular filter (80 ms time constant) to reduce jitter without
averaging across the +/-pi boundary. Reject invalid quaternions, near-zero forward
projections and face-down holds. Hide readings older than 500 ms. Resume resets
alignment because the game reference can change across sensor registration.

This is a phone-facing estimate, **not independently observed body direction**.
Arbitrary phone motion relative to the walker cannot be disambiguated from body
turns using this orientation sensor alone. Tilt/roll changes can still change the
chosen forward vector. Pocket carry, swinging, face-down and arbitrary grips are
not claimed supported. The UI asks for re-alignment after changing grip. Portrait
and landscape reading are supported through Android display-rotation remapping;
transitions while physically repositioning the phone can still disturb heading.
Automatic corridor drift correction requires a trustworthy tracker/corridor
estimate and is deliberately not invented here.

The debug alignment dialog offers four cardinal map arrows for standalone testing.
`alignHeading(Double)` accepts any corridor bearing for later map integration.
Alignment and source selection are locked during recording. JSON sidecars retain
the initial offset, angle convention, source and timestamped display-rotation
changes, so raw quaternion traces remain interpretable.

## Steps and responsiveness

- Android detector is the default. Counter is an independently labelled delayed
  cross-check and is never added to detector counts.
- Fix live counts outside recording. Reset counts on resume, source change and
  Start. Refresh diagnostics at up to 10 Hz, independently of 50 Hz acquisition.
  Persist a separate last-saved count summary across resume so a live reset does
  not look like lost recording data. Show the delayed counter near the detector;
  save its final session delta separately without adding it to per-step events.
- Keep a separate accelerometer magnitude-peak estimate. Time-based gravity/DC
  removal, smoothing, threshold/rearm hysteresis and a 300 ms refractory period
  reject small variations and repeated peaks. A sample gap resets filter state.
  It is insensitive to acceleration-axis rotation, but cannot distinguish all
  hand shakes from steps and is **experimental**, not a proven accuracy upgrade.
- Automatically select peaks if detector registration is unavailable/denied;
  allow explicit selection in the debug screen. Never merge both streams or
  silently replace a functioning detector because it has not reported recently.
- Preserve raw CSV version 1 and its stream meanings. `step` remains hardware
  detector input even in experimental mode. JSON distinguishes raw detector count,
  peak count and selected-source count. Derived peaks are not new raw CSV rows;
  reproducible processing uses the raw accelerometer and documented defaults.
- `SensorMotionProcessor` emits existing `MotionEvent.Step` through the recorder's
  optional sensor-worker callback, only when aligned heading is available. Use
  heading at/before the footfall timestamp from a bounded three-second history,
  not heading at delayed callback delivery. Suppress stale/missing heading and
  expose the skipped-step count; do not fabricate a navigation direction.
  No continuous Turn callbacks are interleaved with delayed footfall events;
  emitted steps have monotonically increasing timestamps. NavigationSession must
  enqueue callbacks on its own state-owner thread when integrated later.

## Power, privacy and validation

No new sensor types, permissions, network, GPS, camera, background service or
wake lock. Sensors stop on pause. The faster diagnostic display uses more UI work
than before; battery consumption has not been measured. Raw persistence remains
explicitly started private local recording, with user-controlled export.

Synthetic tests cover flat-to-upright projection across yaw and display rotations,
quaternion signs, wraparound smoothing, alignment, invalid/ambiguous poses, stale
headings, delayed detector timestamps, exclusive source selection, stationary
rotating gravity and synthetic walking peaks. They establish implementation
behavior, not real-world walking accuracy. The debug build was installed and
launched on a Pixel 7a; device walking results are recorded in the external work
log. Validate on a real handset with manually counted walks at three speeds
and three tilts, plus deliberate hand movement and rotation, before claiming the
issue's +/-5% criterion. A short local walk can do this; EH3 is not needed for
these sensor checks. Distance calibration and tracker evaluation remain open.

Pixel 7a pilot: in a clean user-counted 20-step recording (21.837 seconds), the
detector reported 19, magnitude peaks 21, and the delayed counter 23. No selected
steps lacked usable aligned heading. All 4,073 CSV rows passed column/timestamp
checks. A prior recording containing additional uncounted walking/handling had
22 detector events and 46 peaks; it has no valid 20-step denominator. These
observations justify keeping hardware detection as the default and the peak
option explicitly experimental. One walk does not establish the cross-speed/
tilt accuracy requirement; no thresholds were fitted to force this pilot to 20.
