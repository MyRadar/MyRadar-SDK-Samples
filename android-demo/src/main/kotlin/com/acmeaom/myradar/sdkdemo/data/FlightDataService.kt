package com.acmeaom.myradar.sdkdemo.data

import com.acmeaom.myradar.sdk.LatLng
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sin

/*
 * Flight data access. The live implementation uses free, keyless public APIs:
 *
 *   - adsb.lol  (https://api.adsb.lol)      live ADS-B positions, routes, airports. ODbL licensed.
 *   - adsbdb    (https://api.adsbdb.com)    airline names and marketing flight numbers.
 *   - hexdb     (https://hexdb.io)          airport lookup by IATA code.
 *
 * None provides scheduled times, so the app estimates departure and arrival from distance and
 * ground speed. A sample implementation provides an offline flight so the demo works without
 * network access. The SDK itself makes no flight-related requests.
 */

interface FlightDataService {
    /** The most recent position report for an ICAO callsign such as "UAL1". */
    suspend fun aircraftByCallsign(callsign: String): AircraftState?

    /** The most recent position report for an ICAO 24-bit address such as "a1bfac". */
    suspend fun aircraftByHex(hex: String): AircraftState?

    /** Aircraft within a radius of a coordinate. */
    suspend fun aircraftNear(coordinate: LatLng, radiusNauticalMiles: Int): List<AircraftState>

    /** Filed routes for several callsigns, keyed by callsign. Unknown callsigns are omitted. */
    suspend fun routes(states: List<AircraftState>): Map<String, FlightRoute>

    /** Airline details for a callsign, when known. */
    suspend fun airline(callsign: String): AirlineInfo?

    /** Airport details for an ICAO ("KSFO") or IATA ("SFO") code. */
    suspend fun airport(code: String): Airport?

    /** Recorded positions for the aircraft's current leg, oldest first. Empty when unknown. */
    suspend fun track(hex: String): List<TrackPoint>
}

class FlightDataException(message: String) : IOException(message)

// MARK: - Live implementation

class AdsbFlightDataService(private val client: OkHttpClient = defaultClient) : FlightDataService {

