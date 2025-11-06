package info.benjaminhill.videosmush

import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameConverter
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt

/**
 * Image fully parsed out to a full int per each pixel per each channel.
 * NOT memory efficient, but avoids overflows.
 */
class AveragingImage2
private constructor(
    val width: Int,
    val height: Int,
    private val sums: IntArray = IntArray(width * height * 3),
) {
    // Assume you always have at least one
    var numAdded = 1
        private set

    operator fun plusAssign(other: Frame) {
        plusAssign(converter.get().convert(other))
        other.close()
    }
    /**
     * Merge in another BufferedImage without converting the whole thing.
     */
    operator fun plusAssign(other: BufferedImage) {
        numAdded++
        require(numAdded * 255 < Int.MAX_VALUE) { "Possible overflow in DecodedImage after $numAdded adds." }
        when (other.type) {
            BufferedImage.TYPE_3BYTE_BGR,
            BufferedImage.TYPE_4BYTE_ABGR,
            -> {
                val data = (other.raster.dataBuffer!! as DataBufferByte).data!!
                val stepSize = if (other.alphaRaster == null) 3 else 4
                // ignore alpha channel 3 if it exists
                for (i in 0 until width * height) {
                    sums[i * 3 + 0] += (data[i * stepSize + 2].toInt() and 0xFF)
                    sums[i * 3 + 1] += (data[i * stepSize + 1].toInt() and 0xFF)
                    sums[i * 3 + 2] += (data[i * stepSize + 0].toInt() and 0xFF)
                }
            }


            BufferedImage.TYPE_INT_RGB,
            BufferedImage.TYPE_INT_BGR,
            BufferedImage.TYPE_INT_ARGB,
            -> {
                val data = (other.raster.dataBuffer!! as DataBufferInt).data!!
                // ignore alpha shift 24 if it exists
                for (i in 0 until width * height) {
                    sums[i * 3 + 0] += (data[i] shr 16 and 0xFF)
                    sums[i * 3 + 1] += (data[i] shr 8 and 0xFF)
                    sums[i * 3 + 2] += (data[i] shr 0 and 0xFF)
                }
            }

            else -> throw IllegalArgumentException("Bad image type: $other.type")
        }
    }


    /** Produces an averaged image and resets all the buckets */
    fun toBufferedImage(): BufferedImage {
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            val pixels = IntArray(width * height) { i ->
                val r = (sums[i * 3 + 0] / numAdded) and 0xFF
                val g = (sums[i * 3 + 1] / numAdded) and 0xFF
                val b = (sums[i * 3 + 2] / numAdded) and 0xFF
                (r shl 16) or (g shl 8) or b
            }
            setRGB(0, 0, width, height, pixels, 0, width)
        }.also {
            sums.fill(0)
            numAdded = 0
        }
    }

    companion object {
        val converter = object : ThreadLocal<FrameConverter<BufferedImage>>() {
            override fun initialValue() = Java2DFrameConverter()
        }
        internal fun blankOf(width: Int, height: Int): AveragingImage2 =
            AveragingImage2(width = width, height = height).also {
                it.numAdded = 0
            }

    }
}
