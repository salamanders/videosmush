package info.benjaminhill.video2

import info.benjaminhill.utils.CountHits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform
import java.awt.image.BufferedImage
import kotlin.time.ExperimentalTime

@ExperimentalTime
internal fun Flow<DecodedImage>.mergeFrames(merges: List<Int>): Flow<BufferedImage> {
    require(merges.isNotEmpty())
    val whittleDown = merges.toMutableList()
    var currentWhittle = 0
    var combinedImage: DecodedImage? = null
    val imageRate = CountHits(3_000) { perSec: Int -> "Input running at $perSec images/sec" }

    return transform { inputImage->
        if (combinedImage == null) {
            combinedImage = inputImage
        } else {
            combinedImage!! += inputImage
        }
        imageRate.hit()

        currentWhittle--
        if (currentWhittle == 0) {
            emit(combinedImage!!.toAverage())
        }
        if (currentWhittle < 1 && whittleDown.isNotEmpty()) {
            currentWhittle = whittleDown.removeAt(0)
        }
    }.onCompletion {
        println("Discarded input frames (should be close to 0): $currentWhittle")
        println("Remaining unused script frames (should be close to 0): ${whittleDown.size}")
    }
}





