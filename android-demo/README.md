# MyRadar Android SDK demo

A one-screen flight tracker over the MyRadar weather map, built on the MyRadar Android SDK.
Search for a flight number (`UA 1`, `DAL100`), an airport (`SFO`, `KJFK`) or a route (`SFO-LAX`),
or tap the airplane button for an offline sample flight from San Francisco to New York. Layer chips
turn radar and each overlay on and off; the layers button picks the base map and radar product;
the opacity button shows a slider for radar, warnings, hurricanes and wind. The flight card's
**Follow** / **Route** switch keeps the camera on the aircraft at a regional zoom or fits the whole
route.

## Requirements

- Android Studio with the Android SDK for API 37 (the Gradle wrapper and JDK come with it).
- A device or emulator running Android 10 (API 29) or later with OpenGL ES 3, on `arm64-v8a`,
  `armeabi-v7a` or `x86_64`. Emulators need at least 2 CPU cores.
- The MyRadar Android SDK package (`myradar-android-sdk-VERSION.zip`) or the URL of a hosted
  MyRadar SDK Maven repository.
- A MyRadar API key.

## Setup

1. Unzip the SDK package anywhere, for example next to this repository.
2. Create `local.properties` in this folder (it is git-ignored) with the SDK repository and your key:

   ```properties
   # The `maven` folder of the unzipped package, or the hosted repository URL.
   myradar.sdkRepo=/path/to/myradar-android-sdk-1.0.0/maven
   myradar.apiKey=<your key>
   ```

   Android Studio adds `sdk.dir` to this file on first open. Instead of `local.properties`, you can
   set the `MYRADAR_SDK_REPO` and `MYRADAR_API_KEY` environment variables.
3. If your package is not version 1.0.0, set `myradar.sdkVersion` in `gradle.properties`.
4. Open this folder in Android Studio and run the **app** configuration, or:

   ```sh
   ./gradlew :app:installDebug
   ```

Without an API key the app still builds, but the map shows a "MyRadar SDK is not configured"
notice.

How the SDK is wired in, as in any third-party app:

- `settings.gradle.kts` adds the SDK's Maven repository.
- `app/build.gradle.kts` depends on `com.acmeaom.myradar:myradar-sdk`; the engine artifact comes
  with it.
- `DemoApplication` calls `MyRadarSdk.configure(apiKey = ...)` at launch.

## What it shows

| Screen element | SDK API exercised |
|---|---|
| Map with radar, warnings, lightning | `MyRadarMap`, `overlays` |
| Layer chips | `radarEnabled`, `overlays`, `Overlay` |
| Layers menu | `mapStyle`, `radarProduct` |
| Opacity row | `radarOpacity`, `overlayOpacities`, `Overlay.supportsOpacity` |
| Flown path (solid) and remaining route (dashed) | `polylines`, `MapPolyline` with `dashPattern` and `outlineColor` |
| Rotated aircraft icon, airport code labels | `markers`, `MapMarker.Icon.Image` with `heading`, `MapMarker.Icon.Text` |
| Follow mode at an exact zoom | `camera` with `MapCamera` |
| Route mode | `visibleRegion`, `CoordinateRegion.fitting` |
| Tapping the aircraft or an airport | `onMarkerTap` with `userInfo` |
| Attributions panel (ⓘ on the card) | `MyRadarMapView.licensesAndAttributions` |

Flight data comes from free, keyless public services (adsb.lol, adsbdb.com, hexdb.io, with
OpenSky as a track fallback); the SDK itself makes no flight-related requests.

## Launch extras

For testing and screenshots, the app accepts intent extras:

```sh
adb shell am start -n com.acmeaom.myradar.sdkdemo/.MainActivity \
    --es query "UA 1" --es overlays warnings,lightning,wind --es style dark --ef zoom 6
```

Supported extras: `--ez sample true`, `--es query <text>`, `--ef zoom <level>`,
`--es center <lat>,<lon>`, `--es overlays <list|none>`, `--es style <name>`, `--ez route true`,
`--ez kiosk true` (map only, full screen), `--es mock flood` (mock flood-forecast polygons),
`--es opacity warnings=0.9,hurricanes=0.3`. See `LaunchOptions.kt`.
