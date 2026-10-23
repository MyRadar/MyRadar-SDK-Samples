//
//  ContentView.swift
//  MyRadarSDKDemo
//
//  A one-screen flight tracker built on the MyRadar iOS SDK. Controls sit above
//  the map, the map has its own panel, and the flight details card sits below.
//  The SDK's `.flight(_:)` modifier looks the flight up and draws it over live
//  radar, warnings and lightning; the card shows the FlightInfo it reports.
//

import SwiftUI
import CoreLocation
import MyRadar_iOS_SDK

struct ContentView: View {
    /// What the user typed, and the flight number the map shows.
    @State private var searchText = ""
    @State private var ident: String?
    /// The flight as the SDK last reported it, and why it couldn't be shown.
    @State private var flight: FlightInfo?
    @State private var errorMessage: String?
    @State private var showAttributions = false
    @State private var tappedMarkerMessage: String?
    @FocusState private var searchFocused: Bool

    // Map configuration, driven by the Layers menu.
    @State private var mapStyle: MapStyle = .gray
    @State private var radarProduct: RadarProduct = .radar
    @State private var isRadarEnabled = true
    @State private var overlays: Set<Overlay> = [.warnings, .lightning]

    /// `--kiosk` shows only the map, full screen, for captures of map functionality alone.
    private let isKiosk = ProcessInfo.processInfo.arguments.contains("--kiosk")

    /// `--mock flood` paints a mock flood-forecast overlay (see FloodMock).
    private let showsFloodMock = ContentView.launchArgument("--mock") == "flood"

    /// Which layer the opacity slider controls.
    enum OpacityTarget: Hashable {
        case radar
        case overlay(Overlay)

        var title: String {
            switch self {
            case .radar: return "Radar"
            case .overlay(let overlay): return overlay.displayName
            }
        }

        /// Radar plus every overlay whose opacity the SDK can adjust.
        static let all: [OpacityTarget] = [.radar] + Overlay.allCases.filter(\.supportsOpacity).map { .overlay($0) }
    }

    @State private var showsOpacityControl = false
    @State private var opacityTarget: OpacityTarget = .radar
    @State private var radarOpacity: Float = ContentView.launchArgument("--mock") == "flood" ? 0.4 : 0.65

    /// Per-overlay opacities; seeded from `--opacity warnings=0.9,hurricanes=0.3` (test hook).
    @State private var overlayOpacities: [Overlay: Float] = {
        guard let spec = ContentView.launchArgument("--opacity") else { return [:] }
        var result: [Overlay: Float] = [:]
        for item in spec.split(separator: ",") {
            let parts = item.split(separator: "=")
            guard parts.count == 2, let value = Float(parts[1]) else { continue }
            let name = parts[0].lowercased()
            if let overlay = Overlay.allCases.first(where: { $0.displayName.lowercased() == name }) {
                result[overlay] = value
            }
        }
        return result
    }()

    /// The slider's value for the selected target; overlays fall back to the SDK default.
    private var opacityBinding: Binding<Float> {
        Binding(
            get: {
                switch opacityTarget {
                case .radar: return radarOpacity
                case .overlay(let overlay): return overlayOpacities[overlay] ?? overlay.defaultOpacity ?? 1
                }
            },
            set: { value in
                switch opacityTarget {
                case .radar: radarOpacity = value
                case .overlay(let overlay): overlayOpacities[overlay] = value
                }
            }
        )
    }

    /// Initial zoom. Radar morphing with motion vectors needs a regional view, so start
    /// closer than a whole-continent zoom. Overridable with the `--zoom` launch argument.
    private let initialZoom: Float = {
        if let value = ContentView.launchArgument("--zoom").flatMap(Float.init) {
            return value
        }
        return ContentView.launchArgument("--mock") == "flood" ? FloodMock.zoom : 5
    }()

    /// Initial center, overridable with `--center <lat>,<lon>`.
    private let initialCenter: CLLocationCoordinate2D = {
        if let value = ContentView.launchArgument("--center") {
            let parts = value.split(separator: ",").compactMap { Double($0) }
            if parts.count == 2 {
                return CLLocationCoordinate2D(latitude: parts[0], longitude: parts[1])
            }
        }
        return ContentView.launchArgument("--mock") == "flood"
            ? FloodMock.center
            : CLLocationCoordinate2D(latitude: 37.5, longitude: -96)
    }()

