package info.benjaminhill.videosmush

import org.apache.commons.math3.analysis.polynomials.PolynomialFunction
import org.apache.commons.math3.fitting.PolynomialCurveFitter
import org.apache.commons.math3.fitting.WeightedObservedPoints
import kotlin.math.min
import kotlin.math.pow

/**
 * Applies a Savitzky-Golay filter to smooth data.
 *
 * This smoothing technique fits a polynomial to a window of data points to reduce noise
 * while preserving the shape of peaks better than a simple moving average.
 *
 * Essential for "jittery" data like frame-by-frame pixel differences where we want to find
 * the underlying "action" trend without reacting to every single micro-movement.
 */
fun savitzkyGolaySmooth(data: DoubleArray): DoubleArray {
    // Tune windowSize and polynomialDegree for more or less smoothing.
    val windowSize = 5
    val polynomialDegree = 2
    val sgFilter = SavitzkyGolayFilter(windowSize, polynomialDegree)
    return sgFilter.smooth(data)
}

/**
 * Transforms raw difference data into specific frame-merge counts (speedup factors).
 *
 * This function handles the "artistic" part of the adaptive timelapse:
 * 1. Normalizes input data to 0..1
 * 2. Applies a power curve (`variabilityFactor`) to make quiet parts quieter and loud parts louder.
 * 3. Scales the result to the desired speed range (`minMerge` to `maxMerge`).
 *
 * The output is an array where each integer represents how many input frames should be merged
 * into a single output frame at that point in time.
 */
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


/**
 * Implementation of the Savitzky-Golay smoothing filter.
 *
 * Unlike standard averaging, this uses polynomial regression (Least Squares) on a moving window.
 * This class encapsulates the math complexity (Apache Commons Math) required to fit the curve
 * and predict the smoothed center value.
 */
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

        val fitter = PolynomialCurveFitter.create(polynomialDegree)
        val obs = WeightedObservedPoints()

        for (i in 0 until n) {
            val start = (i - halfWindow).coerceAtLeast(0)
            val end = (i + halfWindow).coerceAtMost(n - 1)
            val window = data.slice(start..end)

            obs.clear()
            for ((j, value) in window.withIndex()) {
                obs.add((start + j).toDouble(), value)
            }

            val coeff = fitter.fit(obs.toList())
            val polynomial = PolynomialFunction(coeff)

            smoothedData[i] = polynomial.value((start + window.size / 2).toDouble())
        }
        return smoothedData
    }
}

/** Rescales the array so the smallest value is 0.0 and the largest is 1.0. */
fun DoubleArray.normalize(): DoubleArray {
    val min = this.minOrNull() ?: 0.0
    val max = this.maxOrNull() ?: 1.0
    return this.map { (it - min) / (max - min) }.toDoubleArray()
}

/** Linearly scales the values in the array to fit within the [min, max] range. */
fun DoubleArray.scale(min: Double, max: Double): DoubleArray {
    val dataMin = this.minOrNull() ?: 0.0
    val dataMax = this.maxOrNull() ?: 1.0
    return this.map { min + (it - dataMin) * (max - min) / (dataMax - dataMin) }.toDoubleArray()
}

/**
 * A raw, memory-resident representation of an image separated into color channels.
 *
 * This exists solely for pixel-level analysis (like hue calculation) where we need fast
 * access to raw integer values without the overhead or complexity of a full `BufferedImage`
 * or JavaCV `Frame`.
 */
data class DecodedImage(
    val width: Int,
    val height: Int,
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
)

/**
 * Converts the RGB image to a Hue map.
 *
 * We use Hue for "difference" calculations because it is robust against lighting changes.
 * A shadow passing over a scene changes Brightness but not Hue, so Hue-based difference
 * allows us to ignore "boring" lighting shifts and focus on "interesting" object movement.
 */
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
