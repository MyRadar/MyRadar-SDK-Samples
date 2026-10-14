//
//  FlightModels.swift
//  MyRadarSDKDemo
//
//  Plain value types describing a tracked flight. Independent of any data source.
//

import Foundation
import CoreLocation

struct Airport: Equatable, Hashable {
    let icao: String
    let iata: String?
    let name: String
    let city: String?
    let coordinate: CLLocationCoordinate2D

    /// The short code most travellers recognise.
    var displayCode: String { iata ?? icao }

    static func == (lhs: Airport, rhs: Airport) -> Bool { lhs.icao == rhs.icao }
    func hash(into hasher: inout Hasher) { hasher.combine(icao) }
}

/// One ADS-B position report for an aircraft.
struct AircraftState: Equatable {
    let hex: String
    let callsign: String
    let registration: String?
    let typeCode: String?
    let coordinate: CLLocationCoordinate2D
    /// True track in degrees clockwise from north.
    let heading: Double?
    let altitudeFeet: Double?
    let onGround: Bool
    let groundSpeedKnots: Double?
    let verticalRateFeetPerMinute: Double?
    let timestamp: Date

    static func == (lhs: AircraftState, rhs: AircraftState) -> Bool {
        lhs.hex == rhs.hex
            && lhs.coordinate.latitude == rhs.coordinate.latitude
            && lhs.coordinate.longitude == rhs.coordinate.longitude
            && lhs.heading == rhs.heading
            && lhs.altitudeFeet == rhs.altitudeFeet
            && lhs.groundSpeedKnots == rhs.groundSpeedKnots
            && lhs.timestamp == rhs.timestamp
    }
}

/// One historical position from a flight's recorded track.
struct TrackPoint: Equatable {
    let coordinate: CLLocationCoordinate2D
    let timestamp: Date
    let altitudeFeet: Double?
    let onGround: Bool

    static func == (lhs: TrackPoint, rhs: TrackPoint) -> Bool {
        lhs.timestamp == rhs.timestamp
            && lhs.coordinate.latitude == rhs.coordinate.latitude
            && lhs.coordinate.longitude == rhs.coordinate.longitude
    }
}

/// The filed route for a callsign: two or more airports in order.
struct FlightRoute: Equatable {
    let airports: [Airport]

    var origin: Airport? { airports.first }
    var destination: Airport? { airports.count >= 2 ? airports.last : nil }

    /// For multi-stop routes, the leg whose origin is nearest to where the recorded
    /// track begins. Falls back to the whole route when no stop is within 50 nm.
    func leg(startingNear start: CLLocationCoordinate2D) -> FlightRoute {
        guard airports.count > 2 else { return self }
        let candidates = airports.dropLast().enumerated()
        guard let nearest = candidates.min(by: {
            GreatCircle.distanceNauticalMiles($0.element.coordinate, start)
                < GreatCircle.distanceNauticalMiles($1.element.coordinate, start)
        }), GreatCircle.distanceNauticalMiles(nearest.element.coordinate, start) <= 50 else {
            return self
        }
        return FlightRoute(airports: [airports[nearest.offset], airports[nearest.offset + 1]])
    }

    /// "SFO → SIN" style summary.
    var summary: String {
        airports.map(\.displayCode).joined(separator: " → ")
    }
}

struct AirlineInfo: Equatable {
    let name: String
    let iata: String?
    let icao: String?
    /// Marketing flight number such as "UA 1", when known.
    let iataFlightNumber: String?
}

/// A search hit: enough to list a flight and start tracking it.
struct FlightSummary: Identifiable, Equatable {
    var id: String { state.hex }
    let state: AircraftState
    let route: FlightRoute?

    var title: String { state.callsign }
    var subtitle: String {
        var parts: [String] = []
        if let route { parts.append(route.summary) }
        if let type = state.typeCode { parts.append(type) }
        if let altitude = state.altitudeFeet, !state.onGround {
            parts.append("\(Int(altitude)) ft")
        } else if state.onGround {
            parts.append("on ground")
        }
        return parts.joined(separator: " · ")
    }
}

/// Everything the UI shows for the flight being followed.
struct TrackedFlight: Equatable {
    var state: AircraftState
    var route: FlightRoute?
    var airline: AirlineInfo?
    /// The path flown on this leg, oldest first: the recorded track where available,
    /// extended with positions observed while tracking.
    var track: [CLLocationCoordinate2D]
    /// When the aircraft left the ground on this leg, when the recorded track shows it.
    var departureTime: Date?
    let trackingSince: Date

    static func == (lhs: TrackedFlight, rhs: TrackedFlight) -> Bool {
        lhs.state == rhs.state
            && lhs.route == rhs.route
            && lhs.airline == rhs.airline
            && lhs.track.count == rhs.track.count
            && lhs.departureTime == rhs.departureTime
            && lhs.trackingSince == rhs.trackingSince
    }

    /// Flight number for display: marketing number if known, else the callsign.
    var displayNumber: String {
        airline?.iataFlightNumber ?? state.callsign
    }

    /// Human-readable aircraft description, e.g. "Boeing 787-9 · N21102".
    var aircraftDescription: String {
        var parts: [String] = []
        if let code = state.typeCode {
            parts.append(AircraftTypes.name(for: code))
        }
        if let registration = state.registration {
            parts.append(registration)
        }
        return parts.isEmpty ? "Unknown aircraft" : parts.joined(separator: " · ")
    }
}
