package co.fallsoft.pocket

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * One draw clock; stable owner/local-line identities. Geometry determines a line's
 * onset once per half-wave. Scrolling never remaps it to another line's phase.
 */
internal class ImmersionReadingWaveClock {
    companion object {
        const val TRAVEL_MS=2600L
        const val WIPE_MS=1200L
        const val HOLD_MS=1800L
        const val HALF_MS=TRAVEL_MS+WIPE_MS+HOLD_MS
    }
    var viewportOrigin by mutableStateOf(Offset.Zero);private set
    var viewportWidthPx by mutableFloatStateOf(0f);private set
    var viewportHeightPx by mutableFloatStateOf(0f);private set
    var elapsedMs by mutableLongStateOf(0L);private set
    private var geometryRevision by mutableLongStateOf(0L)
    private var visibleLines by mutableIntStateOf(0)
    private var nanos=0L
    private var half=-1L
    private data class Key(val owner:Any,val top:Int)
    private data class Lane(val onset:Long,val fromSupport:Boolean,val toSupport:Boolean)
    private val geometry=linkedMapOf<Key,Rect>()
    private val lanes=HashMap<Key,Lane>()
    private val held=linkedMapOf<Key,Boolean>()
    private val selecting=mutableStateMapOf<Any,Boolean>()
    private val frozen=HashMap<Pair<Any,Key>,ImmersionSharedWipeMask>()
    private fun key(owner:Any,top:Float)=Key(owner,(top*64f).roundToInt())
    val hasVisible:Boolean get()=visibleLines>0
    val hasSelection:Boolean get()=selecting.isNotEmpty()

