package info.benjaminhill.video2

import info.benjaminhill.utils.hms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

const val OUTPUT_FPS = 60.0

@ExperimentalCoroutinesApi
@ExperimentalTime
fun main(): Unit = runBlocking(Dispatchers.Default) {
    val fileInput = File("D:\\Recordings\\terry_eclose.mkv").also { require(it.canRead()) }
    val fileOutput = File("timelapse_terry.mp4")

    val (sourceFps, images) = videoToDecodedImages(fileInput)

    val script = customMergeToScript(
        mapOf(
            "0".hms  to 10.seconds, // get clear
            "12:11:25".hms to 95.seconds, // pop
            "12:14:00".hms to 20.seconds, // expand
        ), sourceFps
    )

    images.buffer()
        .mergeFrames(script).buffer()
        .flowOn(Dispatchers.Default)
        .collectToFile(fileOutput, OUTPUT_FPS)
}

/**
 * Transform a script (starting at time X, convert the following segment to duration Y) to frame-merge-counts
 */
@ExperimentalTime
fun customMergeToScript(
    script: Map<Duration, Duration>,
    sourceFps: Double
): MutableList<Int> {
    println("Keyframes: ${script.keys.joinToString(",")}")

    val result = mutableListOf<Int>()
    script.entries.zipWithNext { a, b ->
        check(a.key<b.key) { "Keys must be sequential: ${a.key}, ${b.key}"}
        val sourceDurationFrames = (b.key - a.key).inSeconds * sourceFps
        val targetDurationFrames = a.value.inSeconds * OUTPUT_FPS
        val speedup: Double = sourceDurationFrames / targetDurationFrames
        check(speedup >= 1.0) { "Bad speedup: $speedup" }
        repeat(targetDurationFrames.toInt()) {
            result.add(speedup.toInt())
        }
    }
    println("Total output time: ${result.size / OUTPUT_FPS}sec")
    println(result)
    return result
}



