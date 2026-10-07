package co.fallsoft.pocket

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

internal object BlackoutVisibility { var active by mutableStateOf(false) }

/** Saves only the window settings this mode changes; never changes system brightness. */
internal class BlackoutWindowSession {
    data class Snapshot(val brightness: Float, val keepScreenOn: Boolean)
    var snapshot: Snapshot? = null
        private set
    fun enter(brightness: Float, keepScreenOn: Boolean): Boolean {
        if (snapshot != null) return false
        snapshot = Snapshot(brightness, keepScreenOn)
        return true
    }
    fun exit(): Snapshot? = snapshot.also { snapshot = null }
}

/** Landlock's black overlay approach: hide pixels, keep the underlying work alive. */
class ScreenBlackout(private val activity: Activity) {
    private val session = BlackoutWindowSession()
    private var overlay: View? = null
    private var backCallback: OnBackPressedCallback? = null
    private val accessibilityBefore = mutableMapOf<View, Int>()
    private var priorBarBehavior = 0
    private var priorStatusVisible = true
    private var priorNavigationVisible = true
    private var priorImeVisible = false
    private var priorInputFocus: View? = null
    val active: Boolean get() = session.snapshot != null

    fun enter() {
        val window = activity.window
        val attributes = window.attributes
        if (!session.enter(attributes.screenBrightness,
                attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)) return
        BlackoutVisibility.active = true
        val decor = window.decorView as ViewGroup
        val bars = WindowCompat.getInsetsController(window, decor)
        priorBarBehavior = bars.systemBarsBehavior
        priorInputFocus = activity.currentFocus
        priorImeVisible = false
        ViewCompat.getRootWindowInsets(decor)?.let {
            priorStatusVisible = it.isVisible(WindowInsetsCompat.Type.statusBars())
            priorNavigationVisible = it.isVisible(WindowInsetsCompat.Type.navigationBars())
            priorImeVisible = it.isVisible(WindowInsetsCompat.Type.ime())
        }
        WindowCompat.getInsetsController(window, decor).hide(WindowInsetsCompat.Type.ime())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0f }
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        val surface = View(activity).apply {
            setBackgroundColor(Color.BLACK)
            contentDescription = "Blackout active. Tap to return to NextComp."
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = true
            isClickable = true
            keepScreenOn = true
            setOnClickListener { exit() }
        }
        for (index in 0 until decor.childCount) {
            val child = decor.getChildAt(index)
            accessibilityBefore[child] = child.importantForAccessibility
            child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        overlay = surface
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exit() }
        }.also { callback -> (activity as? ComponentActivity)?.onBackPressedDispatcher?.addCallback(callback) }
        decor.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        surface.requestFocus()
        surface.announceForAccessibility(surface.contentDescription)
    }

    fun exit(restoreInput: Boolean = true) {
        val saved = session.exit() ?: return
        BlackoutVisibility.active = false
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
        backCallback?.remove()
        backCallback = null
        accessibilityBefore.forEach { (view, importance) -> view.importantForAccessibility = importance }
        accessibilityBefore.clear()
        val window = activity.window
        window.attributes = window.attributes.apply { screenBrightness = saved.brightness }
        if (!saved.keepScreenOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = priorBarBehavior
        if (priorStatusVisible) bars.show(WindowInsetsCompat.Type.statusBars()) else bars.hide(WindowInsetsCompat.Type.statusBars())
        if (priorNavigationVisible) bars.show(WindowInsetsCompat.Type.navigationBars()) else bars.hide(WindowInsetsCompat.Type.navigationBars())
        if (restoreInput) priorInputFocus?.requestFocus()
        priorInputFocus = null
        if (restoreInput && priorImeVisible) bars.show(WindowInsetsCompat.Type.ime())
    }
}

internal fun Context.nextCompActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.nextCompActivity()
    else -> null
}
