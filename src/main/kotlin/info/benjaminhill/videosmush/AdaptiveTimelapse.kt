package info.benjaminhill.videosmush

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import io.github.oshai.kotlinlogging.KotlinLogging
import org.bytedeco.javacv.Frame
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.time.measureTime

private val LOG = KotlinLogging.logger {}

private const val OUTPUT_SECONDS = 30.0
private const val OUTPUT_FPS = 60.0
private const val OUTPUT_FRAMES = OUTPUT_SECONDS * OUTPUT_FPS

fun main() {
    val duration = measureTime {
        advancedAdaptiveTimelapse()
    }
    println("Advanced adaptive timelapse took $duration")
}

/**
 * Orchestrates a multi-pass adaptive timelapse generation.
 *
 * This function implements a sophisticated pipeline:
 * 1. **Thumbnail Generation**: Creates a small, low-res version of the input to speed up analysis.
 * 2. **Analysis**: Calculates frame-by-frame visual differences (using Hue) on the thumbnails.
 * 3. **Planning**: Smoothes the difference data and converts it into a "merge plan" (how many input frames -> 1 output frame).
 * 4. **Execution**: Reads the full-resolution input again and averages frames according to the plan.
 *
 * This separation of analysis (on thumbnails) and execution (on full frames) allows for complex
 * look-ahead algorithms that would be too slow if run on the full raw video stream in one pass.
 */
fun advancedAdaptiveTimelapse(): Unit = runBlocking(Dispatchers.Default) {
    val inputDir = File("input")
    val outputDir = File("output")
    outputDir.mkdirs()

    val inputFiles = inputDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sorted()
        ?: emptyList()
    if (inputFiles.isEmpty()) {
        LOG.warn { "No input files found in 'input' directory." }
        return@runBlocking
    }
    val fileInput = inputFiles.first()
    val fileThumbs = File(outputDir, "thumbs.mkv")
    val fileOutput = File(outputDir, "advanced_adaptive_timelapse.mkv")

    if (!fileThumbs.canRead()) {
        LOG.info { "Creating thumbnail file ${fileThumbs.name}" }
        val pb = ProcessBuilder(
            "ffmpeg",
            "-y",
            "-i",
            fileInput.absolutePath,
            "-filter:v",
            "crop=in_w*.5:in_h*.5:in_w*.25:in_h*.25,scale=32:32",
            "-pix_fmt",
            "yuv420p",
            fileThumbs.absolutePath
        )
        pb.redirectErrorStream(true)
        val process = pb.start()
        process.inputStream.bufferedReader().use {
            it.lines().forEach { line -> LOG.debug { line } }
        }
        process.waitFor()
    }


    LOG.info { "Finding pixel differences between thumbnails." }
    val images = fileThumbs.toPath().toFrames(isThumbnail = true, rotFilter = null).map { it.frame.toDecodedImage() }
    val diffs = images
        .zipWithNext { a, b -> a.toHue().averageDiff(b.toHue()) }
        .toList()
        .toDoubleArray()

    val smoothedDiffs = savitzkyGolaySmooth(diffs)
    val variabledDiffs = enhanceVariabilityAndApplyConstraints(smoothedDiffs, 2.0, 1, 100)
    val sourceFrameCounts = frameDiffsToSourceFrameCounts(variabledDiffs.map { it.toDouble() }.toDoubleArray())

    LOG.info {
        "Output length: ${sourceFrameCounts.size / OUTPUT_FPS} sec, " +
                "max merge:${sourceFrameCounts.maxOrNull()}, " +
                "min merge: ${sourceFrameCounts.minOrNull()}"
    }

    LOG.info { "Writing frames to file." }
    val imagesFull =
        fileInput.toPath().toFrames(isThumbnail = false, rotFilter = null).map { it.frame.toDecodedImage() }
    imagesFull.buffer()
        .mergeFrames(sourceFrameCounts.toList()).buffer()
        .flowOn(Dispatchers.IO)
        .collectToFile(fileOutput, OUTPUT_FPS)
}

/**
 * Converts a stream of "frame importance" scores into a concrete schedule of frame merges.
 *
 * It uses an accumulator bucket strategy: we keep accumulating "importance" until we reach
 * the threshold required to produce *one* output frame (`avgDiffPerOutputFrame`).
 *
 * This ensures the total "visual change" per output frame is roughly constant, causing the video
 * to speed up during boring parts and slow down during action.
 */
