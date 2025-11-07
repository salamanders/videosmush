package info.benjaminhill.videosmush

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.Frame
import java.awt.image.BufferedImage

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

data class FrameWithPixelFormat(val frame: Frame, val pixelFormat: PixelFormat) {
    companion object {
        fun ofFfmpeg(frame: Frame, ffmpeg: Int): FrameWithPixelFormat =
            FrameWithPixelFormat(frame, PixelFormat.ofFfmpeg(ffmpeg))
    }
}

