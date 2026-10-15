import java.util.Properties

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

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The MyRadar Android SDK is distributed as a Maven repository: the `maven` folder of the unzipped
// myradar-android-sdk-VERSION.zip, or the hosted repository URL you were given. Point
// `myradar.sdkRepo` at it in local.properties (or pass -Pmyradar.sdkRepo=..., or set
// MYRADAR_SDK_REPO).
val localProperties = Properties().apply {
    val file = file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val sdkRepo: String = providers.gradleProperty("myradar.sdkRepo").orNull
    ?: localProperties.getProperty("myradar.sdkRepo")
    ?: System.getenv("MYRADAR_SDK_REPO")
    ?: error(
        "MyRadar SDK repository not set. Unzip myradar-android-sdk-VERSION.zip and add " +
            "myradar.sdkRepo=/path/to/myradar-android-sdk-VERSION/maven to local.properties.",
    )

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "myradar"
            url = uri(sdkRepo)
            content { includeGroup("com.acmeaom.myradar") }
        }
    }
}

rootProject.name = "MyRadar SDK Demo"

include(":app")
