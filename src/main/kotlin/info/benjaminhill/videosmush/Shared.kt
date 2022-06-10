package info.benjaminhill.videosmush

import mu.KotlinLogging
import java.awt.AlphaComposite.SRC_OVER
import java.awt.AlphaComposite.getInstance
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt

val LOG = KotlinLogging.logger {}

fun Double.toPercent(): String = "${(100 * this).roundToInt()}%"

/** Assuming each int maxes out at 255, average diff independent of array size */
infix fun IntArray.averageDiff(other: IntArray): Double {
    require(isNotEmpty() && size == other.size)
    return (size - 1 downTo 1).sumOf { idx ->
        abs(this[idx] - other[idx])
    } / (255 * size.toDouble())
}


/** Add text to bottom-right of image */
fun BufferedImage.addTextWatermark(text: String): BufferedImage {
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
    // println(textStringBounds)
    // println(maxDescent)
    val locX = (this.width - textStringBounds.width - buffer).toInt()
    val locY = (this.height - (maxDescent + buffer))
    // println("Loc: $locX, $locY")
    g2d.drawString(text, locX, locY)
    g2d.dispose()
    return this
}
