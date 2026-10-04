package co.fallsoft.pocket

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Visual squeeze/stretch stays inside the original, unchanged touch target. */
fun Modifier.pressMotion(enabled: Boolean = true): Modifier = composed {
    val motion = motionAllowed()
    var pressed by remember { mutableStateOf(false) }
    val x by animateFloatAsState(if (pressed && motion && enabled) .94f else 1f, spring(dampingRatio = .52f, stiffness = 550f), label = "pressWidth")
    val y by animateFloatAsState(if (pressed && motion && enabled) .89f else 1f, spring(dampingRatio = .52f, stiffness = 550f), label = "pressHeight")
    this.drawWithContent { scale(x, y) { this@drawWithContent.drawContent() } }.pointerInput(enabled, motion) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            pressed = enabled && motion
            try { waitForUpOrCancellation(pass = PointerEventPass.Initial) } finally { pressed = false }
        }
    }
}
