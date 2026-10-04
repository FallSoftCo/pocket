package co.fallsoft.pocket

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keeps footer controls inside the straight portion of a rounded display. */
@Composable
fun deviceCornerInset(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    var radius by remember(view) { mutableIntStateOf(0) }
    DisposableEffect(view) {
        fun update() {
            radius = if (Build.VERSION.SDK_INT >= 31) {
                val insets = view.rootWindowInsets
                maxOf(
                    insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0,
                    insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0,
                )
            } else 0
        }
        // Re-read after rotation, folding, or a window size change.
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        view.addOnLayoutChangeListener(listener)
        update()
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }
    return with(density) { radius.toDp().coerceAtLeast(24.dp) }
}
