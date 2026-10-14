//
//  FlightTracker.swift
//  MyRadarSDKDemo
//
//  Screen state for the flight tracker: search, the followed flight, periodic
//  position updates, and the map geometry derived from them.
//

import Foundation
import Observation
import UIKit
import CoreLocation
import MapKit
import MyRadar_iOS_SDK

@MainActor
@Observable
final class FlightTracker {

    // MARK: - Observable state

    var searchText = ""
    private(set) var results: [FlightSummary] = []
    private(set) var isSearching = false
    private(set) var errorMessage: String?
    private(set) var flight: TrackedFlight?
    private(set) var lastUpdate: Date?
    private(set) var isUsingSampleData = false

    /// Geometry handed to the map. Rebuilt whenever the flight changes.
    private(set) var polylines: [MapPolyline] = []
    private(set) var markers: [MapMarker] = []
    /// Set to move the camera to a region (whole-route mode); `nil` leaves it alone.
    private(set) var region: MKCoordinateRegion?
    /// Set to move the camera to an exact center and zoom (follow mode); `nil` leaves it alone.
    private(set) var camera: MapCamera?

    /// How the camera tracks the flight.
    enum CameraMode: String, CaseIterable {
        /// Stay close to the aircraft. Keeps the view regional, where radar morphing
        /// with motion vectors is available.
        case followAircraft = "Follow"
        /// Show origin, aircraft and destination together.
        case wholeRoute = "Route"
    }

    var cameraMode: CameraMode = .followAircraft {
        didSet { if cameraMode != oldValue { recenter() } }
    }

    /// Zoom used in follow mode. A whole-number regional zoom keeps the engine's
    /// motion-vector radar morphing active (see `MapCamera` documentation).
    private static let followZoom: Float = 6

    // MARK: - Private

    @ObservationIgnored private var service: any FlightDataService = ADSBFlightDataService()
    @ObservationIgnored private var pollingTask: Task<Void, Never>?
    @ObservationIgnored private let pollInterval: Duration = .seconds(10)
    @ObservationIgnored private let planeIcon = FlightTracker.makePlaneIcon()

    private static let flownColor = UIColor(red: 0.15, green: 0.85, blue: 1.0, alpha: 1)
    private static let remainingColor = UIColor(white: 1, alpha: 0.85)

    // MARK: - Search

    /// What the user typed, interpreted as a flight number, an airport, or an airport pair.
    enum Query: Equatable {
        case flight(callsign: String)
        case airport(code: String)
        case airportPair(origin: String, destination: String)

        init?(_ text: String) {
            let upper = text.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
            guard !upper.isEmpty else { return nil }
            let tokens = upper
                .replacingOccurrences(of: "→", with: "-")
                .replacingOccurrences(of: " TO ", with: "-")
                .split(whereSeparator: { $0 == "-" || $0 == " " || $0 == "," })
                .map(String.init)
            let isAirportCode: (String) -> Bool = { ($0.count == 3 || $0.count == 4) && $0.allSatisfy(\.isLetter) }

            if tokens.count == 2, isAirportCode(tokens[0]), isAirportCode(tokens[1]) {
                self = .airportPair(origin: tokens[0], destination: tokens[1])
            } else if tokens.count == 1, isAirportCode(tokens[0]) {
                self = .airport(code: tokens[0])
            } else {
                self = .flight(callsign: Airlines.callsign(fromFlightNumber: upper))
            }
        }
    }

