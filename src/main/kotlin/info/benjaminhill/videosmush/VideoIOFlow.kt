@file:Suppress("BlockingMethodInNonBlockingContext")

package info.benjaminhill.videosmush

import info.benjaminhill.utils.logexp
import info.benjaminhill.videosmush.DecodedImage.Companion.toDecodedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import org.bytedeco.ffmpeg.global.avutil
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
        logger.info { "Custom filter: `$it`" }
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

            logexp(frameNumber) {
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
        logger.info { "Started collecting BI flow to '${destinationFile.absolutePath}'" }
    }.onEmpty {
        logger.error { "Warning: BI flow was empty, nothing written to output file." }
    }.onCompletion {
        ffr?.close()
        logger.info { "Finished writing to '${destinationFile.absolutePath}' ($maxFrameNumber frames)" }
    }.collectIndexed { frameNumber, frame ->
        // "Lazy" construction of the ffr using the first frame
        if (ffr == null) {
            ffr = FFmpegFrameRecorder(destinationFile.absolutePath, frame.width, frame.height, 0).apply {
                frameRate = fps
                videoBitrate = 0 // max
                videoQuality = 0.0 // max
                start()
            }
            logger.info {"Starting recording to '${destinationFile.absolutePath}' (${ffr!!.imageWidth}, ${ffr!!.imageHeight})" }
        }
        ffr!!.record(converter.convert(frame), avutil.AV_PIX_FMT_ARGB)
        logexp(frameNumber) { "Recorded frame $frameNumber" }
        maxFrameNumber = frameNumber
    }
}

