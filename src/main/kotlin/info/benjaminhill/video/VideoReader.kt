package info.benjaminhill.video

import deepCopy
import info.benjaminhill.workergraph.Worker
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameConverter
import org.bytedeco.javacv.Java2DFrameConverter
import toPercent
import java.awt.image.BufferedImage
import java.util.*

/** No need for ThreadLocal handling or checking isRunning(), because creating everything per input-file is fine */
class VideoReader(fileName: String, private val filterStr: String? = null) : Worker<String, BufferedImage>(fileName) {

    override fun process(input: String, flags: EnumSet<Flag>) {
        // Ok to have one per call, because lots per process.
        val converter: FrameConverter<BufferedImage> = Java2DFrameConverter()
        var frameNumber = 0
        val grabber = FFmpegFrameGrabber(input).apply {
            logger.info { "info.benjaminhill.video.VideoReader is starting read of $input" }
            start()
        }

        val optionalFilter = filterStr?.let {
            val filter = FFmpegFrameFilter(filterStr, grabber.imageWidth, grabber.imageHeight)
            filter.pixelFormat = grabber.pixelFormat
            filter.start()
            filter
        }

        // Tricky because we don't know if the next frame will be our last.  Like life.
        var previousBI: BufferedImage? = null
        while (true) {
            val nextBI: BufferedImage? = grabber.grabImage()?.let { nextFrame ->
                val filteredFrame = optionalFilter?.let {
                    it.push(nextFrame)
                    it.pull()
                } ?: nextFrame
                converter.convert(filteredFrame).deepCopy()
            }

            if (previousBI != null) {
                outputs.put(Pair(previousBI, if (nextBI == null) EnumSet.of(Flag.LAST) else EnumSet.noneOf(Flag::class.java)))
            }
            previousBI = nextBI
            if ((frameNumber and (frameNumber - 1)) == 0) {
                printStatus("Read frame $frameNumber (${(frameNumber.toDouble() / grabber.lengthInVideoFrames).toPercent()}%)")
            }
            frameNumber++
            if (previousBI == null && nextBI == null) {
                logger.info { "Finished reading video after $frameNumber frames." }
                break
            }
        }

        optionalFilter?.stop()
        grabber.stop()
        grabber.close()
    }
}


