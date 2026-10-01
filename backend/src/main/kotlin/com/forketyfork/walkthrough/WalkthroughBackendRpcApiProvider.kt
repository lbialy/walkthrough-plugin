@file:Suppress("UnstableApiUsage")

package com.forketyfork.walkthrough

import com.intellij.platform.rpc.backend.RemoteApiProvider
import fleet.rpc.remoteApiDescriptor

internal class WalkthroughBackendRpcApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<WalkthroughRpcApi>()) {
            BackendWalkthroughRpcApi()
        }
    }
}
