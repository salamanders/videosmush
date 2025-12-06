package info.benjaminhill.videosmush

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flattenConcat
import kotlinx.coroutines.flow.flow
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize

/**
 * An alternative strategy for determining "visual interest" in a video.
 *
 * **Why this class exists:**
 * Pixel-by-pixel difference analysis (as done in [AdaptiveTimelapse.kt]) is computationally expensive
 * and sensitive to noise (wind blowing leaves).
 *
 * This class uses **video compression size** as a proxy for entropy/action.
 * - High action/movement -> hard to compress -> large file size.
 * - Low action/static scene -> easy to compress -> small file size.
 *
 * This method is often faster and more robust to global lighting changes than raw pixel diffs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompressionBasedSmusher(private val sources: List<Source>) {

    /**
     * Pass 1: Analyze the video to determine the "action" in different segments.
     * This is done by compressing small chunks of the video and using the resulting
     * file size as a proxy for the amount of "action" in that chunk.
     */
    private suspend fun analyze(): List<Double> {
        val actionScores = mutableListOf<Double>()
        val frameFlow = sources.map {
            it.path.toFrames(isThumbnail = true, rotFilter = null)
        }.reduce { acc, flow ->
            arrayOf(acc, flow).asFlow().flattenConcat()
        }

        var width = 0
        var height = 0

        frameFlow.chunked(CHUNK_SIZE).collect { frames ->
            if (frames.isEmpty()) return@collect

            if (width == 0) {
                width = frames.first().frame.imageWidth
                height = frames.first().frame.imageHeight
            }
            val tempFile = createTempFile(suffix = ".mkv")
            try {
                FFmpegFrameRecorder(tempFile.toFile(), width, height).use { recorder ->
                    recorder.videoCodecName = "libx264"
                    recorder.frameRate = 30.0
                    recorder.start()
                    frames.forEach { frame ->
                        recorder.record(frame.frame)
                    }
                }
                actionScores.add(tempFile.fileSize().toDouble())
            } finally {
                tempFile.deleteIfExists()
            }
        }
        return actionScores
    }

    /**
     * Pass 2: Smooth the action scores and generate a frame-averaging plan.
     */
    private fun generatePlan(actionScores: List<Double>): Map<Int, Int> {
        val smoothedScores = savitzkyGolaySmooth(actionScores.toDoubleArray())
        val mergePlan = enhanceVariabilityAndApplyConstraints(
            data = smoothedScores,
            variabilityFactor = 2.0, // This can be tuned for more or less dramatic speed changes.
            minMerge = 1,
            maxMerge = 100 // This can be tuned to set an upper limit on the speed-up.
        )

        val script = mutableMapOf<Int, Int>()
        var cumulativeInputFrames = 0
        var cumulativeOutputFrames = 0.0 // Use a double for more precision
        mergePlan.forEach { merge ->
            cumulativeInputFrames += CHUNK_SIZE
            cumulativeOutputFrames += CHUNK_SIZE.toDouble() / merge
            script[cumulativeInputFrames] = cumulativeOutputFrames.toInt()
        }
        return script
    }

    /**
     * The main entry point for the compression-based smusher.
     * This function will orchestrate the analysis and plan generation.
     */
    suspend fun smush(): Map<Int, Int> {
        val actionScores = analyze()
        return generatePlan(actionScores)
    }


    private companion object {
        private const val CHUNK_SIZE = 24
    }
}

/**
 * Chunks a Flow into a Flow of lists of a given size.
 * Useful for batch processing (like encoding 24 frames at a time) where a single frame isn't enough context.
 */
fun <T> Flow<T>.chunked(size: Int): Flow<List<T>> = flow {
    val buffer = mutableListOf<T>()
    collect {
        buffer.add(it)
        if (buffer.size == size) {
            emit(buffer.toList())
            buffer.clear()
        }
    }
    if (buffer.isNotEmpty()) {
        emit(buffer.toList())
    }
}
