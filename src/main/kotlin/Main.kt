import info.benjaminhill.video.ImageAverage
import info.benjaminhill.video.VideoPixelDiff
import info.benjaminhill.video.VideoReader
import info.benjaminhill.video.VideoWriter
import info.benjaminhill.workergraph.WorkerToList
import mu.KotlinLogging
import org.nield.kotlinstatistics.standardDeviation

private val logger = KotlinLogging.logger {}
private val conf = Conf()


fun main() {
    logger.info { "Step 1: Get (possibly from cache) the diffs between thumbnail frames" }
    val diffs = cachedOrCalculated(conf.inputFileName) {

        val file2thumbs = VideoReader(conf.inputFileName, VideoPixelDiff.FILTER)
        val thumbs2diffs = VideoPixelDiff(file2thumbs)
        val diffs2list = WorkerToList(thumbs2diffs)
        diffs2list.takeOne()
    }

    logger.info { "Step 2: Figure out the most aesthetic way to combine the images" }
    val betterFrameDiffs = manipulateFrameDiffs(diffs)
    val sourceFrameCounts = frameDiffsToSourceFrameCounts(betterFrameDiffs)
    logger.info {
        "Output length: ${sourceFrameCounts.size / conf.outputFramesPerSecond} sec, " +
                "max merge:${sourceFrameCounts.max()}, " +
                "min merge: ${sourceFrameCounts.min()}"
    }

    logger.info { sourceFrameCounts.joinToString(",") }

    logger.info { "Step 3: Render merged frames." }
    val file2images = VideoReader(conf.inputFileName)
    val averagedVideo = ImageAverage(file2images, sourceFrameCounts)
    val outputWriter = VideoWriter(averagedVideo, conf.outputFileName, conf.outputFramesPerSecond.toDouble())
    logger.info { outputWriter.takeOne() }
}


/** This is where we play with numbers to make everything look good! */
fun manipulateFrameDiffs(rawFrameDiffs: List<Double>): List<Double> {
    // Smooth first, because more fun when speedup is sustained.
    // Might be better to replace with min or max, or gaussian smoothing
    val smoothed = rawFrameDiffs
            .windowed(size = 2 * conf.outputFramesPerSecond * rawFrameDiffs.size / conf.goalOutputFrames,
                    partialWindows = true
            ) {
                // Smooth over approx 1 second of TARGET video
                it.average()
            }

    // cap >2 standard deviations
    val avg = smoothed.average()
    val std = smoothed.standardDeviation()
    val capped = smoothed.map {
        when {
            it < avg - (2 * std) -> avg - (2 * std)
            it > avg + (2 * std) -> avg + (2 * std)
            else -> it
        }
    }

    // Normalize from 0..1 (last step!)
    val minDiff = capped.min()!!
    val maxDiff = capped.max()!!
    val normalized = capped.map {
        (it - minDiff) / (maxDiff - minDiff)
    }

    return normalized
}

/**
 * The underlying time-lapse: Sequentially merge frames until you exceed a diff threshold.
 * If instead it was "merge until X frames", would be a standard blurred together time-lapse.
 */
fun frameDiffsToSourceFrameCounts(frameDiffs: List<Double>): List<Int> {
    val avgDiffPerOutputFrame = frameDiffs.sum() / conf.goalOutputFrames
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
