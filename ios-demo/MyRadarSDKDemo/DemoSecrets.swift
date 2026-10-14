//
//  DemoSecrets.swift
//  MyRadarSDKDemo
//
//  Supplies the MyRadar API key to the demo without committing it to source control.
//
//  Preferred: set the MYRADAR_API_KEY environment variable in the scheme
//  (Product > Scheme > Edit Scheme… > Run > Arguments > Environment Variables).
//  Alternative for quick local testing: put the key in `localAPIKey` below and do not
//  commit the change.
//

import Foundation

enum DemoSecrets {
    /// Fill in locally if you prefer not to use the environment variable. Keep it out of git.
    private static let localAPIKey: String? = nil

    /// The key to use, or `nil` to run against the data hosts directly.
    static var apiKey: String? {
        if let fromEnvironment = ProcessInfo.processInfo.environment["MYRADAR_API_KEY"],
           !fromEnvironment.isEmpty {
            return fromEnvironment
        }
        return localAPIKey
    }
}
