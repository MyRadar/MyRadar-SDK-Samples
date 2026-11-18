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
            MyRadarSDK.configure(apiKey: apiKey, environment: Self.environment)
        }
    }

    /// The MyRadar gateway: production unless the MYRADAR_ENVIRONMENT environment variable says
    /// `development` (MyRadar's own testing). Apps leave `environment` out.
    private static var environment: MyRadarEnvironment {
        let value = ProcessInfo.processInfo.environment["MYRADAR_ENVIRONMENT"]?.lowercased() ?? ""
        return MyRadarEnvironment(rawValue: value) ?? .production
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
