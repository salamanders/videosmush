package info.benjaminhill.videosmush

import java.awt.image.BufferedImage

/**
 * Abstraction for the core "smushing" operation: averaging many frames into one.
 *
 * We defined this interface to decouple the *logic* of averaging (accumulate pixels -> divide)
 * from the *implementation details* (CPU vs GPU vs OpenCV).
 *
 * This allows us to swap in a GPU-accelerated backend or a memory-optimized one
 * without touching the main application loop.
 */
interface AveragingImage {
    val width: Int
    val height: Int
    var numAdded: Int
    suspend operator fun plusAssign(other: FrameWithPixelFormat)
    fun toBufferedImage(): BufferedImage
    fun close()
}