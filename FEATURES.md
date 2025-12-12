# Possible Features

This document contains a list of possible features and code examples that were extracted from the `old` directory.

## Direct Frame Data Access for Image Averaging

This implementation of `AveragingImage` directly accesses FFMPEG frame data, which could be more efficient than converting to `BufferedImage`.

```kotlin
import info.benjaminhill.videosmush.AveragingImage
import info.benjaminhill.videosmush.BaseAveragingImage
import info.benjaminhill.videosmush.FrameWithPixelFormat
import org.bytedeco.ffmpeg.global.avutil.* // ktlint-disable no-wildcard-imports
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.IntBuffer

class AveragingImageFrames
private constructor(
    override val width: Int,
    override val height: Int,
    private val sums: IntArray = IntArray(width * height * 3),
) : BaseAveragingImage(width, height) {

    override suspend operator fun plusAssign(other: FrameWithPixelFormat) {
        numAdded++
        require(numAdded * 255 < Int.MAX_VALUE) { "Possible overflow in DecodedImage after $numAdded adds." }
        when (other.pixelFormat.ffmpeg) {
            AV_PIX_FMT_BGR24, AV_PIX_FMT_BGRA -> {
                val data = other.frame.image[0] as ByteBuffer
                val stepSize = if (other.pixelFormat.ffmpeg == AV_PIX_FMT_BGR24) 3 else 4
                for (i in 0 until width * height) {
                    sums[i * 3 + 0] += (data[i * stepSize + 2].toInt() and 0xFF)
                    sums[i * 3 + 1] += (data[i * stepSize + 1].toInt() and 0xFF)
                    sums[i * 3 + 2] += (data[i * stepSize + 0].toInt() and 0xFF)
                }
            }

            AV_PIX_FMT_ARGB, AV_PIX_FMT_RGB32, AV_PIX_FMT_RGB24 -> {
                val data = other.frame.image[0] as IntBuffer
                for (i in 0 until width * height) {
                    sums[i * 3 + 0] += (data[i] shr 16 and 0xFF)
                    sums[i * 3 + 1] += (data[i] shr 8 and 0xFF)
                    sums[i * 3 + 2] += (data[i] shr 0 and 0xFF)
                }
            }

            else -> throw IllegalArgumentException("Bad pixel format: ${other.pixelFormat}")
        }
        other.frame.close()
    }

    override fun toBufferedImage(): BufferedImage {
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            val pixels = IntArray(width * height) { i ->
                val r = (sums[i * 3 + 0].toFloat() / numAdded).toInt().coerceIn(0, 255)
                val g = (sums[i * 3 + 1].toFloat() / numAdded).toInt().coerceIn(0, 255)
                val b = (sums[i * 3 + 2].toFloat() / numAdded).toInt().coerceIn(0, 255)
                (r shl 16) or (g shl 8) or b
            }
            setRGB(0, 0, width, height, pixels, 0, width)
        }.also {
            sums.fill(0)
            numAdded = 0
        }
    }

    companion object {
        fun blankOf(width: Int, height: Int): AveragingImage =
            AveragingImageFrames(width = width, height = height)
    }
}
```

## GPU-Accelerated Image Averaging

This implementation uses JOCL to perform image averaging on the GPU.

