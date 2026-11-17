package net.af0.where

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * MapLibre anchors marker icons at their center, so the pin's tip must be at the bitmap's
 * vertical center, with nothing drawn below it (#482).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = TestWhereApplication::class)
class PeerMarkerBitmapTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun Bitmap.lowestOpaqueRow(): Int =
        (height - 1 downTo 0).first { y -> (0 until width).any { x -> Color.alpha(getPixel(x, y)) > 0 } }

    private fun assertTipAtCenter(bmp: Bitmap) {
        val tip = bmp.lowestOpaqueRow()
        // The tip is a point, so antialiasing may end it a couple of pixels short.
        assertTrue(abs(tip - bmp.height / 2) <= 3, "tip at row $tip of ${bmp.height}; want the center")
        // And it's the pin's tip, centered horizontally.
        val tipXs = (0 until bmp.width).filter { x -> Color.alpha(bmp.getPixel(x, tip)) > 0 }
        assertTrue(abs(tipXs.average() - bmp.width / 2.0) <= 2, "tip at x=${tipXs.average()} of ${bmp.width}")
    }

    @Test
    fun tipIsAtTheCenter() = assertTipAtCenter(peerMarkerBitmap(context, "Alice", null, 1f))

    @Test
    fun tipIsAtTheCenterWithASubtitleAndALongName() =
        assertTipAtCenter(peerMarkerBitmap(context, "A rather long friend name", "5 min ago", 1f))
}
