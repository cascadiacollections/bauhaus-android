package com.cascadiacollections.bauhaus.ui

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cascadiacollections.bauhaus.R
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for [SettingsScreen].
 *
 * Uses the stateless [SettingsScreen] overload so tests run entirely in-process
 * without a real [BauhausViewModel], DataStore, or network calls.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val defaultState = UiState()

    /** Resolves a string resource from the app under test at runtime, ensuring
     *  tests are not coupled to any particular English copy. */
    private fun getString(resId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    companion object {
        private const val TEST_ERROR_MESSAGE = "Failed to set wallpaper"
        private val TEST_METADATA = ArtworkMetadata(
            title = "Composition VIII",
            artist = "Wassily Kandinsky",
        )
    }

    // ── Test 1: Preview ───────────────────────────────────────────────────────

    /**
     * The artwork preview card must always be present in the composition.
     * The node is located by its semantic testTag so the test is not coupled
     * to the locale-specific content description text.
     */
    @Test
    fun settingsScreen_artworkPreview_isDisplayed() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState,
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
            )
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.ARTWORK_PREVIEW)
            .assertIsDisplayed()
    }

    // ── Test 2: Segmented button ──────────────────────────────────────────────

    /**
     * The segmented button row must reflect the selected [WallpaperTarget] from
     * the current [UiState].
     */
    @Test
    fun settingsScreen_segmentedButton_reflectsWallpaperTarget() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(wallpaperTarget = WallpaperTarget.HOME),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
            )
        }

        composeTestRule.onNodeWithText("Home").assertIsSelected()
        composeTestRule.onNodeWithText("Lock").assertIsNotSelected()
        composeTestRule.onNodeWithText("Both").assertIsNotSelected()
    }

    /**
     * Tapping a segmented button must invoke [onWallpaperTargetChange] with the
     * tapped [WallpaperTarget].
     */
    @Test
    fun settingsScreen_segmentedButton_invokesCallbackOnTap() {
        var capturedTarget: WallpaperTarget? = null

        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(wallpaperTarget = WallpaperTarget.BOTH),
                onWallpaperTargetChange = { capturedTarget = it },
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
            )
        }

        composeTestRule.onNodeWithText("Lock").performClick()

        assertEquals(WallpaperTarget.LOCK, capturedTarget)
    }

    // ── Test 3: Daily updates toggle ──────────────────────────────────────────

    /**
     * The daily-updates switch must reflect the [UiState.schedulingEnabled] flag.
     * The node is located by its semantic testTag to avoid ambiguity with the
     * segmented buttons, which are also toggleable.
     */
    @Test
    fun settingsScreen_dailyUpdatesSwitch_reflectsSchedulingState() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(schedulingEnabled = false),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
            )
        }

        composeTestRule.onNodeWithTag(SettingsScreenTestTags.DAILY_UPDATES_SWITCH).assertIsOff()
    }

    /**
     * Toggling the daily-updates switch must invoke [onSchedulingToggle] with the
     * new boolean value.
     */
    @Test
    fun settingsScreen_dailyUpdatesSwitch_invokesCallbackOnToggle() {
        var capturedEnabled: Boolean? = null

        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(schedulingEnabled = true),
                onWallpaperTargetChange = {},
                onSchedulingToggle = { capturedEnabled = it },
                onSetWallpaperNow = {},
            )
        }

        composeTestRule.onNodeWithTag(SettingsScreenTestTags.DAILY_UPDATES_SWITCH).assertIsOn()
        composeTestRule.onNodeWithTag(SettingsScreenTestTags.DAILY_UPDATES_SWITCH).performClick()

        assertNotNull("onSchedulingToggle callback should have been invoked", capturedEnabled)
        assertFalse("Callback should be called with false when toggling off", capturedEnabled!!)
    }

    // ── Test 4: Set Now button – loading state ────────────────────────────────

    /**
     * While [UiState.isSettingWallpaper] is `true` the button must be disabled
     * and its text label must be replaced by the loading indicator.
     */
    @Test
    fun settingsScreen_setNowButton_showsLoadingStateWhenSettingWallpaper() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(isSettingWallpaper = true),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
            )
        }

        // Button is present but disabled while loading
        composeTestRule.onNodeWithTag(SettingsScreenTestTags.SET_NOW_BUTTON).assertIsNotEnabled()
        // The text label is replaced by a CircularProgressIndicator
        composeTestRule.onNodeWithText(getString(R.string.set_now)).assertDoesNotExist()
    }

    /**
     * When not loading the "Set Wallpaper Now" button must be visible, enabled,
     * and invoke the callback on click.
     */
    @Test
    fun settingsScreen_setNowButton_isEnabledWhenNotLoading() {
        var callbackInvoked = false

        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(isSettingWallpaper = false),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = { callbackInvoked = true },
            )
        }

        composeTestRule.onNodeWithText(getString(R.string.set_now)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SettingsScreenTestTags.SET_NOW_BUTTON).performClick()

        assertTrue("onSetWallpaperNow callback should be invoked on button click", callbackInvoked)
    }

    // ── Test 5: Error state ───────────────────────────────────────────────────

    /**
     * When [UiState.error] is non-null the error message must be visible.
     */
    @Test
    fun settingsScreen_displaysErrorMessage_whenErrorStateIsSet() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(error = TEST_ERROR_MESSAGE),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
            )
        }

        composeTestRule.onNodeWithText(TEST_ERROR_MESSAGE).assertIsDisplayed()
    }

    /**
     * When [UiState.error] is null no error text must be shown.
     */
    @Test
    fun settingsScreen_doesNotDisplayErrorMessage_whenErrorStateIsNull() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(error = null),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
            )
        }

        composeTestRule.onNodeWithText(TEST_ERROR_MESSAGE).assertDoesNotExist()
    }

    // ── Test 6: Metadata display ──────────────────────────────────────────────

    /**
     * When [UiState.metadata] is non-null both the artwork title and the artist
     * name must be rendered.
     */
    @Test
    fun settingsScreen_displaysMetadata_whenMetadataIsAvailable() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(metadata = TEST_METADATA),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
            )
        }

        composeTestRule.onNodeWithText(TEST_METADATA.title).assertIsDisplayed()
        composeTestRule.onNodeWithText(TEST_METADATA.artist).assertIsDisplayed()
    }

    /**
     * When [UiState.metadata] is null neither title nor artist text must be shown.
     */
    @Test
    fun settingsScreen_doesNotDisplayMetadata_whenMetadataIsNull() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(metadata = null),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
            )
        }

        composeTestRule.onNodeWithText(TEST_METADATA.title).assertDoesNotExist()
        composeTestRule.onNodeWithText(TEST_METADATA.artist).assertDoesNotExist()
    }
}