```kotlin
import info.benjaminhill.videosmush.AveragingImage
import info.benjaminhill.videosmush.BaseAveragingImage
import info.benjaminhill.videosmush.FrameWithPixelFormat
import org.jocl.*
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt

class AveragingImageGPU private constructor(
    override val width: Int,
    override val height: Int,
) : BaseAveragingImage(width, height) {

    private val programSource = """
       __kernel void sum_byte(__global int *sums, __global const uchar *imageData, int stepSize) {
            int i = get_global_id(0);
            sums[i * 3 + 0] += imageData[i * stepSize + 2];
            sums[i * 3 + 1] += imageData[i * stepSize + 1];
            sums[i * 3 + 2] += imageData[i * stepSize + 0];
        }

        __kernel void sum_int(__global int *sums, __global const int *imageData) {
            int i = get_global_id(0);
            sums[i * 3 + 0] += (imageData[i] >> 16) & 0xFF;
            sums[i * 3 + 1] += (imageData[i] >> 8) & 0xFF;
            sums[i * 3 + 2] += (imageData[i] >> 0) & 0xFF;
        }
    """.trimIndent()

    private val context: cl_context
    private val commandQueue: cl_command_queue
    private val kernelByte: cl_kernel
    private val kernelInt: cl_kernel
    private val sumsMem: cl_mem

    init {
        CL.setExceptionsEnabled(true)

        val platformIndex = 0
        val deviceType = CL.CL_DEVICE_TYPE_GPU
        val deviceIndex = 0

        val numPlatformsArray = IntArray(1)
        CL.clGetPlatformIDs(0, null, numPlatformsArray)
        val numPlatforms = numPlatformsArray[0]

        val platforms = arrayOfNulls<cl_platform_id>(numPlatforms)
        CL.clGetPlatformIDs(platforms.size, platforms, null)
        val platform = platforms[platformIndex]

        val contextProperties = cl_context_properties()
        contextProperties.addProperty(CL.CL_CONTEXT_PLATFORM.toLong(), platform)

        val numDevicesArray = IntArray(1)
        CL.clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray)
        val numDevices = numDevicesArray[0]

        val devices = arrayOfNulls<cl_device_id>(numDevices)
        CL.clGetDeviceIDs(platform, deviceType, numDevices, devices, null)
        val device = devices[deviceIndex]

        context = CL.clCreateContext(contextProperties, 1, arrayOf(device), null, null, null)
        commandQueue = CL.clCreateCommandQueue(context, device, 0, null)

        val program = CL.clCreateProgramWithSource(context, 1, arrayOf(programSource), null, null)
        CL.clBuildProgram(program, 0, null, null, null, null)

        kernelByte = CL.clCreateKernel(program, "sum_byte", null)
        kernelInt = CL.clCreateKernel(program, "sum_int", null)

        sumsMem =
            CL.clCreateBuffer(context, CL.CL_MEM_READ_WRITE, (width * height * 3 * Sizeof.cl_int).toLong(), null, null)
    }

    override suspend operator fun plusAssign(other: FrameWithPixelFormat) {
        val bi = converter.get().convert(other.frame)
        other.frame.close()

        numAdded++
        when (bi.type) {
            BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_4BYTE_ABGR -> {
                val data = (bi.raster.dataBuffer as DataBufferByte).data
                val stepSize = if (bi.alphaRaster == null) 3 else 4
                val dataMem = CL.clCreateBuffer(
                    context,
                    CL.CL_MEM_READ_ONLY or CL.CL_MEM_COPY_HOST_PTR,
                    (data.size * Sizeof.cl_char).toLong(),
                    Pointer.to(data),
                    null
                )

                CL.clSetKernelArg(kernelByte, 0, Sizeof.cl_mem.toLong(), Pointer.to(sumsMem))
                CL.clSetKernelArg(kernelByte, 1, Sizeof.cl_mem.toLong(), Pointer.to(dataMem))
                CL.clSetKernelArg(kernelByte, 2, Sizeof.cl_int.toLong(), Pointer.to(intArrayOf(stepSize)))

                CL.clEnqueueNDRangeKernel(
                    commandQueue,
                    kernelByte,
                    1,
                    null,
                    longArrayOf(width.toLong() * height.toLong()),
                    null,
                    0,
                    null,
                    null
                )
                CL.clReleaseMemObject(dataMem)
            }

            BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_BGR, BufferedImage.TYPE_INT_ARGB -> {
                val data = (bi.raster.dataBuffer as DataBufferInt).data
                val dataMem = CL.clCreateBuffer(
                    context,
                    CL.CL_MEM_READ_ONLY or CL.CL_MEM_COPY_HOST_PTR,
                    (data.size * Sizeof.cl_int).toLong(),
                    Pointer.to(data),
                    null
                )

                CL.clSetKernelArg(kernelInt, 0, Sizeof.cl_mem.toLong(), Pointer.to(sumsMem))
                CL.clSetKernelArg(kernelInt, 1, Sizeof.cl_mem.toLong(), Pointer.to(dataMem))

                CL.clEnqueueNDRangeKernel(
                    commandQueue,
                    kernelInt,
                    1,
                    null,
                    longArrayOf(width.toLong() * height.toLong()),
                    null,
                    0,
                    null,
                    null
                )
                CL.clReleaseMemObject(dataMem)
            }

            else -> throw IllegalArgumentException("Unsupported image type: ${bi.type}")
        }
    }

    override fun toBufferedImage(): BufferedImage {
        val sums = IntArray(width * height * 3)
        CL.clEnqueueReadBuffer(
            commandQueue,
            sumsMem,
            true,
            0,
            (sums.size * Sizeof.cl_int).toLong(),
            Pointer.to(sums),
            0,
            null,
            null
        )

        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            val pixels = IntArray(width * height) { i ->
                val r = (sums[i * 3 + 0].toFloat() / numAdded).toInt().coerceIn(0, 255)
                val g = (sums[i * 3 + 1].toFloat() / numAdded).toInt().coerceIn(0, 255)
                val b = (sums[i * 3 + 2].toFloat() / numAdded).toInt().coerceIn(0, 255)
                (r shl 16) or (g shl 8) or b
            }
            setRGB(0, 0, width, height, pixels, 0, width)
        }.also {
            val zero = IntArray(sums.size)
            CL.clEnqueueWriteBuffer(
                commandQueue,
                sumsMem,
                true,
                0,
                (zero.size * Sizeof.cl_int).toLong(),
                Pointer.to(zero),
                0,
                null,
                null
            )
            numAdded = 0
        }
    }

    override fun close() {
        CL.clReleaseMemObject(sumsMem)
        CL.clReleaseKernel(kernelByte)
        CL.clReleaseKernel(kernelInt)
        CL.clReleaseCommandQueue(commandQueue)
        CL.clReleaseContext(context)
    }

    companion object {
        fun blankOf(width: Int, height: Int): AveragingImage = AveragingImageGPU(width, height)
    }
}
```

