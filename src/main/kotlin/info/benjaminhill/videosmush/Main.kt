package info.benjaminhill.videosmush

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flattenConcat
import kotlinx.coroutines.flow.transform
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File
import java.nio.file.Path
import kotlin.io.path.isReadable
import kotlin.io.path.readLines
import kotlin.io.path.walk
import kotlin.io.path.writeText


val outputVideoFile = File("average_thumb_10x.mp4")

fun createScript(): String {
    avutil.av_log_set_level(avutil.AV_LOG_QUIET)
    var runningFrames = 0
    val result: MutableList<String> = mutableListOf()
    Path.of("/Users/benhill/Movies/monarch/").walk().toSortedSet().forEach { path ->
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
    var averagingImage: AveragingImage? = null
    allSources.map { it.path.toFrames(isThumbnail = true, rotFilter = it.transpose) }
        .reduce { acc, flow ->
            arrayOf(acc, flow).asFlow().flattenConcat()
        }.transform { frame ->
            if (averagingImage == null) {
                averagingImage = AveragingImage.blankOf(frame.imageWidth, frame.imageHeight)
                println("Averaging into an image: ${averagingImage!!.width} x ${averagingImage!!.height}")
            }
            averagingImage!! += frame
            if (averagingImage!!.numAdded > 10) {
                emit(averagingImage!!.toBufferedImage())
            }
        }.collectToFile(outputVideoFile)
}

