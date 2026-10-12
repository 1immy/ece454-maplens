package edu.wisc.maplens.model

enum class ConfidenceLevel { HIGH, LOW }

/** The four checks from the proposal; any failing one makes confidence LOW. */
enum class ConfidenceCheck { SPREAD, GROUP_SPLIT, HEADING_FIT, TURN_FIT }

/** Recovery options, in the order the app offers them. */
enum class RecoveryAction { SCAN_SIGN, NEXT_TURN_OR_DEAD_END, ASK_HALLWAY, TAP_SPOT, MAP_ONLY }

/** Producer: confidence monitor (Area B). Consumer: guidance / session. */
data class ConfidenceState(
    val timestampNanos: Long,
    val level: ConfidenceLevel,
    val failedChecks: Set<ConfidenceCheck>,
    val suggestedRecovery: RecoveryAction?,
)
