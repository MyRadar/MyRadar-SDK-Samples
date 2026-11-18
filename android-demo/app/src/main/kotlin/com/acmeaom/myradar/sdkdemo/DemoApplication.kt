package com.acmeaom.myradar.sdkdemo

import android.app.Application
import com.acmeaom.myradar.sdk.MyRadarSdk

class DemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // The API key comes from `myradar.apiKey` in local.properties. Without one the map makes no
        // network requests and shows a "not configured" notice. Requests go to MyRadar's
        // production gateway; MainActivity switches to development for `--es environment development`.
        if (BuildConfig.MYRADAR_API_KEY.isNotEmpty()) {
            MyRadarSdk.configure(apiKey = BuildConfig.MYRADAR_API_KEY)
        }
    }
}
