package net.af0.where

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import org.maplibre.android.maps.MapView

internal class MapLifecycleObserver(private val getMapView: () -> MapView?) : LifecycleEventObserver {
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        val mv = getMapView() ?: return
        when (event) {
            Lifecycle.Event.ON_START -> mv.onStart()
            Lifecycle.Event.ON_RESUME -> mv.onResume()
            Lifecycle.Event.ON_PAUSE -> mv.onPause()
            Lifecycle.Event.ON_STOP -> mv.onStop()
            Lifecycle.Event.ON_DESTROY -> mv.onDestroy()
            else -> Unit
        }
    }
}
