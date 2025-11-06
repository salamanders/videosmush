package info.benjaminhill.videosmush

import org.bytedeco.javacv.Frame
import java.awt.image.BufferedImage

interface AveragingImage {
    val width: Int
    val height: Int
    val numAdded: Int
    operator fun plusAssign(other: Frame)
    operator fun plusAssign(other: BufferedImage)
    fun toBufferedImage(): BufferedImage
}
