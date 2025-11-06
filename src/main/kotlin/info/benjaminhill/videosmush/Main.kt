package info.benjaminhill.videosmush

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flattenConcat
import kotlinx.coroutines.flow.transform
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.walk
import kotlin.math.ceil

val outputVideoFile = File("average_thumb_10x.mp4")

suspend fun main() {
    avutil.av_log_set_level(avutil.AV_LOG_QUIET)

    val allSources = Path.of("inputs").walk()
        .filter { it.toFile().isFile }
        .sorted()
        .map { path ->
            FFmpegFrameGrabber(path.toString()).use { grabber ->
                grabber.start()
                Source(path, grabber.lengthInVideoFrames)
            }
        }.toList()

    val script = Path.of("script.csv").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split(",") }
        .associate { (inputFrame, outputFrame) -> inputFrame.trim().toInt() to outputFrame.trim().toInt() }

    smush(allSources, script)
}

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun smush(allSources: List<Source>, script: Map<Int, Int>) {
    outputVideoFile.delete()
    var averagingImage: AveragingImage? = null
    var currentFrame = 0

    val ratios = script.entries.sortedBy { it.key }.let { sortedEntries ->
        var lastInputFrame = 0
        var lastOutputFrame = 0
        sortedEntries.map { (inputFrame, outputFrame) ->
            val inputFrames = inputFrame - lastInputFrame
            val outputFrames = outputFrame - lastOutputFrame
            val ratio = ceil(inputFrames.toDouble() / outputFrames.toDouble()).toInt()
            lastInputFrame = inputFrame
            lastOutputFrame = outputFrame
            inputFrame to ratio
        }
    }

    allSources.map { it.path.toFrames(isThumbnail = true) }
        .reduce { acc, flow ->
            arrayOf(acc, flow).asFlow().flattenConcat()
        }.transform { frame ->
            val localAveragingImage =
                averagingImage ?: AveragingImage.blankOf(frame.imageWidth, frame.imageHeight).also {
                    println("Averaging into an image: ${it.width} x ${it.height}")
                    averagingImage = it
                }
            localAveragingImage += frame
            currentFrame++

            val ratio = ratios.firstOrNull { currentFrame <= it.first }?.second ?: ratios.last().second

            if (localAveragingImage.numAdded >= ratio) {
                emit(localAveragingImage.toBufferedImage())
                averagingImage = null // Reset for the next batch
            }
        }.collectToFile(outputVideoFile)
}
