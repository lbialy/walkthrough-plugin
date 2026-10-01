@file:Suppress("UnstableApiUsage")

package com.forketyfork.walkthrough

import com.intellij.openapi.diagnostic.Logger
import fleet.rpc.client.durable
import kotlinx.coroutines.withTimeoutOrNull

/** Upper bound for one-shot RPC calls, so a missing backend module can't hang an action forever. */
internal const val RPC_CALL_TIMEOUT_MILLIS: Long = 15_000L

private val LOG = Logger.getInstance("#com.forketyfork.walkthrough.WalkthroughFrontendRpc")

/**
 * Runs a one-shot call against the backend, retrying across transient connection loss (via
 * [durable]) for at most [RPC_CALL_TIMEOUT_MILLIS]. Returns `null` (and logs) when the backend
 * doesn't answer in time.
 */
internal suspend fun <T : Any> callBackend(operation: String, call: suspend WalkthroughRpcApi.() -> T): T? {
    val result = withTimeoutOrNull(RPC_CALL_TIMEOUT_MILLIS) {
        durable {
            WalkthroughRpcApi.getInstance().call()
        }
    }
    if (result == null) LOG.warn("Walkthrough backend did not answer '$operation' in time")
    return result
}
