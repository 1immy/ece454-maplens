# 0001 · Shared records live in `core/model`

**Status:** accepted · 2026-10-12

## Context

Three people build three areas in parallel. Each area consumes another area's output, and the producers won't be ready at the same time.

## Decision

The six records from the proposal (`FloorGraph`, `Route`, `MotionEvent`, `PoseEstimate`, `ConfidenceState`, `SignObservation`) are defined once in a pure-Kotlin module, `core/model`. Every area codes against these types and uses hand-built or recorded fixtures until the real producer exists. Changes to `core/model` need approval from both other teammates.

Routing uses A* (straight-line heuristic over pixel lengths). The schedule's "Dijkstra" entry for task 6 is superseded; A* with a zero heuristic is Dijkstra, so nothing is lost.

## Consequences

- Nobody blocks on anybody else after week 1.
- Interface churn is visible and deliberate.
- The algorithm modules stay unit-testable on a laptop.
