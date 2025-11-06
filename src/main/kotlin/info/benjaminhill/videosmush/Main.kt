package info.benjaminhill.videosmush

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flattenConcat
import kotlinx.coroutines.flow.transform
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.walk
import kotlin.math.ceil


val outputVideoFile = File("output.mp4")

suspend fun main() {
    avutil.av_log_set_level(avutil.AV_LOG_QUIET)

    // Determine the length of each source in frames
    val allSources: List<Source> = Path.of("inputs").walk()
        .filter { it.toFile().isFile && !it.name.startsWith(".") }
        .sorted()
        .map { path ->
            FFmpegFrameGrabber(path.toString()).use { grabber ->
                grabber.start()
                Source(path, grabber.lengthInVideoFrames).also {
                    grabber.stop()
                }
            }
        }.toList()

    // Scripts are "input frame number,target output frame number"
    val script: Map<Int, Int> = Path.of("script.csv").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split(",") }
        .associate { (inputFrame, outputFrame) -> inputFrame.trim().toInt() to 60 * outputFrame.trim().toInt() }

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
    }.also {
        println(it)
    }

    try {
        var previousRatio = 0
        allSources.map {
            it.path.toFrames(
                isThumbnail = false,
                rotFilter = "transpose=1",
            )
        }
            .reduce { acc, flow ->
                arrayOf(acc, flow).asFlow().flattenConcat()
            }.transform { frameWithPixelFormat ->
                val frame = frameWithPixelFormat.frame
                val localAveragingImage: AveragingImage =
                    averagingImage ?: AveragingImageRGB.blankOf(frame.imageWidth, frame.imageHeight).also {
                        println("Averaging into an image: ${it.width} x ${it.height}")
                        @Suppress("ASSIGNED_VALUE_IS_NEVER_READ")
                        averagingImage = it
                    }
                localAveragingImage += frameWithPixelFormat
                currentFrame++

                val ratio = ratios.firstOrNull { currentFrame <= it.first }?.second ?: ratios.last().second
                if (ratio != previousRatio) {
                    println("RATIO: $ratio")
                    previousRatio = ratio
                }
                if (localAveragingImage.numAdded >= ratio) {
                    emit(localAveragingImage.toBufferedImage())
                }
            }.collectToFile(outputVideoFile)
    } finally {
        averagingImage?.close()
    }
}
