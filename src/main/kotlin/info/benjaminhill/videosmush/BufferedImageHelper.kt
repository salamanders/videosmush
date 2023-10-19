package info.benjaminhill.videosmush

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.onCompletion
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import java.io.File


/** Need to get fancy with the thread local objects to keep from crashing (I think.) */
suspend fun Flow<BufferedImage>.collectToFile(destinationFile: File, fps: Double = 60.0) {
    var ffr: FFmpegFrameRecorder? = null
    var maxFrameNumber = 0
    val converter = Java2DFrameConverter()
    println("Started collecting Sequence<BufferedImage> to '${destinationFile.absolutePath}'")
    this.onCompletion {
            println("Finished writing to '${destinationFile.absolutePath}' ($maxFrameNumber frames)")
            ffr?.close()
        }.collectIndexed { index, image ->
            if (ffr == null) {
                ffr = FFmpegFrameRecorder(destinationFile.absolutePath, image.width, image.height, 0).apply {
                    frameRate = fps
                    videoBitrate = 0 // max
                    videoQuality = 0.0 // max
                    start()
                }
            }
            ffr!!.record(converter.convert(image), avutil.AV_PIX_FMT_ARGB)
            maxFrameNumber = index
        }

}

