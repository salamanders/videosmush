package info.benjaminhill.videosmush

import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt

/**
 * Image fully parsed out to a full int per each pixel per each channel.
 * NOT memory efficient, but avoids overflows.
 */
class AveragingImageBIDirect
private constructor(
    width: Int,
    height: Int,
    private val sums: IntArray = IntArray(width * height * 3),
) : BaseAveragingImage(width, height) {

    /**
     * Merge in another BufferedImage without converting the whole thing.
     */
    override suspend operator fun plusAssign(other: FrameWithPixelFormat) {
        val bi = converter.get().convert(other.frame)
        other.frame.close()

        numAdded++
        require(numAdded * 255 < Int.MAX_VALUE) { "Possible overflow in DecodedImage after $numAdded adds." }
        when (bi.type) {
            BufferedImage.TYPE_3BYTE_BGR,
            BufferedImage.TYPE_4BYTE_ABGR,
                -> {
                val data = (bi.raster.dataBuffer!! as DataBufferByte).data!!
                val stepSize = if (bi.alphaRaster == null) 3 else 4
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
                val data = (bi.raster.dataBuffer!! as DataBufferInt).data!!
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
    override fun toBufferedImage(): BufferedImage {
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
        internal fun blankOf(width: Int, height: Int): AveragingImage =
            AveragingImageBIDirect(width = width, height = height)
    }
}
