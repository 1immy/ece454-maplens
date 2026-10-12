package edu.wisc.maplens.model

/** Evaluation conditions A-D. Selected at runtime, never by branch. */
enum class EvalCondition {
    /** A: steps + heading only. */
    RAW_PDR,

    /** B: add corridor constraints. */
    PDR_MAP,

    /** C: PDR + map + room-sign scans at fixed intervals. */
    PERIODIC_SCANS,

    /** D: PDR + map + scan only when confidence drops. */
    CONFIDENCE_TRIGGERED,
}
