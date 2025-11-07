package info.benjaminhill.videosmush

import org.bytedeco.javacv.FrameConverter
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage

abstract class BaseAveragingImage(
    override val width: Int,
    override val height: Int,
) : AveragingImage {
    override var numAdded: Int = 0

    override fun close() {
        // empty by default
    }

    protected val converter = object : ThreadLocal<FrameConverter<BufferedImage>>() {
        override fun initialValue() = Java2DFrameConverter()
    }
}