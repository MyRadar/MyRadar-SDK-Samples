//
//  FlightDataService.swift
//  MyRadarSDKDemo
//
//  Flight data access. The live implementation uses two free, keyless public APIs:
//
//    - adsb.lol  (https://api.adsb.lol)      live ADS-B positions, routes, airports. ODbL licensed.
//    - adsbdb    (https://api.adsbdb.com)    airline names and marketing flight numbers.
//
//  Neither provides scheduled times, so the app estimates departure and arrival
//  from distance and ground speed. A sample implementation provides an offline
//  flight so the demo works without network access.
//

import Foundation
import CoreLocation

protocol FlightDataService: Sendable {
    /// The most recent position report for an ICAO callsign such as "UAL1".
    func aircraft(callsign: String) async throws -> AircraftState?
    /// The most recent position report for an ICAO 24-bit address such as "a1bfac".
    func aircraft(hex: String) async throws -> AircraftState?
    /// Aircraft within a radius of a coordinate.
    func aircraft(near coordinate: CLLocationCoordinate2D, radiusNauticalMiles: Int) async throws -> [AircraftState]
    /// Filed routes for several callsigns, keyed by callsign. Unknown callsigns are omitted.
    func routes(for states: [AircraftState]) async throws -> [String: FlightRoute]
    /// Airline details for a callsign, when known.
    func airline(callsign: String) async throws -> AirlineInfo?
    /// Airport details for an ICAO ("KSFO") or IATA ("SFO") code.
    func airport(code: String) async throws -> Airport?
    /// Recorded positions for the aircraft's current leg, oldest first. Empty when unknown.
    func track(hex: String) async throws -> [TrackPoint]
}

enum FlightDataError: LocalizedError {
    case badResponse(Int)
    case notFound

    var errorDescription: String? {
        switch self {
        case .badResponse(let code): return "The flight data service returned HTTP \(code)."
        case .notFound: return "No matching flight was found."
        }
    }
}

// MARK: - Live implementation

struct ADSBFlightDataService: FlightDataService {

    private let session: URLSession
    private let decoder = JSONDecoder()

    init(session: URLSession = .shared) {
        self.session = session
    }

    private static let adsbLol = URL(string: "https://api.adsb.lol")!
    private static let adsbdb = URL(string: "https://api.adsbdb.com")!
    private static let hexdb = URL(string: "https://hexdb.io")!

    // MARK: Aircraft

    func aircraft(callsign: String) async throws -> AircraftState? {
        let trimmed = callsign.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        let response: ADSBResponse = try await get(Self.adsbLol.appending(path: "v2/callsign/\(trimmed)"))
        return response.ac.compactMap(AircraftState.init).first
    }

    func aircraft(hex: String) async throws -> AircraftState? {
        let response: ADSBResponse = try await get(Self.adsbLol.appending(path: "v2/hex/\(hex)"))
        return response.ac.compactMap(AircraftState.init).first
    }

    func aircraft(near coordinate: CLLocationCoordinate2D, radiusNauticalMiles: Int) async throws -> [AircraftState] {
        let radius = min(max(radiusNauticalMiles, 1), 250)
        let path = String(format: "v2/point/%.4f/%.4f/%d", coordinate.latitude, coordinate.longitude, radius)
        let response: ADSBResponse = try await get(Self.adsbLol.appending(path: path))
        return response.ac.compactMap(AircraftState.init)
    }

    // MARK: Routes

