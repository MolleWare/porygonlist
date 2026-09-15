package io.github.molleware.porygonlist.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start measurements.
 *
 * Cold start is the number that decides whether this feels like a tool or a
 * chore, so it is measured rather than assumed. Each test reports
 * timeToInitialDisplay: process fork to the first frame actually on screen.
 *
 * The three compilation modes exist to separate causes. Comparing [startupNone]
 * with [startupBaselineProfile] shows what the baseline profile is worth;
 * comparing against [startupFull] shows how much of the theoretical maximum the
 * profile captures. If profile and none are equal, the profile is not being
 * applied and something is misconfigured.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** Interpreted / JIT only: the floor, and the worst case for a user. */
    @Test
    fun startupNone() = measureStartup(CompilationMode.None())

    /** What a user actually gets: the baseline profile shipped in the APK. */
    @Test
    fun startupBaselineProfile() = measureStartup(CompilationMode.Partial())

    /** Everything AOT compiled: the ceiling, not shippable, useful as a bound. */
    @Test
    fun startupFull() = measureStartup(CompilationMode.Full())

    private fun measureStartup(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        // Enough runs that the median is stable without making the suite
        // tedious to run on every change.
        iterations = 10,
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
        },
    ) {
        startActivityAndWait()
    }
}