## OpenCV-Based Image Averaging

This implementation uses OpenCV to perform image accumulation.

```kotlin
import info.benjaminhill.videosmush.AveragingImage
import info.benjaminhill.videosmush.BaseAveragingImage
import info.benjaminhill.videosmush.FrameWithPixelFormat
import org.bytedeco.javacv.Java2DFrameConverter
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.opencv.global.opencv_core.CV_32FC3
import org.bytedeco.opencv.global.opencv_core.CV_8UC3
import org.bytedeco.opencv.global.opencv_imgproc.accumulate
import org.bytedeco.opencv.opencv_core.Mat
import java.awt.image.BufferedImage

class AveragingImageOCV private constructor(
    override val width: Int,
    override val height: Int,
) : BaseAveragingImage(width, height) {

    private val accumulator: Mat = Mat.zeros(height, width, CV_32FC3).asMat()
    private val frameToBiConverter = Java2DFrameConverter()
    private val ocvConverter = OpenCVFrameConverter.ToMat()

    override suspend operator fun plusAssign(other: FrameWithPixelFormat) {
        val bi = frameToBiConverter.convert(other.frame)
        other.frame.close()

        val frameForOcv = frameToBiConverter.convert(bi)
        val inputMat = ocvConverter.convert(frameForOcv)
        frameForOcv.close()

        if (inputMat == null || inputMat.isNull) {
            // Don't process null/empty mats
            throw Exception("OCV conversion error")
        }
        val floatMat = Mat()
        inputMat.convertTo(floatMat, CV_32FC3)
        accumulate(floatMat, accumulator)
        numAdded++
        floatMat.close()
        inputMat.close()
    }

    override fun toBufferedImage(): BufferedImage {
        val ocvConverter = OpenCVFrameConverter.ToMat()
        val avgMat = Mat()
        accumulator.convertTo(avgMat, CV_8UC3, 1.0 / numAdded, 0.0)
        val frame = ocvConverter.convert(avgMat)
        val image = frameToBiConverter.convert(frame)
        ocvConverter.close()

        // Reset accumulator
        accumulator.put(Mat.zeros(height, width, CV_32FC3))
        numAdded = 0

        avgMat.close()
        frame.close()
        return image
    }

    override fun close() {
        accumulator.close()
        frameToBiConverter.close()
        ocvConverter.close()
    }

    companion object {
        fun blankOf(width: Int, height: Int): AveragingImage =
            AveragingImageOCV(width = width, height = height)
    }
}
```

## Utility Functions

A collection of useful utility functions.

```kotlin
import mu.KotlinLogging
import java.awt.AlphaComposite.SRC_OVER
import java.awt.AlphaComposite.getInstance
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt

val LOG = KotlinLogging.logger {}

fun Double.toPercent(): String = "${(100 * this).roundToInt()}%"

/** Assuming each int maxes out at 255, average diff independent of array size */
infix fun IntArray.averageDiff(other: IntArray): Double {
    require(isNotEmpty() && size == other.size)
    return (size - 1 downTo 0).sumOf { idx ->
        abs(this[idx] - other[idx])
    } / (255 * size.toDouble())
}


/** Add text to bottom-right of image */
fun BufferedImage.addTextWatermark(text: String): BufferedImage {
    val buffer = 3

    val g2d = this.graphics as Graphics2D
    g2d.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_GASP
    )

    val alphaChannel = getInstance(SRC_OVER, 0.1f)
    g2d.composite = alphaChannel
    g2d.color = Color.WHITE
    g2d.font = Font(Font.SANS_SERIF, Font.BOLD, 24)
    val fontMetrics = g2d.fontMetrics

    val textStringBounds = fontMetrics.getStringBounds(text, g2d)
    val maxDescent = fontMetrics.maxDescent
    // println(textStringBounds)
    // println(maxDescent)
    val locX = (this.width - textStringBounds.width - buffer).toInt()
    val locY = (this.height - (maxDescent + buffer))
    // println("Loc: $locX, $locY")
    g2d.drawString(text, locX, locY)
    g2d.dispose()
    return this
}
```

## Scratchpad Snippets

A collection of potentially useful code snippets from `scratch.txt`.

```kotlin
fun BufferedImage.deepCopy(): BufferedImage {
    val cm = colorModel!!
    val isAlphaPremultiplied = cm.isAlphaPremultiplied
    val raster = copyData(null)!!
    return BufferedImage(cm, raster, isAlphaPremultiplied, null)
}


```
