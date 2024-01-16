package info.benjaminhill.videosmush

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flattenConcat
import kotlinx.coroutines.flow.transform
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File
import java.nio.file.Path
import kotlin.io.path.*


val outputVideoFile = File("average_thumb_10x.mp4")
lateinit var averagingImage: AveragingImage

data class Source(
    val path: Path,
    val frames: Int,
    val totalFrames: Int,
    val fps : Double,
    val transpose: String,
) {
    constructor(line: String) : this(
        path = Path.of(line.split("\t")[0]),
        frames = line.split("\t")[1].toInt(),
        totalFrames = line.split("\t")[2].toInt(),
        fps = line.split("\t")[3].toDouble(),
        transpose = line.split("\t")[4]
    )

    fun toLine() = listOf(path, frames, totalFrames, transpose).joinToString("\t")
}

@OptIn(ExperimentalPathApi::class)
fun createScript(): String {
    avutil.av_log_set_level(avutil.AV_LOG_QUIET)
    var runningFrames = 0
    val result: MutableList<String> = mutableListOf()
    Path.of("D:\\Video\\fullres").walk().toSortedSet().forEach { path ->
        val grabber = FFmpegFrameGrabber(path.toString()).also { gr ->
            gr.start()
        }
        runningFrames += grabber.lengthInVideoFrames
        result.add(
            Source(
                path,
                grabber.lengthInVideoFrames,
                runningFrames,
                grabber.frameRate,
                "transpose=1",
            ).toLine()
        )
        grabber.stop()
    }
    return result.joinToString("\n")
}


// https://creatomate.com/blog/how-to-rotate-videos-using-ffmpeg
@OptIn(ExperimentalPathApi::class)
suspend fun main() {
    val sources = Path.of("sources.tsv")
    if (!sources.isReadable()) {
        println("Edit from base_sources.tsv into sources.tsv:")
        Path.of("base_sources.tsv").writeText(createScript())
    } else {
        println("Creating final output.")
        val allSources = sources.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { Source(it) }
        smush(allSources)
    }
}


@OptIn(ExperimentalCoroutinesApi::class)
suspend fun smush(allSources: List<Source>) {

    outputVideoFile.delete()
    allSources.map { it.path.toFrames(isThumbnail = true, rotFilter = it.transpose) }
        .reduce { acc, flow ->
            arrayOf(acc, flow).asFlow().flattenConcat()
        }.transform { frame ->
            if (!::averagingImage.isInitialized) {
                averagingImage = AveragingImage.blankOf(frame.imageWidth, frame.imageHeight)
                println("Averaging into an image: ${averagingImage.width} x ${averagingImage.height}")
            }
            averagingImage += frame
            if (averagingImage.numAdded > 10) {
                emit(averagingImage.toBufferedImage())
            }
        }.collectToFile(outputVideoFile)
}

