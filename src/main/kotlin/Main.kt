import com.natpryce.konfig.ConfigurationMap
import com.natpryce.konfig.ConfigurationProperties.Companion.systemProperties
import com.natpryce.konfig.EnvironmentVariables
import com.natpryce.konfig.*
import com.natpryce.konfig.stringType
import org.bytedeco.javacpp.avutil
import org.bytedeco.javacpp.avutil.AV_PIX_FMT_ARGB
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameConverter
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage


private val conf = Conf()
private val converter: FrameConverter<BufferedImage> = Java2DFrameConverter()

fun main() {
    avutil.av_log_set_level(avutil.AV_LOG_QUIET) // ffmpeg gets loud per frame otherwise
    val scaledFrameDiffs = getScaledFrameDiffs()
    println("diffBetweenFramesAvg (should be 1):${scaledFrameDiffs.average()}, min:${scaledFrameDiffs.min()}, max:${scaledFrameDiffs.max()}")
    val nicelyShapedMerges = getNicelyShapedMerges(scaledFrameDiffs)
    render(nicelyShapedMerges)

}

fun render(reshapedMerges: List<Int>) {

    val source = KVideo(conf.inputFileName)

    val ffr = FFmpegFrameRecorder(conf.outputFileName, source.width, source.height, 0)
    ffr.frameRate = 30.0
    ffr.videoBitrate = 0 // max
    ffr.videoQuality = 0.0 // max?
    ffr.start()

    sequence<List<Frame>> {
        val consumableMergeList = mutableListOf<Int>()
        consumableMergeList.addAll(reshapedMerges)
        val buffer = mutableListOf<Frame>()
        for(element in source.frames) {
            buffer += element
            consumableMergeList[0]--
            if(consumableMergeList[0] == 0) {
                yield(buffer)
                buffer.clear()
                consumableMergeList.removeAt(0)
            }
        }
        if (buffer.isNotEmpty()) yield(buffer)
    }.constrainOnce().forEach { frames->
        val imageStacker = ImageStacker()
        frames.forEach {
            imageStacker.add(converter.convert(it))
        }
        ffr.record(converter.convert(imageStacker.getStack()), AV_PIX_FMT_ARGB)
    }
    ffr.stop()
}

fun getScaledFrameDiffs(): List<Double> {
    // Takes a long time, so cache the results for dev. all in 0..1
    @Suppress("UNCHECKED_CAST")
    val uncalibratedDiffBetweenFrames = loadObj(conf.inputFileName) as? List<Double> ?: saveObj(getDiffs(conf.inputFileName), conf.inputFileName)
    println("Starting with ${uncalibratedDiffBetweenFrames.size} diffs between frames")
    // If we wanted to smooth or average the diffs, would do it here.
    // uncalibratedDiffBetweenFrames.windowed( size = smoothWindowSize, partialWindows = true ) { it.average() }

    // Scale to average 1, so we can reshape with an exponent later
    val uncalibratedDiffAvg = uncalibratedDiffBetweenFrames.average()
    return uncalibratedDiffBetweenFrames.map { it * 1 / uncalibratedDiffAvg }
}

/** Merges that bring the total length down to sub conf.outputFrames length, yet keep conf.numberSingleFrameSource frames unmerged */
fun getNicelyShapedMerges(scaledFrameDiffs: List<Double>): List<Int> {
    // Because it has been scaled up to an average diff value of 1,
    // we can raise it to a power to bring down the length while preserving the no-merge frames.

    val avgSourceToFinalFrameMerge = scaledFrameDiffs.size/conf.goalOutputFrames
    println("Attempting to reduce $avgSourceToFinalFrameMerge:1")
    for (exp in 1.0..10.0 step 0.01) {
        val sqFrameDiffs = scaledFrameDiffs.map { Math.pow(it, exp) }
        val powThreshold = sqFrameDiffs.sortedDescending()[conf.numberFrameWithSingleSource * avgSourceToFinalFrameMerge]
        val merges = frameDiffsToMergeCounts(sqFrameDiffs, powThreshold)
        if (merges.size < conf.goalOutputFrames) {
            println("powThreshold:$powThreshold, exp:$exp = Length (s): ${merges.size / 30}, Single Source Frames: ${merges.count { it == 1 }}")
            println(merges.joinToString(","))
            return merges
        }
    }
    error("No good shape for merges")
}

/** The underlying speedup: Sequentially merge frames until you exceed a diff threshold */
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


class Conf {
    private val inputFile by stringType
    private val outputFrames by intType
    private val sfDest by intType
    private val outputFile by stringType

    private val config = systemProperties() overriding
            EnvironmentVariables() overriding
            ConfigurationMap(
                    "inputFile" to "input.mp4",
                    "outputFile" to "output.mp4",
                    "outputFrames" to "1800",
                    "sfDest" to "30"
            )

    val inputFileName: String
        get() = config[inputFile]

    val outputFileName: String
        get() = config[outputFile]

    val goalOutputFrames: Int
        get() = config[outputFrames]

    val numberFrameWithSingleSource: Int
        get() = config[sfDest]
}