import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame

/** Small wrapper around FFmpegFrameGrabber that allows for a filter and provides a sequence of frames */
class KVideo(fileName: String) : AutoCloseable {
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
}