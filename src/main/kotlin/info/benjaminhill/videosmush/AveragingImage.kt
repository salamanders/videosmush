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
    fun toBufferedImage(): BufferedImage
    fun close()
}