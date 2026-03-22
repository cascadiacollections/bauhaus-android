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
 * The generated profile is written under the benchmark module's build outputs directory
 * (e.g. `benchmark/build/outputs/...`). Copy or merge it into `app/src/main/baseline-prof.txt`
 * to commit an updated profile.
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

        // Wait for the Coil artwork image to finish loading; fail fast if it never appears
        val artworkLoaded = device.wait(Until.hasObject(By.desc("Today's artwork")), 5_000L)
        check(artworkLoaded) { "Timed out waiting for 'Today's artwork' image to appear in the UI" }

        // Scroll the settings screen to exercise scroll-path rendering
        device.wait(Until.hasObject(By.scrollable(true)), 5_000L)
        val scrollable = requireNotNull(device.findObject(By.scrollable(true))) {
            "Timed out waiting for or could not find a scrollable container for baseline profile generation."
        }
        scrollable.scroll(Direction.DOWN, 1.0f)
    }
}
