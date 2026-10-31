pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TruAgent"

include(":agent-core")

// The Android module is skipped automatically when no Android SDK is configured,
// so the pure-Kotlin core can still be compiled and unit-tested on any JDK host.
val androidSdkPresent: Boolean = run {
    val localProps = file("local.properties")
    val sdkFromLocalProps = if (localProps.exists()) {
        localProps.readLines().any { it.trim().startsWith("sdk.dir=") }
    } else {
        false
    }
    sdkFromLocalProps ||
        System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null
}

if (androidSdkPresent || providers.gradleProperty("pocketagent.forceAndroid").isPresent) {
    include(":app")
} else {
    logger.lifecycle("TruAgent: no Android SDK found, building :agent-core only.")
}
