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

// The MyRadar Android SDK is published to a public Maven repository on GitHub Pages. To build against
// another copy (for example a local SDK build's dist/maven folder), set `myradar.sdkRepo` in
// local.properties, pass -Pmyradar.sdkRepo=..., or set MYRADAR_SDK_REPO.
val localProperties = Properties().apply {
    val file = file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val sdkRepo: String = providers.gradleProperty("myradar.sdkRepo").orNull
    ?: localProperties.getProperty("myradar.sdkRepo")
    ?: System.getenv("MYRADAR_SDK_REPO")
    ?: "https://myradar.github.io/MyRadar-Android-SDK-Maven"

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
