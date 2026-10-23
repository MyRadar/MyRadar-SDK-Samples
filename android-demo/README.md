# MyRadar Android SDK demo

A one-screen flight tracker over the MyRadar weather map, built on the MyRadar Android SDK.
Enter a flight number (`UA 1`, `AA 1643`) or a callsign (`UAL1`) and the SDK draws the flight: its
planned route, the track it has flown, the route ahead, its airports and the aircraft, updated
until it lands. The card below the map shows what the SDK reports about it. Layer chips turn radar
and each overlay on and off; the layers button picks the base map and radar product; the opacity
button shows a slider for radar, warnings, hurricanes and wind.

## Requirements

- Android Studio with the Android SDK for API 37 (the Gradle wrapper and JDK come with it).
- A device or emulator running Android 10 (API 29) or later with OpenGL ES 3, on `arm64-v8a`,
  `armeabi-v7a` or `x86_64`. Emulators need at least 2 CPU cores.
- A MyRadar API key from the [developer portal](https://myradar.com/developer_portal).

The SDK comes from the MyRadar SDK Maven repository (`https://myradar.github.io/MyRadar-Android-SDK-Maven`); Gradle
downloads it, so there is nothing else to install.

## Quickstart (command line)

With Android Studio installed, these commands clone the sample, start an
emulator, and build and launch the app with your key. Replace
`your-myradar-api-key`. The paths are Android Studio's defaults on macOS; on
Linux the SDK is usually in `~/Android/Sdk`, and any JDK 17 or later works for
`JAVA_HOME`.

```sh
export MYRADAR_API_KEY=your-myradar-api-key
git clone https://github.com/MyRadar/MyRadar-SDK-Samples.git
cd MyRadar-SDK-Samples/android-demo

export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

emulator -list-avds
AVD=$(emulator -list-avds | head -1)
emulator -avd "$AVD" -cores 4 >/dev/null 2>&1 &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done

./gradlew :app:installDebug
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell am start -S -n com.acmeaom.myradar.sdkdemo/.MainActivity
```

The last three commands wake the emulator's screen and restart the app. An emulator that
resumes from Quick Boot can come back with its screen asleep, which looks like a blank
screen. `emulator -list-avds` lists your emulators and the commands start the first one; if
the list is empty, create one in Android Studio's Device Manager first. The build
reads `MYRADAR_API_KEY` from the environment and builds it into the app, so after
changing the key, run `./gradlew :app:installDebug` again. The
first build downloads Gradle, the Android build tools and the MyRadar SDK, so it
takes a few minutes.

## Setup

1. Clone the samples repository:

   ```sh
   git clone https://github.com/MyRadar/MyRadar-SDK-Samples.git
   ```

2. Create `local.properties` in this folder (`android-demo`, next to `settings.gradle.kts`) with
   your key:

   ```properties
   myradar.apiKey=your-myradar-api-key
   ```

   The file is git-ignored. At build time the key is copied into `BuildConfig.MYRADAR_API_KEY`,
   which `DemoApplication` passes to `MyRadarSdk.configure`. Android Studio adds `sdk.dir` to the
   same file on first open. Instead of the file, you can set the `MYRADAR_API_KEY` environment
   variable.
3. Open this folder in Android Studio (**File > Open**), let Gradle sync, and run the **app**
   configuration on an emulator or device. Or, from a terminal with one running and
   `ANDROID_HOME` set to your Android SDK (usually `~/Library/Android/sdk` on a Mac; opening the
   project once in Android Studio writes it to `local.properties` instead):

   ```sh
   export ANDROID_HOME="$HOME/Library/Android/sdk"
   ./gradlew :app:installDebug
   ```

Radar and warnings should appear within a few seconds. A "MyRadar SDK is not configured" notice
means the app was built without a key: check the property name and folder, then build again (the
key is read at build time). A map without weather means the key was rejected; run
`adb shell setprop log.tag.MyRadarSDK VERBOSE` and `adb logcat -s MyRadarSDK` to see each request
and its status code.

To build against an unreleased SDK (for example a local SDK build's `dist/maven` folder), set
`myradar.sdkRepo` in `local.properties` and `myradar.sdkVersion` in `gradle.properties`.

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
| The flight on the map | `flight` |
| Flight card | `onFlightUpdate` with `FlightInfo` |
| "Couldn't find that flight" banner | `onFlightError` with `FlightException` |
| Flood mock (`--es mock flood`) | `polygons`, `markers`, `onMarkerTap` |
| Attributions panel (ⓘ by the search box) | `MyRadarMapView.licensesAndAttributions` |

The app has no flight-data code: it passes the flight number to the map and shows the
`FlightInfo` the SDK reports.

```kotlin
MyRadarMap(
    flight = ident,                          // null shows no flight
    onFlightUpdate = { flight = it },        // on load and every update
    onFlightError = { errorMessage = it.message },
)
```

## Launch extras

For testing and screenshots, the app accepts intent extras:

```sh
adb shell am start -n com.acmeaom.myradar.sdkdemo/.MainActivity \
    --es query "UA 1" --es overlays warnings,lightning,wind --es style dark --ef zoom 6
```

Supported extras: `--es query <flight number>`, `--ef zoom <level>`,
`--es center <lat>,<lon>`, `--es overlays <list|none>`, `--es style <name>`,
`--ez kiosk true` (map only, full screen), `--es mock flood` (mock flood-forecast polygons),
`--es opacity warnings=0.9,hurricanes=0.3`. See `LaunchOptions.kt`.
