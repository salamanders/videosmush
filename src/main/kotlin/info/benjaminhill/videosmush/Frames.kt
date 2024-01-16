package info.benjaminhill.videosmush

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

private const val FILTER_THUMB = "scale=128:-1"


/** Compact way of generating a flow of images */
fun Path.toFrames(
    isThumbnail: Boolean,
    rotFilter: String,
    maxFrames: Long = Long.MAX_VALUE - 1
): Flow<Frame> {
    require(Files.isReadable(this)) { "Unable to read file: $this" }
    require(Files.isRegularFile(this)) { "Expected path to be a plain file: $this" }
    val sourceFile = this.toFile()
    val numFrames = AtomicLong()
    var grabber: FFmpegFrameGrabber? = null
    var videoFilter: FFmpegFrameFilter? = null

    return flow<Frame> {
        while (numFrames.get() < maxFrames) {
            val nextFrame = grabber!!.grabImage() ?: break
            if (numFrames.incrementAndGet() % 1_000L == 0L) {
                println(" ${sourceFile.name} ${numFrames.get()}")
            }
            videoFilter!!.push(nextFrame)
            emit(videoFilter!!.pull().clone())
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
            videoFilter = FFmpegFrameFilter(videoFilterString, gr.imageWidth, gr.imageHeight).also { vf ->
                vf.pixelFormat = gr.pixelFormat
                vf.start()
            }
        }
    }.onCompletion {
        videoFilter!!.stop()
        videoFilter!!.close()
        grabber!!.stop()
        grabber!!.close()
        println("Finished reading from: $sourceFile, ${numFrames.get()} frames.")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> concatenate(vararg flows: Flow<T>) = flows.asFlow().flattenConcat()