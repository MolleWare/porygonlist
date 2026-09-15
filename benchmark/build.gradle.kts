plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.baselineprofile)
}

android {
    namespace = "io.github.molleware.porygonlist.benchmark"
    compileSdk = 36

    defaultConfig {
        // Higher than the app's minSdk on purpose: macrobenchmark needs 24+ and
        // baseline profile collection needs 28+. This only constrains the
        // measuring harness, never what the app itself supports.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // Generate on a real connected device. Emulator numbers are dominated by
    // host scheduling noise and do not reflect what users experience.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.junit)
}

kotlin {
    jvmToolchain(21)
}
