package info.benjaminhill.videosmush

/**
 * Generates an FFMPEG crop filter string.
 *
 * @param fromLeft Pixels to crop from the left.
 * @param fromRight Pixels to crop from the right.
 * @param fromTop Pixels to crop from the top.
 * @param fromBottom Pixels to crop from the bottom.
 * @param forceEvenDimensions If true, ensures the output width and height are even numbers by increasing crop if necessary.
 *                            This is important for YUV420P formats which require even dimensions.
 */
fun filterCrop(
    fromLeft: Int = 0,
    fromRight: Int = 0,
    fromTop: Int = 0,
    fromBottom: Int = 0,
    forceEvenDimensions: Boolean = true
): String {
    val wStr = if (forceEvenDimensions) {
        "floor((in_w-${fromLeft + fromRight})/2)*2"
    } else {
        "in_w-${fromLeft + fromRight}"
    }
    val hStr = if (forceEvenDimensions) {
        "floor((in_h-${fromTop + fromBottom})/2)*2"
    } else {
        "in_h-${fromTop + fromBottom}"
    }
    return "crop=${wStr}:${hStr}:${fromLeft}:${fromTop}"
}
