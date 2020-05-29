package info.benjaminhill.video2

import info.benjaminhill.utils.CountHits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt


fun Flow<BufferedImage>.mergeFrames(merges: List<Int>): Flow<BufferedImage> = flow {
    require(merges.isNotEmpty())
    val whittleDown = merges.toMutableList()
    var currentWhittle = 0
    var combinedImage: IntBackedImage? = null
    val imageRate = CountHits(3_000) { perSec: Int -> "Input running at $perSec images/sec" }

    collect { image ->
        if (combinedImage == null) {
            combinedImage = IntBackedImage(image.width, image.height)
        }
        combinedImage!! += image
        imageRate.hit()

        currentWhittle--
        if (currentWhittle == 0) {
            emit(combinedImage!!.toAverage())
        }
        if (currentWhittle < 1 && whittleDown.isNotEmpty()) {
            currentWhittle = whittleDown.removeAt(0)
        }
    }

    println("Discarded input frames (should be close to 0): $currentWhittle")
    println("Remaining unused script frames (should be close to 0): ${whittleDown.size}")
}

/**
 * Gray returns directly
 * BGR (bytes) or RGB (int) convert to hue (because shadows)
 */
fun BufferedImage.toIntArray(): IntArray = when (type) {
    BufferedImage.TYPE_BYTE_GRAY,
    -> (raster.dataBuffer!! as DataBufferByte).data.asIterable().map { pixel ->
        pixel.toInt() and 0xFF
    }
    BufferedImage.TYPE_3BYTE_BGR,
    BufferedImage.TYPE_4BYTE_ABGR,
    -> (raster.dataBuffer!! as DataBufferByte).data.asIterable().chunked(
            if (alphaRaster == null) 3 else 4
    ).map { channels ->
        getHue(
                red = channels[2].toInt() and 0xFF,
                green = channels[1].toInt() and 0xFF,
                blue = channels[0].toInt() and 0xFF
        ) // ignore alpha channel 3 if it exists
    }
    BufferedImage.TYPE_INT_RGB,
    BufferedImage.TYPE_INT_BGR,
    BufferedImage.TYPE_INT_ARGB,
    -> (raster.dataBuffer!! as DataBufferInt).data.asIterable().map { pixel ->
        // ignore alpha shift 24 if it exists
        getHue(
                red = pixel shr 16 and 0xFF,
                green = pixel shr 8 and 0xFF,
                blue = pixel and 0xFF
        )
    }
    else -> throw IllegalArgumentException("Bad image type: $type")
}.toIntArray()

fun BufferedImage.deepCopy(): BufferedImage {
    val cm = colorModel!!
    val isAlphaPremultiplied = cm.isAlphaPremultiplied
    val raster = copyData(null)!!
    return BufferedImage(cm, raster, isAlphaPremultiplied, null)
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


