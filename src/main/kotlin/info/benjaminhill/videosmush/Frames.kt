package info.benjaminhill.videosmush

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.LongAdder
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.isRegularFile

private const val FILTER_THUMB = "scale=128:-1"

/** Compact way of generating a series of Frames */
fun Path.toBufferedImages(
    isThumbnail: Boolean
): Flow<BufferedImage> {
    var optionalFilter: FFmpegFrameFilter? = null
    var grabber: FFmpegFrameGrabber? = null
    val numConverters = LongAdder()
     val converter = object : ThreadLocal<FrameConverter<BufferedImage>>() {
        override fun initialValue() = Java2DFrameConverter().also { numConverters.increment() }
    }
    require(this.isReadable()) { "Unable to read top-level file or folder $this" }
    return if (this.isDirectory()) {
        Files.walk(this).filter { it.isRegularFile() }.map { it.toBufferedImages(isThumbnail) }.reduce { result, element ->
                concatenate(result, element)
            }.orElseThrow()
    } else {
        require(Files.isReadable(this)) { "Unable to read file: $this" }
        require(Files.isRegularFile(this)) { "Expected path to be a plain file: $this" }
        val sourceFile = this.toFile()
        flow<BufferedImage> {
            while (true) {
                emit(grabber!!.grabImage()?.let { frame ->
                    val possiblyFilteredFrame: Frame = optionalFilter?.let { filter ->
                        filter.push(frame)
                        filter.pull()
                    } ?: frame
                    // Don't clone, because immediately converted to a BI.
                    converter.get().convert(possiblyFilteredFrame)
                } ?: break)
            }
        }.onStart {
            println("Starting reading from: $sourceFile")
            avutil.av_log_set_level(avutil.AV_LOG_QUIET)
            grabber = FFmpegFrameGrabber(sourceFile)
            grabber!!.start()
            val inputWidth: Int = grabber!!.imageWidth
            val inputHeight: Int = grabber!!.imageHeight
            if (isThumbnail) {
                optionalFilter = FFmpegFrameFilter(FILTER_THUMB, inputWidth, inputHeight)
                optionalFilter!!.pixelFormat = grabber!!.pixelFormat
                optionalFilter!!.start()
            }
        }.onCompletion {
            optionalFilter?.stop()
            optionalFilter?.close()
            grabber?.stop()
            grabber?.close()
            println("Finished reading from: $sourceFile")
            println("Created ${numConverters.sum()} converters.")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun <T> concatenate(vararg flows: Flow<T>) = flows.asFlow().flattenConcat()