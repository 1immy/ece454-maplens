# temi guide robot: setup and connection

MapLens can let a temi robot walk a visitor to their room. The phone keeps doing what it
already does (poster map, route, step tracking, rerouting, confidence) and decides where temi
goes and when it waits. temi's own navigation does the driving and obstacle avoidance.
Phone-only navigation is unchanged and needs no robot.

```
 phone (MapLens)                          temi (MapLens guide companion)
 ─────────────────                        ──────────────────────────────
 route, walker pose ──┐                   ┌─ goToPosition / stopMovement / turnBy
 EscortController ────┼── JSON lines ───► ├─ speak, Detection Mode, screen + button
 TemiGuide            │   TCP :8765       └─ pose, go-to status, detection, button ─┐
 map marker, voice ◄──┴───────────────────────────────────────────────────────────┘
```

Nothing goes to the internet: the two devices only need to be on the same local network
(the phone's hotspot, temi's, or the lab Wi-Fi).

## What you have

- **temi S1, SKU SIBL10, made Sept 2022.** Check which Android it runs: temi Settings →
  About. temi V2 runs Android 6 (API 23), V3 runs Android 11 (API 30). The companion app
  supports both (minSdk 23). The MapLens phone app itself needs API 26, so it stays on the
  phone.
- **temi needs developer access to install apps.** Settings → temi Developer Tools → ADB
  port opening. You may need the temi admin account or the lab owner to enable it.

## 1 · Map the floor on temi (once)

1. On temi: Settings → Map → **Create new map**, then drive it around the EH3 corridors you
   will use (the 3418 / 3446 area first). Save the map.
2. Optional: save a location at each destination door (Settings → Locations, or say "Hey
   temi, save location"). MapLens doesn't need them, but they are handy for testing ("Saved
   places" in the dev screen).

## 2 · Install the companion app on temi

From this repository on the laptop:

```bash
cd temi-companion
../gradlew -p . :app:assembleDebug
```

The APK is `temi-companion/app/build/outputs/apk/debug/app-debug.apk`. With temi on the
same Wi-Fi as the laptop and its ADB port opened (temi's screen shows its IP):

```bash
adb connect <temi-ip>:5555
adb install -r temi-companion/app/build/outputs/apk/debug/app-debug.apk
```

Open **MapLens guide** from temi's app list. It shows `Waiting for MapLens on <temi-ip>:8765`.
Keep it open while guiding: it hides temi's top bar and asks to be the kiosk app so nothing
else takes over.

## 3 · Connect the phone

1. Put the phone and temi on the same network. A phone hotspot works well indoors: temi
   joins it, and then temi's address is the one shown on its screen.
2. MapLens → Home → Developer → **temi guide robot** → **temi on Wi-Fi** → type temi's IP
   (port 8765) → **Connect**. The status turns blue and the Robot card shows temi's
   position and battery.
3. Try **Say hello**, **Turn around** and **Stop**.

No robot nearby? Choose **Simulator** instead: everything below works the same, with a
pretend temi on the map.

## 4 · Line up temi's map with the poster map

temi measures in metres in its own map; MapLens uses pixels of the photographed poster. They
are matched with two or more shared places:

1. Drive temi (or walk it with "Follow me") to a junction you can find on the poster.
2. In the dev screen, type that junction's node id (e.g. `J6`) and tap **Use temi here**.
3. Repeat at a second junction far from the first, ideally around a corner. Three or four
   are better. The card shows how well they agree (e.g. ±0.4 m). More than about ±1 m means
   the poster isn't to scale there: add an anchor near where temi will walk.

## 5 · Mark doors

Under **Doors and pace**, list the junctions where a door crosses the corridor (e.g.
`J12, J20`) and save. temi stops 1.5 m before each one, turns to the walker, says "Could you
open the door for me, please?" and shows a big **Door is open** button. The phone shows the
same button. It goes on only after one of them is pressed. You can also set how far ahead
temi walks (default 3 m) and its speed (default 0.6 m/s; slower than a walker on purpose).

## 6 · Guide someone

Indoor navigation → pick a room → **Directions** → **Guide · With temi** → **Phone in hand**
or **In my pocket** → **Start**.

- **Phone in hand.**
  - The phone tracks the walker as usual and sees temi as a teal marker. The card under
    the banner says what temi is doing ("temi is leading · 3 m ahead of you").
  - temi stays about 3 m ahead.
  - If the walker stops for 4 s, or falls 6 m behind, temi stops, turns to face them and
    says "I'll wait for you here". It leads on once they walk toward it.
- **In my pocket.**
  - The phone can't track the walker, so temi uses its own camera instead.
  - It walks one stretch (to the next turn, a door, or 8 m), turns round and waits until
    its Detection Mode sees someone within 2 m ("I'll wait here until you catch up").
  - Then it says "Great, let's keep going".
- **Reroutes.** If the walker goes off route, the phone reroutes as usual and temi follows
  the new route.
- **Obstacles.** If temi gives up on a blocked path, it says so and tries again a few times.
  After that the card says to carry on with the phone's steps.
- **Arrival.** temi says "Here is room 3418", and the phone says "You've arrived at room
  3418 with temi".
- **Safety.** Ending navigation, losing the connection, or closing the app stops temi. The
  companion app stops temi if the phone disconnects.

## Protocol (both apps implement it)

One JSON object per line over TCP port 8765 (`app/.../robot/RobotProtocol.kt`).

| Phone → temi | Fields | temi SDK call |
|---|---|---|
| `hello` | client, version | — (temi answers `hello`) |
| `goTo` | x, y (m, temi frame), yaw?, speed (m/s) | `setCurrentGoToSpeed`, `goToPosition` |
| `goToLocation` | name, speed | `goTo` |
| `stop` | — | `stopMovement` |
| `turnBy` | degrees (+ = left) | `turnBy` |
| `speak` | text | `speak(TtsRequest)` |
| `detection` | on, maxDistance (m) | `setDetectionModeOn` |
| `screen` | message, button? | companion screen |
| `listLocations` | — | `getLocations` |

| temi → phone | Fields |
|---|---|
| `hello` | model, sdk, version, battery |
| `pose` | x, y, yaw (5 per second) |
| `goToStatus` | status (start, calculating, going, complete, abort, reposing), description |
| `detection` | detected, distance (m), angle |
| `locations` | names |
| `button` | id (e.g. `door_open`) |

## Limits and what to check on the real robot

- **Doors:** temi cannot open doors and its lidar treats a closed door as a wall, so mark
  every corridor door. Swinging doors that close on their own need the walker to hold them.
- **Pocket mode** relies on temi detecting *a* person, not *the* walker; in a busy corridor
  it may carry on for someone else. Phone in hand is more reliable.
- **Thresholds** (3 m lead, 6 m max gap, 4 s stop, 2 m meet distance, 0.6 m/s) are starting
  values to tune on the first walks.
- **Detection units:** temi's DetectionData distance is assumed to be metres. Check it in
  the dev screen (Robot card → Camera) by standing 1 m and 2 m in front of temi.
- Phone-only A–D trials stay separate from robot-guided walks (`project-AI-context.md` §9).
