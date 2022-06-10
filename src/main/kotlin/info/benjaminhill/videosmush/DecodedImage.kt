package info.benjaminhill.videosmush

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt

/**
 * Image fully parsed out to a full int per each pixel per each channel.
 * NOT memory efficient, but avoids overflows.
 */
class DecodedImage
private constructor(
    internal val width: Int,
    internal val height: Int,
    private val red: IntArray = IntArray(width * height),
    private val green: IntArray = IntArray(width * height),
    private val blue: IntArray = IntArray(width * height),
) {
    // Assume you always have at least one
    private var numAdded = 1
    private val lock = Mutex()

    /** Adds pixel RGB values to the internal image */

    suspend operator fun plusAssign(other: DecodedImage) {
        lock.withLock(this) {
            numAdded++
            // ~300 images/second
            for (i in red.indices) {
                red[i] += other.red[i]
                green[i] += other.green[i]
                blue[i] += other.blue[i]
            }
            /*
            // ~280 images/second
            coroutineScope {
                launch {
                    for (i in red.indices) {
                        red[i] += other.red[i]
                    }
                }
                launch {
                    for (i in green.indices) {
                        green[i] += other.green[i]
                    }
                }
                launch {
                    for (i in blue.indices) {
                        blue[i] += other.blue[i]
                    }
                }
            }*/
        }
    }


    suspend fun toAverage(): BufferedImage {
        lock.withLock(this) {
            return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
                setRGB(0, 0, width, height, IntArray(red.size) { index ->
                    (((red[index] / numAdded) and 0xFF) shl 16) or
                            (((green[index] / numAdded) and 0xFF) shl 8) or
                            (((blue[index] / numAdded) and 0xFF) shl 0)
                }, 0, width)
            }.also {
                red.fill(0)
                green.fill(0)
                blue.fill(0)
                numAdded = 0
            }
        }
    }

    fun toHue(): IntArray = IntArray(red.size) { index ->
        getHue(red[index], green[index], blue[index])
    }

    companion object {
        internal fun blankOf(width: Int, height: Int): DecodedImage = DecodedImage(width = width, height = height)

        fun BufferedImage.toDecodedImage(): DecodedImage {
            val width = width
            val height = height
            val red = IntArray(width * height)
            val green = IntArray(width * height)
            val blue = IntArray(width * height)

            when (type) {
                BufferedImage.TYPE_3BYTE_BGR,
                BufferedImage.TYPE_4BYTE_ABGR,
                -> {
                    val data = (raster.dataBuffer!! as DataBufferByte).data!!
                    val stepSize = if (alphaRaster == null) 3 else 4
                    for (i in red.indices) {
                        // ignore alpha channel 3 if it exists
                        red[i] = data[i * stepSize + 2].toInt() and 0xFF
                        green[i] = data[i * stepSize + 1].toInt() and 0xFF
                        blue[i] = data[i * stepSize + 0].toInt() and 0xFF
                    }
                }
                BufferedImage.TYPE_INT_RGB,
                BufferedImage.TYPE_INT_BGR,
                BufferedImage.TYPE_INT_ARGB,
                -> {
                    val data = (raster.dataBuffer!! as DataBufferInt).data!!
                    for (i in red.indices) {
                        // ignore alpha shift 24 if it exists
                        red[i] = (data[i] shr 16 and 0xFF)
                        green[i] = (data[i] shr 8 and 0xFF)
                        blue[i] = (data[i] shr 0 and 0xFF)
                    }
                }
                else -> throw IllegalArgumentException("Bad image type: $type")
            }
            return DecodedImage(width, height, red, green, blue)
        }


        /**
         * Gray returns directly
         * BGR (bytes) or RGB (int) convert to hue (because shadows)
         */
        internal fun BufferedImage.toHue(): IntArray =
            if (type == BufferedImage.TYPE_BYTE_GRAY) {
                (raster.dataBuffer!! as DataBufferByte).data.asIterable().map { pixel ->
                    pixel.toInt() and 0xFF
                }.toIntArray()
            } else {
                toDecodedImage().toHue()
            }
    }

    /** from https://stackoverflow.com/questions/23090019/fastest-formula-to-get-hue-from-rgb/26233318 */
    private fun getHue(red: Int, green: Int, blue: Int): Int {
        val min = minOf(red, green, blue)
        val max = maxOf(red, green, blue)
        if (min == max) {
            return 0
        }
        var hue = when (max) {
            red -> (green - blue).toDouble() / (max - min)
            green -> 2 + (blue - red).toDouble() / (max - min)
            else -> 4 + (red - green).toDouble() / (max - min)
        }
        hue *= 60
        if (hue < 0) hue += 360
        return hue.toInt()
    }
}




