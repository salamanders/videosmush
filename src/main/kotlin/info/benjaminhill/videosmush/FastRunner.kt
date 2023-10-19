package info.benjaminhill.videosmush

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Maybe-correct wrapper around virtual threads.
 * Does not limit the number of parallel virtual threads.
 * see https://howtodoinjava.com/java/multi-threading/virtual-threads/
 * see https://void2unit.onrender.com/post/virtualthreads-in-kotlin/
 */
class FastRunner(private val maxWaitToFinish: Long = 60) : AutoCloseable {
    private val executorService: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    fun prun(task: Runnable) {
        executorService.submit(task)
    }

    override fun close() {
        executorService.shutdown()
        executorService.awaitTermination(maxWaitToFinish, TimeUnit.SECONDS)
    }
}
