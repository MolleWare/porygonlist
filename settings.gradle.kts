pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

// The foojay-resolver-convention plugin was removed deliberately. It lets
// Gradle download a JDK when the requested toolchain is missing, which makes
// builds non-reproducible and silently network-dependent -- F-Droid builds
// must use the JDK provided by the build environment. jvmToolchain(21) in
// app/build.gradle.kts must therefore match an installed JDK.

rootProject.name = "PorygonList"
include(":app")
include(":benchmark")
