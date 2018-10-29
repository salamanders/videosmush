private val conf = Conf()

fun main() {
    // Average diff of ~1
    println("Step 1: Get diffs between thumbnail frames")
    @Suppress("UNCHECKED_CAST")
    val uncalibratedDiffBetweenFrames = loadObj(conf.inputFileName) as? List<Double>
            ?: saveObj(getDiffs(conf.inputFileName, false), conf.inputFileName)

    val scaledFrameDiffs = getScaledFrameDiffs(uncalibratedDiffBetweenFrames)
    println("diffBetweenFramesAvg (should be 1):${scaledFrameDiffs.average()}, min:${scaledFrameDiffs.min()}, max:${scaledFrameDiffs.max()}")

    // Do voodoo to produce the frame merge instructions: Each list elt is "how many frames to collapse into one"
    val nicelyShapedMerges = getNicelyShapedMerges(scaledFrameDiffs)

    println("Step 2: Render merged frames.")
    render(nicelyShapedMerges)
}

fun render(reshapedMerges: List<Int>) {

    val source = KInputVideo(conf.inputFileName)
    println("Attempting to boil down ${conf.inputFileName} to ${conf.outputFileName} (${reshapedMerges.size / 30}sec).")
    val output = KOutputVideo(conf.outputFileName)

    sequenceToChunks(source.frames, reshapedMerges).forEachIndexed { outputFrame, framesToMerge ->
        require(framesToMerge.isNotEmpty())
        if ((outputFrame and (outputFrame - 1)) == 0) {
            println("Output frame $outputFrame is merging ${framesToMerge.size}")
        }
        output.add(ImageStacker().addAll(framesToMerge).combined)
    }

    output.close()
}

/**
 * Frame diffs, but scaled up from [0..1] until the average is 1.0
 * Also handles caching the time-consuming getDiffs function
 */
fun getScaledFrameDiffs(uncalibratedDiffBetweenFrames: List<Double>): List<Double> {
    println("Starting with ${uncalibratedDiffBetweenFrames.size} diffs between frames")
    // If we wanted to smooth or average the diffs, would do it here.
    // uncalibratedDiffBetweenFrames.windowed( size = smoothWindowSize, partialWindows = true ) { it.average() }

    // Scale to average 1, so we can reshape with an exponent later
    val uncalibratedDiffAvg = uncalibratedDiffBetweenFrames.average()
    return uncalibratedDiffBetweenFrames.map { it * 1 / uncalibratedDiffAvg }
}

/**
 * Merges that bring the total length down to sub conf.outputFrames length,
 * yet keep conf.numberSingleFrameSource frames unmerged
 */
fun getNicelyShapedMerges(scaledFrameDiffs: List<Double>): List<Int> {
    // Because it has been scaled up to an average diff value of 1,
    // we can raise it to a power to bring down the length while preserving the no-merge frames.

    val avgSourceToFinalFrameMerge = scaledFrameDiffs.size / conf.goalOutputFrames
    println("Attempting to reduce $avgSourceToFinalFrameMerge:1")
    // If you don't get it working by raising to the power of 10, give up!
    for (exp in 1.0..10.0 step 0.01) {
        val sqFrameDiffs = scaledFrameDiffs.map { Math.pow(it, exp) }
        val sufficientSinglesThreshold = sqFrameDiffs.sortedDescending()[conf.numberFrameWithSingleSource * avgSourceToFinalFrameMerge]
        val merges = frameDiffsToMergeCounts(sqFrameDiffs, sufficientSinglesThreshold)
        if (merges.size < conf.goalOutputFrames) {
            println("(sufficientSinglesThreshold:$sufficientSinglesThreshold, exp:$exp) = Output Seconds : ${merges.size / 30}, Single Source Frames: ${merges.count { it == 1 }}")
            println(merges.joinToString(","))
            return merges
        }
    }
    println("No good shape for merges, going with direct threshold:$avgSourceToFinalFrameMerge")
    return frameDiffsToMergeCounts(scaledFrameDiffs, avgSourceToFinalFrameMerge.toDouble())
}

/**
 * The underlying time-lapse: Sequentially merge frames until you exceed a diff threshold.
 * If the diff threshold was fixed, would be a standard blurred together time-lapse.
 */
fun frameDiffsToMergeCounts(frameDiffs: List<Double>, threshold: Double): List<Int> {
    val numberFramesMergedIntoOne = mutableListOf<Int>()
    var pendingFrameCount = 0
    var runningDiff = 0.0
    frameDiffs.forEach { diff ->
        runningDiff += diff
        pendingFrameCount++
        if (runningDiff >= threshold) {
            numberFramesMergedIntoOne.add(pendingFrameCount)
            pendingFrameCount = 0
            runningDiff = 0.0
        }
    }
    return numberFramesMergedIntoOne
}
