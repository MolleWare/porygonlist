package io.github.molleware.porygonlist.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the baseline profile shipped inside the APK.
 *
 * The profile is a list of the methods and classes exercised by the journeys
 * below. ART compiles those ahead of time at install, so the work does not
 * happen during the user's first launches.
 *
 * Whatever is exercised here is what gets optimised. Right now that is launch
 * only; as real features land, the journeys that matter most (opening a list,
 * adding an item) belong here too.
 *
 * Regenerate with:  ./scripts/benchmark.sh profile
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = TARGET_PACKAGE) {
        pressHome()
        startActivityAndWait()
    }
}
