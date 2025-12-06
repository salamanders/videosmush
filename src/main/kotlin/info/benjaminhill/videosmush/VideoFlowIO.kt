package info.benjaminhill.videosmush

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P10LE
import org.bytedeco.javacv.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

private const val FILTER_THUMB = "scale=128:-1"

/**
 * Consumes a Flow of images and encodes them into a video file.
 *
 * **Role:** The "Sink" of our processing pipeline.
 *
 * It abstracts away the complexity of FFmpeg video encoding (codecs, pixel formats, threading)
 * so the main logic can just yield `BufferedImage`s without worrying about how they get saved.
 *
 * Currently configured for high-quality, modern AV1 (libaom-av1) encoding in MKV.
 */
suspend fun Flow<BufferedImage>.collectToFile(destinationFile: File, fps: Double = 60.0) {
    var ffr: FFmpegFrameRecorder? = null
    var maxFrameNumber = 0
    val converter = Java2DFrameConverter()
    println("Started collecting Flow<BufferedImage> to '${destinationFile.absolutePath}'")
    this.onCompletion {
        println("Finishing writing to '${destinationFile.absolutePath}' ($maxFrameNumber frames)")
        ffr?.close()
        println("Closed")
    }.collectIndexed { index, image ->
        if (ffr == null) {
            require(image.width % 2 == 0 && image.height % 2 == 0) {
                "Video dimensions must be even for YUV420P pixel format."
            }
            FFmpegLogCallback.set()
            ffr = FFmpegFrameRecorder(destinationFile.absolutePath, image.width, image.height, 0).apply {
                frameRate = fps
                // --- H.265 10-bit in MKV Container ---
                format = "mkv"  // Set container to Matroska
                require(destinationFile.extension == format) { "Output extension must match the format: $format" }
                setVideoCodecName("libaom-av1")
                pixelFormat = AV_PIX_FMT_YUV420P10LE // 10-bit
                setVideoOption("crf", "10") // Quality (0-63, lower is better). 20 is very high.
                // Enable some parallel processing in the output encoding
                setVideoOption("tile-columns", "3")
                setVideoOption("tile-rows", "2")
                setVideoOption("row-mt", "1")
                // Don't set num-cpu!
                // --- End Settings ---
                setVideoOption("threads", "auto")
                start()
            }
        }
        ffr.record(converter.convert(image), PixelFormat.ARGB.ffmpeg)
        maxFrameNumber = index
    }
}

/**
 * Produces a Flow of frames from a video file.
 *
 * **Role:** The "Source" of our processing pipeline.
 *
 * It wraps the imperative, stateful `FFmpegFrameGrabber` into a reactive Kotlin Flow.
 * It also handles:
 * - Resource management (closing grabbers)
 * - Optional resizing/filtering (e.g. for creating thumbnails)
 * - Pixel format metadata propagation
 */
fun Path.toFrames(
    isThumbnail: Boolean,
    rotFilter: String?,
    maxFrames: Long = Long.MAX_VALUE - 1
): Flow<FrameWithPixelFormat> {
    require(Files.isReadable(this)) { "Unable to read file: $this" }
    require(Files.isRegularFile(this)) { "Expected path to be a plain file: $this" }
    val sourceFile = this.toFile()
    val numFrames = AtomicLong()
    var grabber: FFmpegFrameGrabber? = null
    var videoFilter: FFmpegFrameFilter? = null

    return flow {
        while (numFrames.get() < maxFrames) {
            val nextFrame = grabber!!.grabImage() ?: break
            if (numFrames.incrementAndGet() % 5_000L == 0L) {
                println(" in:${sourceFile.name} ${numFrames.get()}")
            }
            if (videoFilter != null) {
                videoFilter!!.push(nextFrame)
                // The frame from pull() must be closed after use to prevent memory leaks.
                videoFilter!!.pull()?.let { filteredFrame ->
                    emit(FrameWithPixelFormat.ofFfmpeg(filteredFrame.clone(), grabber!!.pixelFormat))
                    filteredFrame.close()
                }
            } else {
                emit(FrameWithPixelFormat.ofFfmpeg(nextFrame.clone(), grabber!!.pixelFormat))
            }

        }
    }.onStart {
        avutil.av_log_set_level(avutil.AV_LOG_QUIET)
        println("Starting $sourceFile")
        grabber = FFmpegFrameGrabber(sourceFile).also { gr ->
            gr.start()
            val videoFilterString = listOfNotNull(
                rotFilter,
                if (isThumbnail) {
                    FILTER_THUMB
                } else {
                    null
                }
            ).joinToString(",")
            println("  Filter: `$videoFilterString` on ${gr.imageWidth}, ${gr.imageHeight}")
            if (videoFilterString.isNotBlank()) {
                videoFilter = FFmpegFrameFilter(videoFilterString, gr.imageWidth, gr.imageHeight).also { vf ->
                    vf.pixelFormat = gr.pixelFormat
                    vf.start()
                }
            }
        }
    }.onCompletion {
        videoFilter?.apply {
            stop()
            close()
        } ?: System.err.println("Unable to stop video filter: $this")
        grabber?.apply {
            stop()
            close()
        } ?: System.err.println("Unable to stop grabber: $this")
        println("Finished reading from: $sourceFile, ${numFrames.get()} frames.")
    }
}

@Suppress("unused")
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> concatenate(vararg flows: Flow<T>) = flows.asFlow().flattenConcat()