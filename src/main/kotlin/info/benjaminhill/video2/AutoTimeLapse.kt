package info.benjaminhill.video2

import info.benjaminhill.utils.averageDiff
import info.benjaminhill.utils.cachedOrCalculated
import info.benjaminhill.utils.zipWithNext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.nield.kotlinstatistics.standardDeviation
import java.io.File
import kotlin.time.ExperimentalTime

private const val OUTPUT_SECONDS = 30.0
private const val OUTPUT_FRAMES = OUTPUT_SECONDS * OUTPUT_FPS


@ExperimentalTime
fun autoTimelapse(): Unit = runBlocking(Dispatchers.Default) {
    val fileInput = File("input.mp4")
    val fileThumbs = File("thumbs.mp4")
    val fileOutput = File("timelapse.mp4")

    if (!fileThumbs.canRead()) {
        println("Creating thumbnail file ${fileThumbs.name}")
        error("Use `ffmpeg -y -i \"input.mp4\" -filter:v \"crop=in_w*.5:in_h*.5:in_w*.25:in_h*.25,scale=32:32\" -pix_fmt yuv420p thumbs.mp4`")
        //videoToFrames(fileInput, FILTER_THUMB32).collectToFile(fileThumbs, OUTPUT_FPS)
    }

    val diffs = cachedOrCalculated("diffs") {
        println("Finding pixel differences between thumbnails.")
        val (fps, images) = videoToDecodedImages(fileThumbs)
        images
            .map { it.toHue() }
            .zipWithNext { a, b -> a averageDiff b }
            .toList()
    }
    val betterFrameDiffs = manipulateFrameDiffs(diffs)
    val sourceFrameCounts = frameDiffsToSourceFrameCounts(betterFrameDiffs)

    println(
        "Output length: ${sourceFrameCounts.size / OUTPUT_FPS} sec, " +
                "max merge:${sourceFrameCounts.max()}, " +
                "min merge: ${sourceFrameCounts.min()}"
    )

    println("Writing frames to file.")
    val (fps, images) = videoToDecodedImages(fileInput)
    images.buffer()
        .mergeFrames(sourceFrameCounts).buffer()
        .flowOn(Dispatchers.IO)
        .collectToFile(fileOutput, OUTPUT_FPS)
}


/** This is where we play with numbers to make everything look good! */
private fun manipulateFrameDiffs(rawFrameDiffs: List<Double>): List<Double> {
    // Smooth first, because more fun when speedup is sustained.
    // Might be better to replace with min or max, or gaussian smoothing
    val smoothed = rawFrameDiffs
        .windowed(
            size = (2 * OUTPUT_FPS * rawFrameDiffs.size / OUTPUT_FRAMES).toInt(),
            partialWindows = true
        ) {
            // Smooth over approx 1 second of TARGET video
            it.average()
        }

    // cap >2 standard deviations
    val avg = smoothed.average()
    val std = smoothed.standardDeviation()
    val capped = smoothed.map { it.coerceIn(avg - (2 * std), avg + (2 * std)) }

    // Normalize from 0..1 (last step!)
    val minDiff = capped.min()!!
    val maxDiff = capped.max()!!

    // Normalized
    return capped.map {
        (it - minDiff) / (maxDiff - minDiff)
    }
}

/**
 * The underlying time-lapse: Sequentially merge frames until you exceed a diff threshold.
 * If instead it was "merge until X frames", would be a standard blurred together time-lapse.
 */
private fun frameDiffsToSourceFrameCounts(frameDiffs: List<Double>): List<Int> {
    val avgDiffPerOutputFrame = frameDiffs.sum() / OUTPUT_FRAMES
    val numberFramesMergedIntoOne = mutableListOf<Int>()
    var pendingFrameCount = 0
    var runningDiff = 0.0
    frameDiffs.forEach { diff ->
        runningDiff += diff
        pendingFrameCount++
        if (runningDiff >= avgDiffPerOutputFrame) {
            numberFramesMergedIntoOne.add(pendingFrameCount)
            pendingFrameCount = 0
            runningDiff = 0.0
        }
    }
    return numberFramesMergedIntoOne
}