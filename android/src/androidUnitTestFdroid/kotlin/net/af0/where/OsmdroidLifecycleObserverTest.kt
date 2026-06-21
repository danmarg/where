package net.af0.where

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.views.MapView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class OsmdroidLifecycleObserverTest {
    private val mockOwner = mockk<LifecycleOwner>(relaxed = true)

    @Test
    fun onResume_callsMapViewOnResume() {
        val mapView = mockk<MapView>(relaxed = true)
        val observer = OsmdroidLifecycleObserver { mapView }

        observer.onStateChanged(mockOwner, Lifecycle.Event.ON_RESUME)

        verify(exactly = 1) { mapView.onResume() }
        verify(exactly = 0) { mapView.onPause() }
    }

    @Test
    fun onPause_callsMapViewOnPause() {
        val mapView = mockk<MapView>(relaxed = true)
        val observer = OsmdroidLifecycleObserver { mapView }

        observer.onStateChanged(mockOwner, Lifecycle.Event.ON_PAUSE)

        verify(exactly = 1) { mapView.onPause() }
        verify(exactly = 0) { mapView.onResume() }
    }

    @Test
    fun resumeThenPause_callsBothInOrder() {
        val mapView = mockk<MapView>(relaxed = true)
        val observer = OsmdroidLifecycleObserver { mapView }

        observer.onStateChanged(mockOwner, Lifecycle.Event.ON_RESUME)
        observer.onStateChanged(mockOwner, Lifecycle.Event.ON_PAUSE)

        verify(exactly = 1) { mapView.onResume() }
        verify(exactly = 1) { mapView.onPause() }
    }

    @Test
    fun otherLifecycleEvents_areIgnored() {
        val mapView = mockk<MapView>(relaxed = true)
        val observer = OsmdroidLifecycleObserver { mapView }

        listOf(
            Lifecycle.Event.ON_CREATE,
            Lifecycle.Event.ON_START,
            Lifecycle.Event.ON_STOP,
            Lifecycle.Event.ON_DESTROY,
        ).forEach { observer.onStateChanged(mockOwner, it) }

        verify(exactly = 0) { mapView.onResume() }
        verify(exactly = 0) { mapView.onPause() }
    }

    @Test
    fun nullMapView_doesNotThrow() {
        val observer = OsmdroidLifecycleObserver { null }
        // Should not throw even with no MapView available yet.
        observer.onStateChanged(mockOwner, Lifecycle.Event.ON_RESUME)
        observer.onStateChanged(mockOwner, Lifecycle.Event.ON_PAUSE)
    }
}
