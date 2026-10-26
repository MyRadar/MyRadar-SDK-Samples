# MyRadar iOS SDK demo

A one-screen flight tracker over the MyRadar weather map, built on the MyRadar iOS SDK. Enter a
flight number (`UA 1`, `AA 1643`) or a callsign (`UAL1`) and the SDK draws the flight: its planned
route, the track it has flown, the route ahead, its airports and the aircraft, updated until it
lands. The card below the map shows what the SDK reports about it. Layer chips turn radar and
each overlay on and off; the layers menu picks the base map and radar product; the opacity row
sets radar, warnings, hurricanes and wind opacity.

## Requirements

- Xcode 16 or later.
- An iPhone or iPad simulator or device running iOS 17 or later.
- A MyRadar API key from the [developer portal](https://myradar.com/developer_portal).

## Quickstart (command line)

On a Mac with Xcode 16 or later, these commands clone the sample, build it and
launch it on a simulator with your key. Replace `your-myradar-api-key`, and set
`SIM` to any iOS 17 or later simulator from `xcrun simctl list devices available`.

```sh
export MYRADAR_API_KEY=your-myradar-api-key
git clone https://github.com/MyRadar/MyRadar-SDK-Samples.git
cd MyRadar-SDK-Samples/ios-demo

SIM="iPhone 16"
UDID=$(xcrun simctl list devices available | grep -m1 "    $SIM (" | grep -oE '[0-9A-F-]{36}')
xcrun simctl boot "$UDID" 2>/dev/null || true

xcodebuild -project MyRadarSDKDemo.xcodeproj -scheme MyRadarSDKDemo \
  -destination "id=$UDID" -derivedDataPath build \
  -packageAuthorizationProvider netrc -quiet build

xcrun simctl install "$UDID" build/Build/Products/Debug-iphonesimulator/MyRadarSDKDemo.app
SIMCTL_CHILD_MYRADAR_API_KEY="$MYRADAR_API_KEY" \
  xcrun simctl launch "$UDID" com.myradar.MyRadarSDKDemo
open -a Simulator --args -CurrentDeviceUDID "$UDID" 2>/dev/null ||
  open -b com.apple.dt.Devices --args -CurrentDeviceUDID "$UDID"
```

`SIM` can be any iOS 17 or later simulator; `xcrun simctl list devices available` lists them.
Booting a simulator that is already running is fine. The last command brings up the
simulator's window: in the Simulator app, or in Device Hub in newer versions of Xcode,
which replaces it. The app runs either way; without that window you just can't see it.
The first build downloads the `MyRadarSDK` package, so it takes a few minutes.
`-packageAuthorizationProvider netrc` stops `xcodebuild` from waiting on a
Keychain prompt for GitHub credentials, which the public package doesn't need.
The key reaches the app through the `xcrun simctl launch` command (`SIMCTL_CHILD_`
passes an environment variable to the launched app), so start the app with that
command again rather than from the simulator's home screen.

## Setup

1. Open `MyRadarSDKDemo.xcodeproj`. On first open Xcode downloads the **MyRadarSDK** Swift
   package (about 5 MB) from `https://github.com/MyRadar/MyRadar-iOS-SDK-SPM`; wait for it to
   appear under **Package Dependencies**.
2. Add your API key to the scheme:
   1. **Product > Scheme > Edit Scheme…** (⌘<).
   2. Select **Run**, then the **Arguments** tab.
   3. Under **Environment Variables**, click **+**. Name: `MYRADAR_API_KEY`. Value: your key.

   Leave **Shared** unchecked at the bottom of the scheme editor so the key stays in your
   `xcuserdata`, which is git-ignored. Alternatively set `localAPIKey` in
   `MyRadarSDKDemo/DemoSecrets.swift`, and don't commit it.
3. Pick a simulator and press ⌘R. For a device, set your signing team and a bundle identifier of
   your own on the **MyRadarSDKDemo** target first.

Radar and warnings should appear within a few seconds. All weather data comes through the MyRadar
API gateway, so if the base map shows but radar and warnings never do, the key is missing or was
rejected. `[MyRadarSDK] No API key` in Xcode's console means the app received no key (check the
variable name and its checkbox); otherwise check the key for stray spaces or quotes. The
environment variable is passed only when Xcode launches the app, so run with ⌘R rather than from
the home screen.

How the SDK is wired in, as in any third-party app:

- The project depends on the `MyRadarSDK` package product, which embeds the SDK and its rendering
  engine.
- `MyRadarSDKDemoApp` calls `MyRadarSDK.configure(apiKey:)` at launch (see `DemoSecrets.swift`).

## What it shows

| Screen element | SDK API exercised |
|---|---|
| Map with radar, warnings, lightning | `MyRadarMap`, `.overlays(_:)` |
| Layer chips | `.radarEnabled(_:)`, `.overlays(_:)`, `Overlay` |
| Layers menu | `.mapStyle(_:)`, `.radarProduct(_:)` |
| Opacity row | `.radarOpacity(_:)`, `.overlayOpacity(_:for:)`, `Overlay.supportsOpacity` |
| The flight on the map | `.flight(_:)` |
| Flight card | `.onFlightUpdate(_:)` with `FlightInfo` |
| "Couldn't find that flight" banner | `.onFlightError(_:)` with `FlightError` |
| Flood mock (`--mock flood`) | `.polygons(_:)`, `.markers(_:)`, `.onMarkerTap(_:)` |
| Attributions panel | `MyRadarMapView.licensesAndAttributions` |

The app has no flight-data code: it passes the flight number to the map and shows the
`FlightInfo` the SDK reports.

```swift
MyRadarMap()
    .flight(ident)                          // nil shows no flight
    .onFlightUpdate { flight = $0 }         // on load and every update
    .onFlightError { errorMessage = $0.localizedDescription }
```

## Launch arguments

For testing and screenshots, the app accepts launch arguments, for example from the command line:

```sh
xcrun simctl launch booted com.myradar.MyRadarSDKDemo --query UA1 --overlays warnings,lightning
```

Others include `--center`, `--zoom`, `--kiosk`, `--style`, `--opacity`, `--opacity-panel` (opens
the opacity row on a layer) and `--mock flood`; see `ContentView.swift`.
