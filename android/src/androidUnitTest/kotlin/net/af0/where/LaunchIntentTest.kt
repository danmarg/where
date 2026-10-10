package net.af0.where

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An invite deep link must be handled once, not again on recreation or relaunch from Recents. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class LaunchIntentTest {
    private fun invite() = Intent(Intent.ACTION_VIEW, Uri.parse("https://where.af0.net/invite#abc"))

    @Test
    fun freshLaunchIsHandled() {
        assertTrue(shouldHandleLaunchIntent(null, invite()))
    }

    @Test
    fun recreationIsNotHandledAgain() {
        assertFalse(shouldHandleLaunchIntent(Bundle(), invite()))
    }

    @Test
    fun relaunchFromRecentsIsNotHandledAgain() {
        val intent = invite().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertFalse(shouldHandleLaunchIntent(null, intent))
    }
}