    fun viewport(origin:Offset,widthPx:Float,heightPx:Float,density:Float){
        if(widthPx<=0f||heightPx<=0f||density<=0f)return
        if(viewportOrigin==origin&&viewportWidthPx==widthPx&&viewportHeightPx==heightPx)return
        viewportOrigin=origin;viewportWidthPx=widthPx;viewportHeightPx=heightPx
        geometryRevision++
    }
    fun lines(owner:Any,origin:Offset,lines:List<Rect>){
        val next=linkedMapOf<Key,Rect>()
        for(local in lines){
            val rect=local.translate(origin)
            if(rect.bottom<=0f||rect.top>=viewportHeightPx||rect.right<=0f||rect.left>=viewportWidthPx)continue
            val key=key(owner,local.top)
            val old=next[key]
            next[key]=if(old==null)rect else Rect(minOf(old.left,rect.left),minOf(old.top,rect.top),maxOf(old.right,rect.right),maxOf(old.bottom,rect.bottom))
        }
        val old=geometry.filterKeys{it.owner==owner}
        if(old==next)return
        old.keys.forEach{geometry.remove(it)}
        geometry.putAll(next)
        visibleLines=geometry.size
        // A line enrolled after its frontier passed holds its previous form.
        // It never borrows a neighbour's phase or catches up on entry.
        if(half>=0)for((key,rect) in next)if(key !in lanes)enroll(key,rect,late=true)
        geometryRevision++
    }
    fun remove(owner:Any){
        val keys=geometry.keys.filter{it.owner==owner}
        if(keys.isEmpty())return
        keys.forEach{geometry.remove(it)}
        visibleLines=geometry.size;geometryRevision++
        // Keep this wave's identity and held form through LazyColumn recycling.
    }
    private fun goal()=half%2L==0L
    private fun enroll(key:Key,rect:Rect,late:Boolean){
        val onset=(rect.top.coerceIn(0f,viewportHeightPx)/viewportHeightPx.coerceAtLeast(1f)*TRAVEL_MS).toLong()
        val passed=late&&elapsedMs%HALF_MS>=onset
        val target=goal()
        val start=held[key]?:if(passed)target else !target
        lanes[key]=if(passed)Lane(onset,start,start)else Lane(onset,start,target)
        held[key]=start
    }
    private fun beginHalf(next:Long){
        lanes.forEach{(key,lane)->held[key]=lane.toSupport}
        lanes.clear();half=next
        geometry.forEach{(key,rect)->enroll(key,rect,late=false)}
        // Bounded presentation state, not a command/read-ack journal.
        while(held.size>4096){
            val old=held.keys.firstOrNull{it !in geometry}?:break
            held.remove(old)
        }
        geometryRevision++
    }
    fun selecting(owner:Any,value:Boolean){
        if(value)selecting[owner]=true
        else{selecting.remove(owner);frozen.keys.removeAll{it.first==owner}}
    }
    private fun hold(form:Boolean,rtl:Boolean):ImmersionSharedWipeMask{
        val feather=(viewportWidthPx*.055f).coerceAtLeast(12f)
        val center=if(form xor rtl)viewportWidthPx+feather else -feather
        return ImmersionSharedWipeMask(center,feather,true)
    }
    /** O(1) lookup in the draw path, keyed independently of scrolling coordinates. */
    fun lineMask(owner:Any,localTop:Float,rtl:Boolean,selection:Any?=null):ImmersionSharedWipeMask{
        geometryRevision // Draw invalidation for new layout; no frame-driven measurement.
        val key=key(owner,localTop)
        if(selection!=null&&selecting[selection]==true)frozen[selection to key]?.let{return it}
        val lane=lanes[key]
        val mask=if(lane==null)hold(held[key]?:goal(),rtl)
        else if(lane.fromSupport==lane.toSupport)hold(lane.toSupport,rtl)
        else{
            val feather=(viewportWidthPx*.055f).coerceAtLeast(12f)
            val progress=((elapsedMs%HALF_MS-lane.onset).toFloat()/WIPE_MS).coerceIn(0f,1f)
            val distance=(viewportWidthPx+2*feather)*progress
            ImmersionSharedWipeMask(if(rtl)viewportWidthPx+feather-distance else -feather+distance,feather,lane.toSupport)
        }
        if(selection!=null&&selecting[selection]==true)frozen[selection to key]=mask
        return mask
    }
    /** Geometry observation for native qualification only; rendering always supplies an owner. */
    fun lineMask(y:Float,rtl:Boolean):ImmersionSharedWipeMask?{
        val entry=geometry.entries.firstOrNull{kotlin.math.abs(it.value.top-y)<2f}?:return null
        return lineMask(entry.key.owner,entry.key.top/64f,rtl)
    }
    internal data class Observation(val identity:String,val rect:Rect,val mask:ImmersionSharedWipeMask)
    /** Bounded, content-free inspection. No observer or allocation runs unless requested. */
    internal fun observeLines():List<Observation> = geometry.map{(key,rect)->
        Observation(key.owner.hashCode().toString()+":"+key.top,rect,lineMask(key.owner,key.top/64f,false))
    }
    fun advanceNanos(deltaNanos:Long){
        if(BlackoutVisibility.active)return
        nanos=(nanos+deltaNanos.coerceAtLeast(0L)).coerceAtLeast(0L)
        elapsedMs=nanos/1_000_000L
        val next=elapsedMs/HALF_MS
        if(next!=half)beginHalf(next)
    }
}
internal val LocalImmersionReadingWave=staticCompositionLocalOf<ImmersionReadingWaveClock?>{null}

@Composable internal fun ImmersionReadingWaveHost(content:@Composable ()->Unit){
    val clock=remember{ImmersionReadingWaveClock()}
    val running=immersionMotionReady()&&immersionTransitionAnimated()&&clock.hasVisible&&!BlackoutVisibility.active
    LaunchedEffect(clock,running){
        if(!running)return@LaunchedEffect
        var previous=withFrameNanos{it}
        while(isActive){
            val now=withFrameNanos{it}
            clock.advanceNanos(now-previous);previous=now
        }
    }
    Box(Modifier.onGloballyPositioned{coordinates->
        clock.viewport(coordinates.localToScreen(Offset.Zero),coordinates.size.width.toFloat(),coordinates.size.height.toFloat(),1f)
    }){
        CompositionLocalProvider(LocalImmersionReadingWave provides clock,content=content)
    }
}
internal data class ImmersionRendererStatus(val waveRequested:Boolean,val aligned:Boolean,val slots:Int,val markerCounts:List<Int>,val matchingIds:Boolean)
internal val LocalImmersionRendererObserver=staticCompositionLocalOf<((ImmersionRendererStatus)->Unit)?>{null}
