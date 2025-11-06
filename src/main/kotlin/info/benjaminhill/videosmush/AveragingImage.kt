package info.benjaminhill.videosmush

import java.awt.image.BufferedImage

/**
 * Various techniques for averaging together a **lot** of frames without binning
 */
interface AveragingImage {
    val width: Int
    val height: Int
    var numAdded: Int
    suspend operator fun plusAssign(other: FrameWithPixelFormat)
    suspend operator fun plusAssign(other: BufferedImage)
    fun toBufferedImage(): BufferedImage
    fun close()
}


abstract class BaseAveragingImage(
    override val width: Int,
    override val height: Int,
) : AveragingImage {
    override var numAdded: Int = 0

    override fun close() {
        // empty by default
    }
}