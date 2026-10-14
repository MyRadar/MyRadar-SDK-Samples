import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The MyRadar API key for the demo: `myradar.apiKey` in local.properties (kept out of git) or the
// MYRADAR_API_KEY environment variable. With no key the SDK talks to the data hosts directly.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val apiKey: String = localProperties.getProperty("myradar.apiKey") ?: System.getenv("MYRADAR_API_KEY") ?: ""

android {
    namespace = "com.acmeaom.myradar.sdkdemo"
    // Same NDK the SDK's native engine is built with; packaging needs it to strip the engine's symbols.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.acmeaom.myradar.sdkdemo"
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

// The MyRadar SDK version to build against: `myradar.sdkVersion` in gradle.properties or `-Pmyradar.sdkVersion=...`.
val myradarSdkVersion: String = providers.gradleProperty("myradar.sdkVersion").orNull
    ?: error("Set myradar.sdkVersion in gradle.properties to the MyRadar SDK version to use")

dependencies {
    implementation("com.acmeaom.myradar:myradar-sdk:$myradarSdkVersion")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.square.okhttp.bom))
    implementation(libs.square.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