    companion object {
        private const val ADSB_LOL = "https://api.adsb.lol"
        private const val ADSBDB = "https://api.adsbdb.com"
        private const val HEXDB = "https://hexdb.io"

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(20))
                .build()
        }

        /**
         * "UA1" -> "UA 1", "B6413" -> "B6 413" for display. Airline codes may contain digits, so
         * prefer splitting after the known code.
         */
        fun spacedFlightNumber(compact: String, airlineCode: String?): String {
            if (!airlineCode.isNullOrEmpty() && compact.startsWith(airlineCode) && compact.length > airlineCode.length) {
                return airlineCode + " " + compact.substring(airlineCode.length)
            }
            val firstDigit = compact.indexOfFirst { it.isDigit() }
            if (firstDigit < 0) return compact
            return compact.substring(0, firstDigit) + " " + compact.substring(firstDigit)
        }

        /** A readsb-style aircraft record from adsb.lol's `/v2/...` endpoints. */
        fun parseAircraft(record: JSONObject, now: Instant = Instant.now()): AircraftState? {
            val lat = record.optDoubleOrNull("lat") ?: return null
            val lon = record.optDoubleOrNull("lon") ?: return null
            // `alt_baro` is a number in feet, or the string "ground".
            val altitude = record.opt("alt_baro")
            val seenPos = record.optDoubleOrNull("seen_pos") ?: 0.0
            return AircraftState(
                hex = record.optString("hex"),
                callsign = record.optString("flight").trim(),
                registration = record.optStringOrNull("r"),
                typeCode = record.optStringOrNull("t"),
                coordinate = LatLng(lat, lon),
                heading = record.optDoubleOrNull("track") ?: record.optDoubleOrNull("true_heading")
                    ?: record.optDoubleOrNull("mag_heading"),
                altitudeFeet = (altitude as? Number)?.toDouble(),
                onGround = altitude == "ground",
                groundSpeedKnots = record.optDoubleOrNull("gs"),
                verticalRateFeetPerMinute = record.optDoubleOrNull("baro_rate") ?: record.optDoubleOrNull("geom_rate"),
                timestamp = now.minusMillis((seenPos * 1000).toLong()),
            )
        }

        /** An adsb.lol airport record (route entry element and `/api/0/airport`). */
        fun parseRouteAirport(o: JSONObject): Airport? {
            val icao = o.optStringOrNull("icao") ?: return null
            val lat = o.optDoubleOrNull("lat") ?: return null
            val lon = o.optDoubleOrNull("lon") ?: return null
            return Airport(icao, o.optStringOrNull("iata"), o.optStringOrNull("name") ?: icao,
                o.optStringOrNull("location"), LatLng(lat, lon))
        }

        /** An adsb.lol route entry (routeset response element and static route file). */
        fun parseRoute(entry: JSONObject): FlightRoute? {
            val airports = entry.optJSONArray("_airports")?.objects()?.mapNotNull(::parseRouteAirport) ?: return null
            return if (airports.size >= 2) FlightRoute(airports) else null
        }

        /**
         * adsb.lol publishes each aircraft's trace for the current day (the same feed its map UI
         * reads) as a readsb trace file. Rows are
         * `[secondsSinceBase, lat, lon, altitudeOrGround, groundSpeed, track, flags, verticalRate, …]`;
         * flag bit 1 marks the start of a new leg.
         */
        fun parseTrace(json: JSONObject): List<TrackPoint> {
            val base = json.optDoubleOrNull("timestamp") ?: return emptyList()
            val rows = json.optJSONArray("trace") ?: return emptyList()
            val points = ArrayList<TrackPoint>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                if (row.length() < 7) continue
                val offset = row.optDoubleOrNull(0) ?: continue
                val lat = row.optDoubleOrNull(1) ?: continue
                val lon = row.optDoubleOrNull(2) ?: continue
                val onGround = row.opt(3) == "ground"
                val altitude = row.optDoubleOrNull(3)
                val flags = row.optInt(6, 0)
                val newLeg = flags and 2 != 0
                val timestamp = Instant.ofEpochMilli(((base + offset) * 1000).toLong())
                // Keep only the most recent leg: restart at a takeoff marker, after the aircraft
                // was last on the ground, or after a long silence that began at low altitude
                // (a landing where ground coverage was missing).
                val previous = points.lastOrNull()
                var startsNewLeg = newLeg || (previous?.onGround == true && !onGround)
                if (previous != null && Duration.between(previous.timestamp, timestamp).toMinutes() > 30 &&
                    (previous.altitudeFeet ?: 0.0) < 10_000
                ) {
                    startsNewLeg = true
                }
                if (startsNewLeg) points.clear()
                points.add(TrackPoint(LatLng(lat, lon), timestamp, altitude, onGround))
            }
            // Drop taxi positions so the path starts at the takeoff roll.
            val firstAirborne = points.indexOfFirst { !it.onGround }
            if (firstAirborne > 1) points.subList(0, firstAirborne - 1).clear()
            return points
        }
    }

    // MARK: Aircraft

    override suspend fun aircraftByCallsign(callsign: String): AircraftState? {
        val trimmed = callsign.trim()
        if (trimmed.isEmpty()) return null
        return aircraftList("$ADSB_LOL/v2/callsign/$trimmed").firstOrNull()
    }

    override suspend fun aircraftByHex(hex: String): AircraftState? =
        aircraftList("$ADSB_LOL/v2/hex/$hex").firstOrNull()

    override suspend fun aircraftNear(coordinate: LatLng, radiusNauticalMiles: Int): List<AircraftState> {
        val radius = radiusNauticalMiles.coerceIn(1, 250)
        val path = String.format(Locale.US, "v2/point/%.4f/%.4f/%d", coordinate.latitude, coordinate.longitude, radius)
        return aircraftList("$ADSB_LOL/$path")
    }

    private suspend fun aircraftList(url: String): List<AircraftState> {
        val json = JSONObject(getString(url))
        return json.optJSONArray("ac")?.objects()?.mapNotNull { parseAircraft(it) } ?: emptyList()
    }

    // MARK: Routes

    override suspend fun routes(states: List<AircraftState>): Map<String, FlightRoute> {
        val candidates = states.filter { it.callsign.isNotEmpty() }
        if (candidates.isEmpty()) return emptyMap()

        // Batch lookup. adsb.lol only answers this endpoint for requests that carry its own site
        // as the referer; otherwise it returns an empty 201.
        val planes = JSONArray()
        for (state in candidates.take(120)) {
            planes.put(JSONObject().put("callsign", state.callsign)
                .put("lat", state.coordinate.latitude).put("lng", state.coordinate.longitude))
        }
        val request = Request.Builder()
            .url("$ADSB_LOL/api/0/routeset")
            .header("Referer", "https://adsb.lol/")
            .post(JSONObject().put("planes", planes).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val batch = runCatching {
            val response = execute(request)
            if (response.code !in 200..299 || response.body.isBlank()) return@runCatching emptyMap()
            JSONArray(response.body).objects().mapNotNull { entry ->
                parseRoute(entry)?.let { entry.optString("callsign") to it }
            }.toMap()
        }.getOrDefault(emptyMap())
        if (batch.isNotEmpty()) return batch

        // Fallback: the same data as static files, one request per callsign.
        return coroutineScope {
            candidates.take(25).map { state ->
                async { runCatching { staticRoute(state.callsign) }.getOrNull()?.let { state.callsign to it } }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    private suspend fun staticRoute(callsign: String): FlightRoute? {
        if (callsign.length < 2) return null
        val url = "https://vrs-standing-data.adsb.lol/routes/${callsign.take(2)}/$callsign.json"
        return parseRoute(JSONObject(getString(url)))
    }

    // MARK: Airline

    override suspend fun airline(callsign: String): AirlineInfo? {
        val body = getStringOrNull("$ADSBDB/v0/callsign/$callsign") ?: return null
        val flightRoute = JSONObject(body).optJSONObject("response")?.optJSONObject("flightroute") ?: return null
        val airline = flightRoute.optJSONObject("airline")
        val airlineIata = airline?.optStringOrNull("iata")
        return AirlineInfo(
            name = airline?.optStringOrNull("name") ?: flightRoute.optString("callsign"),
            iata = airlineIata,
            icao = airline?.optStringOrNull("icao"),
            iataFlightNumber = flightRoute.optStringOrNull("callsign_iata")?.let { spacedFlightNumber(it, airlineIata) },
        )
    }

    // MARK: Airports

    override suspend fun airport(code: String): Airport? {
        val normalized = code.trim().uppercase()
        if (normalized.length == 4) {
            runCatching { adsbLolAirport(normalized) }.getOrNull()?.let { return it }
        }
        if (normalized.length == 3) {
            runCatching { hexdbAirport(normalized) }.getOrNull()?.let { return it }
            // Most US airports are the IATA code prefixed with K.
            runCatching { adsbLolAirport("K$normalized") }.getOrNull()?.let { return it }
        }
        return null
    }

    private suspend fun adsbLolAirport(icao: String): Airport? =
        getStringOrNull("$ADSB_LOL/api/0/airport/$icao")?.let { parseRouteAirport(JSONObject(it)) }

    private suspend fun hexdbAirport(iata: String): Airport? {
        val o = getStringOrNull("$HEXDB/api/v1/airport/iata/$iata")?.let(::JSONObject) ?: return null
        val icao = o.optStringOrNull("icao") ?: return null
        val lat = o.optDoubleOrNull("latitude") ?: return null
        val lon = o.optDoubleOrNull("longitude") ?: return null
        return Airport(icao, o.optStringOrNull("iata"), o.optStringOrNull("airport") ?: icao,
            o.optStringOrNull("region_name"), LatLng(lat, lon))
    }

    // MARK: Track history

    override suspend fun track(hex: String): List<TrackPoint> {
        val lower = hex.lowercase()
        if (lower.length < 2) return emptyList()
        val primary = runCatching {
            getStringOrNull("https://adsb.lol/data/traces/${lower.takeLast(2)}/trace_full_$lower.json")
                ?.let { parseTrace(JSONObject(it)) }
        }.getOrNull().orEmpty()
        if (primary.isNotEmpty()) return primary
        // The trace feed is undocumented; fall back to OpenSky's track endpoint (anonymous
        // access, research-use terms, acceptable for this non-commercial demo).
        return runCatching { openSkyTrack(lower) }.getOrDefault(emptyList())
    }

    /** OpenSky `/tracks/all`. Waypoints are `[time, lat, lon, baroAltitudeMeters, trueTrack, onGround]`. */
    private suspend fun openSkyTrack(hex: String): List<TrackPoint> {
        val body = getStringOrNull("https://opensky-network.org/api/tracks/all?icao24=$hex&time=0") ?: return emptyList()
        val rows = JSONObject(body).optJSONArray("path") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONArray(i) ?: return@mapNotNull null
            if (row.length() < 6) return@mapNotNull null
            val time = row.optDoubleOrNull(0) ?: return@mapNotNull null
            val lat = row.optDoubleOrNull(1) ?: return@mapNotNull null
            val lon = row.optDoubleOrNull(2) ?: return@mapNotNull null
            TrackPoint(LatLng(lat, lon), Instant.ofEpochSecond(time.toLong()),
                row.optDoubleOrNull(3)?.let { it * 3.28084 }, row.optBoolean(5, false))
        }
    }

    // MARK: Transport

    private class HttpResult(val code: Int, val body: String)

    private fun get(url: String): Request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()

    private suspend fun getString(url: String): String {
        val response = execute(get(url))
        if (response.code !in 200..299) {
            throw FlightDataException("The flight data service returned HTTP ${response.code}.")
        }
        return response.body
    }

    /** The body of a 200 response, or null for any other status. */
    private suspend fun getStringOrNull(url: String): String? =
        execute(get(url)).takeIf { it.code == 200 }?.body

    /** Runs a request, reading the body on OkHttp's thread so the caller never does network I/O. */
    private suspend fun execute(request: Request): HttpResult = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { HttpResult(it.code, it.body.string()) } }
                result.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }
}

