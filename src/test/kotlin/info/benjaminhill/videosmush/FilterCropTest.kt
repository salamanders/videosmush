package info.benjaminhill.videosmush

import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import org.bytedeco.ffmpeg.global.avutil
import java.io.File

class FilterCropTest {

    @Test
    fun testFilterCropString() {
        // Default behavior (force even)
        // 10+10 = 20. Even.
        val result = filterCrop(10, 10, 10, 10)
        assertTrue(result.contains("crop=floor((in_w-20)/2)*2:floor((in_h-20)/2)*2:10:10"))

        // Disable force even
        val resultRaw = filterCrop(10, 10, 10, 10, forceEvenDimensions = false)
        assertEquals("crop=in_w-20:in_h-20:10:10", resultRaw)
    }

    @Test
    fun testFilterCropForceEvenLogic() {
         // 10 left + 11 right = 21 total crop.
         // If input width is 100, 100 - 21 = 79.
         // floor(79/2)*2 = 78.
         // So resulting width should be even.

         val cropFilter = filterCrop(10, 11, 10, 10, forceEvenDimensions = true)
         // We can't easily assert the string exactly because it depends on input vars in FFMPEG,
         // but we can check the string format.
         assertTrue(cropFilter.contains("floor((in_w-21)/2)*2"))
    }

    @Test
    fun testRecorderWorksWithFixedFilter() {
        val w = 100
        val h = 100
        val fl = 10
        val fr = 11 // Result width 79 if not fixed, 78 if fixed.
        val ft = 10
        val fb = 10

        val bi = BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR)
        val g = bi.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, w, h)
        g.dispose()

        val converter = Java2DFrameConverter()
        val frame = converter.convert(bi)

        val cropFilter = filterCrop(fl, fr, ft, fb, forceEvenDimensions = true)
        // Filter outputting BGR24
        val filter = FFmpegFrameFilter(cropFilter, w, h)
        filter.pixelFormat = avutil.AV_PIX_FMT_BGR24
        filter.start()

        filter.push(frame)
        val processedFrame = filter.pull()

        assertNotNull(processedFrame)
        // Should be even width now.
        // 100 - 21 = 79. floor(79/2)*2 = 78.
        assertEquals(78, processedFrame.imageWidth)
        assertEquals(80, processedFrame.imageHeight) // 100 - 20 = 80.

        // Now try to record this frame to YUV420P. Should work fine.
        val outputFile = File.createTempFile("test_fixed", ".mp4")
        val recorder = FFmpegFrameRecorder(outputFile, processedFrame.imageWidth, processedFrame.imageHeight, 0)
        recorder.pixelFormat = avutil.AV_PIX_FMT_YUV420P
        recorder.start()

        try {
            recorder.record(processedFrame)
        } catch (e: Exception) {
            throw e
        } finally {
            recorder.stop()
            recorder.release()
            outputFile.delete()
            filter.stop()
            filter.release()
        }
    }
}
