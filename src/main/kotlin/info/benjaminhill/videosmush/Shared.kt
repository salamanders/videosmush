package info.benjaminhill.videosmush

import org.apache.logging.log4j.kotlin.logger
import java.awt.*
import java.awt.AlphaComposite.*
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt

val logger by lazy { logger("video2") }

fun Double.toPercent(): String = "${(100 * this).roundToInt()}%"

/** Assuming each int maxes out at 255, average diff independent of array size */
infix fun IntArray.averageDiff(other: IntArray): Double {
    require(isNotEmpty() && size == other.size)
    return (size - 1 downTo 0).sumBy { idx ->
        abs(this[idx] - other[idx])
    } / (255 * size.toDouble())
}


/** Add text to bottom-right of image */
fun BufferedImage.addTextWatermark(text: String) {
    val buffer = 3

    val g2d = this.graphics as Graphics2D
    g2d.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_GASP
    )

    val alphaChannel = getInstance(SRC_OVER, 0.1f)
    g2d.composite = alphaChannel
    g2d.color = Color.WHITE
    g2d.font = Font(Font.SANS_SERIF, Font.BOLD, 24)
    val fontMetrics = g2d.fontMetrics

    val textStringBounds = fontMetrics.getStringBounds(text, g2d)
    val maxDescent = fontMetrics.maxDescent
    println(textStringBounds)
    println(maxDescent)
    val locX = (this.width - textStringBounds.width - buffer).toInt()
    val locY = (this.height - (maxDescent + buffer))
    println("Loc: $locX, $locY")
    g2d.drawString(text, locX, locY)
    g2d.dispose()
}
