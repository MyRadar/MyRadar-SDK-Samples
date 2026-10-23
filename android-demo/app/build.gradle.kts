import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The MyRadar API key for the demo: `myradar.apiKey` in local.properties (kept out of git) or the
// MYRADAR_API_KEY environment variable. With no key the map shows a "not configured" notice.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val apiKey: String = localProperties.getProperty("myradar.apiKey") ?: System.getenv("MYRADAR_API_KEY") ?: ""

android {
    namespace = "com.acmeaom.myradar.sdkdemo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.acmeaom.myradar.sdkdemo"
        // The SDK requires Android 10 (API 29) and OpenGL ES 3.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "MYRADAR_API_KEY", "\"$apiKey\"")
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            // Signed with the debug key so the release build can be installed for testing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The MyRadar SDK version: `myradar.sdkVersion` in gradle.properties. The repository it comes from is
// set in settings.gradle.kts.
val myradarSdkVersion: String = providers.gradleProperty("myradar.sdkVersion").get()

dependencies {
    implementation("com.acmeaom.myradar:myradar-sdk:$myradarSdkVersion")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
