import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.baselineprofile)
}

android {
    namespace = "io.github.molleware.porygonlist"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.molleware.porygonlist"
        minSdk = 26
        targetSdk = 36
        // Every release bumps both, and a code is never reused: F-Droid and the Play Store refuse an
        // update whose code is not higher. See docs/release-checklist.md.
        versionCode = 1
        versionName = "0.1.0"

        // Never set, which is why `./scripts/test.sh instrumented` could only ever report an empty
        // suite: without a runner the androidTest variant has nothing to execute the tests with.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing, read from keystore.properties at the repository root — git-ignored, because
    // this repository is public and a published app's key can never be replaced. Without that file
    // the release build is simply unsigned, which is what F-Droid's build server expects: it
    // compares its own unsigned build with our signed APK. See keystore.properties.example.
    val keystoreFile = rootProject.file("keystore.properties")
    val signing =
        if (keystoreFile.exists()) Properties().apply { keystoreFile.inputStream().use { load(it) } } else null
    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (signing != null) signingConfig = signingConfigs.getByName("release")
            // R8: shrink, optimize and obfuscate. Also the prerequisite for
            // baseline profiles to have their full effect on startup.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    // Must match a JDK actually installed on the build machine. With 17 here,
    // Gradle's toolchain resolver downloaded a whole JDK mid-build, which is
    // both wasteful and unacceptable for F-Droid's reproducible builds.
    jvmToolchain(21)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Baseline profile. profileinstaller applies the shipped profile on first
  // run; the baselineProfile dependency pulls the generated profile from
  // :benchmark into the release APK at build time.
  implementation(libs.androidx.profileinstaller)
  baselineProfile(project(":benchmark"))

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
