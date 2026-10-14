//
//  FloodMock.swift
//  MyRadarSDKDemo
//
//  Mock flood-forecast overlay for marketing captures. There is no flood API
//  behind this: it draws procedurally generated water-depth bands with the SDK's
//  polygon API and a depth marker with the marker API, to show what a flood
//  product could look like on the map. Enabled with `--mock flood`.
//

import UIKit
import CoreLocation
import MyRadar_iOS_SDK

enum FloodMock {

    /// Default location: downtown Tampa, along the Hillsborough River. Override with `--center`.
    static let center = CLLocationCoordinate2D(latitude: 27.9506, longitude: -82.4572)
    static let zoom: Float = 12

    /// Depth bands from shallow (outer) to deep (inner), with the marker placed in the 2 ft band.
    static func polygons(around center: CLLocationCoordinate2D = center) -> [MapPolygon] {
        let bands: [(depth: String, radius: Double, color: UIColor)] = [
            ("1 ft", 4200, UIColor(red: 0.45, green: 0.75, blue: 1.0, alpha: 0.40)),
            ("2 ft", 2700, UIColor(red: 0.20, green: 0.50, blue: 0.95, alpha: 0.55)),
            ("3 ft", 1400, UIColor(red: 0.05, green: 0.25, blue: 0.75, alpha: 0.70)),
        ]
        var result: [MapPolygon] = []
        for (index, band) in bands.enumerated() {
            // Two flooded pockets: the river bend downtown and a low area to the south-east.
            result.append(MapPolygon(id: "flood-river-\(index)",
                                     coordinates: blob(around: center, radiusMeters: band.radius,
                                                       stretch: 1.6, rotation: 0.35, seed: Double(index) + 1),
                                     fillColor: band.color,
                                     strokeColor: index == bands.count - 1 ? UIColor.white.withAlphaComponent(0.6) : nil,
                                     strokeWidth: 1,
                                     userInfo: ["depth": band.depth]))
            let bayou = CLLocationCoordinate2D(latitude: center.latitude - 0.045, longitude: center.longitude + 0.036)
            result.append(MapPolygon(id: "flood-bayou-\(index)",
                                     coordinates: blob(around: bayou, radiusMeters: band.radius * 0.6,
                                                       stretch: 1.2, rotation: -0.8, seed: Double(index) + 7),
                                     fillColor: band.color,
                                     userInfo: ["depth": band.depth]))
        }
        return result
    }

    /// A "2 ft" depth marker inside the 2 ft band.
    static func markers(around center: CLLocationCoordinate2D = center) -> [MapMarker] {
        let spot = CLLocationCoordinate2D(latitude: center.latitude + 0.010, longitude: center.longitude - 0.020)
        return [MapMarker(id: "flood-depth", coordinate: spot, icon: .image(depthPill("2 ft")),
                          userInfo: ["depth": "2 ft"])]
    }

    // MARK: - Geometry

    /// An irregular, roughly elliptical ring around a center.
    private static func blob(around c: CLLocationCoordinate2D, radiusMeters: Double, stretch: Double,
                             rotation: Double, seed: Double) -> [CLLocationCoordinate2D] {
        let points = 36
        let metersPerDegreeLat = 111_320.0
        let metersPerDegreeLon = metersPerDegreeLat * cos(c.latitude * .pi / 180)
        return (0..<points).map { i in
            let t = Double(i) / Double(points) * 2 * .pi
            // Low-frequency wobble so the edge looks like terrain, not a circle.
            let wobble = 1 + 0.18 * sin(3 * t + seed) + 0.10 * sin(5 * t - seed * 1.3) + 0.06 * sin(8 * t + seed * 0.7)
            let r = radiusMeters * wobble
            let x = r * stretch * cos(t), y = r * sin(t)
            let xr = x * cos(rotation) - y * sin(rotation)
            let yr = x * sin(rotation) + y * cos(rotation)
            return CLLocationCoordinate2D(latitude: c.latitude + yr / metersPerDegreeLat,
                                          longitude: c.longitude + xr / metersPerDegreeLon)
        }
    }

    // MARK: - Marker image

    /// A rounded pill with a water-drop glyph and the depth text, pointing north (no rotation needed).
    private static func depthPill(_ text: String) -> UIImage {
        let size = CGSize(width: 92, height: 40)
        return UIGraphicsImageRenderer(size: size).image { context in
            let cg = context.cgContext
            cg.setShadow(offset: CGSize(width: 0, height: 1), blur: 4, color: UIColor.black.withAlphaComponent(0.6).cgColor)
            let pill = UIBezierPath(roundedRect: CGRect(origin: .zero, size: size).insetBy(dx: 3, dy: 3), cornerRadius: 17)
            UIColor(red: 0.05, green: 0.30, blue: 0.80, alpha: 1).setFill()
            pill.fill()
            cg.setShadow(offset: .zero, blur: 0, color: nil)
            UIColor.white.setStroke()
            pill.lineWidth = 1.5
            pill.stroke()

            let symbol = UIImage(systemName: "drop.fill",
                                 withConfiguration: UIImage.SymbolConfiguration(pointSize: 15, weight: .bold))?
                .withTintColor(.white, renderingMode: .alwaysOriginal)
            symbol?.draw(in: CGRect(x: 14, y: 11, width: 14, height: 18))

            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 17, weight: .bold),
                .foregroundColor: UIColor.white,
            ]
            let string = NSAttributedString(string: text, attributes: attributes)
            let textSize = string.size()
            string.draw(at: CGPoint(x: 36, y: (size.height - textSize.height) / 2))
        }
    }
}
