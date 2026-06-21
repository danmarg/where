package net.af0.where

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import org.osmdroid.views.MapView

internal class OsmdroidLifecycleObserver(private val getMapView: () -> MapView?) : LifecycleEventObserver {
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        val mv = getMapView() ?: return
        when (event) {
            Lifecycle.Event.ON_RESUME -> mv.onResume()
            Lifecycle.Event.ON_PAUSE -> mv.onPause()
            else -> Unit
        }
    }
}