    func search() async {
        guard let query = Query(searchText) else { return }
        isSearching = true
        errorMessage = nil
        results = []
        defer { isSearching = false }

        if isUsingSampleData {
            // Searching again leaves sample mode and goes back to live data.
            service = ADSBFlightDataService()
            isUsingSampleData = false
        }

        do {
            switch query {
            case .flight(let callsign):
                guard let state = try await service.aircraft(callsign: callsign) else {
                    errorMessage = "\(callsign) is not currently airborne or reporting a position."
                    return
                }
                let routes = try await service.routes(for: [state])
                await select(FlightSummary(state: state, route: routes[state.callsign]))

            case .airport(let code):
                try await searchAirports(origin: code, destination: nil)

            case .airportPair(let origin, let destination):
                try await searchAirports(origin: origin, destination: destination)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func searchAirports(origin originCode: String, destination destinationCode: String?) async throws {
        guard let origin = try await service.airport(code: originCode) else {
            errorMessage = "Unknown airport \(originCode). Try an IATA code like SFO or ICAO like KSFO."
            return
        }
        var destination: Airport?
        if let destinationCode {
            destination = try await service.airport(code: destinationCode)
            if destination == nil {
                errorMessage = "Unknown airport \(destinationCode)."
                return
            }
        }

        // Gather traffic around the origin, the destination, and (for a pair) the midpoint.
        var searchPoints = [origin.coordinate]
        if let destination {
            searchPoints.append(destination.coordinate)
            searchPoints.append(GreatCircle.interpolate(from: origin.coordinate, to: destination.coordinate, fraction: 0.5))
        }
        var seen: Set<String> = []
        var candidates: [AircraftState] = []
        for point in searchPoints {
            for state in try await service.aircraft(near: point, radiusNauticalMiles: 250)
            where !state.callsign.isEmpty && !state.onGround && seen.insert(state.hex).inserted {
                candidates.append(state)
            }
        }

        let routes = try await service.routes(for: candidates)
        let matches = candidates.compactMap { state -> FlightSummary? in
            guard let route = routes[state.callsign] else { return nil }
            let matchesOrigin = route.origin?.icao == origin.icao
            let matchesDestination = route.destination?.icao == origin.icao
            if let destination {
                guard matchesOrigin && route.destination?.icao == destination.icao else { return nil }
            } else {
                guard matchesOrigin || matchesDestination else { return nil }
            }
            return FlightSummary(state: state, route: route)
        }
        .sorted { ($0.route?.summary ?? "") < ($1.route?.summary ?? "") }

        if matches.isEmpty {
            let what = destination.map { "between \(origin.displayCode) and \($0.displayCode)" }
                ?? "to or from \(origin.displayCode)"
            errorMessage = "No airborne flights \(what) were found right now."
        }
        results = Array(matches.prefix(40))
    }

    // MARK: - Selection and tracking

    func select(_ summary: FlightSummary) async {
        results = []
        errorMessage = nil
        stopPolling()

        var tracked = TrackedFlight(state: summary.state, route: summary.route, airline: nil,
                                    track: [summary.state.coordinate], departureTime: nil,
                                    trackingSince: Date())
        flight = tracked
        lastUpdate = Date()
        rebuildGeometry()
        recenter()

        // Enrich in the background; failures here just leave fields blank.
        async let routeLookup = tracked.route == nil ? try? service.routes(for: [summary.state]) : nil
        async let airlineLookup = try? service.airline(callsign: summary.state.callsign)
        async let trackLookup = try? service.track(hex: summary.state.hex)
        if let routes = await routeLookup {
            tracked.route = routes[summary.state.callsign]
        }
        tracked.airline = await airlineLookup
        if let recorded = await trackLookup, !recorded.isEmpty {
            Self.merge(recorded: recorded, into: &tracked)
        }
        if flight?.state.hex == tracked.state.hex {
            flight = tracked
            rebuildGeometry()
            recenter()
        }
        startPolling()
    }

    /// Replaces the observed breadcrumb trail with the recorded track, keeping the
    /// current position at the end, and notes the takeoff time.
    private static func merge(recorded: [TrackPoint], into flight: inout TrackedFlight) {
        var path = recorded.map(\.coordinate)
        let current = flight.state.coordinate
        if let last = path.last, GreatCircle.distanceNauticalMiles(last, current) > 0.2 {
            path.append(current)
        }
        flight.track = path
        flight.departureTime = recorded.first(where: { !$0.onGround })?.timestamp
        guard let start = path.first else { return }

        // Multi-stop routes: show the leg the aircraft is actually flying.
        if let route = flight.route {
            flight.route = route.leg(startingNear: start)
        }

        // Reconcile the filed route with where the track really began. Flight numbers are
        // reused across legs and the route database is not always current, so when the
        // recording starts far from the filed origin, take the nearest airport to the
        // track start as the origin. If the aircraft is still heading toward the filed
        // origin, that airport is its next stop.
        let actualOrigin = AirportIndex.nearest(to: start)
        if let route = flight.route, let filedOrigin = route.origin {
            let startsAtFiledOrigin = GreatCircle.distanceNauticalMiles(filedOrigin.coordinate, start) <= 50
            if !startsAtFiledOrigin, let actualOrigin, actualOrigin != filedOrigin {
                let current = flight.state.coordinate
                let towardFiledOrigin = headingPoints(from: current, toward: filedOrigin.coordinate,
                                                      heading: flight.state.heading)
                let destination = towardFiledOrigin ? filedOrigin : (route.destination ?? filedOrigin)
                flight.route = FlightRoute(airports: [actualOrigin, destination])
            }
        } else if let actualOrigin {
            flight.route = FlightRoute(airports: [actualOrigin])
        }
    }

    /// Whether the aircraft's heading is within 60° of the bearing to a point.
    private static func headingPoints(from position: CLLocationCoordinate2D, toward target: CLLocationCoordinate2D,
                                      heading: Double?) -> Bool {
        guard let heading else { return false }
        let bearing = GreatCircle.initialBearing(from: position, to: target)
        var delta = abs(bearing - heading).truncatingRemainder(dividingBy: 360)
        if delta > 180 { delta = 360 - delta }
        return delta <= 60
    }

    /// Switches to the built-in simulated flight for offline use.
    func loadSample() async {
        service = SampleFlightDataService()
        isUsingSampleData = true
        searchText = SampleFlightDataService.callsign
        if let state = try? await service.aircraft(callsign: SampleFlightDataService.callsign) {
            let routes = (try? await service.routes(for: [state])) ?? [:]
            await select(FlightSummary(state: state, route: routes[state.callsign]))
        }
    }

    func clearFlight() {
        stopPolling()
        flight = nil
        polylines = []
        markers = []
        region = nil
        camera = nil
        errorMessage = nil
    }

    private func startPolling() {
        pollingTask = Task { [weak self] in
            var polls = 0
            while !Task.isCancelled {
                try? await Task.sleep(for: self?.pollInterval ?? .seconds(10))
                guard !Task.isCancelled else { break }
                await self?.refreshPosition()
                polls += 1
                // Re-sync with the recorded track every few minutes to fill any gaps.
                if polls % 30 == 0 {
                    await self?.refreshTrack()
                }
            }
        }
    }

    private func refreshTrack() async {
        guard var current = flight,
              let recorded = try? await service.track(hex: current.state.hex), !recorded.isEmpty,
              flight?.state.hex == current.state.hex else { return }
        Self.merge(recorded: recorded, into: &current)
        flight = current
        rebuildGeometry()
    }

    private func stopPolling() {
        pollingTask?.cancel()
        pollingTask = nil
    }

    /// Consecutive position polls that failed; transient errors are not shown until several in a row.
    @ObservationIgnored private var consecutivePollFailures = 0

    private func refreshPosition() async {
        guard let current = flight else { return }
        do {
            guard let state = try await service.aircraft(hex: current.state.hex) else {
                errorMessage = "\(current.state.callsign) stopped reporting. It may have landed."
                return
            }
            consecutivePollFailures = 0
            errorMessage = nil
            flight?.state = state
            lastUpdate = Date()
            // Only extend the breadcrumb trail when the aircraft actually moved.
            if let last = current.track.last,
               GreatCircle.distanceNauticalMiles(last, state.coordinate) > 0.2 {
                flight?.track.append(state.coordinate)
            }
            rebuildGeometry()
            if cameraMode == .followAircraft {
                recenter()
            }
        } catch {
            // A single failed poll (for example a rate-limit response) is not worth a banner;
            // the last known position stays on screen and the next poll usually succeeds.
            consecutivePollFailures += 1
            if consecutivePollFailures >= 3 {
                errorMessage = error.localizedDescription
            }
        }
    }

    // MARK: - Derived values

    var distanceRemainingNauticalMiles: Double? {
        guard let flight, let destination = flight.route?.destination else { return nil }
        return GreatCircle.distanceNauticalMiles(flight.state.coordinate, destination.coordinate)
    }

    var distanceFlownNauticalMiles: Double? {
        guard let flight, let origin = flight.route?.origin else { return nil }
        return GreatCircle.distanceNauticalMiles(origin.coordinate, flight.state.coordinate)
    }

    /// Fraction of the great-circle route completed, in `0...1`.
    var progress: Double? {
        guard let flown = distanceFlownNauticalMiles, let remaining = distanceRemainingNauticalMiles,
              flown + remaining > 0 else { return nil }
        return flown / (flown + remaining)
    }

    /// Flight time remaining, estimated from distance remaining and current ground speed.
    var timeRemaining: TimeInterval? {
        guard let flight, let remaining = distanceRemainingNauticalMiles,
              let speed = flight.state.groundSpeedKnots, speed > 50 else { return nil }
        return remaining / speed * 3600
    }

    /// `timeRemaining` as "H:MM", for example "2:05".
    var timeRemainingText: String? {
        guard let seconds = timeRemaining else { return nil }
        let minutes = Int((seconds / 60).rounded())
        return String(format: "%d:%02d", minutes / 60, minutes % 60)
    }

    /// Arrival estimated from distance remaining and current ground speed.
    var estimatedArrival: Date? {
        guard let flight, let seconds = timeRemaining else { return nil }
        return flight.state.timestamp.addingTimeInterval(seconds)
    }

    /// Departure estimated from distance flown and current ground speed. Used only when the
    /// recorded track does not show the takeoff.
    var estimatedDeparture: Date? {
        guard let flight, let flown = distanceFlownNauticalMiles,
              let speed = flight.state.groundSpeedKnots, speed > 50 else { return nil }
        return flight.state.timestamp.addingTimeInterval(-(flown / speed * 3600))
    }

    /// Actual takeoff time when known, otherwise the estimate. The flag says which.
    var departure: (date: Date, isEstimate: Bool)? {
        if let actual = flight?.departureTime { return (actual, false) }
        if let estimate = estimatedDeparture { return (estimate, true) }
        return nil
    }

    // MARK: - Map geometry

    func recenter() {
        guard let flight else { return }
        if cameraMode == .followAircraft {
            region = nil
            camera = MapCamera(center: flight.state.coordinate, zoom: Self.followZoom)
            return
        }
        camera = nil
        var points = [flight.state.coordinate]
        if let origin = flight.route?.origin { points.append(origin.coordinate) }
        if let destination = flight.route?.destination { points.append(destination.coordinate) }
        if points.count == 1 {
            region = MKCoordinateRegion(center: flight.state.coordinate,
                                        span: MKCoordinateSpan(latitudeDelta: 4, longitudeDelta: 4))
        } else {
            region = MyRadarMapView.region(fitting: points, paddingFraction: 0.35)
        }
    }

    private func rebuildGeometry() {
        guard let flight else {
            polylines = []
            markers = []
            return
        }
        let state = flight.state
        var lines: [MapPolyline] = []
        var pins: [MapMarker] = []

        // Flown segment: the recorded track (or observed positions). Bridge from the filed
        // origin only to cover a short coverage gap after takeoff; if the recording starts far
        // from that airport the filed route is for a different leg, so trust the track alone.
        var flown: [CLLocationCoordinate2D] = []
        if let origin = flight.route?.origin, let first = flight.track.first {
            let gap = GreatCircle.distanceNauticalMiles(origin.coordinate, first)
            if gap > 10 && gap < 150 {
                flown.append(contentsOf: GreatCircle.path(from: origin.coordinate, to: first, segments: 16))
            }
        }
        flown.append(contentsOf: flight.track)
        if flown.count >= 2 {
            lines.append(MapPolyline(id: "flown", coordinates: flown, color: Self.flownColor, width: 3,
                                     outlineColor: UIColor(white: 0, alpha: 0.6),
                                     userInfo: ["segment": "flown"]))
        }

        // Remaining segment: great circle from the aircraft to the destination.
        if let destination = flight.route?.destination {
            lines.append(MapPolyline(id: "remaining",
                                     coordinates: GreatCircle.path(from: state.coordinate, to: destination.coordinate),
                                     color: Self.remainingColor, width: 2, dashPattern: [8, 6],
                                     userInfo: ["segment": "remaining"]))
        }

        for airport in [flight.route?.origin, flight.route?.destination].compactMap({ $0 }) {
            pins.append(MapMarker(id: "airport-\(airport.icao)", coordinate: airport.coordinate,
                                  icon: .text(airport.displayCode, color: .white),
                                  userInfo: ["airport": airport.name]))
        }
        pins.append(MapMarker(id: "aircraft", coordinate: state.coordinate, icon: .image(planeIcon),
                              heading: state.heading, userInfo: ["callsign": state.callsign]))

        polylines = lines
        markers = pins
    }

    /// Renders the SF Symbol airplane pointing north, so the SDK can rotate it by heading.
    private static func makePlaneIcon() -> UIImage {
        let size = CGSize(width: 34, height: 34)
        let config = UIImage.SymbolConfiguration(pointSize: 26, weight: .bold)
        let symbol = UIImage(systemName: "airplane", withConfiguration: config)?
            .withTintColor(.white, renderingMode: .alwaysOriginal) ?? UIImage()
        return UIGraphicsImageRenderer(size: size).image { context in
            let cg = context.cgContext
            cg.translateBy(x: size.width / 2, y: size.height / 2)
            // The symbol points east; rotate it to point north.
            cg.rotate(by: -.pi / 2)
            cg.setShadow(offset: .zero, blur: 3, color: UIColor.black.cgColor)
            let rect = CGRect(x: -symbol.size.width / 2, y: -symbol.size.height / 2,
                              width: symbol.size.width, height: symbol.size.height)
            symbol.draw(in: rect)
        }
    }
}
