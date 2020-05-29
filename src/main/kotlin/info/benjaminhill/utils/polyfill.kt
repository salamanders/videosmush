package info.benjaminhill.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt


/** Load a cached object if available, calculate and cache if not. */
fun <T> cachedOrCalculated(label: String, exec: suspend () -> T): T = runBlocking(Dispatchers.IO) {
    val file = File("$label.ser.gz")
    if (!file.canRead()) {
        ObjectOutputStream(GZIPOutputStream(file.outputStream())).use {
            it.writeObject(exec())
        }
    }
    ObjectInputStream(GZIPInputStream(file.inputStream())).use {
        @Suppress("UNCHECKED_CAST")
        return@runBlocking it.readObject() as T
    }
}

fun Double.toPercent(): String = "${(100 * this).roundToInt()}%"

fun <T, R> Flow<T>.zipWithNext(transform: (a: T, b: T) -> R): Flow<R> = flow {
    var last: T? = null
    this@zipWithNext.collect { elt ->
        last?.let {
            emit(transform(it, elt))
        }
        last = elt
    }
}

/** Assuming each int maxes out at 255, average diff independent of array size */
infix fun IntArray.averageDiff(other: IntArray): Double {
    require(isNotEmpty() && size == other.size)
    return (size - 1 downTo 0).sumBy { idx ->
        abs(this[idx] - other[idx])
    } / (255 * size.toDouble())
}

/** Print the line if the lineNum is a power of 2 */
fun println2(lineNum: Int, log: () -> String) {
    if ((lineNum and (lineNum - 1)) == 0) {
        println(log())
    }
}

