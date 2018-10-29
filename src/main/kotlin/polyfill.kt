import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.security.MessageDigest


// Unsure why this isn't standard https://stackoverflow.com/questions/44315977/ranges-in-kotlin-using-data-type-double
infix fun ClosedRange<Double>.step(step: Double): Iterable<Double> {
    require(start.isFinite())
    require(endInclusive.isFinite())
    require(step > 0.0) { "Step must be positive, was: $step." }
    val sequence = generateSequence(start) { previous ->
        if (previous == Double.POSITIVE_INFINITY) return@generateSequence null
        val next = previous + step
        if (next > endInclusive) null else next
    }
    return sequence.asIterable()
}

/** If you pass in a file name, the label is a hash of the file.  Or pass in a generic string label */
fun labelToFile(fileOrLabel: String): File {
    val f = File(fileOrLabel)

    // Max of 10MB
    val hash = if (f.canRead()) {
        val byteArray = ByteArray(Math.min(f.length(), 1024 * 1024 * 10L).toInt())
        File(fileOrLabel).inputStream().use {
            it.read(byteArray)
        }
        "_" + MessageDigest.getInstance("MD5").digest(byteArray).joinToString("") { byte ->
            String.format("%02X", byte)
        }.substring(0, 10)
    } else {
        ""
    }
    return File("$fileOrLabel$hash.ser")
}

fun <T> sequenceToChunks(sourceSequence: Sequence<T>, chunkSizes: List<Int>): Sequence<List<T>> = sequence<List<T>> {
    val consumableMergeList = mutableListOf<Int>().apply { addAll(chunkSizes) }
    val buffer = mutableListOf<T>()

    for (element in sourceSequence) {
        buffer += element
        if (consumableMergeList.isNotEmpty()) {
            consumableMergeList[0]--
            if (consumableMergeList[0] == 0) {
                yield(buffer)
                buffer.clear()
                consumableMergeList.removeAt(0)
            }
        } else {
            println("Past end of merged instructions, lumping remaining elements into last list.")
        }
    }
    if (buffer.isNotEmpty()) yield(buffer)
}


/** Drop an object off in the cache */
fun <T> saveObj(obj: T, sourceFileNameOrLabel: String): T {
    ObjectOutputStream(labelToFile(sourceFileNameOrLabel).outputStream()).use {
        it.writeObject(obj)
    }
    return obj
}

/** Load a cached object if available */
fun loadObj(sourceFileNameOrLabel: String): Any? {
    val file = labelToFile(sourceFileNameOrLabel)
    if (file.canRead()) {
        ObjectInputStream(file.inputStream()).use {
            return it.readObject()
        }
    }
    return null
}
