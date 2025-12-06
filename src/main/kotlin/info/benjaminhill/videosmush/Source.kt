package info.benjaminhill.videosmush

import java.nio.file.Path

/**
 * Represents a single video input file.
 *
 * **Why this class exists:**
 * We often treat multiple input files (e.g., `GOPR001.MP4`, `GOPR002.MP4`) as a single continuous stream.
 * To do this effectively, we need to pre-calculate metadata like `frames` (length) for each segment
 * so we can map "global time" to "local file time".
 */
data class Source(
    val path: Path,
    val frames: Int,
)
