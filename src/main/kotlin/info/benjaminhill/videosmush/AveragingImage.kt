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
 * Makes use of Java 21 virtual threads.
 */
class AveragingImage
private constructor(
     val width: Int,
     val height: Int,
    private val red: IntArray = IntArray(width * height),
    private val green: IntArray = IntArray(width * height),
    private val blue: IntArray = IntArray(width * height),
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
    operator fun plusAssign(other: BufferedImage):Unit  = FastRunner().use { fr ->
        numAdded++
        require(numAdded * 255 < Int.MAX_VALUE) { "Possible overflow in DecodedImage after $numAdded adds." }
        when (other.type) {
            BufferedImage.TYPE_3BYTE_BGR,
            BufferedImage.TYPE_4BYTE_ABGR,
            -> {
                val data = (other.raster.dataBuffer!! as DataBufferByte).data!!
                val stepSize = if (other.alphaRaster == null) 3 else 4
                // ignore alpha channel 3 if it exists

                fr.prun {
                    for (i in red.indices) {
                        red[i] += (data[i * stepSize + 2].toInt() and 0xFF)
                    }
                }
                fr.prun {
                    for (i in green.indices) {
                        green[i] += (data[i * stepSize + 1].toInt() and 0xFF)
                    }
                }
                fr.prun {
                    for (i in blue.indices) {
                        blue[i] += (data[i * stepSize + 0].toInt() and 0xFF)
                    }
                }
            }


            BufferedImage.TYPE_INT_RGB,
            BufferedImage.TYPE_INT_BGR,
            BufferedImage.TYPE_INT_ARGB,
            -> {
                val data = (other.raster.dataBuffer!! as DataBufferInt).data!!
                // ignore alpha shift 24 if it exists
                fr.prun {
                    for (i in red.indices) {
                        red[i] += (data[i] shr 16 and 0xFF)
                    }
                }
                fr.prun {
                    for (i in green.indices) {
                        green[i] += (data[i] shr 8 and 0xFF)
                    }
                }
                fr.prun {
                    for (i in blue.indices) {
                        blue[i] += (data[i] shr 0 and 0xFF)
                    }
                }
            }

            else -> throw IllegalArgumentException("Bad image type: $other.type")
        }
    }


    /** Produces an averaged image and resets all the buckets */
    fun toBufferedImage(): BufferedImage {
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
        val converter = object : ThreadLocal<FrameConverter<BufferedImage>>() {
            override fun initialValue() = Java2DFrameConverter()
        }
        internal fun blankOf(width: Int, height: Int): AveragingImage =
            AveragingImage(width = width, height = height).also {
                it.numAdded = 0
            }

    }
}
