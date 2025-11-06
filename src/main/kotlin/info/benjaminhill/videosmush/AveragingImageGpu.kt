package info.benjaminhill.videosmush

import org.jocl.*
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt

class AveragingImageGpu private constructor(
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
        commandQueue = CL.clCreateCommandQueueWithProperties(context, device, null, null)

        val program = CL.clCreateProgramWithSource(context, 1, arrayOf(programSource), null, null)
        CL.clBuildProgram(program, 0, null, null, null, null)

        kernelByte = CL.clCreateKernel(program, "sum_byte", null)
        kernelInt = CL.clCreateKernel(program, "sum_int", null)

        sumsMem =
            CL.clCreateBuffer(context, CL.CL_MEM_READ_WRITE, (width * height * 3 * Sizeof.cl_int).toLong(), null, null)
    }

    override suspend operator fun plusAssign(other: FrameWithPixelFormat) {
        plusAssign(AveragingImageBIDirect.converter.get().convert(other.frame))
        other.frame.close()
    }

    override suspend operator fun plusAssign(other: BufferedImage) {
        numAdded++
        when (other.type) {
            BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_4BYTE_ABGR -> {
                val data = (other.raster.dataBuffer as DataBufferByte).data
                val stepSize = if (other.alphaRaster == null) 3 else 4
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
                val data = (other.raster.dataBuffer as DataBufferInt).data
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

            else -> throw IllegalArgumentException("Unsupported image type: ${other.type}")
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
        fun blankOf(width: Int, height: Int): AveragingImage = AveragingImageGpu(width, height)
    }
}
