//
//  MyRadarSDKDemoApp.swift
//  MyRadarSDKDemo
//

import SwiftUI
import MyRadar_iOS_SDK

@main
struct MyRadarSDKDemoApp: App {

    init() {
        // Weather data comes from the MyRadar API gateway, which needs your key.
        // Without one the map shows the base map only.
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
