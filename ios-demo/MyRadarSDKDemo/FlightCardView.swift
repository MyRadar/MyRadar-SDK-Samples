//
//  FlightCardView.swift
//  MyRadarSDKDemo
//
//  Compact card describing the flight the map shows, built from the SDK's FlightInfo.
//

import SwiftUI
import MyRadar_iOS_SDK

struct FlightCardView: View {
    let flight: FlightInfo
    let onClose: () -> Void

    private static let accent = Color(red: 0.15, green: 0.85, blue: 1.0)

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            routeRow
            statsRow
        }
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - Rows

    private var header: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(flight.ident)
                .font(.title3.bold())
                .lineLimit(1)
                .fixedSize()
            Text(subtitle)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .layoutPriority(-1)
            Spacer(minLength: 4)
            statusBadge
            Button(action: onClose) {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
            .accessibilityLabel("Stop tracking")
        }
    }

    /// "B789 · N61101 · En Route / On Time"
    private var subtitle: String {
        [flight.aircraftType, flight.registration, flight.status.isEmpty ? nil : flight.status]
            .compactMap { $0 }
            .joined(separator: " · ")
    }

    private var statusBadge: some View {
        let (text, color): (String, Color) = {
            if flight.isCancelled { return ("Cancelled", .red) }
            if flight.hasLanded { return ("Landed", .orange) }
            if flight.isAirborne { return ("In the air", .green) }
            return ("Scheduled", .secondary)
        }()
        return Text(text)
            .font(.caption2.bold())
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(color.opacity(0.2), in: Capsule())
            .foregroundStyle(color)
    }

    private var routeRow: some View {
        HStack(alignment: .center, spacing: 10) {
            airportColumn(flight.origin, time: flight.actualDeparture ?? flight.scheduledDeparture,
                          alignment: .leading)
            VStack(spacing: 3) {
                ProgressView(value: flight.hasLanded ? 1 : flight.progress ?? 0)
                    .tint(Self.accent)
                Text(progressCaption)
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            airportColumn(flight.destination,
                          time: flight.actualArrival ?? flight.estimatedArrival ?? flight.scheduledArrival,
                          alignment: .trailing)
        }
    }

    /// "2 h 10 min to go", noting when the aircraft's position is estimated (common over oceans).
    private var progressCaption: String {
        let parts = [timeToGo.map { "\($0) to go" },
                     flight.isAirborne && flight.position?.isEstimated == true ? "estimated position" : nil]
        let text = parts.compactMap { $0 }.joined(separator: " · ")
        return text.isEmpty ? " " : text
    }

    private var timeToGo: String? {
        guard flight.isAirborne, let arrival = flight.estimatedArrival else { return nil }
        let minutes = max(0, Int(arrival.timeIntervalSinceNow / 60))
        return minutes < 60 ? "\(minutes) min" : "\(minutes / 60) h \(minutes % 60) min"
    }

    private func airportColumn(_ airport: FlightAirport?, time: Date?, alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 1) {
            Text(airport?.code ?? "—")
                .font(.title3.bold())
            Text(time?.formatted(date: .omitted, time: .shortened) ?? airport?.city ?? "—")
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .frame(minWidth: 64, alignment: alignment == .leading ? .leading : .trailing)
    }

    private var statsRow: some View {
        // After landing the last position is on the taxiway, so the in-flight stats are blank.
        let position = flight.hasLanded ? nil : flight.position
        return HStack(spacing: 14) {
            stat("Alt", position?.altitudeFeet.map { "\(Int($0).formatted()) ft" } ?? "—")
            stat("Speed", position?.groundSpeedKnots.map { "\(Int(($0 * 1.15078).rounded())) mph" } ?? "—")
            stat("Hdg", position?.heading.map { "\(Int($0))°" } ?? "—")
            Spacer()
        }
    }

    private func stat(_ label: String, _ value: String) -> some View {
        HStack(spacing: 4) {
            Text(label)
                .foregroundStyle(.secondary)
            Text(value)
                .monospacedDigit()
        }
        .font(.caption)
        .lineLimit(1)
    }
}
