package info.benjaminhill.utils


/**
 * Spit out println log lines every hitFrequency calls to hit()
 */
class CountHits(
        private val hitFrequency: Int = 5_000,
        private val logLine: (perSec: Int) -> String = { perSec: Int -> "Running at $perSec/sec" }
) {

    private var startTimeMs = System.currentTimeMillis()
    private var hitCount = 0

    fun hit() {
        hitCount++
        if (hitCount >= hitFrequency) {
            val nowMs = System.currentTimeMillis()
            val elapsedTimeMs = nowMs - startTimeMs
            val hitPerMs = hitCount.toDouble() / elapsedTimeMs
            println(logLine((1_000 * hitPerMs).toInt()))
            hitCount = 0
            startTimeMs = nowMs
        }
    }
}