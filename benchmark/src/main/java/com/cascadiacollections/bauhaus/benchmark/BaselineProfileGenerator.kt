package com.cascadiacollections.bauhaus.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates a baseline profile for bauhaus-android to enable AOT compilation of hot paths.
 *
 * Run with:
 * ```
 * ./gradlew :benchmark:connectedBenchmarkAndroidTest
 * ```
 * The generated profile is written to `app/src/main/baseline-prof.txt`.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "com.cascadiacollections.bauhaus") {
        pressHome()
        startActivityAndWait()

        // Wait for the Coil artwork image to finish loading
        device.wait(Until.hasObject(By.desc("Today's artwork")), 5_000L)

        // Scroll the settings screen to exercise scroll-path rendering
        device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 1.0f)
    }
}
