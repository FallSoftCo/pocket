package co.fallsoft.pocket

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*

internal class EnvironmentRequestContext(val destination:EnvironmentRequest):AbstractCoroutineContextElement(Key) {
    companion object Key:CoroutineContext.Key<EnvironmentRequestContext>
}
/** Inherits a captured destination through delays and nested suspensions; never redirects queued work. */
internal fun CoroutineScope.launchEnvironment(block:suspend CoroutineScope.()->Unit):Job {
    val destination=Pocket.captureEnvironment()
    return launch(if(destination==null)kotlin.coroutines.EmptyCoroutineContext else EnvironmentRequestContext(destination)){
        if(destination!=null&&Pocket.captureEnvironment()?.matches(destination)!=true)return@launch
        block()
    }
}
