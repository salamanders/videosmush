package info.benjaminhill.videosmush

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt

/**
 * A CPU-based implementation of [AveragingImage] that keeps full integer precision per channel.
 *
 * **Why this class exists:**
 * Standard `BufferedImage` backends often cap pixel values at 255 (byte), which leads to overflow
 * when summing thousands of frames. This class uses `IntArray` to store the running sum,
 * preventing overflow at the cost of significantly higher memory usage (4x per channel).
 *
 * **Trade-offs:**
 * - Pros: thread-safe accumulation (via coroutines), no overflow, simple to debug.
 * - Cons: High memory footprint. Not suitable for extremely high-res video on low-RAM machines.
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
        coroutineScope {
            when (bi.type) {
                BufferedImage.TYPE_3BYTE_BGR,
                BufferedImage.TYPE_4BYTE_ABGR,
                    -> {
                    val data = (bi.raster.dataBuffer!! as DataBufferByte).data!!
                    val stepSize = if (bi.alphaRaster == null) 3 else 4
                    // ignore alpha channel 3 if it exists

                    launch(Dispatchers.Default) {
                        for (i in red.indices) {
                            red[i] += (data[i * stepSize + 2].toInt() and 0xFF)
                        }
                    }
                    launch(Dispatchers.Default) {
                        for (i in green.indices) {
                            green[i] += (data[i * stepSize + 1].toInt() and 0xFF)
                        }
                    }
                    launch(Dispatchers.Default) {
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
                    launch(Dispatchers.Default) {
                        for (i in red.indices) {
                            red[i] += (data[i] shr 16 and 0xFF)
                        }
                    }
                    launch(Dispatchers.Default) {
                        for (i in green.indices) {
                            green[i] += (data[i] shr 8 and 0xFF)
                        }
                    }
                    launch(Dispatchers.Default) {
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