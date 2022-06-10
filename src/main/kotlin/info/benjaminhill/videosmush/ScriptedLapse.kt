package info.benjaminhill.videosmush


import info.benjaminhill.utils.hms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.Integer.min
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime

const val OUTPUT_FPS = 60.0

val INPUT_FILE = File("D:\\RPAN\\Meg\\meg_raw.mp4")
val OUTPUT_FILE = File("D:\\RPAN\\Meg\\meg_speed.mp4")

val keyframes: Map<Duration, Duration> = mapOf(
    "0".hms to 4.seconds, // egg
    "14:32:25".hms to 8.seconds, // hatch
    "14:42:00".hms to 8.seconds, // growing
    "30:41:29".hms to 2.seconds, // button (bad)
    "51:26:29".hms to 8.seconds, // straighten
    "61:15:02".hms to 15.seconds,  // split
    "61:19:35".hms to 5.seconds, // dry
    "63:57:28".hms to 8.seconds, // rest
    "153:46:28".hms to 5.seconds,// clear
    "181:22:00".hms to 15.seconds, // eclose
    "181:24:15".hms to 8.seconds, // inflate
    "182:53:00".hms to 0.seconds
)


@ExperimentalCoroutinesApi
@ExperimentalTime
fun main(): Unit = runBlocking(Dispatchers.Default) {
    val fileInput = INPUT_FILE.also { require(it.canRead()) }

    val (sourceFps, images) = videoToDecodedImages(fileInput)

    // key to next-key compressed to value seconds
    val script = customMergeToScript(keyframes, sourceFps)

    LOG.info { "Script: ${script.joinToString(",")}" }

    images.buffer(capacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)
        .mergeFrames(script).buffer(capacity = 128, onBufferOverflow = BufferOverflow.SUSPEND)
        .flowOn(Dispatchers.Default)
        .collectToFile(OUTPUT_FILE, OUTPUT_FPS)
}

/**
 * Transform a script (starting at time X, convert the following segment to duration Y) to frame-merge-counts
 */
@ExperimentalTime
fun customMergeToScript(
    script: Map<Duration, Duration>,
    sourceFps: Double
): MutableList<Int> {
    val result = mutableListOf<Int>()
    var currentSourceFrame = 0

    script.entries.zipWithNext { a, b ->
        // For each span of time (a.key to b.key), smush into the value of time.
        check(a.key < b.key) { "Keys must be sequential: ${a.key}, ${b.key}" }
        val sourceDurationFrames = (b.key - a.key).toDouble(DurationUnit.SECONDS) * sourceFps
        val targetDurationFrames = a.value.toDouble(DurationUnit.SECONDS) * OUTPUT_FPS
        val maxFramesCombined = (sourceDurationFrames / targetDurationFrames).roundToInt().coerceAtLeast(1)
        val endFrameTarget = (b.key.toDouble(DurationUnit.SECONDS) * sourceFps).toInt()

        while (currentSourceFrame < endFrameTarget) {
            val stepSize = min(endFrameTarget - currentSourceFrame, maxFramesCombined)
            check(stepSize > 0)
            result.add(stepSize)
            currentSourceFrame += stepSize
        }
    }
    LOG.info { "Total output time: ${result.size / OUTPUT_FPS}sec" }
    return result
}



