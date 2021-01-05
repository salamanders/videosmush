package info.benjaminhill.video2

import info.benjaminhill.utils.hms
import info.benjaminhill.video2.DecodedImage.Companion.mergeFrames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

const val OUTPUT_FPS = 60.0

val INPUT_FILE = File("/Users/benhill/Documents/screensaver/IMG_8467.mov")
// "D:\\Recordings\\terry_eclose.mkv"

val OUTPUT_FILE = File(INPUT_FILE.parentFile.absolutePath, "scripted_lapse.mp4")

@ExperimentalCoroutinesApi
@ExperimentalTime
fun main(): Unit = runBlocking(Dispatchers.Default) {
    val fileInput = INPUT_FILE.also { require(it.canRead()) }

    val (sourceFps, images) = videoToDecodedImages(fileInput)

    val script = customMergeToScript(
        mapOf(
            "0".hms to 0.1.seconds,
            "1".hms to 0.5.seconds,
            //"0".hms  to 10.seconds, // get clear
            //"12:11:25".hms to 95.seconds, // pop
            //"12:14:00".hms to 20.seconds, // expand
        ), sourceFps
    )

    println("Script: ${script.joinToString(",")}")

    images.buffer()
        .mergeFrames(script)
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
    println("Keyframes: ${script.keys.joinToString(",")}")

    val result = mutableListOf<Int>()
    script.entries.zipWithNext { a, b ->
        check(a.key < b.key) { "Keys must be sequential: ${a.key}, ${b.key}" }
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



