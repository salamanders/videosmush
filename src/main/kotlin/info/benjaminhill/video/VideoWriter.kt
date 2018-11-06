package info.benjaminhill.video

import info.benjaminhill.workergraph.Worker
import org.bytedeco.javacpp.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.FrameConverter
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.util.*

/** Need to get fancy with the thread local objects to keep from crashing (I think.) */
class VideoWriter(upstreamWorker: Worker<*, BufferedImage>, private val destinationFileName: String, private val fps: Double = 30.0) : Worker<BufferedImage, String>(upstreamWorker) {
    companion object {
        private val converter = object : ThreadLocal<FrameConverter<BufferedImage>>() {
            override fun initialValue() = Java2DFrameConverter()
        }
    }

    private lateinit var ffr: FFmpegFrameRecorder
    private var frameNumber = 0

    override fun process(input: BufferedImage, flags: EnumSet<Flag>) {
        if (!::ffr.isInitialized) {
            ffr = FFmpegFrameRecorder(destinationFileName, input.width, input.height, 0)
            ffr.frameRate = fps
            ffr.videoBitrate = 0 // max
            ffr.videoQuality = 0.0 // max?
            ffr.start()
            logger.info { "Starting recording to $destinationFileName (${ffr.imageWidth}, ${ffr.imageHeight})" }
        }
        ffr.record(converter.get().convert(input), avutil.AV_PIX_FMT_ARGB)
        frameNumber++

        if (flags.contains(Flag.LAST)) {
            ffr.close()
            outputs.put(Pair(destinationFileName, flags))
            logger.info { "Finished and closed recording to $destinationFileName" }
        }
    }
}
