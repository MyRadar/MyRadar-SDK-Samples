//
//  GreatCircle.swift
//  MyRadarSDKDemo
//
//  Spherical-earth helpers for distances, bearings and route interpolation.
//

import Foundation
import CoreLocation

enum GreatCircle {
    static let earthRadiusNauticalMiles = 3440.065

    /// Shortest surface distance between two coordinates in nautical miles.
    static func distanceNauticalMiles(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        let lat1 = a.latitude.radians, lat2 = b.latitude.radians
        let dLat = (b.latitude - a.latitude).radians
        let dLon = (b.longitude - a.longitude).radians
        let h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadiusNauticalMiles * asin(min(1, sqrt(h)))
    }

    /// Initial bearing from `a` toward `b`, in degrees clockwise from north.
    static func initialBearing(from a: CLLocationCoordinate2D, to b: CLLocationCoordinate2D) -> Double {
        let lat1 = a.latitude.radians, lat2 = b.latitude.radians
        let dLon = (b.longitude - a.longitude).radians
        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        let bearing = atan2(y, x).degrees
        return bearing < 0 ? bearing + 360 : bearing
    }

    /// The point a fraction of the way along the great circle from `a` to `b`.
    static func interpolate(from a: CLLocationCoordinate2D, to b: CLLocationCoordinate2D,
                            fraction f: Double) -> CLLocationCoordinate2D {
        let lat1 = a.latitude.radians, lon1 = a.longitude.radians
        let lat2 = b.latitude.radians, lon2 = b.longitude.radians
        let d = distanceNauticalMiles(a, b) / earthRadiusNauticalMiles
        guard d > 1e-9 else { return a }
        let sa = sin((1 - f) * d) / sin(d)
        let sb = sin(f * d) / sin(d)
        let x = sa * cos(lat1) * cos(lon1) + sb * cos(lat2) * cos(lon2)
        let y = sa * cos(lat1) * sin(lon1) + sb * cos(lat2) * sin(lon2)
        let z = sa * sin(lat1) + sb * sin(lat2)
        return CLLocationCoordinate2D(latitude: atan2(z, sqrt(x * x + y * y)).degrees,
                                      longitude: atan2(y, x).degrees)
    }

    /// A polyline approximating the great circle from `a` to `b`.
    static func path(from a: CLLocationCoordinate2D, to b: CLLocationCoordinate2D,
                     segments: Int = 64) -> [CLLocationCoordinate2D] {
        guard segments > 1 else { return [a, b] }
        return (0...segments).map { interpolate(from: a, to: b, fraction: Double($0) / Double(segments)) }
    }
}

private extension Double {
    var radians: Double { self * .pi / 180 }
    var degrees: Double { self * 180 / .pi }
}
