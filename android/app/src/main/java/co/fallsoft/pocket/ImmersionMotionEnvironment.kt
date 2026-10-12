package co.fallsoft.pocket

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** Touch diagnostics only: ordinary taps must not stop the reading clock. */
internal object ImmersionInteraction {
    var touching by mutableStateOf(false); private set
    fun touch(action:Int){
        when(action){
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE -> touching=true
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> touching=false
        }
    }
}

internal val LocalImmersionMotionState=staticCompositionLocalOf<Boolean?>{null}
internal val LocalImmersionTransitionAnimated=staticCompositionLocalOf<Boolean?>{null}
@Composable internal fun immersionTransitionAnimated():Boolean=LocalImmersionTransitionAnimated.current?:motionAllowed()
@Composable internal fun immersionMotionReady():Boolean = LocalImmersionMotionState.current?:rememberImmersionMotionEnvironment()

@Composable internal fun rememberImmersionMotionEnvironment():Boolean {
    val context=LocalContext.current
    val lifecycle=remember(context){var c=context;while(c is ContextWrapper&&c !is Activity)c=c.baseContext;(c as? LifecycleOwner)?.lifecycle}
    var resumed by remember(lifecycle){mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true)}
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,_->resumed=lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true};lifecycle?.addObserver(observer);onDispose{lifecycle?.removeObserver(observer)}}
    val accessibility=remember(context){context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager}
    var exploration by remember(accessibility){mutableStateOf(accessibility.isTouchExplorationEnabled)}
    DisposableEffect(accessibility){val listener=AccessibilityManager.TouchExplorationStateChangeListener{exploration=it};accessibility.addTouchExplorationStateChangeListener(listener);onDispose{accessibility.removeTouchExplorationStateChangeListener(listener)}}
    // Editable input remains source text. An open IME must not freeze the rest of the screen.
    return PocketImmersion.motionEnabled&&resumed&&!exploration
}

internal data class ImmersionControlPhase(val original:Boolean,val remainingMs:Long)
internal fun immersionControlPhase(nowMs:Long):ImmersionControlPhase {
    val at=Math.floorMod(nowMs,24000L)
    return if(at<18000)ImmersionControlPhase(false,18000-at)else ImmersionControlPhase(true,24000-at)
}

/** Shared monotonic control epoch; no periodic global recomposition or provider work. */
internal object ImmersionControlWave {
    private val epoch=SystemClock.elapsedRealtime()
    fun elapsedMs()=SystemClock.elapsedRealtime()-epoch
}
/** Top-to-bottom rows, then the locale's reading direction within each row. */
internal fun immersionControlWaveOffset(vertical:Float,horizontal:Float,rtl:Boolean):Long {
    val across=if(rtl)1f-horizontal else horizontal
    return (vertical.coerceIn(0f,1f)*1600f+across.coerceIn(0f,1f)*200f).toLong()
}
