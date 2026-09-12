package dev.molasses.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Hosts Compose content in a `WindowManager` window.
 *
 * ## Why not an Activity
 * The movement gate must appear over Instagram from a service with no user
 * interaction to point at. Background activity launch restrictions from API 29
 * on drop exactly that `startActivity` call -- silently, with only a logcat
 * line. A second `TYPE_ACCESSIBILITY_OVERLAY` window always appears.
 *
 * ## Why this class has to exist
 * `ComposeView` refuses to compose unless it can find a `LifecycleOwner`, a
 * `ViewModelStoreOwner` and a `SavedStateRegistryOwner` in its view tree. An
 * Activity supplies all three; a raw `WindowManager` window supplies none, so
 * this provides them. The `performRestore(null)` call is required before the
 * registry is usable and is the step most implementations miss -- without it
 * the composition throws on first frame.
 */
class OverlayHost(
    private val context: Context,
    private val windowManager: WindowManager,
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    private var composeView: ComposeView? = null
    private var destroyed = false
    var isShowing: Boolean = false
        private set

    /**
     * Gate window params. Unlike the stall sink this window is **focusable**,
     * so it consumes the back key rather than letting it dismiss the gate.
     */
    private fun gateParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        @Suppress("DEPRECATION")
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    /**
     * Idempotent while showing (replaces the content). Refused after
     * [dismiss] -- see "Single use" above.
     */
    fun show(content: @Composable () -> Unit) {
        if (destroyed) {
            Log.w(TAG, "show() on a dismissed host; build a new OverlayHost instead")
            return
        }
        if (isShowing) {
            composeView?.setContent(content)
            return
        }

        val view = ComposeView(context)
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED

        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        view.setContent(content)

        try {
            windowManager.addView(view, gateParams())
        } catch (e: Exception) {
            Log.w(TAG, "gate addView failed", e)
            registry.currentState = Lifecycle.State.DESTROYED
            destroyed = true
            return
        }

        composeView = view
        isShowing = true
        registry.currentState = Lifecycle.State.RESUMED
    }

    /** Idempotent and exception-safe. Terminal: the host cannot be reshown. */
    fun dismiss() {
        if (destroyed) return
        val view = composeView
        composeView = null
        isShowing = false
        destroyed = true

        // DESTROYED before removeView so the composition disposes while the
        // view is still attached; the reverse order leaks the recomposer.
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()

        if (view == null) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: Exception) {
            Log.w(TAG, "gate removeView failed", e)
        }
    }

    private companion object { const val TAG = "Molasses.OverlayHost" }
}
