package co.fallsoft.pocket

import android.app.Activity
import android.content.ContextWrapper
import android.provider.Settings
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

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
        val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { update() }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, settingsObserver)
        owner?.lifecycle?.addObserver(observer); update()
        onDispose { owner?.lifecycle?.removeObserver(observer); context.contentResolver.unregisterContentObserver(settingsObserver) }
    }
    return allowed
}

/** Native rotation frames sampled on the display clock; paused outside the resumed UI. */
@Composable fun AnimatedMark(size: Int = 44, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animated = motionAllowed()
    val cell = if (size * LocalDensity.current.density <= 128f) 128 else 256
    val atlas = remember(cell) { RenderedAtlas(if (cell == 128) "nextcomp_motion_small" else "nextcomp_motion", 160, cell, 8, 32) }
    val playback = rememberRenderedPlayback(context, atlas, animated)
    val fallback = ImageBitmap.imageResource(R.drawable.nextcomp_motion)
    Canvas(modifier.size(size.dp).semantics { contentDescription = "NextComp" }) {
        val frame = playback.frame.intValue
        val sheet = if (animated) playback.images[frame / atlas.pageFrames] else null
        if (sheet != null) {
            val local = frame % atlas.pageFrames
            drawImage(sheet, srcOffset = IntOffset(local % atlas.columns * atlas.cell,
                local / atlas.columns * atlas.cell), srcSize = IntSize(atlas.cell, atlas.cell),
                dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()))
        } else drawImage(fallback, srcOffset = IntOffset.Zero, srcSize = IntSize(256, 256),
            dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()))
    }
}
