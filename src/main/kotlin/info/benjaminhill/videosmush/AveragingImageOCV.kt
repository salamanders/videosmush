package info.benjaminhill.videosmush

import org.bytedeco.javacv.Java2DFrameConverter
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.opencv.global.opencv_core.CV_32FC3
import org.bytedeco.opencv.global.opencv_core.CV_8UC3
import org.bytedeco.opencv.global.opencv_imgproc.accumulate
import org.bytedeco.opencv.opencv_core.Mat
import java.awt.image.BufferedImage

class AveragingImageOCV private constructor(
    override val width: Int,
    override val height: Int,
) : BaseAveragingImage(width, height) {

    private val accumulator: Mat = Mat.zeros(height, width, CV_32FC3).asMat()
    private val frameToBiConverter = Java2DFrameConverter()
    private val ocvConverter = OpenCVFrameConverter.ToMat()

    override suspend operator fun plusAssign(other: FrameWithPixelFormat) {
        val bi = frameToBiConverter.convert(other.frame)
        other.frame.close()

        val frameForOcv = frameToBiConverter.convert(bi)
        val inputMat = ocvConverter.convert(frameForOcv)
        frameForOcv.close()

        if (inputMat == null || inputMat.isNull) {
            // Don't process null/empty mats
            throw Exception("OCV conversion error")
        }
        val floatMat = Mat()
        inputMat.convertTo(floatMat, CV_32FC3)
        accumulate(floatMat, accumulator)
        numAdded++
        floatMat.close()
        inputMat.close()
    }

    override fun toBufferedImage(): BufferedImage {
        val ocvConverter = OpenCVFrameConverter.ToMat()
        val avgMat = Mat()
        accumulator.convertTo(avgMat, CV_8UC3, 1.0 / numAdded, 0.0)
        val frame = ocvConverter.convert(avgMat)
        val image = frameToBiConverter.convert(frame)
        ocvConverter.close()

        // Reset accumulator
        accumulator.put(Mat.zeros(height, width, CV_32FC3))
        numAdded = 0

        avgMat.close()
        frame.close()
        return image
    }

    override fun close() {
        accumulator.close()
        frameToBiConverter.close()
        ocvConverter.close()
    }

    companion object {
        fun blankOf(width: Int, height: Int): AveragingImage =
            AveragingImageOCV(width = width, height = height)
    }
}