package info.benjaminhill.videosmush

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.Frame
import java.awt.image.BufferedImage

/**
 * Maps the chaotic world of pixel formats between libraries.
 *
 * **Why this class exists:**
 * FFmpeg (via JavaCV) uses one set of constants (e.g., `AV_PIX_FMT_ARGB`) for pixel formats,
 * while Java's AWT uses another (e.g., `BufferedImage.TYPE_INT_ARGB`).
 * Mismatches here cause weird color shifts (blue people!) or hard crashes.
 *
 * This enum acts as the Rosetta Stone to ensure we always use matching pairs.
 */
enum class PixelFormat(val ffmpeg: Int, val bufferedImage: Int) {
    ARGB(avutil.AV_PIX_FMT_ARGB, BufferedImage.TYPE_INT_ARGB),
    THREE_BYTE_BGR(avutil.AV_PIX_FMT_BGR24, BufferedImage.TYPE_3BYTE_BGR),
    FOUR_BYTE_ABGR(avutil.AV_PIX_FMT_ABGR, BufferedImage.TYPE_4BYTE_ABGR),
    INT_RGB(avutil.AV_PIX_FMT_0RGB, BufferedImage.TYPE_INT_RGB),
    INT_BGR(avutil.AV_PIX_FMT_0BGR, BufferedImage.TYPE_INT_BGR);

    companion object {
        fun ofFfmpeg(avutilAvPixFmt: Int): PixelFormat = PixelFormat.entries.first { it.ffmpeg == avutilAvPixFmt }
        fun ofBufferedImage(biType: Int): PixelFormat = PixelFormat.entries.first { it.bufferedImage == biType }
    }
}

/**
 * A strongly-typed wrapper for a [Frame] that carries its [PixelFormat].
 *
 * **Why this class exists:**
 * A raw `Frame` object doesn't always strictly enforce or communicate its pixel format in a way
 * that's easy to check at runtime without digging into FFmpeg internals.
 * Passing this wrapper around prevents "guessing" the format later in the pipeline.
 */
data class FrameWithPixelFormat(val frame: Frame, val pixelFormat: PixelFormat) {
    companion object {
        fun ofFfmpeg(frame: Frame, ffmpeg: Int): FrameWithPixelFormat =
            FrameWithPixelFormat(frame, PixelFormat.ofFfmpeg(ffmpeg))
    }
}
