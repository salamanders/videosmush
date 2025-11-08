package info.benjaminhill.videosmush

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
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