    /// Returns the value following a launch argument, if present.
    private static func launchArgument(_ name: String) -> String? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: name), index + 1 < arguments.count else { return nil }
        return arguments[index + 1]
    }

    var body: some View {
        Group {
            if isKiosk {
                map.ignoresSafeArea().statusBarHidden(true)
            } else {
                GeometryReader { proxy in
                    adaptiveLayout(size: proxy.size)
                }
                .padding()
                .background(Color(.systemGroupedBackground))
            }
        }
        .onTapGesture { searchFocused = false }
        .task { await handleLaunchArguments() }
    }

    /// Portrait: controls above the map, flight card below. Landscape: controls and card in
    /// a left column, map filling the rest. The same `mapPanel` view instance is used in
    /// both, so rotating never tears down and recreates the map.
    private func adaptiveLayout(size: CGSize) -> some View {
        let isLandscape = size.width > size.height
        let outer = isLandscape
            ? AnyLayout(HStackLayout(alignment: .top, spacing: 12))
            : AnyLayout(VStackLayout(spacing: 12))
        let columnWidth: CGFloat? = isLandscape ? min(max(size.width * 0.42, 320), 420) : nil

        return outer {
            // In landscape the controls and info share one column placed before the map;
            // in portrait the map sits between them, so the info panel is emitted separately.
            VStack(spacing: 12) {
                controls
                if isLandscape {
                    Spacer(minLength: 0)
                    infoPanel
                }
            }
            .frame(width: columnWidth)
            mapPanel
            if !isLandscape {
                infoPanel
            }
        }
    }

    /// The flight card when tracking, otherwise the hint, plus attributions when shown.
    @ViewBuilder
    private var infoPanel: some View {
        if let flight {
            FlightCardView(flight: flight, onClose: stopTracking)
        } else {
            hint
        }
        if showAttributions {
            attributionsCard
        }
    }

    // MARK: - Controls

    private var controls: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                searchBar
                layersMenu
                Button {
                    withAnimation { showAttributions.toggle() }
                } label: {
                    Image(systemName: "info.circle")
                        .font(.title2)
                }
                .accessibilityLabel("Attributions")
                Button {
                    withAnimation { showsOpacityControl.toggle() }
                } label: {
                    Image(systemName: showsOpacityControl ? "circle.lefthalf.filled.inverse" : "circle.lefthalf.filled")
                        .font(.title2)
                }
                .accessibilityLabel("Layer opacity")
            }
            layerChips
            if showsOpacityControl {
                opacityRow
            }
            if let message = errorMessage {
                banner(message, systemImage: "exclamationmark.triangle")
            }
            if let message = tappedMarkerMessage {
                banner(message, systemImage: "hand.tap")
                    .onTapGesture { tappedMarkerMessage = nil }
            }
        }
    }

    private var searchBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
            TextField("Flight number, e.g. UA 1", text: $searchText)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .focused($searchFocused)
                .onSubmit {
                    searchFocused = false
                    track(searchText)
                }
            if ident != nil && flight == nil && errorMessage == nil {
                ProgressView()
            } else if !searchText.isEmpty {
                Button {
                    searchText = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .accessibilityLabel("Clear search")
            }
        }
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    /// Base map and radar product pickers. Layer on/off switches live in `layerChips`.
    private var layersMenu: some View {
        Menu {
            Picker("Base Map", selection: $mapStyle) {
                ForEach(MapStyle.allCases, id: \.self) { style in
                    Text(style.displayName).tag(style)
                }
            }
            Picker("Radar Product", selection: $radarProduct) {
                ForEach(RadarProduct.allCases, id: \.self) { product in
                    Text(product.displayName).tag(product)
                }
            }
        } label: {
            Image(systemName: "square.3.layers.3d")
                .font(.title2)
        }
        .accessibilityLabel("Base map and radar product")
    }

    /// Layer picker plus slider, driving the SDK's radar and overlay opacity APIs.
    private var opacityRow: some View {
        HStack(spacing: 10) {
            Picker("Layer", selection: $opacityTarget) {
                ForEach(OpacityTarget.all, id: \.self) { target in
                    Text(target.title).tag(target)
                }
            }
            .pickerStyle(.menu)
            .fixedSize()
            Slider(value: opacityBinding, in: 0...1)
                .accessibilityLabel("\(opacityTarget.title) opacity")
            Text("\(Int((opacityBinding.wrappedValue * 100).rounded()))%")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 40, alignment: .trailing)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    /// One tappable chip per layer, wrapping onto as many lines as needed: filled when on.
    private var layerChips: some View {
        FlowLayout(spacing: 8) {
            chip("Radar", systemImage: "cloud.rain", isOn: $isRadarEnabled)
            ForEach(Overlay.allCases, id: \.self) { overlay in
                chip(Self.shortName(for: overlay), systemImage: Self.symbol(for: overlay),
                     isOn: overlayBinding(overlay))
            }
        }
    }

    private static func shortName(for overlay: Overlay) -> String {
        overlay == .lightningFlashes ? "Flashes" : overlay.displayName
    }

    private func chip(_ title: String, systemImage: String, isOn: Binding<Bool>) -> some View {
        Button {
            isOn.wrappedValue.toggle()
        } label: {
            Label(title, systemImage: systemImage)
                .font(.caption.weight(.medium))
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(isOn.wrappedValue ? Color.accentColor : Color(.secondarySystemGroupedBackground),
                            in: Capsule())
                .foregroundStyle(isOn.wrappedValue ? Color.white : Color.primary)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityValue(isOn.wrappedValue ? "On" : "Off")
        .accessibilityAddTraits(.isToggle)
    }

    private static func symbol(for overlay: Overlay) -> String {
        switch overlay {
        case .warnings: return "exclamationmark.triangle"
        case .lightning: return "bolt"
        case .lightningFlashes: return "bolt.fill"
        case .hurricanes: return "hurricane"
        case .wind: return "wind"
        @unknown default: return "square.3.layers.3d"
        }
    }

    /// A binding that adds or removes a single overlay from the enabled set.
    private func overlayBinding(_ overlay: Overlay) -> Binding<Bool> {
        Binding(
            get: { overlays.contains(overlay) },
            set: { enabled in
                if enabled {
                    overlays.insert(overlay)
                } else {
                    overlays.remove(overlay)
                }
            }
        )
    }

    private func banner(_ text: String, systemImage: String) -> some View {
        Label(text, systemImage: systemImage)
            .font(.footnote)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
    }

    // MARK: - Map

    private var mapPanel: some View {
        map
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    /// The configured map, shared by the panel layout and kiosk mode.
    private var map: some View {
        var view = MyRadarMap()
            .camera(center: initialCenter, zoom: initialZoom)
            .mapStyle(mapStyle)
            .radarProduct(radarProduct)
            .radarEnabled(isRadarEnabled)
            .radarOpacity(radarOpacity)
            .overlays(overlays)
        for (overlay, opacity) in overlayOpacities {
            view = view.overlayOpacity(opacity, for: overlay)
        }
        return view
            .polygons(showsFloodMock ? FloodMock.polygons(around: initialCenter) : [])
            .markers(showsFloodMock ? FloodMock.markers(around: initialCenter) : [])
            .onMarkerTap { marker in
                tappedMarkerMessage = marker.userInfo["depth"].map { "Water depth \($0)" }
            }
            // The SDK looks the flight up, draws it and updates it until it lands.
            .flight(ident)
            .onFlightUpdate { info in
                flight = info
                errorMessage = nil
            }
            .onFlightError { error in
                // A failed update keeps the last data on the map; only a failed first load is shown.
                if flight == nil { errorMessage = error.localizedDescription }
            }
    }

    // MARK: - Tracking

    private func track(_ text: String) {
        let query = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return }
        flight = nil
        errorMessage = nil
        ident = query
    }

    private func stopTracking() {
        ident = nil
        flight = nil
        errorMessage = nil
        searchText = ""
    }


    // MARK: - Hint and attributions

    private var hint: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Track a flight over live radar")
                .font(.headline)
            Text("Enter an airline flight number (UA 1, AA 1643) or a callsign (UAL1). Tap a layer chip to turn it on or off; the layers button picks the base map and radar product.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    private var attributionsCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Attributions")
                .font(.footnote.bold())
            Text("Map: " + MyRadarMapView.licensesAndAttributions.map(\.name).joined(separator: ", "))
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Launch arguments

    /// Test hooks: `--query <flight number>` shows a flight at launch, `--zoom <level>` and
    /// `--center <lat>,<lon>` set the initial camera, and `--overlays a,b,c` (names: warnings,
    /// lightning, flashes, hurricanes, wind, none) sets the initial overlays.
    private func handleLaunchArguments() async {
        // `--style <gray|roads|aerial|topographic|dark|light>` picks the base map at launch.
        if let styleName = Self.launchArgument("--style")?.lowercased(),
           let style = MapStyle.allCases.first(where: { $0.displayName.lowercased() == styleName }) {
            mapStyle = style
        }
        if let list = Self.launchArgument("--overlays") {
            let names = Set(list.lowercased().split(separator: ",").map(String.init))
            var selected: Set<Overlay> = []
            if names.contains("warnings") { selected.insert(.warnings) }
            if names.contains("lightning") { selected.insert(.lightning) }
            if names.contains("flashes") { selected.insert(.lightningFlashes) }
            if names.contains("hurricanes") { selected.insert(.hurricanes) }
            if names.contains("wind") { selected.insert(.wind) }
            overlays = selected
        }
        if let query = Self.launchArgument("--query") {
            searchText = query
            track(query)
        }
    }
}

#Preview {
    ContentView()
}
