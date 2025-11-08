package info.benjaminhill.videosmush

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.bytedeco.javacv.Frame
import org.nield.kotlinstatistics.standardDeviation
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.measureTime

private val LOG = KotlinLogging.logger {}

private const val OUTPUT_SECONDS = 30.0
private const val OUTPUT_FPS = 30.0
private const val OUTPUT_FRAMES = OUTPUT_SECONDS * OUTPUT_FPS

fun main() {
    val duration = measureTime {
        adaptiveTimelapse()
    }
    println("Adaptive timelapse took $duration")
}

fun adaptiveTimelapse(): Unit = runBlocking(Dispatchers.Default) {
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
    val fileOutput = File(outputDir, "adaptive_timelapse.mkv")

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


    val betterFrameDiffs = manipulateFrameDiffs(diffs)
    val sourceFrameCounts = frameDiffsToSourceFrameCounts(betterFrameDiffs)

    LOG.info {
        "Output length: ${sourceFrameCounts.size / OUTPUT_FPS} sec, " +
                "max merge:${sourceFrameCounts.maxOrNull()}, " +
                "min merge: ${sourceFrameCounts.minOrNull()}"
    }

    LOG.info { "Writing frames to file." }
    val imagesFull = fileInput.toPath().toFrames(isThumbnail = false, rotFilter = null).map { it.frame.toDecodedImage() }
    imagesFull.buffer()
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
    val minDiff = capped.minOrNull()!!
    val maxDiff = capped.maxOrNull()!!

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
    if (pendingFrameCount > 0) {
        numberFramesMergedIntoOne.add(pendingFrameCount)
    }
    return numberFramesMergedIntoOne
}

private fun DecodedImage.toHue(): IntArray = IntArray(red.size) { index ->
    getHue(red[index], green[index], blue[index])
}

private fun getHue(red: Int, green: Int, blue: Int): Int {
    val min = min(min(red, green), blue)
    val max = max(max(red, green), blue)
    if (min == max) {
        return 0
    }
    var hue = when (max) {
        red -> (green - blue).toDouble() / (max - min)
        green -> 2 + (blue - red).toDouble() / (max - min)
        else -> 4 + (red - green).toDouble() / (max - min)
    }
    hue *= 60
    if (hue < 0) hue += 360
    return hue.toInt()
}

fun IntArray.averageDiff(b: IntArray): Double {
    var sum = 0.0
    for (i in this.indices) {
        sum += min(
            abs(this[i] - b[i]).toDouble(),
            360 - abs(this[i] - b[i]).toDouble()
        )
    }
    return sum / this.size
}

data class DecodedImage(
    val width: Int,
    val height: Int,
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
)

fun <T> Flow<T>.zipWithNext(transform: (a: T, b: T) -> Double): Flow<Double> = flow {
    var prev: T? = null
    collect {
        if (prev != null) {
            emit(transform(prev!!, it))
        }
        prev = it
    }
}

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

fun <T> List<T>.windowed(size: Int, partialWindows: Boolean, transform: (List<T>) -> Double): List<Double> {
    val result = mutableListOf<Double>()
    for (i in 0 until this.size) {
        val window = this.subList(max(0, i - size / 2), min(this.size, i + size / 2))
        if (!partialWindows && window.size < size) {
            continue
        }
        result.add(transform(window))
    }
    return result
}

/**
 * This function can be found in `scratch.txt`
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
