package info.benjaminhill.utils

import kotlin.time.ExperimentalTime
import kotlin.time.seconds


/**
 * Spit out println log lines every hitFrequency calls to hit()
 */
class CountHits(
    private val hitFrequency: Int = 5_000,
    private val logLine: (perSec: Int) -> String = { perSec: Int -> "Running at $perSec/sec" }
) {

    private var startTimeNs = System.nanoTime()
    private var hitCount = 0

    @ExperimentalTime
    fun hit() {
        hitCount++
        if (hitCount >= hitFrequency) {
            val nowNs = System.nanoTime()
            val elapsedTimeNs = nowNs - startTimeNs
            val hitPerNs = hitCount.toDouble() / elapsedTimeNs
            println(logLine((1.seconds.inNanoseconds * hitPerNs).toInt()))
            hitCount = 0
            startTimeNs = nowNs
        }
    }
}