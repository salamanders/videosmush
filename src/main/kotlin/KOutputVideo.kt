import org.bytedeco.javacpp.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameConverter
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage

class KOutputVideo(private val outputFileName: String) : AutoCloseable {
    lateinit var ffr: FFmpegFrameRecorder

    fun add(bi: BufferedImage) = add(CONVERTER.convert(bi))

    fun add(frame: Frame) {
        if (!::ffr.isInitialized) {
            ffr = FFmpegFrameRecorder(outputFileName, frame.imageWidth, frame.imageHeight, 0)
            ffr.frameRate = 30.0
            ffr.videoBitrate = 0 // max
            ffr.videoQuality = 0.0 // max?
            ffr.start()
        }
        ffr.record(frame, avutil.AV_PIX_FMT_ARGB)
    }

    override fun close() {
        ffr.stop()
    }

    companion object {
        private val CONVERTER: FrameConverter<BufferedImage> = Java2DFrameConverter()
    }
}