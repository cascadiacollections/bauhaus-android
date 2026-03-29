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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
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
        private val TEST_METADATA = ArtworkMetadata(
            title = "Composition VIII",
            artist = "Wassily Kandinsky",
        )
    }

    // ── Test 1: Preview ───────────────────────────────────────────────────────

    @Test
    fun settingsScreen_artworkPreview_isDisplayed() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState,
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onRefresh = {},
            )
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.ARTWORK_PREVIEW)
            .assertIsDisplayed()
    }

    // ── Test 2: Segmented button ──────────────────────────────────────────────

    @Test
    fun settingsScreen_segmentedButton_reflectsWallpaperTarget() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(wallpaperTarget = WallpaperTarget.HOME),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithText("Home").assertIsSelected()
        composeTestRule.onNodeWithText("Lock").assertIsNotSelected()
        composeTestRule.onNodeWithText("Both").assertIsNotSelected()
    }

    @Test
    fun settingsScreen_segmentedButton_invokesCallbackOnTap() {
        var capturedTarget: WallpaperTarget? = null

        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(wallpaperTarget = WallpaperTarget.BOTH),
                onWallpaperTargetChange = { capturedTarget = it },
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithText("Lock").performClick()

        assertEquals(WallpaperTarget.LOCK, capturedTarget)
    }

    // ── Test 3: Daily updates toggle ──────────────────────────────────────────

    @Test
    fun settingsScreen_dailyUpdatesSwitch_reflectsSchedulingState() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(schedulingEnabled = false),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithTag(SettingsScreenTestTags.DAILY_UPDATES_SWITCH).assertIsOff()
    }

    @Test
    fun settingsScreen_dailyUpdatesSwitch_invokesCallbackOnToggle() {
        var capturedEnabled: Boolean? = null

        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(schedulingEnabled = true),
                onWallpaperTargetChange = {},
                onSchedulingToggle = { capturedEnabled = it },
                onSetWallpaperNow = {},
                onSaveImage = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithTag(SettingsScreenTestTags.DAILY_UPDATES_SWITCH).assertIsOn()
        composeTestRule.onNodeWithTag(SettingsScreenTestTags.DAILY_UPDATES_SWITCH).performClick()

        assertNotNull("onSchedulingToggle callback should have been invoked", capturedEnabled)
        assertFalse("Callback should be called with false when toggling off", capturedEnabled!!)
    }

    // ── Test 4: Set Now button ────────────────────────────────────────────────

    @Test
    fun settingsScreen_setNowButton_showsLoadingStateWhenSettingWallpaper() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(isSettingWallpaper = true),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithTag(SettingsScreenTestTags.SET_NOW_BUTTON).assertIsNotEnabled()
        composeTestRule.onNodeWithText(getString(R.string.set_now)).assertIsDisplayed()
    }

    @Test
    fun settingsScreen_setNowButton_isEnabledWhenNotLoading() {
        var callbackInvoked = false

        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(isSettingWallpaper = false),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = { callbackInvoked = true },
                onSaveImage = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithText(getString(R.string.set_now)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SettingsScreenTestTags.SET_NOW_BUTTON).performClick()

        assertTrue("onSetWallpaperNow callback should be invoked on button click", callbackInvoked)
    }

    // ── Test 5: Metadata display ──────────────────────────────────────────────

    @Test
    fun settingsScreen_displaysMetadata_whenMetadataIsAvailable() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(metadata = TEST_METADATA),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithText(TEST_METADATA.title).assertIsDisplayed()
        composeTestRule.onNodeWithText(TEST_METADATA.artist).assertIsDisplayed()
    }

    @Test
    fun settingsScreen_doesNotDisplayMetadata_whenMetadataIsNull() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(metadata = null),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithText(TEST_METADATA.title).assertDoesNotExist()
        composeTestRule.onNodeWithText(TEST_METADATA.artist).assertDoesNotExist()
    }

    // ── Test 6: Pull-to-refresh ───────────────────────────────────────────────

    @Test
    fun settingsScreen_rendersContentDuringRefresh() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(isRefreshing = true),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onRefresh = {},
            )
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.ARTWORK_PREVIEW)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.SET_NOW_BUTTON)
            .assertIsDisplayed()
    }

    // ── Test 7: Image cache invalidation ─────────────────────────────────────

    @Test
    fun settingsScreen_artworkPreview_rendersAfterImageRevisionChange() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(imageRevision = 5),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onRefresh = {},
            )
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.ARTWORK_PREVIEW)
            .assertIsDisplayed()
    }

    // ── Test 8: Long-press save image ────────────────────────────────────────

    @Test
    fun settingsScreen_longPressCard_invokesOnSaveImageCallback() {
        var callbackInvoked = false

        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState,
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = { callbackInvoked = true },
                onRefresh = {},
            )
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.ARTWORK_PREVIEW)
            .performTouchInput { longClick() }

        assertTrue("onSaveImage callback should be invoked on long press", callbackInvoked)
    }
}