    func routes(for states: [AircraftState]) async throws -> [String: FlightRoute] {
        let candidates = states.filter { !$0.callsign.isEmpty }
        guard !candidates.isEmpty else { return [:] }

        // Batch lookup. adsb.lol only answers this endpoint for requests that carry its own
        // site as the referer; otherwise it returns an empty 201.
        var request = URLRequest(url: Self.adsbLol.appending(path: "api/0/routeset"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("https://adsb.lol/", forHTTPHeaderField: "Referer")
        let planes = candidates.prefix(120).map { state in
            ["callsign": state.callsign, "lat": state.coordinate.latitude, "lng": state.coordinate.longitude] as [String: Any]
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: ["planes": planes])

        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode), !data.isEmpty,
           let entries = try? decoder.decode([RouteEntry].self, from: data) {
            var result: [String: FlightRoute] = [:]
            for entry in entries {
                if let route = entry.route {
                    result[entry.callsign] = route
                }
            }
            if !result.isEmpty { return result }
        }

        // Fallback: the same data as static files, one request per callsign.
        return try await withThrowingTaskGroup(of: (String, FlightRoute?).self) { group in
            for state in candidates.prefix(25) {
                group.addTask { (state.callsign, try? await staticRoute(callsign: state.callsign)) }
            }
            var result: [String: FlightRoute] = [:]
            for try await (callsign, route) in group {
                if let route { result[callsign] = route }
            }
            return result
        }
    }

    private func staticRoute(callsign: String) async throws -> FlightRoute? {
        guard callsign.count >= 2 else { return nil }
        let prefix = String(callsign.prefix(2))
        let url = URL(string: "https://vrs-standing-data.adsb.lol/routes/\(prefix)/\(callsign).json")!
        let entry: RouteEntry = try await get(url)
        return entry.route
    }

    // MARK: Airline

    func airline(callsign: String) async throws -> AirlineInfo? {
        let url = Self.adsbdb.appending(path: "v0/callsign/\(callsign)")
        let (data, response) = try await session.data(from: url)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { return nil }
        let envelope = try decoder.decode(ADSBDBCallsignEnvelope.self, from: data)
        guard let flightRoute = envelope.response.flightroute else { return nil }
        let number = flightRoute.callsign_iata.map {
            Self.spacedFlightNumber($0, airlineCode: flightRoute.airline?.iata)
        }
        return AirlineInfo(name: flightRoute.airline?.name ?? flightRoute.callsign,
                           iata: flightRoute.airline?.iata,
                           icao: flightRoute.airline?.icao,
                           iataFlightNumber: number)
    }

    /// "UA1" -> "UA 1", "B6413" -> "B6 413" for display. Airline codes may contain digits,
    /// so prefer splitting after the known code.
    private static func spacedFlightNumber(_ compact: String, airlineCode: String?) -> String {
        if let airlineCode, !airlineCode.isEmpty, compact.hasPrefix(airlineCode),
           compact.count > airlineCode.count {
            return airlineCode + " " + compact.dropFirst(airlineCode.count)
        }
        guard let firstDigit = compact.firstIndex(where: \.isNumber) else { return compact }
        return compact[..<firstDigit] + " " + compact[firstDigit...]
    }

    // MARK: Airports

    func airport(code: String) async throws -> Airport? {
        let normalized = code.uppercased().trimmingCharacters(in: .whitespaces)
        if normalized.count == 4, let airport = try? await adsbLolAirport(icao: normalized) {
            return airport
        }
        if normalized.count == 3 {
            if let airport = try? await hexdbAirport(iata: normalized) {
                return airport
            }
            // Most US airports are the IATA code prefixed with K.
            if let airport = try? await adsbLolAirport(icao: "K" + normalized) {
                return airport
            }
        }
        return nil
    }

    private func adsbLolAirport(icao: String) async throws -> Airport? {
        let url = Self.adsbLol.appending(path: "api/0/airport/\(icao)")
        let (data, response) = try await session.data(from: url)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              let entry = try? decoder.decode(RouteAirport.self, from: data) else { return nil }
        return entry.airport
    }

    private func hexdbAirport(iata: String) async throws -> Airport? {
        let url = Self.hexdb.appending(path: "api/v1/airport/iata/\(iata)")
        let (data, response) = try await session.data(from: url)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              let entry = try? decoder.decode(HexDBAirport.self, from: data),
              let icao = entry.icao, let lat = entry.latitude, let lon = entry.longitude else { return nil }
        return Airport(icao: icao, iata: entry.iata, name: entry.airport ?? icao, city: entry.region_name,
                       coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon))
    }

    // MARK: Track history

    /// adsb.lol publishes each aircraft's trace for the current day (the same feed its
    /// map UI reads) as a readsb trace file. Rows are
    /// `[secondsSinceBase, lat, lon, altitudeOrGround, groundSpeed, track, flags, verticalRate, …]`;
    /// flag bit 1 marks the start of a new leg.
    func track(hex: String) async throws -> [TrackPoint] {
        let lower = hex.lowercased()
        guard lower.count >= 2 else { return [] }
        let primary = try await adsbLolTrace(hex: lower)
        if !primary.isEmpty { return primary }
        // The trace feed is undocumented; fall back to OpenSky's track endpoint (anonymous
        // access, research-use terms, acceptable for this non-commercial demo).
        return (try? await openSkyTrack(hex: lower)) ?? []
    }

