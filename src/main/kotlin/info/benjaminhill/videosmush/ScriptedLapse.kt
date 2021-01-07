package info.benjaminhill.videosmush

import info.benjaminhill.utils.hms
import info.benjaminhill.videosmush.DecodedImage.Companion.mergeFrames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.Integer.min
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

const val OUTPUT_FPS = 30.0

val INPUT_FILE = File("D:\\Recordings\\terry_eclose.mkv")
val OUTPUT_FILE = File(INPUT_FILE.parentFile.absolutePath, "scripted_lapse.mp4")

@ExperimentalCoroutinesApi
@ExperimentalTime
fun main(): Unit = runBlocking(Dispatchers.Default) {
    val fileInput = INPUT_FILE.also { require(it.canRead()) }

    val (sourceFps, images) = videoToDecodedImages(fileInput, "crop=in_w:640:0:0")

    // key to next-key compressed to value seconds
    val script = customMergeToScript(
        mapOf(
            "0".hms to 10.seconds, // get clear
            "12:11:20".hms to 40.seconds, // pop
            "12:14:00".hms to 10.seconds, // expand
            "14:42:00".hms to 0.seconds, // end
        ), sourceFps
    )

    logger.info {"Script: ${script.joinToString(",")}" }

    images.buffer()
        .mergeFrames(script).buffer()
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
        val sourceDurationFrames = (b.key - a.key).inSeconds * sourceFps
        val targetDurationFrames = a.value.inSeconds * OUTPUT_FPS
        val maxFramesCombined = (sourceDurationFrames / targetDurationFrames).roundToInt().coerceAtLeast(1)
        val endFrameTarget = (b.key.inSeconds * sourceFps).toInt()

        while(currentSourceFrame<endFrameTarget) {
            val stepSize = min(endFrameTarget-currentSourceFrame, maxFramesCombined)
            check(stepSize>0)
            result.add(stepSize)
            currentSourceFrame+=stepSize
        }
    }
    logger.info { "Total output time: ${result.size / OUTPUT_FPS}sec" }
    return result
}



