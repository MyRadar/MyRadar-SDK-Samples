//
//  FlightCardView.swift
//  MyRadarSDKDemo
//
//  Compact card describing the tracked flight.
//

import SwiftUI
import MyRadar_iOS_SDK

struct FlightCardView: View {
    let tracker: FlightTracker
    let flight: TrackedFlight
    @Binding var showAttributions: Bool

    private static let accent = Color(red: 0.15, green: 0.85, blue: 1.0)

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            routeRow
            statsRow
            footer
        }
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - Rows

    private var header: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(flight.displayNumber)
                .font(.title3.bold())
                .lineLimit(1)
                .fixedSize()
            if flight.displayNumber != flight.state.callsign {
                Text(flight.state.callsign)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .fixedSize()
            }
            Text(subtitle)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .layoutPriority(-1)
            Spacer(minLength: 4)
            statusBadge
            Button {
                tracker.clearFlight()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
            .accessibilityLabel("Stop tracking")
        }
    }

    /// "MyRadar Air · Boeing 737-800 · N123MR"
    private var subtitle: String {
        [flight.airline?.name, flight.aircraftDescription]
            .compactMap { $0 }
            .joined(separator: " · ")
    }

    private var statusBadge: some View {
        let (text, color): (String, Color) = {
            if flight.state.onGround { return ("On ground", .orange) }
            if let rate = flight.state.verticalRateFeetPerMinute {
                if rate > 300 { return ("Climbing", .green) }
                if rate < -300 { return ("Descending", .yellow) }
            }
            return ("En route", .green)
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
            airportColumn(flight.route?.origin, time: tracker.departure?.date,
                          isEstimate: tracker.departure?.isEstimate ?? true, alignment: .leading)
            VStack(spacing: 3) {
                ProgressView(value: tracker.progress ?? 0)
                    .tint(Self.accent)
                Text(tracker.timeRemainingText.map { "\($0) to go" } ?? " ")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            airportColumn(flight.route?.destination, time: tracker.estimatedArrival, isEstimate: true,
                          alignment: .trailing)
        }
    }

    private func airportColumn(_ airport: Airport?, time: Date?, isEstimate: Bool,
                               alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 1) {
            Text(airport?.displayCode ?? "—")
                .font(.title3.bold())
            Text(time.map { (isEstimate ? "≈ " : "") + $0.formatted(date: .omitted, time: .shortened) }
                 ?? (airport?.city ?? "—"))
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .frame(minWidth: 64, alignment: alignment == .leading ? .leading : .trailing)
    }

    private var statsRow: some View {
        HStack(spacing: 14) {
            stat("Alt", flight.state.altitudeFeet.map { "\(Int($0).formatted()) ft" } ?? "—")
            stat("Speed", flight.state.groundSpeedKnots.map { "\(Int(($0 * 1.15078).rounded())) mph" } ?? "—")
            stat("Hdg", flight.state.heading.map { "\(Int($0))°" } ?? "—")
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

    private var footer: some View {
        HStack(spacing: 10) {
            Picker("Camera", selection: Binding(
                get: { tracker.cameraMode },
                set: { tracker.cameraMode = $0 }
            )) {
                ForEach(FlightTracker.CameraMode.allCases, id: \.self) { mode in
                    Text(mode.rawValue).tag(mode)
                }
            }
            .pickerStyle(.segmented)
            .frame(width: 150)
            .controlSize(.small)
            Button {
                tracker.recenter()
            } label: {
                Image(systemName: "scope")
            }
            .accessibilityLabel("Recenter")
            Spacer()
            if let updated = tracker.lastUpdate {
                // Last position update time; the clock glyph replaces the word "Updated"
                // so the row fits the narrower landscape column.
                Label(updated.formatted(date: .omitted, time: .shortened), systemImage: "clock")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .fixedSize()
                    .accessibilityLabel("Updated \(updated.formatted(date: .omitted, time: .shortened))")
            }
            Button {
                showAttributions.toggle()
            } label: {
                Image(systemName: "info.circle")
            }
            .accessibilityLabel("Data attributions")
        }
        .font(.caption)
    }
}
