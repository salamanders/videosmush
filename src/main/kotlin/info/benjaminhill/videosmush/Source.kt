package info.benjaminhill.videosmush

import java.nio.file.Path

data class Source(
    val path: Path,
    val frames: Int,
    val totalFrames: Int,
    val fps: Double,
    val transpose: String,
) {
    constructor(line: String) : this(
        path = Path.of(line.split("\t")[0]),
        frames = line.split("\t")[1].toInt(),
        totalFrames = line.split("\t")[2].toInt(),
        fps = line.split("\t")[3].toDouble(),
        transpose = line.split("\t")[4]
    )

    fun toLine() = listOf(path, frames, totalFrames, fps, transpose).joinToString("\t")
}