package info.benjaminhill.videosmush

import org.apache.logging.log4j.kotlin.logger
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
