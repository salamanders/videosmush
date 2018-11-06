package info.benjaminhill.workergraph

import java.util.*


/** Terminal toList() */
class WorkerToList<T : Any>(upstreamWorker: Worker<*, T>) : Worker<T, List<T>>(upstreamWorker) {

    private val result = mutableListOf<T>()
    override fun process(input: T, flags: EnumSet<Flag>) {
        result.add(input)

        // If you have everything possible
        if (flags.contains(Flag.LAST)) {
            outputs.put(Pair(result.toList(), flags))
            logger.warn { "Terminal toList: ${result.size}" }
        }
    }
}