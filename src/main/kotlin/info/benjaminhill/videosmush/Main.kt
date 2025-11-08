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
import kotlin.time.measureTime


suspend fun main() {
    avutil.av_log_set_level(avutil.AV_LOG_QUIET)

    // Determine the length of each source in frames
    val allSources: List<Source> = Path.of("input").walk()
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

    println("Total input frames: ${allSources.sumOf { it.frames }}")

    // Scripts are "input frame number,target output frame number"
    val useCompressionSmusher = true // Set to false to use script.csv
    val script: Map<Int, Int> = if (useCompressionSmusher) {
        val smusher = CompressionBasedSmusher(allSources)
        smusher.smush()
    } else {
        Path.of("script.csv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(",") }
            .associate { (inputFrame, outputFrame) -> inputFrame.trim().toInt() to 60 * outputFrame.trim().toInt() }
    }

    val implementations = mapOf(
        //"bidirect" to { w: Int, h: Int -> AveragingImageBIDirect.blankOf(w, h) },
        //"frames" to { w: Int, h: Int -> AveragingImageFrames.blankOf(w, h) },
        //"gpu" to { w: Int, h: Int -> AveragingImageGPU.blankOf(w, h) },
        // "ocv" to { w: Int, h: Int -> AveragingImageOCV.blankOf(w, h) },
        "rgb" to { w: Int, h: Int -> AveragingImageRGB.blankOf(w, h) },
    )

    for ((name, factory) in implementations) {
        try {
            val outputFile = File("output/output_$name.mkv")
            val duration = measureTime {
                smush(allSources, script, factory, outputFile)
            }
            println("Implementation '$name' took $duration")
        } catch (t: Throwable) {
            System.err.println(t)
            System.err.println(t.stackTraceToString())
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun smush(
    allSources: List<Source>,
    script: Map<Int, Int>,
    averagingImageFactory: (Int, Int) -> AveragingImage,
    outputFile: File
) {
    outputFile.delete()
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
                    averagingImage ?: averagingImageFactory(frame.imageWidth, frame.imageHeight).also {
                        println("Averaging into an image: ${it.width} x ${it.height}")
                        averagingImage = it
                    }
                localAveragingImage += frameWithPixelFormat
                currentFrame++

                val ratio = ratios.firstOrNull { currentFrame <= it.first }?.second ?: ratios.last().second
                if (ratio != previousRatio) {
                    println("RATIO: $ratio")
                    @SuppressWarnings("unused")
                    previousRatio = ratio
                }
                if (localAveragingImage.numAdded >= ratio) {
                    emit(localAveragingImage.toBufferedImage())
                }
            }.collectToFile(outputFile)
    } finally {
        averagingImage?.close()
    }
}
