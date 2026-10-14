//
//  MyRadarSDKDemoApp.swift
//  MyRadarSDKDemo
//

import SwiftUI
import MyRadar_iOS_SDK

@main
struct MyRadarSDKDemoApp: App {

    init() {
        // Route weather data through the MyRadar API gateway when a key is available.
        // Without a key the SDK talks to the data hosts directly.
        if let apiKey = DemoSecrets.apiKey {
            MyRadarSDK.configure(apiKey: apiKey)
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
