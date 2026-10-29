# Debug sensor trace recorder

## Context

Issue #11 needs reusable walking traces before heading/calibration (#12) and tracking
(#13) exist. This first increment intentionally excludes checkpoint marking and does
not complete #11. The app currently has a placeholder Activity, without Compose.

## Decision

- Keep the sensor adapter in `tracking/`, with Android-free CSV/counter logic and
  synthetic tests. Add a native Views recorder Activity, resources and launcher
  override in `app/src/debug/` only. The main Activity, navigation records and
  release launcher remain unchanged. No new dependencies or build changes.
- Request 20,000 microseconds (~50 Hz, Android GAME) for accelerometer, gyroscope
  and game rotation vector. Request NORMAL for the event-driven step detector
  and counter; their reporting follows sensor events, not a fixed Hz. FASTEST
  is not justified for the initial walking experiments. Rates are requests, not
  guarantees; show observed delivery frequency and sample age in the preview.
- Keep requested sampling/display intervals in `TrackingConfig`. Refresh the UI
  every 250 ms independently of the raw CSV stream. Actual rates and power
  consumption must be measured on the selected handset before tuning.
- Use a HandlerThread for sensor callbacks, buffered writes and start/stop commands.
  A second worker copies completed files only after an explicit export. Sensor
  timestamps use Android's elapsed-realtime nanoseconds. Do not sort or replace
  timestamps with callback arrival time; separate sensors can deliver interleaved
  timestamps. Ignore pre-session timestamps when saving a recording.
- Observe sensors only while this debug screen is resumed. Preview is memory-only.
  Start creates a private CSV; Stop or leaving the screen closes it and its JSON
  metadata. No background service, wake lock, upload, location/camera/microphone
  access or additional permission. Request activity recognition in context for
  step sensors (Android 10+); show missing/denied sensors and keep other readings usable.
- Keep the screen awake only during an explicit recording. Files live in private
  app storage (`files/recordings/`); the existing application disables backup.
  Export CSV or JSON through Android's document picker. The selected destination
  may be cloud-backed: exporting is the user's explicit choice. Delete removes
  recorder-owned local files, including partial files, but not exported copies.
- Write `.csv.partial` until normal finalization succeeds. An interrupted process
  may leave an incomplete private file; never present it as a completed trace.
  There is no promise of recording through a process kill or background transition.

### CSV, format version 1

The proposed seven-column layout is retained:

```text
timestamp_ns,type,v0,v1,v2,v3,label
```

| Type | Values | Units |
| --- | --- | --- |
| `step` | detector value (normally 1), empty, empty, empty | one detected step |
| `step_counter` | session-relative delta, empty, empty, empty | steps |
| `accel` | x, y, z, empty | m/s², including gravity |
| `gyro` | x, y, z, empty | rad/s |
| `game_rv` | x, y, z, w | quaternion components, unitless |

`label` is empty: checkpoints are deferred. `step_counter` extends the proposed
type list as a separate stream; it must not be replayed as additional `step` events.
The Android since-boot total is not saved. Its baseline is the last preview reading
at Start, or the first counter reading if none exists. Delayed counter delivery
means its delta is approximate around session boundaries; missing initial steps
are not invented. The detector remains the source for precise per-step timestamps.
If Android supplies only three rotation components, reconstruct positive w from
sqrt(max(0, 1 - x² - y² - z²)), as Android's rotation-vector convention specifies.

The sidecar JSON records format version, start/end time, monotonic start, duration,
device model/API, requested period, sensor names/vendors and initial status, row
count and detected steps. It contains no device serial, account or location.
Map, heading, walking protocol and checkpoint annotations are later work; these
initial recordings alone are not a complete localization evaluation dataset.

## Consequences

The debug APK temporarily opens directly into the recorder; release builds retain
the current app entry point. Before merging, coordinate that debug-launcher choice
with the app owner. A later debug menu can expose the same Activity without changing
the tracking module. No shared navigation contract is expanded.

Required follow-ups: checkpoint marking with distinct door IDs; a real two-minute
EH3 walk through LFS; validation of step accuracy at three speeds and three tilts;
heading alignment/calibration; trace replay; handset power measurements. Do not
close #11 until its full acceptance criteria are met.

References: [Android sensor overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview),
[motion sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion),
[position sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position).
