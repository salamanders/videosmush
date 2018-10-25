import org.bytedeco.javacv.FrameConverter
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import java.lang.Math.abs


private const val THUMB_RES = 32 // NPE when 16 pixels, strange!
private val converter: FrameConverter<BufferedImage> = Java2DFrameConverter()

/** Assuming each int maxes out at 255, average diff independent of array size */
infix fun IntArray.averageDiff(other: IntArray): Double {
    require(isNotEmpty() && size == other.size)
    return (size - 1 downTo 0).sumBy { idx ->
        abs(this[idx] - other[idx])
    } / (255 * size.toDouble())
}

/**
 * Difference between frame and the next frame from 0.0..1.0
 * First cropped to center, then scaled to very small thumb. (ok if not square)
 */
fun getDiffs(inputFileName: String): List<Double> {
    KVideo(inputFileName).use { source ->
        source.setFilter("crop=in_w*.5:in_h*.5:in_w*.25:in_h*.25,scale=$THUMB_RES:$THUMB_RES")
        return source.frames.map {
            converter.convert(it)!!
        }.map {
            it.getAllPixelData() // ok because small, hopefully sequence reduces memory as well
        }.zipWithNext().map {
            it.first averageDiff it.second
        }.toList()
    }
}

fun BufferedImage.getAllPixelData(): IntArray = when (type) {
    BufferedImage.TYPE_BYTE_GRAY -> (raster.dataBuffer!! as DataBufferByte).data.asIterable().map { pixel ->
        pixel.toInt() and 0xFF
    }
    BufferedImage.TYPE_3BYTE_BGR,
    BufferedImage.TYPE_4BYTE_ABGR -> (raster.dataBuffer!! as DataBufferByte).data.asIterable().chunked(
            if (alphaRaster == null) 3 else 4
    ).map { channels ->
        listOf(
                channels[0].toInt() and 0xFF,
                channels[1].toInt() and 0xFF,
                channels[2].toInt() and 0xFF)
        // ignore alpha channel 3 if it exists
    }.flatten()
    BufferedImage.TYPE_INT_RGB,
    BufferedImage.TYPE_INT_BGR,
    BufferedImage.TYPE_INT_ARGB -> (raster.dataBuffer!! as DataBufferInt).data.asIterable().map { pixel ->
        // ignore alpha shift 24 if it exists
        listOf(
                pixel shr 16 and 0xFF,
                pixel shr 8 and 0xFF,
                pixel and 0xFF)
    }.flatten()
    else -> throw IllegalArgumentException("Bad image type: $type")
}.toIntArray()


class ImageStacker : AutoCloseable {
    private var combined: BufferedImage? = null
    private var g2d: Graphics2D? = null
    private var imageCount = 0

    fun add(img: BufferedImage) {
        if (combined == null) {
            combined = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
            g2d = combined!!.createGraphics()
        }
        imageCount++
        g2d!!.composite = AlphaComposite.SrcOver.derive(1.0f / imageCount)
        g2d!!.drawImage(img, 0, 0, null)
    }

    /** Gets and resets the stack */
    fun getStack(): BufferedImage = combined!!

    fun reset() {
        g2d?.dispose()
        g2d = null
        combined = null
        imageCount = 0
    }

    override fun close() {
        g2d?.dispose()
    }
}