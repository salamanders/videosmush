package info.benjaminhill.video

import info.benjaminhill.workergraph.Worker
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import java.lang.Math.abs
import java.util.*


class VideoPixelDiff(upstreamWorker: Worker<*, BufferedImage>) : Worker<BufferedImage, Double>(upstreamWorker) {

    private var previousFramePixels: IntArray? = null
    override fun process(input: BufferedImage, flags: EnumSet<Flag>) {
        val nextFramePixels = input.getAllPixelData()
        previousFramePixels?.let { prev ->
            outputs.put(Pair(prev averageDiff nextFramePixels, flags))
        }
        previousFramePixels = nextFramePixels
    }

    companion object {
        // NPE when 16 pixels, strange!
        const val FILTER = "crop=in_w*.5:in_h*.5:in_w*.25:in_h*.25,scale=32:32"

        /**
         * Gray returns directly
         * BGR (bytes) or RGB (int) convert to hue (because shadows)
         */
        fun BufferedImage.getAllPixelData(): IntArray = when (type) {
            BufferedImage.TYPE_BYTE_GRAY -> (raster.dataBuffer!! as DataBufferByte).data.asIterable().map { pixel ->
                pixel.toInt() and 0xFF
            }
            BufferedImage.TYPE_3BYTE_BGR,
            BufferedImage.TYPE_4BYTE_ABGR -> (raster.dataBuffer!! as DataBufferByte).data.asIterable().chunked(
                    if (alphaRaster == null) 3 else 4
            ).map { channels ->
                getHue(
                        red = channels[2].toInt() and 0xFF,
                        green = channels[1].toInt() and 0xFF,
                        blue = channels[0].toInt() and 0xFF
                )
                // ignore alpha channel 3 if it exists
            }
            BufferedImage.TYPE_INT_RGB,
            BufferedImage.TYPE_INT_BGR,
            BufferedImage.TYPE_INT_ARGB -> (raster.dataBuffer!! as DataBufferInt).data.asIterable().map { pixel ->
                // ignore alpha shift 24 if it exists
                getHue(
                        red = pixel shr 16 and 0xFF,
                        green = pixel shr 8 and 0xFF,
                        blue = pixel and 0xFF
                )
            }
            else -> throw IllegalArgumentException("Bad image type: $type")
        }.toIntArray()

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

    /** Assuming each int maxes out at 255, average diff independent of array size */
    private infix fun IntArray.averageDiff(other: IntArray): Double {
        require(isNotEmpty() && size == other.size)
        return (size - 1 downTo 0).sumBy { idx ->
            abs(this[idx] - other[idx])
        } / (255 * size.toDouble())
    }

}