    private func adsbLolTrace(hex lower: String) async throws -> [TrackPoint] {
        let url = URL(string: "https://adsb.lol/data/traces/\(lower.suffix(2))/trace_full_\(lower).json")!
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let base = object["timestamp"] as? Double,
              let rows = object["trace"] as? [[Any]] else {
            return []
        }

        var points: [TrackPoint] = []
        points.reserveCapacity(rows.count)
        for row in rows where row.count >= 7 {
            guard let offset = row[0] as? Double, let lat = row[1] as? Double, let lon = row[2] as? Double else {
                continue
            }
            let onGround = (row[3] as? String) == "ground"
            let altitude = row[3] as? Double
            let flags = (row[6] as? Int) ?? 0
            let newLeg = flags & 2 != 0
            let timestamp = Date(timeIntervalSince1970: base + offset)
            // Keep only the most recent leg: restart at a takeoff marker, after the aircraft
            // was last on the ground, or after a long silence that began at low altitude
            // (a landing where ground coverage was missing).
            var startsNewLeg = newLeg || (points.last?.onGround == true && !onGround)
            if let previous = points.last, timestamp.timeIntervalSince(previous.timestamp) > 30 * 60,
               (previous.altitudeFeet ?? 0) < 10_000 {
                startsNewLeg = true
            }
            if startsNewLeg {
                points.removeAll(keepingCapacity: true)
            }
            points.append(TrackPoint(coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon),
                                     timestamp: timestamp, altitudeFeet: altitude, onGround: onGround))
        }
        // Drop taxi positions so the path starts at the takeoff roll.
        if let firstAirborne = points.firstIndex(where: { !$0.onGround }) {
            points.removeFirst(max(firstAirborne - 1, 0))
        }
        return points
    }

    /// OpenSky `/tracks/all` for the current flight. Waypoints are
    /// `[time, lat, lon, baroAltitudeMeters, trueTrack, onGround]`.
    private func openSkyTrack(hex lower: String) async throws -> [TrackPoint] {
        let url = URL(string: "https://opensky-network.org/api/tracks/all?icao24=\(lower)&time=0")!
        let (data, response) = try await session.data(from: url)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = object["path"] as? [[Any]] else {
            return []
        }
        return rows.compactMap { row in
            guard row.count >= 6, let time = row[0] as? Double,
                  let lat = row[1] as? Double, let lon = row[2] as? Double else { return nil }
            let meters = row[3] as? Double
            return TrackPoint(coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon),
                              timestamp: Date(timeIntervalSince1970: time),
                              altitudeFeet: meters.map { $0 * 3.28084 },
                              onGround: (row[5] as? Bool) ?? false)
        }
    }

    // MARK: Transport

    private func get<T: Decodable>(_ url: URL) async throws -> T {
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw FlightDataError.badResponse(http.statusCode)
        }
        return try decoder.decode(T.self, from: data)
    }
}

// MARK: - Wire formats

/// adsb.lol `/v2/...` envelope.
private struct ADSBResponse: Decodable {
    let ac: [ADSBAircraft]
    let now: Double?
}

/// A readsb-style aircraft record. Only the fields the demo uses.
private struct ADSBAircraft: Decodable {
    let hex: String
    let flight: String?
    let r: String?
    let t: String?
    let lat: Double?
    let lon: Double?
    let track: Double?
    let true_heading: Double?
    let mag_heading: Double?
    let alt_baro: Altitude?
    let gs: Double?
    let baro_rate: Double?
    let geom_rate: Double?
    let seen_pos: Double?

    /// `alt_baro` is a number in feet, or the string "ground".
    enum Altitude: Decodable {
        case feet(Double)
        case ground

        init(from decoder: Decoder) throws {
            let container = try decoder.singleValueContainer()
            if let value = try? container.decode(Double.self) {
                self = .feet(value)
            } else if let string = try? container.decode(String.self), string == "ground" {
                self = .ground
            } else {
                throw DecodingError.dataCorruptedError(in: container, debugDescription: "Unexpected alt_baro")
            }
        }
    }
}

private extension AircraftState {
    init?(_ record: ADSBAircraft) {
        guard let lat = record.lat, let lon = record.lon else { return nil }
        let altitude: Double?
        let onGround: Bool
        switch record.alt_baro {
        case .feet(let feet): altitude = feet; onGround = false
        case .ground: altitude = nil; onGround = true
        case nil: altitude = nil; onGround = false
        }
        self.init(
            hex: record.hex,
            callsign: (record.flight ?? "").trimmingCharacters(in: .whitespaces),
            registration: record.r,
            typeCode: record.t,
            coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon),
            heading: record.track ?? record.true_heading ?? record.mag_heading,
            altitudeFeet: altitude,
            onGround: onGround,
            groundSpeedKnots: record.gs,
            verticalRateFeetPerMinute: record.baro_rate ?? record.geom_rate,
            timestamp: Date().addingTimeInterval(-(record.seen_pos ?? 0))
        )
    }
}

/// adsb.lol route entry (routeset response element and static route file).
private struct RouteEntry: Decodable {
    let callsign: String
    let airport_codes: String?
    let _airports: [RouteAirport]?

    var route: FlightRoute? {
        guard let airports = _airports?.compactMap(\.airport), airports.count >= 2 else { return nil }
        return FlightRoute(airports: airports)
    }
}

private struct RouteAirport: Decodable {
    let name: String?
    let icao: String?
    let iata: String?
    let location: String?
    let lat: Double?
    let lon: Double?

