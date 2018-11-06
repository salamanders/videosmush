import java.awt.image.BufferedImage
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream


/** If you pass in a file name, the label is a hash of the file.  Or pass in a generic string label */
fun labelToFile(fileOrLabel: String): File {
    val f = File(fileOrLabel)

    // Max of 10MB for hashing, which should be plenty but still fast.
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
    return File("$fileOrLabel$hash.ser.gz")
}

/** Load a cached object if available, calculate and cache if not. */
fun <T> cachedOrCalculated(sourceFileNameOrLabel: String, exec: () -> T): T {
    val file = labelToFile(sourceFileNameOrLabel)
    if (file.canRead()) {
        ObjectInputStream(GZIPInputStream(file.inputStream())).use {
            @Suppress("UNCHECKED_CAST")
            return it.readObject() as T
        }
    }
    val result = exec()
    ObjectOutputStream(GZIPOutputStream(labelToFile(sourceFileNameOrLabel).outputStream())).use {
        it.writeObject(result)
    }
    return result
}

fun BufferedImage.deepCopy(): BufferedImage {
    val cm = colorModel!!
    val isAlphaPremultiplied = cm.isAlphaPremultiplied
    val raster = copyData(null)!!
    return BufferedImage(cm, raster, isAlphaPremultiplied, null)
}

fun Double.toPercent(): String = "${Math.round(100 * this)}%"
