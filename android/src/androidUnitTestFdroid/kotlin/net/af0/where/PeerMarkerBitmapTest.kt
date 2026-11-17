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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    private fun Bitmap.lowestOpaqueRow(): Int? =
        (height - 1 downTo 0).firstOrNull { y -> (0 until width).any { x -> Color.alpha(getPixel(x, y)) > 0 } }

    private fun assertTipAtCenter(bmp: Bitmap) {
        val tip = assertNotNull(bmp.lowestOpaqueRow(), "nothing rendered; native graphics unavailable?")
        // The tip is a point, so antialiasing may end it a couple of pixels short.
        assertTrue(abs(tip - bmp.height / 2) <= 3, "tip at row $tip of ${bmp.height}; want the center")
        // And it's the pin's tip, centered horizontally.
        val tipXs = (0 until bmp.width).filter { x -> Color.alpha(bmp.getPixel(x, tip)) > 0 }
        assertTrue(abs(tipXs.average() - bmp.width / 2.0) <= 2, "tip at x=${tipXs.average()} of ${bmp.width}")
        // The pin's red, not the end of the label box.
        val px = bmp.getPixel(bmp.width / 2, tip - 2)
        assertEquals(Triple(176, 0, 32), Triple(Color.red(px), Color.green(px), Color.blue(px)), "pin color at the tip")
    }

    private fun pin(
        id: String,
        tipX: Float,
        tipY: Float,
    ) = PinOnScreen(id, tipX, tipY, width = 60, height = 200)

    @Test
    fun aTapOnAPinOrItsLabelPicksIt() {
        val pins = listOf(pin("a", 500f, 500f))
        assertEquals("a", pinAt(500f, 495f, pins), "just above the tip")
        assertEquals("a", pinAt(480f, 405f, pins), "on the label, at the top")
    }

    @Test
    fun aTapInThePaddingBelowATipIsEmptyMap() {
        val pins = listOf(pin("a", 500f, 500f))
        assertNull(pinAt(500f, 520f, pins))
        assertNull(pinAt(500f, 599f, pins))
    }

    @Test
    fun stackedPinsResolveToTheOneDrawnUnderTheTap() {
        // "north" is drawn later, on top; its padding covers "south"'s pin.
        val pins = listOf(pin("south", 500f, 580f), pin("north", 500f, 500f))
        assertEquals("south", pinAt(500f, 560f, pins), "below north's tip: south's pin")
        assertEquals("north", pinAt(500f, 490f, pins), "where both are drawn, the one on top")
    }

    @Test
    fun tipIsAtTheCenter() = assertTipAtCenter(peerMarkerBitmap(context, "Alice", null, 1f))

    @Test
    fun tipIsAtTheCenterWithASubtitleAndALongName() =
        assertTipAtCenter(peerMarkerBitmap(context, "A rather long friend name", "5 min ago", 1f))
}
