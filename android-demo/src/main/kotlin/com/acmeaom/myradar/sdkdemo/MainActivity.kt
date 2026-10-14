package com.acmeaom.myradar.sdkdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.acmeaom.myradar.sdkdemo.ui.DemoScreen
import com.acmeaom.myradar.sdkdemo.ui.DemoTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val options = LaunchOptions.from(intent)
        setContent {
            DemoTheme {
                DemoScreen(options)
            }
        }
    }
}
