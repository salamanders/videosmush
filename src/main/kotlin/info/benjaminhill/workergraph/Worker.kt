package info.benjaminhill.workergraph

import mu.KLoggable
import toPercent
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import kotlin.concurrent.thread


/**
 * Easy way to chain together steps.
 * Each node will run in it's own thread, and be reasonably memory constrained.
 * Upstream can be a single item, a BlockingQueue of items, or another Worker.
 * If fan-out process, throw in some Thread.yield() in the loop for single-core machines.
 * Implementing classes MUST include or pass through Flag.LAST
 */
abstract class Worker<In, Out> private constructor(private val inputs: BlockingQueue<Pair<In, EnumSet<Flag>>>) {

    private var upstreamWorker: Worker<*, In>? = null

    /** Most common constructor is the upstream node */
    constructor(usWorker: Worker<*, In>) : this(usWorker.outputs) {
        this.upstreamWorker = usWorker
        logger.warn { "Created worker-source: ${this::class.simpleName}" }
    }

    /** Also an option to start with a single thing (like a file) */
    constructor(input: In) : this(ArrayBlockingQueue<Pair<In, EnumSet<Flag>>>(1).apply {
        put(Pair(input, EnumSet.of(Flag.LAST)))
    }) {
        logger.warn { "Created single-item source ${this::class.simpleName}: '$input'" }
    }

    /**  Number of input items handled */
    private var inputsProcessed = 0

    protected val outputs: BlockingQueue<Pair<Out, EnumSet<Flag>>> = ArrayBlockingQueue(32)

    protected fun printStatus(additionalStatus: String = "") = logger.debug {
        "Thread:'${Thread.currentThread().name}' Class:'${this::class.simpleName}'" +
                ", Input Count=$inputsProcessed" +
                ", pipeline fill=${(outputs.remainingCapacity().toDouble() / outputs.size).toPercent()}" +
                "; $additionalStatus"


    }


    private val executionThread = thread(name = "WorkerThread:" + this::class.simpleName) {
        while (true) {

            val nextInput = inputs.take()!!
            inputsProcessed++
            process(nextInput.first, nextInput.second)

            if ((inputsProcessed and (inputsProcessed - 1)) == 0) {
                printStatus()
            }

            if (nextInput.second.contains(Flag.LAST)) {
                break
            }
            Thread.yield()
        }
        logger.info { "${this::class.simpleName} finished a total of $inputsProcessed inputs." }
    }


    /** Blocks on input.take (automatic) -> outputs.put (manual in the implementation) */
    abstract fun process(input: In, flags: EnumSet<Flag>)

    /** If you collapse to one, good way to block on the one being ready. */
    fun takeOne(): Out {
        val theOne = outputs.take()!!
        require(theOne.second.contains(Flag.LAST))
        return theOne.first
    }

    /** Signals that ride along the queue */
    enum class Flag {
        // Maybe someday other flags like FIRST
        LAST
    }

    companion object : KLoggable {
        override val logger = logger()
    }
}