package io.github.abdukir.klippercam.stream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response

/**
 * Executes the call on the IO dispatcher and runs the (blocking) [block] with the response.
 * Cancelling the coroutine cancels the HTTP call, which unblocks any socket read in [block].
 */
suspend fun <T> Call.executeBlocking(block: (Response) -> T): T = coroutineScope {
    val call = this@executeBlocking
    val watcher = launch {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        withContext(Dispatchers.IO) { call.execute().use(block) }
    } finally {
        watcher.cancel()
    }
}
