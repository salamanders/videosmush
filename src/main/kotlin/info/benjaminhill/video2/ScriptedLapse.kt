package info.benjaminhill.video2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.time.*

const val OUTPUT_FPS = 60.0

@ExperimentalTime
fun main(): Unit = runBlocking(Dispatchers.Default) {
    val fileInput = File("/Users/benhill/Downloads/molt/molt_crop.mp4")
    val fileOutput = File("timelapse_molt.mp4")

    val (sourceFps, images) = videoToBufferedImages(fileInput)

    val script = customMergeToScript(mapOf(
            0.seconds to 4.seconds, // straighten
            1.hours + 17.minutes + 20.seconds to 15.seconds, //split
            1.hours + 21.minutes + 52.seconds to 5.seconds, // dance
            1.hours + 25.minutes + 0.seconds to 10.seconds, // contract
            2.hours + 17.minutes to 1.seconds, // end
            2.hours + 17.minutes + 60.seconds to 1.seconds // pause at end
    ), sourceFps)

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
    val result = mutableListOf<Int>()
    script.entries.zipWithNext { a, b ->
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



