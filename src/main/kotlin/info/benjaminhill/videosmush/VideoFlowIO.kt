package info.benjaminhill.videosmush

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

private const val FILTER_THUMB = "scale=128:-1"

/** Need to get fancy with the thread local objects to keep from crashing (I think.) */
suspend fun Flow<BufferedImage>.collectToFile(destinationFile: File, fps: Double = 60.0, isLossless: Boolean = false) {
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
            ffr = FFmpegFrameRecorder(destinationFile.absolutePath, image.width, image.height, 0).apply {
                frameRate = fps
                videoBitrate = 0 // max
                videoQuality = 0.0 // max
                setVideoOption("threads", "auto")
                videoCodec = if (isLossless) avcodec.AV_CODEC_ID_FFV1 else avcodec.AV_CODEC_ID_AV1
                start()
            }
        }
        ffr.record(converter.convert(image), PixelFormat.ARGB.ffmpeg)
        maxFrameNumber = index
    }
}

/** Compact way of generating a flow of images */
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
                println(" ${sourceFile.name} ${numFrames.get()}")
            }
            if (videoFilter != null) {
                videoFilter!!.push(nextFrame)
                emit(FrameWithPixelFormat.ofFfmpeg(videoFilter!!.pull().clone(), grabber!!.pixelFormat))
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