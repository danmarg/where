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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The peer layer anchors icons at their bottom-center (ICON_ANCHOR_BOTTOM), so the pin's tip must
 * be the bitmap's bottom-center (#482).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = TestWhereApplication::class)
class PeerMarkerBitmapTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun Bitmap.lowestOpaqueRow(): Int? =
        (height - 1 downTo 0).firstOrNull { y -> (0 until width).any { x -> Color.alpha(getPixel(x, y)) > 0 } }

    private fun assertTipAtBottomCenter(bmp: Bitmap) {
        val tip = assertNotNull(bmp.lowestOpaqueRow(), "nothing rendered; native graphics unavailable?")
        // The tip is a point, so antialiasing may end it a couple of pixels short.
        assertTrue(bmp.height - 1 - tip <= 3, "tip at row $tip of ${bmp.height}; want the bottom")
        // And it's the pin's tip, centered horizontally.
        val tipXs = (0 until bmp.width).filter { x -> Color.alpha(bmp.getPixel(x, tip)) > 0 }
        assertTrue(abs(tipXs.average() - bmp.width / 2.0) <= 2, "tip at x=${tipXs.average()} of ${bmp.width}")
        // The pin's red, not the end of the label box.
        // A few rows up, inside the triangle; a tolerance for antialiasing and premultiply rounding.
        val px = bmp.getPixel(bmp.width / 2, tip - 4)
        assertTrue(
            abs(Color.red(px) - 176) <= 3 && Color.green(px) <= 3 && abs(Color.blue(px) - 32) <= 3,
            "pin color near the tip: ${Integer.toHexString(px)}",
        )
    }

    @Test
    fun tipIsAtTheBottomCenter() = assertTipAtBottomCenter(peerMarkerBitmap(context, "Alice", null, 1f))

    @Test
    fun tipIsAtTheBottomCenterWithASubtitleAndALongName() =
        assertTipAtBottomCenter(peerMarkerBitmap(context, "A rather long friend name", "5 min ago", 1f))
}