// MARK: - Offline sample

/**
 * A simulated San Francisco to New York flight that advances in real time, so the demo can be
 * exercised with no network connection and no live traffic.
 */
class SampleFlightDataService : FlightDataService {

    companion object {
        const val CALLSIGN = "MYR123"
        const val HEX = "sample"

        private val sfo = Airport("KSFO", "SFO", "San Francisco International Airport", "San Francisco",
            LatLng(37.619, -122.375))
        private val jfk = Airport("KJFK", "JFK", "John F. Kennedy International Airport", "New York",
            LatLng(40.6413, -73.7781))
        private const val CRUISE_SPEED_KNOTS = 470.0
    }

    /** The flight "departed" 90 minutes before the service was created. */
    private val departure = Instant.now().minus(Duration.ofMinutes(90))

    override suspend fun aircraftByCallsign(callsign: String) =
        if (callsign.uppercase() == CALLSIGN) currentState() else null

    override suspend fun aircraftByHex(hex: String) = if (hex == HEX) currentState() else null

    override suspend fun aircraftNear(coordinate: LatLng, radiusNauticalMiles: Int) = listOf(currentState())

    override suspend fun routes(states: List<AircraftState>) = mapOf(CALLSIGN to FlightRoute(listOf(sfo, jfk)))

    override suspend fun airline(callsign: String) =
        AirlineInfo(name = "MyRadar Air (sample)", iata = "MY", icao = "MYR", iataFlightNumber = "MY 123")