    var airport: Airport? {
        guard let icao, let lat, let lon else { return nil }
        return Airport(icao: icao, iata: iata?.isEmpty == false ? iata : nil, name: name ?? icao,
                       city: location, coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon))
    }
}

private struct ADSBDBCallsignEnvelope: Decodable {
    struct Response: Decodable {
        let flightroute: FlightRouteRecord?
    }
    struct FlightRouteRecord: Decodable {
        let callsign: String
        let callsign_iata: String?
        let airline: AirlineRecord?
    }
    struct AirlineRecord: Decodable {
        let name: String
        let icao: String?
        let iata: String?
    }
    let response: Response
}

private struct HexDBAirport: Decodable {
    let airport: String?
    let iata: String?
    let icao: String?
    let latitude: Double?
    let longitude: Double?
    let region_name: String?
}

// MARK: - Offline sample

/// A simulated San Francisco to New York flight that advances in real time, so the
/// demo can be exercised with no network connection and no live traffic.
struct SampleFlightDataService: FlightDataService {
    static let callsign = "MYR123"
    static let hex = "sample"

    private static let sfo = Airport(icao: "KSFO", iata: "SFO", name: "San Francisco International Airport",
                                     city: "San Francisco",
                                     coordinate: CLLocationCoordinate2D(latitude: 37.619, longitude: -122.375))
    private static let jfk = Airport(icao: "KJFK", iata: "JFK", name: "John F. Kennedy International Airport",
                                     city: "New York",
                                     coordinate: CLLocationCoordinate2D(latitude: 40.6413, longitude: -73.7781))
    private static let cruiseSpeedKnots = 470.0
    /// The flight "departed" 90 minutes before the service was created.
    private let departure = Date().addingTimeInterval(-90 * 60)

    func aircraft(callsign: String) async throws -> AircraftState? {
        callsign.uppercased() == Self.callsign ? currentState() : nil
    }

    func aircraft(hex: String) async throws -> AircraftState? {
        hex == Self.hex ? currentState() : nil
    }

    func aircraft(near coordinate: CLLocationCoordinate2D, radiusNauticalMiles: Int) async throws -> [AircraftState] {
        [currentState()]
    }

    func routes(for states: [AircraftState]) async throws -> [String: FlightRoute] {
        [Self.callsign: FlightRoute(airports: [Self.sfo, Self.jfk])]
    }

    func airline(callsign: String) async throws -> AirlineInfo? {
        AirlineInfo(name: "MyRadar Air (sample)", iata: "MY", icao: "MYR", iataFlightNumber: "MY 123")
    }

    func airport(code: String) async throws -> Airport? {
        [Self.sfo, Self.jfk].first { $0.icao == code.uppercased() || $0.iata == code.uppercased() }
    }

    /// A gently weaving path from the departure airport to the current position.
    func track(hex: String) async throws -> [TrackPoint] {
        guard hex == Self.hex else { return [] }
        let state = currentState()
        let total = GreatCircle.distanceNauticalMiles(Self.sfo.coordinate, Self.jfk.coordinate)
        let flownFraction = GreatCircle.distanceNauticalMiles(Self.sfo.coordinate, state.coordinate) / total
        let steps = 60
        return (0...steps).map { i in
            let f = flownFraction * Double(i) / Double(steps)
            var c = GreatCircle.interpolate(from: Self.sfo.coordinate, to: Self.jfk.coordinate, fraction: f)
            c.latitude += 0.35 * sin(f * 9)    // simulated routing around weather
            let elapsed = f * total / Self.cruiseSpeedKnots * 3600
            return TrackPoint(coordinate: c, timestamp: departure.addingTimeInterval(elapsed),
                              altitudeFeet: i == 0 ? nil : 36_000, onGround: i == 0)
        }
    }

    private func currentState() -> AircraftState {
        let total = GreatCircle.distanceNauticalMiles(Self.sfo.coordinate, Self.jfk.coordinate)
        let flown = min(total, Date().timeIntervalSince(departure) / 3600 * Self.cruiseSpeedKnots)
        let fraction = flown / total
        let position = GreatCircle.interpolate(from: Self.sfo.coordinate, to: Self.jfk.coordinate, fraction: fraction)
        let landed = fraction >= 1
        return AircraftState(
            hex: Self.hex,
            callsign: Self.callsign,
            registration: "N123MR",
            typeCode: "B738",
            coordinate: position,
            heading: GreatCircle.initialBearing(from: position, to: Self.jfk.coordinate),
            altitudeFeet: landed ? nil : 36_000,
            onGround: landed,
            groundSpeedKnots: landed ? 0 : Self.cruiseSpeedKnots,
            verticalRateFeetPerMinute: 0,
            timestamp: Date()
        )
    }
}
