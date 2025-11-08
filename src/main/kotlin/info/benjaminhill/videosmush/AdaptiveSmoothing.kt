package info.benjaminhill.videosmush

import org.apache.commons.math3.analysis.polynomials.PolynomialFunction
import org.apache.commons.math3.fitting.PolynomialCurveFitter
import org.apache.commons.math3.fitting.WeightedObservedPoints
import kotlin.math.min
import kotlin.math.pow

fun savitzkyGolaySmooth(data: DoubleArray): DoubleArray {
    // Tune windowSize and polynomialDegree for more or less smoothing.
    val windowSize = 5
    val polynomialDegree = 2
    val sgFilter = SavitzkyGolayFilter(windowSize, polynomialDegree)
    return sgFilter.smooth(data)
}

fun enhanceVariabilityAndApplyConstraints(
    data: DoubleArray,
    // Higher factor = more dramatic speed changes.
    variabilityFactor: Double,
    // 1:1 speed limit.
    minMerge: Int,
    // Upper limit on speed-up.
    maxMerge: Int
): IntArray {
    val normalized = data.normalize()
    val variabled = normalized.map { it.pow(variabilityFactor) }.toDoubleArray()
    val scaled = variabled.scale(minMerge.toDouble(), maxMerge.toDouble())
    return scaled.map { it.toInt() }.toIntArray()
}


class SavitzkyGolayFilter(
    private val windowSize: Int,
    private val polynomialDegree: Int
) {
    fun smooth(data: DoubleArray): DoubleArray {
        val n = data.size
        if (n < windowSize) {
            return data.clone()
        }

        val halfWindow = windowSize / 2
        val smoothedData = DoubleArray(n)

        for (i in 0 until n) {
            val start = (i - halfWindow).coerceAtLeast(0)
            val end = (i + halfWindow).coerceAtMost(n - 1)
            val window = data.slice(start..end)

            val obs = WeightedObservedPoints()
            for ((j, value) in window.withIndex()) {
                obs.add((start + j).toDouble(), value)
            }

            val fitter = PolynomialCurveFitter.create(polynomialDegree)
            val coeff = fitter.fit(obs.toList())
            val polynomial = PolynomialFunction(coeff)

            smoothedData[i] = polynomial.value((start + window.size / 2).toDouble())
        }
        return smoothedData
    }
}

fun DoubleArray.normalize(): DoubleArray {
    val min = this.minOrNull() ?: 0.0
    val max = this.maxOrNull() ?: 1.0
    return this.map { (it - min) / (max - min) }.toDoubleArray()
}

fun DoubleArray.scale(min: Double, max: Double): DoubleArray {
    val dataMin = this.minOrNull() ?: 0.0
    val dataMax = this.maxOrNull() ?: 1.0
    return this.map { min + (it - dataMin) * (max - min) / (dataMax - dataMin) }.toDoubleArray()
}

data class DecodedImage(
    val width: Int,
    val height: Int,
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
)

fun DecodedImage.toHue(): IntArray = IntArray(red.size) { index ->
    getHue(red[index], green[index], blue[index])
}

private fun getHue(red: Int, green: Int, blue: Int): Int {
    val min = min(min(red, green), blue)
    val max = red.coerceAtLeast(green).coerceAtLeast(blue)
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
