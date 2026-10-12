package co.fallsoft.pocket

import androidx.compose.runtime.*
import kotlinx.coroutines.isActive

/** One screen-owned display clock, enrolled only by actually visible text. */
internal class ImmersionReadingWaveClock(val policy:ImmersionReadingWave=ImmersionReadingWave()) {
    var elapsedMs by mutableLongStateOf(0L); private set
    private val visible=mutableStateMapOf<Any,Boolean>()
    private val selection=mutableStateMapOf<Any,Boolean>()
    private var nanos=0L
    fun selecting(owner:Any,value:Boolean){if(value)selection[owner]=true else selection.remove(owner)}
    val hasSelection:Boolean get()=selection.isNotEmpty()
    fun visible(owner:Any,value:Boolean){if(value)visible[owner]=true else visible.remove(owner)}
    val hasVisible:Boolean get()=visible.isNotEmpty()
    fun advanceNanos(deltaNanos:Long){
        if(hasSelection)return
        nanos=Math.floorMod(nanos+deltaNanos.coerceIn(0L,100_000_000L),policy.periodMs*1_000_000L)
        elapsedMs=nanos/1_000_000L
    }
}
internal val LocalImmersionReadingWave=staticCompositionLocalOf<ImmersionReadingWaveClock?>{null}

@Composable internal fun ImmersionReadingWaveHost(content:@Composable ()->Unit) {
    val clock=remember{ImmersionReadingWaveClock()}
    val running=immersionMotionReady()&&immersionTransitionAnimated()&&clock.hasVisible&&!clock.hasSelection
    LaunchedEffect(clock,running) {
        if(!running)return@LaunchedEffect
        var previous=withFrameNanos{it}
        while(isActive) {
            val now=withFrameNanos{it}
            // Paused intervals never advance, and missed frames never create a
            // burst of catch-up transitions. No clock loop survives background.
            clock.advanceNanos(now-previous)
            previous=now
        }
    }
    CompositionLocalProvider(LocalImmersionReadingWave provides clock,content=content)
}

/** Optional native qualification observation; carries no content, account or credentials. */
internal data class ImmersionRendererStatus(val waveRequested:Boolean,val aligned:Boolean,val slots:Int,val markerCounts:List<Int>,val matchingIds:Boolean)
internal val LocalImmersionRendererObserver=staticCompositionLocalOf<((ImmersionRendererStatus)->Unit)?>{null}
