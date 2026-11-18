package com.acmeaom.myradar.sdkdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.acmeaom.myradar.sdk.MyRadarSdk
import com.acmeaom.myradar.sdkdemo.ui.DemoScreen
import com.acmeaom.myradar.sdkdemo.ui.DemoTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val options = LaunchOptions.from(intent)
        // `--es environment development` points the demo at MyRadar's development gateway, for
        // testing SDK builds. The SDK reads its configuration on every request, so configuring
        // again here, before the map is created, switches every request the map makes.
        if (options.environment != null && BuildConfig.MYRADAR_API_KEY.isNotEmpty()) {
            MyRadarSdk.configure(apiKey = BuildConfig.MYRADAR_API_KEY, environment = options.environment)
        }
        setContent {
            DemoTheme {
                DemoScreen(options)
            }
        }
    }
}
