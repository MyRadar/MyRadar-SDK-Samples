# MyRadar iOS SDK demo

A one-screen flight tracker over the MyRadar weather map, built on the MyRadar iOS SDK. Search for a
flight number (`UA 1`, `DAL100`), an airport (`SFO`, `KJFK`) or a route (`SFO-LAX`), or tap the
airplane button for an offline sample flight from San Francisco to New York. Layer chips turn radar
and each overlay on and off; the layers menu picks the base map and radar product; the opacity row
sets radar, warnings, hurricanes and wind opacity. The flight card's **Follow** / **Route** switch
keeps the camera on the aircraft at a regional zoom or fits the whole route.

## Requirements

- Xcode 16 or later.
- An iPhone or iPad simulator or device.
- A MyRadar API key.

## Setup

1. Open `MyRadarSDKDemo.xcodeproj`. Xcode resolves the **MyRadarSDK** Swift package from
   `https://github.com/ACMEAtronOmatic/MyRadar-iOS-SDK-SPM`.
2. Add your API key: **Product > Scheme > Edit Scheme… > Run > Arguments > Environment Variables**,
   name `MYRADAR_API_KEY`. Don't commit the scheme with the key in it (Xcode keeps it in your
   `xcuserdata`, which is git-ignored).
3. Pick a simulator or device and run. For a device, set your signing team on the
   **MyRadarSDKDemo** target first.

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
| Flown path (solid) and remaining route (dashed) | `.polylines(_:)`, `MapPolyline` with `dashPattern` and `outlineColor` |
| Rotated aircraft icon, airport code labels | `.markers(_:)`, `MapMarker.Icon.image(_:)` with `heading`, `MapMarker.Icon.text(_:color:)` |
| Follow mode at an exact zoom | `.camera(_:animated:)` with `MapCamera` |
| Route mode | `.visibleRegion(_:animated:)`, `MyRadarMapView.region(fitting:paddingFraction:)` |
| Tapping the aircraft or an airport | `.onMarkerTap(_:)` with `userInfo` |
| Attributions panel | `MyRadarMapView.licensesAndAttributions` |

Flight data comes from free, keyless public services (adsb.lol, adsbdb.com, hexdb.io, with
OpenSky as a track fallback); the SDK itself makes no flight-related requests.

## Launch arguments

For testing and screenshots, the app accepts launch arguments, for example from the command line:

```sh
xcrun simctl launch booted com.myradar.MyRadarSDKDemo --center 46.9,-91.8 --zoom 6 --overlays warnings
```

Others include `--sample`, `--kiosk`, `--style` and `--route`; see `ContentView.swift`.
