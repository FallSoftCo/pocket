package co.fallsoft.pocket

import android.app.Activity
import android.content.ContextWrapper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay

@Composable internal fun motionAllowed(): Boolean {
    val context = LocalContext.current
    val owner = remember(context) {
        var c = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        c as? LifecycleOwner
    }
    var allowed by remember { mutableStateOf(false) }
    DisposableEffect(owner, context) {
        fun update() { allowed = owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
        val observer = LifecycleEventObserver { _, _ -> update() }
        owner?.lifecycle?.addObserver(observer); update()
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    return allowed
}

/** Native rotation frames played at a calm6fps, with no per-frame decoding or layout movement. */
@Composable fun AnimatedMark(size: Int = 44, modifier: Modifier = Modifier) {
    val sheet = ImageBitmap.imageResource(R.drawable.nextcomp_motion)
    val animated = motionAllowed()
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(animated) {
        frame = 0
        if (animated) while (true) { delay(166); frame = (frame + 1) % 16 }
    }
    Canvas(modifier.size(size.dp).semantics { contentDescription = "NextComp" }) {
        drawImage(sheet, srcOffset = IntOffset(frame * 256, 0), srcSize = IntSize(256, 256), dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()))
    }
}
