import org.bytedeco.javacv.*
import java.awt.image.BufferedImage

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

    val frames = sequence<Frame> {
        while (true) {
            val frame = grabber.grabImage() ?: break
            if (filter != null) {
                filter!!.push(frame)
                yield(filter!!.pull())
            } else {
                yield(frame)
            }
        }
        grabber.stop()
    }.constrainOnce()

    val framesBi = frames.map { CONVERTER.convert(it)!! }

    private var filter: FFmpegFrameFilter? = null
    fun setFilter(filterStr: String) {
        val newFilter = FFmpegFrameFilter(filterStr, width, height)
        newFilter.pixelFormat = pixelFormat
        newFilter.start()
        filter = newFilter
    }

    override fun close() {
        grabber.stop()
        filter?.close()
    }

    companion object {
        private val CONVERTER: FrameConverter<BufferedImage> = Java2DFrameConverter()
    }
}