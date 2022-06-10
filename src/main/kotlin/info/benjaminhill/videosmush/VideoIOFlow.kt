@file:Suppress("BlockingMethodInNonBlockingContext")

package info.benjaminhill.videosmush

import info.benjaminhill.utils.LogInfrequently
import info.benjaminhill.utils.logExp
import info.benjaminhill.utils.r
import info.benjaminhill.videosmush.DecodedImage.Companion.toDecodedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.*
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

// const val FILTER_THUMB32 = "crop=in_w*.5:in_h*.5:in_w*.25:in_h*.25,scale=32:32"

/**
 * @param file ffmpeg-readable video file
 * @param filterStr optional ffmpeg filter string
 * @return Frame Rate/sec, flow of Frames (minimal processing, just clone)
 */
fun videoToDecodedImages(
    file: File,
    filterStr: String? = null
): Pair<Double, Flow<DecodedImage>> {
    require(file.canRead()) { "Unable to read ${file.name}" }
    avutil.av_log_set_level(avutil.AV_LOG_QUIET)

    val converter = object : ThreadLocal<FrameConverter<BufferedImage>>() {
        override fun initialValue() = Java2DFrameConverter()
    }

    var frameNumber = 0

    val grabber = FFmpegFrameGrabber(file).apply {
        start()
    }

    val optionalFilter = filterStr?.let {
        LOG.info { "Custom filter: `$it`" }
        val filter = FFmpegFrameFilter(it, grabber.imageWidth, grabber.imageHeight)
        filter.pixelFormat = grabber.pixelFormat
        filter.start()
        filter
    }

    val result = flow {
        while (true) {
            val nextFrame = grabber.grabImage() ?: break
            val filteredFrame = optionalFilter?.let {
                it.push(nextFrame)
                it.pull()
            } ?: nextFrame
            // Immediately move into a DecodedImage so we don't need to Java2DFrameConverter.cloneBufferedImage
            emit(converter.get().convert(filteredFrame).toDecodedImage())

            logExp(frameNumber) {
                "Read frame $frameNumber (${(frameNumber.toDouble() / grabber.lengthInVideoFrames).toPercent()})"
            }

            frameNumber++
        }

        optionalFilter?.stop()
        optionalFilter?.close()
        grabber.stop()
        grabber.close()
    }
        .flowOn(Dispatchers.Default)

    return Pair(grabber.videoFrameRate, result)
}

/** Need to get fancy with the thread local objects to keep from crashing (I think.) */
suspend fun Flow<BufferedImage>.collectToFile(destinationFile: File, fps: Double) {
    var ffr: FFmpegFrameRecorder? = null
    var maxFrameNumber = 0
    val converter = Java2DFrameConverter()

    onStart {
        LOG.info { "Started collecting BI flow to '${destinationFile.absolutePath}'" }
    }.onEmpty {
        LOG.error { "Warning: BI flow was empty, nothing written to output file." }
    }.onCompletion {
        ffr?.close()
        LOG.info { "Finished writing to '${destinationFile.absolutePath}' ($maxFrameNumber frames)" }
    }.collectIndexed { frameNumber, frame ->
        // "Lazy" construction of the ffr using the first frame
        if (ffr == null) {
            ffr = FFmpegFrameRecorder(destinationFile.absolutePath, frame.width, frame.height, 0).apply {
                frameRate = fps
                videoBitrate = 0 // max
                videoQuality = 0.0 // max
                start()
            }
            LOG.info { "Starting recording to '${destinationFile.absolutePath}' (${ffr!!.imageWidth}, ${ffr!!.imageHeight})" }
        }
        ffr!!.record(converter.convert(frame), avutil.AV_PIX_FMT_ARGB)
        logExp(frameNumber) { "Recorded frame $frameNumber" }
        maxFrameNumber = frameNumber
    }
}

/**
 * Takes NX frames from the flow an averages them,
 * where the merge list is (N1, N2...)
 */
@ExperimentalTime
internal fun Flow<DecodedImage>.mergeFrames(merges: List<Int>): Flow<BufferedImage> {
    require(merges.isNotEmpty()) { "Empty list of merges, halting." }

    val whittleDown = merges.toMutableList()
    var currentWhittle = whittleDown.removeAt(0)
    var startingWhittle = currentWhittle
    lateinit var combinedImage: DecodedImage
    val isCombinedInit = AtomicBoolean(false)
    val imageRate = LogInfrequently(30.seconds) { perSec -> "Input running at ${perSec.r} images/sec" }

    return transform { inputImage: DecodedImage ->
        if (isCombinedInit.compareAndSet(false, true)) {
            // do NOT keep a reference to the inputImage, very bad things will happen.
            combinedImage = DecodedImage.blankOf(inputImage.width, inputImage.height)
        }
        combinedImage += inputImage
        imageRate.hit()
        currentWhittle--
        if (currentWhittle == 0) {
            emit(
                combinedImage
                    .toAverage()
                //.addTextWatermark("${startingWhittle}x")
            )
        }
        if (currentWhittle < 1 && whittleDown.isNotEmpty()) {
            currentWhittle = whittleDown.removeAt(0)
            startingWhittle = currentWhittle
        }

    }.onCompletion {
        LOG.info { "Discarded input frames (should be close to 0): $currentWhittle" }
        LOG.info { "Remaining unused script frames (should be close to 0): ${whittleDown.size}" }
    }
}

