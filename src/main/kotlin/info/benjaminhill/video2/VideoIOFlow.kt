@file:Suppress("BlockingMethodInNonBlockingContext")

package info.benjaminhill.video2

import info.benjaminhill.utils.println2
import info.benjaminhill.utils.toPercent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.bytedeco.javacpp.avutil
import org.bytedeco.javacv.*
import java.awt.image.BufferedImage
import java.io.File

const val FILTER_THUMB32 = "crop=in_w*.5:in_h*.5:in_w*.25:in_h*.25,scale=32:32"

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
        println("Custom filter: `$it`")
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
            emit(DecodedImage(converter.get().convert(filteredFrame)))

            println2(frameNumber) {
                "Read frame $frameNumber (${(frameNumber.toDouble() / grabber.lengthInVideoFrames).toPercent()})"
            }

            frameNumber++
        }

        optionalFilter?.stop()
        optionalFilter?.close()
        grabber.stop()
        grabber.close()
    }.flowOn(Dispatchers.Default)

    return Pair(grabber.videoFrameRate, result)
}

/** Need to get fancy with the thread local objects to keep from crashing (I think.) */
suspend fun Flow<BufferedImage>.collectToFile(destinationFile: File, fps: Double) {
    var ffr: FFmpegFrameRecorder? = null
    var maxFrameNumber = 0
    val converter = Java2DFrameConverter()

    collectIndexed { frameNumber, frame ->
        if (ffr == null) {
            ffr = FFmpegFrameRecorder(destinationFile.name, frame.width, frame.height, 0)
            ffr!!.frameRate = fps
            ffr!!.videoBitrate = 0 // max
            ffr!!.videoQuality = 0.0 // max?
            ffr!!.start()
            println("Starting recording to ${destinationFile.name} (${ffr!!.imageWidth}, ${ffr!!.imageHeight})")
        }
        ffr!!.record(converter.convert(frame), avutil.AV_PIX_FMT_ARGB)
        println2(frameNumber) { "Recorded frame $frameNumber" }
        maxFrameNumber = frameNumber
    }
    ffr?.close()
    println("Finished writing to ${destinationFile.name} ($maxFrameNumber frames)")
}