    override suspend fun airport(code: String): Airport? {
        val upper = code.uppercase()
        return listOf(sfo, jfk).firstOrNull { it.icao == upper || it.iata == upper }
    }

    /** A gently weaving path from the departure airport to the current position. */
    override suspend fun track(hex: String): List<TrackPoint> {
        if (hex != HEX) return emptyList()
        val state = currentState()
        val total = GreatCircle.distanceNauticalMiles(sfo.coordinate, jfk.coordinate)
        val flownFraction = GreatCircle.distanceNauticalMiles(sfo.coordinate, state.coordinate) / total
        val steps = 60
        return (0..steps).map { i ->
            val f = flownFraction * i / steps
            val c = GreatCircle.interpolate(sfo.coordinate, jfk.coordinate, f)
            val elapsedSeconds = f * total / CRUISE_SPEED_KNOTS * 3600
            TrackPoint(
                // Simulated routing around weather.
                coordinate = LatLng(c.latitude + 0.35 * sin(f * 9), c.longitude),
                timestamp = departure.plusSeconds(elapsedSeconds.toLong()),
                altitudeFeet = if (i == 0) null else 36_000.0,
                onGround = i == 0,
            )
        }
    }

    private fun currentState(): AircraftState {
        val total = GreatCircle.distanceNauticalMiles(sfo.coordinate, jfk.coordinate)
        val hours = Duration.between(departure, Instant.now()).toMillis() / 3_600_000.0
        val flown = minOf(total, hours * CRUISE_SPEED_KNOTS)
        val fraction = flown / total
        val position = GreatCircle.interpolate(sfo.coordinate, jfk.coordinate, fraction)
        val landed = fraction >= 1
        return AircraftState(
            hex = HEX,
            callsign = CALLSIGN,
            registration = "N123MR",
            typeCode = "B738",
            coordinate = position,
            heading = GreatCircle.initialBearing(position, jfk.coordinate),
            altitudeFeet = if (landed) null else 36_000.0,
            onGround = landed,
            groundSpeedKnots = if (landed) 0.0 else CRUISE_SPEED_KNOTS,
            verticalRateFeetPerMinute = 0.0,
            timestamp = Instant.now(),
        )
    }
}
