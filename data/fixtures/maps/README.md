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

1. Use the straight-on shot. Note its pixel size; coordinates are relative to it.
2. Place a node at every junction, corner, dead end, room door, stair, elevator, exit and AED, plus `START` at "You Are Here". Put each node on the corridor centerline, opposite the door it represents.
3. Connect nodes that are joined by corridor. One edge per corridor segment, no shortcuts through rooms.
4. Add a landmark for every readable room number, attached to its door node.
5. Run `./gradlew :core:model:test` to check it.
