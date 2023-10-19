package info.benjaminhill.videosmush

import kotlinx.coroutines.flow.transform
import java.io.File
import java.nio.file.Path

val outputAverageImage = File("average.mp4")
lateinit var averagingImage: AveragingImage

suspend fun main() {
    outputAverageImage.delete()
    val frames = Path.of(".", "input").toBufferedImages(true)
    frames.transform { bi ->
            if (!::averagingImage.isInitialized) {
                averagingImage = AveragingImage.blankOf(bi.width, bi.height)
                println("Averaging into an image: ${averagingImage.width} x ${averagingImage.height}")
            }
            averagingImage += bi
            if (averagingImage.numAdded > 10) {
                emit(averagingImage.toBufferedImage())
            }
        }.collectToFile(outputAverageImage)
}

