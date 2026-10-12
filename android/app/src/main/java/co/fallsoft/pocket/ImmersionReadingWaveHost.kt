package co.fallsoft.pocket

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.isActive

/** One screen-owned display clock, enrolled only by actually visible text. */
internal class ImmersionReadingWaveClock(policy:ImmersionReadingWave=ImmersionReadingWave()) {
    var policy by mutableStateOf(policy); private set
    var viewportOrigin by mutableStateOf(Offset.Zero); private set
    var viewportWidthPx by mutableFloatStateOf(0f); private set
    fun viewport(origin:Offset,widthPx:Float,heightPx:Float,density:Float) {
        if(widthPx<=0f||heightPx<=0f||density<=0f)return
        viewportOrigin=origin;viewportWidthPx=widthPx
        policy=ImmersionReadingWave.forViewport((heightPx/density).toDouble())
        rebuildLanes()
    }
    var elapsedMs by mutableLongStateOf(0L); private set
    private val visible=mutableStateMapOf<Any,Boolean>()
    private val selection=mutableStateMapOf<Any,Boolean>()
    private var nanos=0L
    private val lineGeometry=mutableStateMapOf<Any,List<Rect>>()
    fun lines(owner:Any,lines:List<Rect>){
        if(lineGeometry[owner]==lines)return
        if(lines.isEmpty())lineGeometry.remove(owner) else lineGeometry[owner]=lines
        rebuildLanes()
    }
    private data class Lane(val rect:Rect,val start:Float,val distance:Float)
    private var laneSchedule by mutableStateOf(emptyList<Lane>())
    private var laneHalfPeriod=1L
    private var laneVelocity=1f
    private var laneFeather=12f
    private fun rebuildLanes() {
        val sorted=lineGeometry.values.flatten().sortedBy{it.top}
        val result=mutableListOf<Rect>()
        for(rect in sorted){
            val last=result.lastOrNull()
            if(last!=null&&kotlin.math.abs(last.top-rect.top)<=2f)
                result[result.lastIndex]=Rect(minOf(last.left,rect.left),minOf(last.top,rect.top),maxOf(last.right,rect.right),maxOf(last.bottom,rect.bottom))
            else result+=rect
        }
        laneFeather=(viewportWidthPx*.06f).coerceAtLeast(12f)
        laneVelocity=(viewportWidthPx+2*laneFeather).coerceAtLeast(1f)/1200f
        var start=0f
        laneSchedule=result.map{rect->
            val distance=rect.width+2*laneFeather
            Lane(rect,start,distance).also{start+=distance/laneVelocity+120f}
        }
        laneHalfPeriod=(start+1880f).toLong().coerceAtLeast(1)
    }
    fun lineMask(y:Float,rtl:Boolean):ImmersionSharedWipeMask? {
        val lane=laneSchedule.minByOrNull{kotlin.math.abs(it.rect.top-y)}?:return null
        val time=Math.floorMod(elapsedMs,laneHalfPeriod*2)
        val incoming=time<laneHalfPeriod
        val phase=if(incoming)time.toFloat()else(time-laneHalfPeriod).toFloat()
        val distance=((phase-lane.start)*laneVelocity).coerceIn(0f,lane.distance)
        val center=if(rtl)lane.rect.right+laneFeather-distance else lane.rect.left-laneFeather+distance
        return ImmersionSharedWipeMask(center,laneFeather,incoming)
    }
    fun selecting(owner:Any,value:Boolean){if(value)selection[owner]=true else selection.remove(owner)}
    val hasSelection:Boolean get()=selection.isNotEmpty()
    fun visible(owner:Any,value:Boolean){if(value)visible[owner]=true else {visible.remove(owner);if(lineGeometry.remove(owner)!=null)rebuildLanes()}}
    val hasVisible:Boolean get()=visible.isNotEmpty()
    fun advanceNanos(deltaNanos:Long){
        if(hasSelection||BlackoutVisibility.active)return
        nanos=(nanos+deltaNanos.coerceIn(0L,100_000_000L)).coerceAtLeast(0L)
        elapsedMs=nanos/1_000_000L
    }
}
internal val LocalImmersionReadingWave=staticCompositionLocalOf<ImmersionReadingWaveClock?>{null}

@Composable internal fun ImmersionReadingWaveHost(content:@Composable ()->Unit) {
    val clock=remember{ImmersionReadingWaveClock()}
    val activity=LocalContext.current.nextCompActivity()
    val nativeOwner=remember{Any()}
    val nativeSelection=activity?.immersionSelectionActive==true
    DisposableEffect(clock,nativeOwner,nativeSelection){
        clock.selecting(nativeOwner,nativeSelection)
        onDispose{clock.selecting(nativeOwner,false)}
    }
    val running=immersionMotionReady()&&immersionTransitionAnimated()&&clock.hasVisible&&!clock.hasSelection&&!BlackoutVisibility.active
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
    val density=LocalDensity.current.density
    Box(Modifier.onGloballyPositioned { coordinates->
        // Screen space is shared by native popup windows and the main activity.
        // Window-local coordinates restart at each popup and are not a viewport.
        clock.viewport(coordinates.localToScreen(Offset.Zero),
            coordinates.size.width.toFloat(),coordinates.size.height.toFloat(),density)
    }) {
        CompositionLocalProvider(LocalImmersionReadingWave provides clock,content=content)
    }
}

/** Optional native qualification observation; carries no content, account or credentials. */
internal data class ImmersionRendererStatus(val waveRequested:Boolean,val aligned:Boolean,val slots:Int,val markerCounts:List<Int>,val matchingIds:Boolean)
internal val LocalImmersionRendererObserver=staticCompositionLocalOf<((ImmersionRendererStatus)->Unit)?>{null}
