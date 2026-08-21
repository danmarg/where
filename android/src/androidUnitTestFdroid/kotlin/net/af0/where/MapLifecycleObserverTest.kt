package net.af0.where

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class MapLifecycleObserverTest {
    private val mockOwner = mockk<LifecycleOwner>(relaxed = true)

    @Test
    fun onStart_callsMapViewOnStart() {
        val mapView = mockk<MapView>(relaxed = true)
        val observer = MapLifecycleObserver { mapView }

        observer.onStateChanged(mockOwner, Lifecycle.Event.ON_START)

        verify(exactly = 1) { mapView.onStart() }
    }

    @Test
    fun onResume_callsMapViewOnResume() {
        val mapView = mockk<MapView>(relaxed = true)
        val observer = MapLifecycleObserver { mapView }

        observer.onStateChanged(mockOwner, Lifecycle.Event.ON_RESUME)

        verify(exactly = 1) { mapView.onResume() }
        verify(exactly = 0) { mapView.onPause() }
    }

    @Test
    fun onPause_callsMapViewOnPause() {
        val mapView = mockk<MapView>(relaxed = true)
        val observer = MapLifecycleObserver { mapView }

        observer.onStateChanged(mockOwner, Lifecycle.Event.ON_PAUSE)

        verify(exactly = 1) { mapView.onPause() }
        verify(exactly = 0) { mapView.onResume() }
    }

    @Test
    fun onStop_callsMapViewOnStop() {
        val mapView = mockk<MapView>(relaxed = true)
        val observer = MapLifecycleObserver { mapView }

        observer.onStateChanged(mockOwner, Lifecycle.Event.ON_STOP)

        verify(exactly = 1) { mapView.onStop() }
    }

    @Test
    fun onDestroy_callsMapViewOnDestroy() {
        val mapView = mockk<MapView>(relaxed = true)
        val observer = MapLifecycleObserver { mapView }

        observer.onStateChanged(mockOwner, Lifecycle.Event.ON_DESTROY)

        verify(exactly = 1) { mapView.onDestroy() }
    }

    @Test
    fun fullLifecycle_callsInOrder() {
        val mapView = mockk<MapView>(relaxed = true)
        val observer = MapLifecycleObserver { mapView }

        listOf(
            Lifecycle.Event.ON_START,
            Lifecycle.Event.ON_RESUME,
            Lifecycle.Event.ON_PAUSE,
            Lifecycle.Event.ON_STOP,
            Lifecycle.Event.ON_DESTROY,
        ).forEach { observer.onStateChanged(mockOwner, it) }

        verify(exactly = 1) { mapView.onStart() }
        verify(exactly = 1) { mapView.onResume() }
        verify(exactly = 1) { mapView.onPause() }
        verify(exactly = 1) { mapView.onStop() }
        verify(exactly = 1) { mapView.onDestroy() }
    }

    @Test
    fun onCreate_isIgnored() {
        val mapView = mockk<MapView>(relaxed = true)
        val observer = MapLifecycleObserver { mapView }

        observer.onStateChanged(mockOwner, Lifecycle.Event.ON_CREATE)

        verify(exactly = 0) { mapView.onStart() }
        verify(exactly = 0) { mapView.onResume() }
    }

    @Test
    fun nullMapView_doesNotThrow() {
        val observer = MapLifecycleObserver { null }
        observer.onStateChanged(mockOwner, Lifecycle.Event.ON_RESUME)
        observer.onStateChanged(mockOwner, Lifecycle.Event.ON_PAUSE)
        observer.onStateChanged(mockOwner, Lifecycle.Event.ON_DESTROY)
    }
}
