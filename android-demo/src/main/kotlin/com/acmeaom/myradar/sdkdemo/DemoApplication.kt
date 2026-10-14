package com.acmeaom.myradar.sdkdemo

import android.app.Application
import com.acmeaom.myradar.sdk.MyRadarSdk

class DemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Route weather data through the MyRadar API gateway when a key is available (set
        // `myradar.apiKey` in local.properties). Without a key the SDK talks to the data hosts directly.
        if (BuildConfig.MYRADAR_API_KEY.isNotEmpty()) {
            MyRadarSdk.configure(apiKey = BuildConfig.MYRADAR_API_KEY)
        }
    }
}
