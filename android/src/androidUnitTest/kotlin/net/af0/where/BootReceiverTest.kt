package net.af0.where

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class BootReceiverTest {
    @Test
    fun testOnReceiveStartsService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receiver = BootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        receiver.onReceive(context, intent)

        val nextIntent = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(nextIntent)
        assertEquals(LocationService::class.java.name, nextIntent.component?.className)
    }

    @Test
    fun testOnReceiveRespectsSharingPreference() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        UserPrefs.setSharing(context, false)

        val receiver = BootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        receiver.onReceive(context, intent)

        val nextIntent = shadowOf(context as android.app.Application).nextStartedService
        assertEquals(null, nextIntent, "Should not start service when sharing is disabled")
    }

    @Test
    fun testOnReceiveIgnoresOtherActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receiver = BootReceiver()
        val intent = Intent(Intent.ACTION_ANSWER)

        receiver.onReceive(context, intent)

        val nextIntent = shadowOf(context as android.app.Application).nextStartedService
        assertEquals(null, nextIntent)
    }
}
