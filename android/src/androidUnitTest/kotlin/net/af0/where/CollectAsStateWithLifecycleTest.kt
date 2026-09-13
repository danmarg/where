package net.af0.where

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for MainActivity's switch from collectAsState() to
 * collectAsStateWithLifecycle(): verifies the actual property that switch buys - collection
 * pausing below STARTED and resuming above it - rather than just asserting the code compiles.
 *
 * A fake LifecycleOwner is provided via CompositionLocalProvider rather than driving the real
 * hosting Activity's lifecycle: moving TestActivity itself through CREATED under Robolectric
 * tears down the whole compose root (verified while writing this test - it isn't just a pause),
 * which would test Robolectric's activity-recreation behavior rather than the collection
 * pause/resume behavior this PR actually relies on.
 *
 * MainActivity itself isn't driven directly here: its ViewModel/permission wiring would make
 * this a much heavier integration test for no added signal, since every one of its ~19 call
 * sites uses the exact same collectAsStateWithLifecycle() API exercised below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CollectAsStateWithLifecycleTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<TestActivity>()

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun collection_pausesBelowStarted_andResumesAboveIt() {
        val flow = MutableStateFlow("initial")
        val lifecycleOwner = FakeLifecycleOwner()
        composeTestRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                val value by flow.collectAsStateWithLifecycle()
                Text(text = value)
            }
        }

        composeTestRule.onNodeWithText("initial").assertExists()

        // Update while STARTED: must be reflected.
        flow.value = "started-update"
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("started-update").assertExists()

        // Drop below STARTED: further updates must NOT be collected.
        composeTestRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        }
        flow.value = "update-while-stopped"
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("started-update").assertExists()
        composeTestRule.onNodeWithText("update-while-stopped").assertDoesNotExist()

        // Return to STARTED: collection resumes and picks up the latest emission.
        composeTestRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("update-while-stopped").assertExists()
    }
}
