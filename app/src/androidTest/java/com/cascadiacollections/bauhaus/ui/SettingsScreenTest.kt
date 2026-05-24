package com.cascadiacollections.bauhaus.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

private val TEST_METADATA = ArtworkMetadata(
    title = "Composition VIII",
    artist = "Wassily Kandinsky",
    source = "Guggenheim Museum",
    date = "1923-07-01",
)

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val defaultState = UiState()

    private fun getString(resId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private fun targetLabel(target: WallpaperTarget): String = getString(
        when (target) {
            WallpaperTarget.HOME -> R.string.wallpaper_target_home
            WallpaperTarget.LOCK -> R.string.wallpaper_target_lock
            WallpaperTarget.BOTH -> R.string.wallpaper_target_both
        },
    )

    @Test
    fun settingsScreen_artworkPreview_isDisplayed() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState,
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onArchivePageSelected = {},
                onRefresh = {},
            )
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.ARTWORK_PREVIEW)
            .assertIsDisplayed()
    }

    @Test
    fun settingsScreen_segmentedButton_reflectsWallpaperTarget() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(wallpaperTarget = WallpaperTarget.HOME),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onArchivePageSelected = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithText(targetLabel(WallpaperTarget.HOME)).assertIsSelected()
        composeTestRule.onNodeWithText(targetLabel(WallpaperTarget.LOCK)).assertIsNotSelected()
        composeTestRule.onNodeWithText(targetLabel(WallpaperTarget.BOTH)).assertIsNotSelected()
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
                onArchivePageSelected = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithText(targetLabel(WallpaperTarget.LOCK)).performClick()

        assertEquals(WallpaperTarget.LOCK, capturedTarget)
    }

    @Test
    fun settingsScreen_dailyUpdatesSwitch_reflectsSchedulingState() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(schedulingEnabled = false),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onArchivePageSelected = {},
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
                onArchivePageSelected = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithTag(SettingsScreenTestTags.DAILY_UPDATES_SWITCH).assertIsOn()
        composeTestRule.onNodeWithTag(SettingsScreenTestTags.DAILY_UPDATES_SWITCH).performClick()

        assertNotNull("onSchedulingToggle callback should have been invoked", capturedEnabled)
        assertFalse("Callback should be called with false when toggling off", capturedEnabled!!)
    }

    @Test
    fun settingsScreen_setNowButton_showsLoadingStateWhenSettingWallpaper() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(isSettingWallpaper = true),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onArchivePageSelected = {},
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
                onArchivePageSelected = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithText(getString(R.string.set_now)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SettingsScreenTestTags.SET_NOW_BUTTON).performClick()

        assertTrue("onSetWallpaperNow callback should be invoked on button click", callbackInvoked)
    }

    @Test
    fun settingsScreen_saveButton_invokesOnSaveImageCallback() {
        var callbackInvoked = false

        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState,
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = { callbackInvoked = true },
                onArchivePageSelected = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithTag(SettingsScreenTestTags.SAVE_IMAGE_BUTTON).performClick()

        assertTrue("onSaveImage callback should be invoked on button click", callbackInvoked)
    }

    @Test
    fun settingsScreen_saveButton_showsLoadingStateWhenSavingImage() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(isSavingImage = true),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onArchivePageSelected = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithTag(SettingsScreenTestTags.SAVE_IMAGE_BUTTON).assertIsNotEnabled()
        composeTestRule.onNodeWithText(getString(R.string.save_image)).assertIsDisplayed()
    }

    @Test
    fun settingsScreen_displaysMetadata_whenMetadataIsAvailable() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(metadata = TEST_METADATA),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onArchivePageSelected = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithText(TEST_METADATA.title).assertIsDisplayed()
        composeTestRule.onNodeWithText(TEST_METADATA.artist).assertIsDisplayed()
        composeTestRule.onNodeWithText(TEST_METADATA.date).assertIsDisplayed()
        composeTestRule.onNodeWithText(TEST_METADATA.source).assertIsDisplayed()
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
                onArchivePageSelected = {},
                onRefresh = {},
            )
        }

        composeTestRule.onAllNodesWithText(TEST_METADATA.title).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(TEST_METADATA.artist).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(TEST_METADATA.date).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(TEST_METADATA.source).assertCountEquals(0)
    }

    @Test
    fun settingsScreen_rendersContentDuringRefresh() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(isRefreshing = true),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onArchivePageSelected = {},
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

    @Test
    fun settingsScreen_artworkPreview_rendersAfterImageRevisionChange() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(imageRevision = 5),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onArchivePageSelected = {},
                onRefresh = {},
            )
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.ARTWORK_PREVIEW)
            .assertIsDisplayed()
    }

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
                onArchivePageSelected = {},
                onRefresh = {},
            )
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.ARTWORK_PREVIEW)
            .performTouchInput { longClick() }

        assertTrue("onSaveImage callback should be invoked on long press", callbackInvoked)
    }

    @Test
    fun settingsScreen_favoriteButton_invokesCallback() {
        var callbackInvoked = false

        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState,
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onFavoriteToggle = { callbackInvoked = true },
                onArchivePageSelected = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithTag(SettingsScreenTestTags.FAVORITE_BUTTON).performClick()

        assertTrue("onFavoriteToggle callback should be invoked on tap", callbackInvoked)
    }

    @Test
    fun settingsScreen_favoriteButton_showsFilledIconWhenFavorited() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(isFavorite = true),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onArchivePageSelected = {},
                onRefresh = {},
            )
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.FAVORITE_BUTTON)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(getString(R.string.unfavorite_artwork))
            .assertIsDisplayed()
    }

    @Test
    fun settingsScreen_favoritesFilterChip_invokesCallback() {
        var callbackInvoked = false
        val stateWithFavorites = defaultState.copy(
            isFavorite = true,
            favoriteDates = setOf(defaultState.visibleDate),
        )

        composeTestRule.setContent {
            SettingsScreen(
                uiState = stateWithFavorites,
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onFavoritesFilterToggle = { callbackInvoked = true },
                onArchivePageSelected = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithTag(SettingsScreenTestTags.FAVORITES_FILTER_CHIP).performClick()

        assertTrue("onFavoritesFilterToggle should be invoked on chip tap", callbackInvoked)
    }

    @Test
    fun settingsScreen_favoritesFilterChip_isDisabledWhenNoFavorites() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState.copy(favoriteDates = emptySet()),
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onArchivePageSelected = {},
                onRefresh = {},
            )
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.FAVORITES_FILTER_CHIP)
            .assertIsNotEnabled()
    }

    @Test
    fun settingsScreen_jumpToDateButton_opensDatePickerDialog() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = defaultState,
                onWallpaperTargetChange = {},
                onSchedulingToggle = {},
                onSetWallpaperNow = {},
                onSaveImage = {},
                onArchivePageSelected = {},
                onRefresh = {},
            )
        }

        composeTestRule.onNodeWithTag(SettingsScreenTestTags.JUMP_TO_DATE_BUTTON).performClick()
        composeTestRule.onNodeWithText(getString(android.R.string.ok)).assertIsDisplayed()
        composeTestRule.onNodeWithText(getString(android.R.string.cancel)).assertIsDisplayed()
    }
}
