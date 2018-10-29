import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import java.lang.Math.abs


private const val THUMB_RES = 32 // NPE when 16 pixels, strange!

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
fun getDiffs(inputFileName: String, trimBorders: Boolean): List<Double> {
    KInputVideo(inputFileName).use { source ->
        var filter = ""
        if (trimBorders) {
            filter += "crop=in_w*.5:in_h*.5:in_w*.25:in_h*.25,"
        }
        filter += "scale=$THUMB_RES:$THUMB_RES"
        source.setFilter(filter)
        return source.frames.map {
            it.getAllPixelData() // ok because small, hopefully sequence reduces memory as well
        }.zipWithNext().map {
            it.first averageDiff it.second
        }.toList()
    }
}

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