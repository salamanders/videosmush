import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameConverter
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.lang.Math.round

/** Small wrapper around FFmpegFrameGrabber that allows for a filter and provides a sequence of frames or BI */
class KInputVideo(fileName: String) : AutoCloseable {
    val width: Int
    val height: Int
    private val pixelFormat: Int
    private val grabber: FFmpegFrameGrabber = FFmpegFrameGrabber(fileName)

    init {
        grabber.start()
        // Sacrifice 1 image
        grabber.grabImage()!!.let { frame0 ->
            width = frame0.imageWidth
            height = frame0.imageHeight
        }
        pixelFormat = grabber.pixelFormat
        println("Opened video: $fileName: $width x $height $pixelFormat")
    }

    val frames = sequence {
        var frameNumber = 1
        while (true) {
            frameNumber++
            val frame = grabber.grabImage()?.clone() ?: break

            yield(CONVERTER.convert(
                    filter?.let {
                        it.push(frame)
                        it.pull()
                    } ?: frame))

            if ((frameNumber and (frameNumber - 1)) == 0) {
                println("Read frame $frameNumber (${round((100.0 * frameNumber) / grabber.lengthInVideoFrames)}%)")
            }
        }
        grabber.stop()
    }.constrainOnce()

    private var filter: FFmpegFrameFilter? = null

    fun setFilter(filterStr: String) {
        FFmpegFrameFilter(filterStr, width, height).let {
            it.pixelFormat = pixelFormat
            it.start()
            filter = it
        }
    }

    override fun close() {
        grabber.stop()
        filter?.close()
    }

    companion object {
        private val CONVERTER: FrameConverter<BufferedImage> = Java2DFrameConverter()
    }
}