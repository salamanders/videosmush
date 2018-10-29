import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.image.BufferedImage

class ImageStacker : AutoCloseable {
    lateinit var combined: BufferedImage
    private lateinit var g2d: Graphics2D
    private var imageCount = 0

    fun addAll(images: List<BufferedImage>): ImageStacker {
        images.forEach { this.add(it) }
        return this
    }

    fun add(img: BufferedImage) {
        if (!::combined.isInitialized) {
            combined = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
            g2d = combined.createGraphics()
        }
        imageCount++
        g2d.composite = AlphaComposite.SrcOver.derive(1.0f / imageCount)
        g2d.drawImage(img, 0, 0, null)
    }

    override fun close() {
        if (::g2d.isInitialized) {
            g2d.dispose()
        }
    }
}