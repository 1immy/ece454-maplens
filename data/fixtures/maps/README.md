# Map fixtures

Each traced map is a pair:

```
eh-3-north-stair.jpg          # straight-on photo (Git LFS)
eh-3-north-stair.graph.json   # hand-traced FloorGraph, pixel coords on that photo
```

`./gradlew test` loads every `*.graph.json` here and fails if one doesn't pass `FloorGraph.validate()`.

## Format (`schemaVersion` 1)

```json
{
  "schemaVersion": 1,
  "buildingId": "engineering-hall",
  "floor": "3",
  "startNodeId": "S",
  "verified": true,
  "source": { "image": "eh-3-north-stair.jpg" },
  "nodes": [
    { "id": "S", "type": "START", "x": 812, "y": 1040 },
    { "id": "J1", "type": "JUNCTION", "x": 812, "y": 610 }
  ],
  "edges": [
    { "from": "S", "to": "J1" },
    { "from": "J1", "to": "D3650", "polyline": [[812, 610], [900, 600], [1010, 600]] }
  ],
  "landmarks": [
    { "label": "3650", "nodeId": "D3650" }
  ]
}
```

| Field | Required | Notes |
|---|---|---|
| `nodes[].type` | yes | `JUNCTION`, `DOOR`, `START`, `DEAD_END`, `ELEVATOR`, `STAIRS`, `EXIT`, `AED` |
| `nodes[].x`, `y` | yes | Pixels, origin top-left of the image |
| `edges[].id` | no | Defaults to `e1`, `e2`, … in file order |
| `edges[].polyline` | no | Defaults to a straight line between the two nodes; add it for bent corridors |
| `edges[].lengthPx` | no | Defaults to the polyline length |
| `landmarks[].ocrConfidence` | no | Defaults to 1.0 (hand-labeled) |
| `source` | no | Free-form metadata, ignored by the loader |

## Tracing a map by hand

Use `tools/maplabel.py` (see `tools/README.md`): label corners, `warp` the straight-on photo, then `trace` it. The rectified image and its `.graph.json` live here side by side.

What to place:

1. `START` at "You Are Here" (the star on evacuation posters, the pink dot on directory maps), on the corridor centerline.
2. A node at every junction, corner, dead end, stair, elevator, exit and AED.
3. A `DOOR` node for every numbered room you can read, on the centerline opposite the door, with its room number as the label.
4. Edges only along corridors, never through rooms.
