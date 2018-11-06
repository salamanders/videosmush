package info.benjaminhill.video

import IntBackedImage
import info.benjaminhill.workergraph.Worker
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.util.*

/** Merge up some frames! */
class ImageAverage(upstreamWorker: Worker<*, BufferedImage>, sourceFrameCounts: List<Int>) : Worker<BufferedImage, BufferedImage>(upstreamWorker) {
    private val whittleDown = sourceFrameCounts.toMutableList()
    private fun whittle(): Boolean {
        if (whittleDown.isEmpty()) {
            return false
        }
        whittleDown[0]--
        if (whittleDown[0] < 1) {
            whittleDown.removeAt(0)
            return true
        }
        return false
    }

    private lateinit var combinedImage: IntBackedImage

    init {
        require(sourceFrameCounts.isNotEmpty())
    }

    override fun process(input: BufferedImage, flags: EnumSet<Flag>) {
        if (!this::combinedImage.isInitialized) {
            combinedImage = IntBackedImage(input.width, input.height)
        }
        combinedImage.add(input)

        if (whittle() || flags.contains(Flag.LAST)) {
            outputs.put(Pair(combinedImage.toAverage(), flags))
        }
    }

    companion object {
        fun create48BitRGBImage(width: Int, height: Int): BufferedImage {
            val precision = 16
            val colorSpace = ColorSpace.getInstance(ColorSpace.CS_sRGB)!!
            val colorModel = ComponentColorModel(colorSpace, intArrayOf(precision, precision, precision, precision), true, false, Transparency.TRANSLUCENT, DataBuffer.TYPE_USHORT)
            return BufferedImage(colorModel, colorModel.createCompatibleWritableRaster(width, height), colorModel.isAlphaPremultiplied, null)
        }
    }
}



