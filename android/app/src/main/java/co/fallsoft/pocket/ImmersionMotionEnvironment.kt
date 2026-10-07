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
import kotlinx.coroutines.delay

/** Text motion yields to direct interaction; ordinary action hit regions never change. */
internal object ImmersionInteraction {
    var touching by mutableStateOf(false); private set
    var quietUntil by mutableLongStateOf(0); private set
    fun touch(action:Int){
        when(action){
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE -> touching=true
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {touching=false;quietUntil=SystemClock.elapsedRealtime()+1600}
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
    val touching=ImmersionInteraction.touching
    val until=ImmersionInteraction.quietUntil
    var quiet by remember{mutableStateOf(false)}
    LaunchedEffect(touching,until){quiet=false;if(!touching){delay((until-SystemClock.elapsedRealtime()).coerceAtLeast(0));quiet=true}}
    // Editable input remains source text. An open IME must not freeze the rest of the screen.
    return PocketImmersion.motionEnabled&&resumed&&!exploration&&!touching&&quiet
}

internal data class ImmersionControlPhase(val original:Boolean,val remainingMs:Long)
internal fun immersionControlPhase(nowMs:Long):ImmersionControlPhase {
    val at=Math.floorMod(nowMs,24000L)
    return if(at<18000)ImmersionControlPhase(false,18000-at)else ImmersionControlPhase(true,24000-at)
}
