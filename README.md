# MyRadar SDK samples

Sample apps for the MyRadar SDKs. Each one is a flight tracker over the MyRadar weather map
(animated radar, warnings, lightning, hurricanes and wind) and uses its SDK exactly as your app
would: from the public package, with your own API key.

| Sample | SDK | Package | Walkthrough |
|---|---|---|---|
| [`ios-demo/`](ios-demo/) | MyRadar iOS SDK (SwiftUI) | Swift package [`MyRadar-iOS-SDK-SPM`](https://github.com/MyRadar/MyRadar-iOS-SDK-SPM) | [Build a flight tracker (iOS)](https://myradar.com/apidocs/ios-flight-tracker) |
| [`android-demo/`](android-demo/) | MyRadar Android SDK (Jetpack Compose) | `com.acmeaom.myradar:myradar-sdk` from [`MyRadar-Android-SDK-Maven`](https://github.com/MyRadar/MyRadar-Android-SDK-Maven) | [Build a flight tracker (Android)](https://myradar.com/apidocs/android-flight-tracker) |
| [`web-demo/`](web-demo/) | MyRadar Web SDK (Mapbox GL JS) | `@myradar/mapbox` and `@myradar/controls` from npm | [Run the sample app (web)](https://myradar.com/apidocs/web-sdk-overview#run-the-sample-app) |

## Your API key

Every sample needs a MyRadar API key from the [developer portal](https://myradar.com/developer_portal).
The SDKs send it only to the MyRadar API gateway. Where it goes:

| Sample | Put the key in | Kept out of git by |
|---|---|---|
| iOS | Xcode scheme: **Product > Scheme > Edit Scheme… > Run > Arguments > Environment Variables**, name `MYRADAR_API_KEY` | Xcode stores the scheme in your `xcuserdata` (leave **Shared** unchecked) |
| Android | `android-demo/local.properties`: `myradar.apiKey=your-key` | `local.properties` is git-ignored |
| Web | `web-demo/.env.local` (copy `.env.example`): `VITE_MYRADAR_API_KEY=your-key` | `.env.local` is git-ignored |

The web sample also needs a [Mapbox access token](https://account.mapbox.com/access-tokens/) in
the same file, as `VITE_MAPBOX_TOKEN`.

## Run one

```sh
git clone https://github.com/MyRadar/MyRadar-SDK-Samples.git
```

then follow the README in the sample's folder; each starts with a command-line quickstart. In short:

- **iOS:** open `ios-demo/MyRadarSDKDemo.xcodeproj` in Xcode 16 or later, wait for the
  **MyRadarSDK** package to resolve, add the key to the scheme, and run on an iOS 17+ simulator.
- **Android:** add the key to `android-demo/local.properties`, open `android-demo` in Android
  Studio and run the **app** configuration (or `./gradlew :app:installDebug` with `ANDROID_HOME`
  set) on an Android 10+ emulator or device.
- **Web:** in `web-demo`, run `npm install`, put your keys in `.env.local`, then `npm run dev` and
  open <http://localhost:5173>. Needs Node.js 20.19 or later.

With a valid key, radar and warnings appear within a few seconds. A map with no weather means the
key is missing or was rejected; each sample's README explains how to tell which.

None of the samples contains flight-data code. Each passes the flight number to its SDK's
`showFlight`, which looks the flight up through the MyRadar API gateway, draws it, and keeps it
updated; the sample shows the flight details the SDK reports.
