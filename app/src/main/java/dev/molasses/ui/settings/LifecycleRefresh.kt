package dev.molasses.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Re-runs [onResume] every time the screen comes back to the foreground.
 *
 * The onboarding checklist depends on state that only changes on system
 * Settings screens, so the only moment it can be correct is on return from
 * one. Reading it once at composition would leave the checklist permanently
 * stale after the user grants anything.
 */
@Composable
fun LifecycleRefresh(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
