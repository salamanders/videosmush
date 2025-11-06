package info.benjaminhill.videosmush

import java.nio.file.Path

data class Source(
    val path: Path,
    val frames: Int,
)