private fun frameDiffsToSourceFrameCounts(frameDiffs: DoubleArray): List<Int> {
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
    if (pendingFrameCount > 0) {
        numberFramesMergedIntoOne.add(pendingFrameCount)
    }
    return numberFramesMergedIntoOne
}

/**
 * Helper to get raw pixel data out of a JavaCV Frame.
 * Used during the analysis phase to convert obscure FFmpeg/JavaCV objects into
 * simple integer arrays that are easy to do math on.
 */
fun Frame.toDecodedImage(): DecodedImage {
    val converter = org.bytedeco.javacv.Java2DFrameConverter()
    val bufferedImage = converter.convert(this)
    val red = IntArray(this.imageWidth * this.imageHeight)
    val green = IntArray(this.imageWidth * this.imageHeight)
    val blue = IntArray(this.imageWidth * this.imageHeight)
    for (row in 0 until this.imageHeight) {
        for (col in 0 until this.imageWidth) {
            val index = row * this.imageWidth + col
            val rgb = bufferedImage.getRGB(col, row)
            red[index] = (rgb shr 16) and 0xFF
            green[index] = (rgb shr 8) and 0xFF
            blue[index] = rgb and 0xFF
        }
    }
    return DecodedImage(this.imageWidth, this.imageHeight, red, green, blue)
}

/**
 * Inverse operation of `toDecodedImage`.
 * Reconstructs a JavaCV-compatible frame from our raw pixel data so it can be passed
 * back into the standard `AveragingImage` pipeline.
 */
fun DecodedImage.toFrameWithPixelFormat(): FrameWithPixelFormat {
    val bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (row in 0 until height) {
        for (col in 0 until width) {
            val index = row * width + col
            val rgb = (red[index] shl 16) or (green[index] shl 8) or blue[index]
            bufferedImage.setRGB(col, row, rgb)
        }
    }
    val converter = org.bytedeco.javacv.Java2DFrameConverter()
    val frame = converter.convert(bufferedImage)
    return FrameWithPixelFormat.ofFfmpeg(frame, -1)
}

/**
 * Flow operator that pairs each element with the subsequent one.
 * Essential for calculating "diffs" between consecutive frames (t and t+1).
 */
fun <T> Flow<T>.zipWithNext(transform: (a: T, b: T) -> Double): Flow<Double> = flow {
    var prev: T? = null
    collect {
        if (prev != null) {
            emit(transform(prev!!, it))
        }
        prev = it
    }
}

/**
 * The core execution engine for the adaptive timelapse.
 *
 * It consumes the stream of input frames and aggregates them into averaged frames
 * based on the provided `sourceFrameCounts` schedule.
 *
 * @param sourceFrameCounts A list where index `i` is the number of input frames to merge for output frame `i`.
 */
fun Flow<DecodedImage>.mergeFrames(sourceFrameCounts: List<Int>): Flow<BufferedImage> = flow {
    val sourceFrameCountsIterator = sourceFrameCounts.iterator()
    var frameCountToMerge = sourceFrameCountsIterator.next()
    var mergedImage: AveragingImage? = null
    var frameCount = 0
    var totalFrames = 0
    collect {
        if (mergedImage == null) {
            mergedImage = AveragingImageRGB.blankOf(it.width, it.height)
        }
        mergedImage!! += it.toFrameWithPixelFormat()
        frameCount++
        totalFrames++
        if (totalFrames % 5000 == 0) {
            LOG.info { "Processed $totalFrames input frames." }
        }
        if (frameCount >= frameCountToMerge) {
            emit(mergedImage!!.toBufferedImage())
            mergedImage = null
            frameCount = 0
            if (sourceFrameCountsIterator.hasNext()) {
                frameCountToMerge = sourceFrameCountsIterator.next()
            }
        }
    }
}

/**
 * Calculates the average pixel-by-pixel difference between two hue arrays.
 * Handles the wrap-around nature of hue (e.g. difference between 359 and 1 is 2, not 358).
 */
fun IntArray.averageDiff(b: IntArray): Double {
    require(this.size == b.size) { "Arrays must be same size" }
    var sum = 0.0
    val size = this.size
    for (i in 0 until size) {
        val diff = abs(this[i] - b[i])
        sum += if (diff > 180) 360 - diff else diff
    }
    return sum / size
}
