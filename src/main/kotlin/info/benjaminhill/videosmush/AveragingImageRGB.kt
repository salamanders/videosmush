package info.benjaminhill.videosmush

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt

/**
 * Image fully parsed out to a full int per each pixel per each channel.
 * NOT memory efficient, but avoids overflows.
 * Makes use of Java 21 virtual threads.
 */
class AveragingImageRGB
private constructor(
    override val width: Int,
    override val height: Int,
    private val red: IntArray = IntArray(width * height),
    private val green: IntArray = IntArray(width * height),
    private val blue: IntArray = IntArray(width * height),
) : BaseAveragingImage(width, height) {

    /**
     * Merge in another BufferedImage without converting the whole thing.
     */
    override suspend operator fun plusAssign(other: FrameWithPixelFormat) {
        val bi = converter.get().convert(other.frame)
        other.frame.close()
        numAdded++
        require(numAdded * 255 < Int.MAX_VALUE) { "Possible overflow in DecodedImage after $numAdded adds." }
        withContext(Dispatchers.Default) {
            when (bi.type) {
                BufferedImage.TYPE_3BYTE_BGR,
                BufferedImage.TYPE_4BYTE_ABGR,
                    -> {
                    val data = (bi.raster.dataBuffer!! as DataBufferByte).data!!
                    val stepSize = if (bi.alphaRaster == null) 3 else 4
                    // ignore alpha channel 3 if it exists

                    launch {
                        for (i in red.indices) {
                            red[i] += (data[i * stepSize + 2].toInt() and 0xFF)
                        }
                    }
                    launch {
                        for (i in green.indices) {
                            green[i] += (data[i * stepSize + 1].toInt() and 0xFF)
                        }
                    }
                    launch {
                        for (i in blue.indices) {
                            blue[i] += (data[i * stepSize + 0].toInt() and 0xFF)
                        }
                    }
                }

                BufferedImage.TYPE_INT_RGB,
                BufferedImage.TYPE_INT_BGR,
                BufferedImage.TYPE_INT_ARGB,
                    -> {
                    val data = (bi.raster.dataBuffer!! as DataBufferInt).data!!
                    // ignore alpha shift 24 if it exists
                    launch {
                        for (i in red.indices) {
                            red[i] += (data[i] shr 16 and 0xFF)
                        }
                    }
                    launch {
                        for (i in green.indices) {
                            green[i] += (data[i] shr 8 and 0xFF)
                        }
                    }
                    launch {
                        for (i in blue.indices) {
                            blue[i] += (data[i] shr 0 and 0xFF)
                        }
                    }
                }

                else -> throw IllegalArgumentException("Bad image type: ${bi.type}")
            }
        }
    }

    /** Produces an averaged image and resets all the buckets */
    override fun toBufferedImage(): BufferedImage {
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            setRGB(0, 0, width, height, IntArray(red.size) { index ->
                (((red[index] / numAdded) and 0xFF) shl 16) or (((green[index] / numAdded) and 0xFF) shl 8) or (((blue[index] / numAdded) and 0xFF) shl 0)
            }, 0, width)
        }.also {
            red.fill(0)
            green.fill(0)
            blue.fill(0)
            numAdded = 0
        }
    }

    companion object {


        internal fun blankOf(width: Int, height: Int): AveragingImage =
            AveragingImageRGB(width = width, height = height)

    }
}