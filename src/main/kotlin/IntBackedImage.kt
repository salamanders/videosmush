import info.benjaminhill.workergraph.Worker
import mu.KLoggable
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt


/** Lots of alpha blurs looks bad using regular images */
class IntBackedImage(private val width: Int, private val height: Int) {
    private val red = IntArray(width * height)
    private val green = IntArray(width * height)
    private val blue = IntArray(width * height)
    private var numAdded = 0

    /** Adds pixel RGB values to the internal image */
    fun add(colorImage: BufferedImage) {
        numAdded++
        when (colorImage.type) {
            BufferedImage.TYPE_3BYTE_BGR,
            BufferedImage.TYPE_4BYTE_ABGR -> (colorImage.raster.dataBuffer!! as DataBufferByte).data.asIterable().chunked(
                    if (colorImage.alphaRaster == null) 3 else 4
            ).forEachIndexed { pixelLocation, channels ->
                // ignore alpha channel 3 if it exists
                red[pixelLocation] += channels[2].toInt() and 0xFF
                green[pixelLocation] += channels[1].toInt() and 0xFF
                blue[pixelLocation] += channels[0].toInt() and 0xFF
            }
            BufferedImage.TYPE_INT_RGB,
            BufferedImage.TYPE_INT_BGR,
            BufferedImage.TYPE_INT_ARGB -> (colorImage.raster.dataBuffer!! as DataBufferInt).data.asIterable().forEachIndexed { pixelLocation, pixel ->
                // ignore alpha shift 24 if it exists
                red[pixelLocation] += (pixel shr 16 and 0xFF)
                green[pixelLocation] += (pixel shr 8 and 0xFF)
                blue[pixelLocation] += (pixel shr 0 and 0xFF)
            }
            else -> throw IllegalArgumentException("Bad image type: ${colorImage.type}")
        }
    }

    /** Divides by the number of images, terminal operation, resets to 0 */
    fun toAverage(): BufferedImage {
        for (i in 0 until width * height) {
            red[i] = red[i] / numAdded
            green[i] = green[i] / numAdded
            blue[i] = blue[i] / numAdded
        }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val data = IntArray(width * height)
        for (i in 0 until data.size) {
            data[i] = ((red[i] and 0xFF) shl 16) or
                    ((green[i] and 0xFF) shl 8) or
                    ((blue[i] and 0xFF) shl 0)
        }
        image.setRGB(0, 0, width, height, data, 0, width)
        logger.debug { "Producing average of $numAdded frames." }
        reset()
        return image
    }

    private fun reset() {
        red.fill(0)
        green.fill(0)
        blue.fill(0)
        numAdded = 0
    }

    companion object : KLoggable {
        override val logger = Worker.logger()
    }
}
