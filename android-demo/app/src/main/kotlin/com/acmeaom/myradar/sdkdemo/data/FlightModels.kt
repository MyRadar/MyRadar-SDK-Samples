package com.acmeaom.myradar.sdkdemo.data

import com.acmeaom.myradar.sdk.LatLng
import java.time.Instant

/*
 * Plain value types describing a tracked flight. Independent of any data source.
 */

data class Airport(
    val icao: String,
    val iata: String?,
    val name: String,
    val city: String?,
    val coordinate: LatLng,
) {
    /** The short code most travellers recognise. */
    val displayCode: String get() = iata ?: icao

    override fun equals(other: Any?): Boolean = other is Airport && other.icao == icao
    override fun hashCode(): Int = icao.hashCode()
}

/** One ADS-B position report for an aircraft. */
data class AircraftState(
    val hex: String,
    val callsign: String,
    val registration: String?,
    val typeCode: String?,
    val coordinate: LatLng,
    /** True track in degrees clockwise from north. */
    val heading: Double?,
    val altitudeFeet: Double?,
    val onGround: Boolean,
    val groundSpeedKnots: Double?,
    val verticalRateFeetPerMinute: Double?,
    val timestamp: Instant,
)

/** One historical position from a flight's recorded track. */
data class TrackPoint(
    val coordinate: LatLng,
    val timestamp: Instant,
    val altitudeFeet: Double?,
    val onGround: Boolean,
)

/** The filed route for a callsign: two or more airports in order. */
data class FlightRoute(val airports: List<Airport>) {

    val origin: Airport? get() = airports.firstOrNull()
    val destination: Airport? get() = if (airports.size >= 2) airports.last() else null

    /**
     * For multi-stop routes, the leg whose origin is nearest to where the recorded track begins.
     * Falls back to the whole route when no stop is within 50 nm.
     */
    fun leg(start: LatLng): FlightRoute {
        if (airports.size <= 2) return this
        val nearest = airports.dropLast(1).withIndex()
            .minBy { GreatCircle.distanceNauticalMiles(it.value.coordinate, start) }
        if (GreatCircle.distanceNauticalMiles(nearest.value.coordinate, start) > 50) return this
        return FlightRoute(listOf(airports[nearest.index], airports[nearest.index + 1]))
    }

    /** "SFO → SIN" style summary. */
    val summary: String get() = airports.joinToString(" → ") { it.displayCode }
}

data class AirlineInfo(
    val name: String,
    val iata: String?,
    val icao: String?,
    /** Marketing flight number such as "UA 1", when known. */
    val iataFlightNumber: String?,
)

/** A search hit: enough to list a flight and start tracking it. */
data class FlightSummary(val state: AircraftState, val route: FlightRoute?) {
    val id: String get() = state.hex
    val title: String get() = state.callsign
    val subtitle: String
        get() = buildList {
            route?.let { add(it.summary) }
            state.typeCode?.let { add(it) }
            if (state.onGround) {
                add("on ground")
            } else {
                state.altitudeFeet?.let { add("${it.toInt()} ft") }
            }
        }.joinToString(" · ")
}

/** Everything the UI shows for the flight being followed. */
data class TrackedFlight(
    val state: AircraftState,
    val route: FlightRoute?,
    val airline: AirlineInfo?,
    /**
     * The path flown on this leg, oldest first: the recorded track where available, extended
     * with positions observed while tracking.
     */
    val track: List<LatLng>,
    /** When the aircraft left the ground on this leg, when the recorded track shows it. */
    val departureTime: Instant?,
    val trackingSince: Instant,
) {
    /** Flight number for display: marketing number if known, else the callsign. */
    val displayNumber: String get() = airline?.iataFlightNumber ?: state.callsign

    /** Human-readable aircraft description, e.g. "Boeing 787-9 · N21102". */
    val aircraftDescription: String
        get() {
            val parts = listOfNotNull(state.typeCode?.let(AircraftTypes::name), state.registration)
            return if (parts.isEmpty()) "Unknown aircraft" else parts.joinToString(" · ")
        }
}
