//
//  AirportIndex.swift
//  MyRadarSDKDemo
//
//  Offline airport lookup backed by Airports.json, a filtered copy of the
//  OurAirports dataset (public domain): large airports plus medium airports with
//  scheduled service. Used to work out where a recorded track actually began.
//

import Foundation
import CoreLocation

enum AirportIndex {

    private struct Record: Decodable {
        let icao: String
        let iata: String?
        let name: String
        let city: String?
        let lat: Double
        let lon: Double
    }

    private static let airports: [Airport] = {
        guard let url = Bundle.main.url(forResource: "Airports", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let records = try? JSONDecoder().decode([Record].self, from: data) else {
            return []
        }
        return records.map {
            Airport(icao: $0.icao, iata: $0.iata, name: $0.name, city: $0.city,
                    coordinate: CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon))
        }
    }()

    /// The airport closest to a coordinate, if one lies within `radiusNauticalMiles`.
    static func nearest(to coordinate: CLLocationCoordinate2D, within radiusNauticalMiles: Double = 15) -> Airport? {
        var best: (Airport, Double)?
        for airport in airports {
            // Cheap bounding-box reject before the spherical distance.
            guard abs(airport.coordinate.latitude - coordinate.latitude) < 1,
                  abs(airport.coordinate.longitude - coordinate.longitude) < 1.5 else { continue }
            let distance = GreatCircle.distanceNauticalMiles(airport.coordinate, coordinate)
            if distance <= radiusNauticalMiles, distance < (best?.1 ?? .infinity) {
                best = (airport, distance)
            }
        }
        return best?.0
    }

    /// Looks up an airport by ICAO or IATA code.
    static func airport(code: String) -> Airport? {
        let upper = code.uppercased()
        return airports.first { $0.icao == upper || $0.iata == upper }
    }
}
