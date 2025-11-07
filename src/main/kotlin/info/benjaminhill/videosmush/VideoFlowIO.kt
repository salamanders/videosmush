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

/** Need to get fancy with the thread local objects to keep from crashing (I think.) */
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
                // NOTE: Output file names matter!

                // videoBitrate = 0 // max
                // videoQuality = 0.0 // max
                //setVideoOption("tag", "hvc1")
                //format = "mp4"
                // videoCodec = avcodec.AV_CODEC_ID_H265 // Use HEVC
                //setVideoCodecName("libx265")
                //pixelFormat = AV_PIX_FMT_YUV420P10LE // Use 10-bit pixel format
                //setVideoOption("crf", "16") // 16 is already extremely high quality for H.265
                //setVideoOption("preset", "veryslow")
                //setVideoOption("profile", "main10") // Specify the main 10-bit profile

                // This works.  Lossless.
//                format = "mkv" // Use Matroska container
//                videoCodec = avcodec.AV_CODEC_ID_FFV1 // FFV1 lossless codec
//                pixelFormat = AV_PIX_FMT_YUV420P10LE // 10-bit

                // "tune" is less critical now, but "film" is fine.
                //setVideoOption("tune", "film")
                // etVideoOption("threads", "auto")
                // --- Key Changes Here ---
//                format = "mp4" // MP4 is correct for H.265
//                setVideoOption("tag", "hvc1") // Good for Apple compatibility
//                setVideoCodecName("libx265") // Specify encoder by name
//                pixelFormat = AV_PIX_FMT_YUV420P10LE // 10-bit
//                setVideoOption("crf", "16")
//                setVideoOption("preset", "veryslow")
//                setVideoOption("profile", "main10") // 10-bit profile
//                // --- End Changes ---

                // setVideoOption("tune", "film")
                // setVideoOption("threads", "auto")
                // start()

                // --- H.265 10-bit in MKV Container ---
                format = "mkv"                       // Set container to Matroska
                setVideoCodecName("libaom-av1")
                pixelFormat = AV_PIX_FMT_YUV420P10LE // 10-bit is fully supported
                setVideoOption("crf", "10")          // Quality (0-63, lower is better). 20 is very high.
                setVideoOption("tile-columns", "3")
                setVideoOption("tile-rows", "2")
                setVideoOption("row-mt", "1")
                // --- End Settings ---
                setVideoOption("threads", "auto")
                require(destinationFile.extension == format)
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