package com.acmeaom.myradar.sdkdemo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.acmeaom.myradar.sdk.CoordinateRegion
import com.acmeaom.myradar.sdk.LatLng
import com.acmeaom.myradar.sdk.MapCamera
import com.acmeaom.myradar.sdk.MapMarker
import com.acmeaom.myradar.sdk.MapPolyline
import com.acmeaom.myradar.sdkdemo.data.AdsbFlightDataService
import com.acmeaom.myradar.sdkdemo.data.Airlines
import com.acmeaom.myradar.sdkdemo.data.Airport
import com.acmeaom.myradar.sdkdemo.data.AirportIndex
import com.acmeaom.myradar.sdkdemo.data.AircraftState
import com.acmeaom.myradar.sdkdemo.data.FlightDataService
import com.acmeaom.myradar.sdkdemo.data.FlightRoute
import com.acmeaom.myradar.sdkdemo.data.FlightSummary
import com.acmeaom.myradar.sdkdemo.data.GreatCircle
import com.acmeaom.myradar.sdkdemo.data.SampleFlightDataService
import com.acmeaom.myradar.sdkdemo.data.TrackPoint
import com.acmeaom.myradar.sdkdemo.data.TrackedFlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Screen state for the flight tracker: search, the followed flight, periodic position updates,
 * and the map geometry derived from them.
 */
class FlightTracker(application: Application) : AndroidViewModel(application) {

    // MARK: Observable state

    var searchText by mutableStateOf("")
    var results by mutableStateOf<List<FlightSummary>>(emptyList())
        private set
    var isSearching by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var flight by mutableStateOf<TrackedFlight?>(null)
        private set
    var lastUpdate by mutableStateOf<Instant?>(null)
        private set
    var isUsingSampleData by mutableStateOf(false)
        private set

    /** Geometry handed to the map. Rebuilt whenever the flight changes. */
    var polylines by mutableStateOf<List<MapPolyline>>(emptyList())
        private set
    var markers by mutableStateOf<List<MapMarker>>(emptyList())
        private set

    /** Set to move the camera to a region (whole-route mode); `null` leaves it alone. */
    var region by mutableStateOf<CoordinateRegion?>(null)
        private set

    /** Set to move the camera to an exact center and zoom (follow mode); `null` leaves it alone. */
    var camera by mutableStateOf<MapCamera?>(null)
        private set

    /** How the camera tracks the flight. */
    enum class CameraMode(val title: String) {
        /**
         * Stay close to the aircraft. Keeps the view regional, where radar morphing with motion
         * vectors is available.
         */
        FollowAircraft("Follow"),

        /** Show origin, aircraft and destination together. */
        WholeRoute("Route"),
    }

    var cameraMode by mutableStateOf(CameraMode.FollowAircraft)
        private set

    fun updateCameraMode(mode: CameraMode) {
        if (mode == cameraMode) return
        cameraMode = mode
        recenter()
    }

    // MARK: Private

    private var service: FlightDataService = AdsbFlightDataService()
    private var pollingJob: Job? = null
    private val planeIcon: Bitmap = PlaneIcon.create(application)

    /** Loaded in the background; empty until then. */
    private var airportIndex = AirportIndex.EMPTY

    /** Position polls that failed in a row; transient errors are not shown until several in a row. */
    private var consecutivePollFailures = 0

    init {
        viewModelScope.launch {
            airportIndex = withContext(Dispatchers.IO) { AirportIndex.load(application) }
        }
    }

    /** Runs a data call off the main thread; responses can be several megabytes of JSON. */
    private suspend fun <T> fetch(block: suspend FlightDataService.() -> T): T {
        val current = service
        return withContext(Dispatchers.Default) { current.block() }
    }

    // MARK: Search

    /** What the user typed, interpreted as a flight number, an airport, or an airport pair. */
    sealed interface Query {
        data class Flight(val callsign: String) : Query
        data class SingleAirport(val code: String) : Query
        data class AirportPair(val origin: String, val destination: String) : Query

        companion object {
            fun parse(text: String): Query? {
                val upper = text.uppercase().trim()
                if (upper.isEmpty()) return null
                val tokens = upper
                    .replace("→", "-")
                    .replace(" TO ", "-")
                    .split('-', ' ', ',')
                    .filter { it.isNotEmpty() }
                fun isAirportCode(s: String) = (s.length == 3 || s.length == 4) && s.all { it.isLetter() }
                return when {
                    tokens.size == 2 && isAirportCode(tokens[0]) && isAirportCode(tokens[1]) ->
                        AirportPair(tokens[0], tokens[1])
                    tokens.size == 1 && isAirportCode(tokens[0]) -> SingleAirport(tokens[0])
                    else -> Flight(Airlines.callsign(upper))
                }
            }
        }
    }

    fun search() {
        viewModelScope.launch { performSearch() }
    }

    suspend fun performSearch() {
        val query = Query.parse(searchText) ?: return
        isSearching = true
        errorMessage = null
        results = emptyList()

        if (isUsingSampleData) {
            // Searching again leaves sample mode and goes back to live data.
            service = AdsbFlightDataService()
            isUsingSampleData = false
        }

        try {
            when (query) {
                is Query.Flight -> {
                    val state = fetch { aircraftByCallsign(query.callsign) }
                    if (state == null) {
                        errorMessage = "${query.callsign} is not currently airborne or reporting a position."
                        return
                    }
                    val routes = fetch { routes(listOf(state)) }
                    select(FlightSummary(state, routes[state.callsign]))
                }
                is Query.SingleAirport -> searchAirports(query.code, null)
                is Query.AirportPair -> searchAirports(query.origin, query.destination)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "The search failed."
        } finally {
            isSearching = false
        }
    }

    private suspend fun searchAirports(originCode: String, destinationCode: String?) {
        val origin = fetch { airport(originCode) }
        if (origin == null) {
            errorMessage = "Unknown airport $originCode. Try an IATA code like SFO or ICAO like KSFO."
            return
        }
        var destination: Airport? = null
        if (destinationCode != null) {
            destination = fetch { airport(destinationCode) }
            if (destination == null) {
                errorMessage = "Unknown airport $destinationCode."
                return
            }
        }

        // Gather traffic around the origin, the destination, and (for a pair) the midpoint.
        val searchPoints = buildList {
            add(origin.coordinate)
            if (destination != null) {
                add(destination.coordinate)
                add(GreatCircle.interpolate(origin.coordinate, destination.coordinate, 0.5))
            }
        }
        val seen = HashSet<String>()
        val candidates = mutableListOf<AircraftState>()
        for (point in searchPoints) {
            for (state in fetch { aircraftNear(point, 250) }) {
                if (state.callsign.isNotEmpty() && !state.onGround && seen.add(state.hex)) {
                    candidates.add(state)
                }
            }
        }

        val routes = fetch { routes(candidates) }
        val matches = candidates.mapNotNull { state ->
            val route = routes[state.callsign] ?: return@mapNotNull null
            val matchesOrigin = route.origin?.icao == origin.icao
            val matchesDestination = route.destination?.icao == origin.icao
            val keep = if (destination != null) {
                matchesOrigin && route.destination?.icao == destination.icao
            } else {
                matchesOrigin || matchesDestination
            }
            if (keep) FlightSummary(state, route) else null
        }.sortedBy { it.route?.summary.orEmpty() }

        if (matches.isEmpty()) {
            val what = destination?.let { "between ${origin.displayCode} and ${it.displayCode}" }
                ?: "to or from ${origin.displayCode}"
            errorMessage = "No airborne flights $what were found right now."
        }
        results = matches.take(40)
    }

    // MARK: Selection and tracking

    fun selectResult(summary: FlightSummary) {
        viewModelScope.launch { select(summary) }
    }

    suspend fun select(summary: FlightSummary) {
        results = emptyList()
        errorMessage = null
        stopPolling()

        var tracked = TrackedFlight(
            state = summary.state, route = summary.route, airline = null,
            track = listOf(summary.state.coordinate), departureTime = null, trackingSince = Instant.now(),
        )
        flight = tracked
        lastUpdate = Instant.now()
        rebuildGeometry()
        recenter()

        // Enrich in the background; failures here just leave fields blank.
        val state = summary.state
        val routeLookup = if (tracked.route == null) viewModelScope.async { runCatching { fetch { routes(listOf(state)) } }.getOrNull() } else null
        val airlineLookup = viewModelScope.async { runCatching { fetch { airline(state.callsign) } }.getOrNull() }
        val trackLookup = viewModelScope.async { runCatching { fetch { track(state.hex) } }.getOrNull() }
        routeLookup?.await()?.let { tracked = tracked.copy(route = it[state.callsign]) }
        tracked = tracked.copy(airline = airlineLookup.await())
        trackLookup.await()?.takeIf { it.isNotEmpty() }?.let { tracked = merge(it, tracked) }
        if (flight?.state?.hex == tracked.state.hex) {
            flight = tracked
            rebuildGeometry()
            recenter()
        }
        startPolling()
    }

    /**
     * Replaces the observed breadcrumb trail with the recorded track, keeping the current position
     * at the end, and notes the takeoff time.
     */
    private fun merge(recorded: List<TrackPoint>, flight: TrackedFlight): TrackedFlight {
        val path = recorded.map { it.coordinate }.toMutableList()
        val current = flight.state.coordinate
        path.lastOrNull()?.let { if (GreatCircle.distanceNauticalMiles(it, current) > 0.2) path.add(current) }
        var result = flight.copy(track = path, departureTime = recorded.firstOrNull { !it.onGround }?.timestamp)
        val start = path.firstOrNull() ?: return result

        // Multi-stop routes: show the leg the aircraft is actually flying.
        result.route?.let { result = result.copy(route = it.leg(start)) }

        // Reconcile the filed route with where the track really began. Flight numbers are reused
        // across legs and the route database is not always current, so when the recording starts
        // far from the filed origin, take the nearest airport to the track start as the origin.
        // If the aircraft is still heading toward the filed origin, that airport is its next stop.
        val actualOrigin = airportIndex.nearest(start)
        val route = result.route
        val filedOrigin = route?.origin
        if (route != null && filedOrigin != null) {
            val startsAtFiledOrigin = GreatCircle.distanceNauticalMiles(filedOrigin.coordinate, start) <= 50
            if (!startsAtFiledOrigin && actualOrigin != null && actualOrigin != filedOrigin) {
                val towardFiledOrigin = headingPoints(current, filedOrigin.coordinate, result.state.heading)
                val destination = if (towardFiledOrigin) filedOrigin else (route.destination ?: filedOrigin)
                result = result.copy(route = FlightRoute(listOf(actualOrigin, destination)))
            }
        } else if (actualOrigin != null) {
            result = result.copy(route = FlightRoute(listOf(actualOrigin)))
        }
        return result
    }

    /** Whether the aircraft's heading is within 60° of the bearing to a point. */
    private fun headingPoints(position: LatLng, target: LatLng, heading: Double?): Boolean {
        heading ?: return false
        val bearing = GreatCircle.initialBearing(position, target)
        var delta = abs(bearing - heading) % 360
        if (delta > 180) delta = 360 - delta
        return delta <= 60
    }

    /** Switches to the built-in simulated flight for offline use. */
    fun loadSample() {
        viewModelScope.launch { performLoadSample() }
    }

    suspend fun performLoadSample() {
        service = SampleFlightDataService()
        isUsingSampleData = true
        searchText = SampleFlightDataService.CALLSIGN
        val state = fetch { aircraftByCallsign(SampleFlightDataService.CALLSIGN) } ?: return
        val routes = fetch { routes(listOf(state)) }
        select(FlightSummary(state, routes[state.callsign]))
    }

    fun clearFlight() {
        stopPolling()
        flight = null
        polylines = emptyList()
        markers = emptyList()
        region = null
        camera = null
        errorMessage = null
    }

    private fun startPolling() {
        pollingJob = viewModelScope.launch {
            var polls = 0
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                refreshPosition()
                polls += 1
                // Re-sync with the recorded track every few minutes to fill any gaps.
                if (polls % 30 == 0) refreshTrack()
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun refreshTrack() {
        val current = flight ?: return
        val recorded = runCatching { fetch { track(current.state.hex) } }.getOrNull()
        if (recorded.isNullOrEmpty() || flight?.state?.hex != current.state.hex) return
        flight = merge(recorded, flight ?: return)
        rebuildGeometry()
    }

    private suspend fun refreshPosition() {
        val current = flight ?: return
        try {
            val state = fetch { aircraftByHex(current.state.hex) }
            if (state == null) {
                errorMessage = "${current.state.callsign} stopped reporting. It may have landed."
                return
            }
            consecutivePollFailures = 0
            errorMessage = null
            val latest = flight ?: return
            // Only extend the breadcrumb trail when the aircraft actually moved.
            val moved = latest.track.lastOrNull()?.let { GreatCircle.distanceNauticalMiles(it, state.coordinate) > 0.2 } ?: true
            flight = latest.copy(state = state, track = if (moved) latest.track + state.coordinate else latest.track)
            lastUpdate = Instant.now()
            rebuildGeometry()
            if (cameraMode == CameraMode.FollowAircraft) recenter()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A single failed poll (for example a rate-limit response) is not worth a banner; the
            // last known position stays on screen and the next poll usually succeeds.
            consecutivePollFailures += 1
            if (consecutivePollFailures >= 3) errorMessage = e.message
        }
    }

    // MARK: Derived values

    val distanceRemainingNauticalMiles: Double?
        get() {
            val flight = flight ?: return null
            val destination = flight.route?.destination ?: return null
            return GreatCircle.distanceNauticalMiles(flight.state.coordinate, destination.coordinate)
        }

    val distanceFlownNauticalMiles: Double?
        get() {
            val flight = flight ?: return null
            val origin = flight.route?.origin ?: return null
            return GreatCircle.distanceNauticalMiles(origin.coordinate, flight.state.coordinate)
        }

    /** Fraction of the great-circle route completed, in `0..1`. */
    val progress: Double?
        get() {
            val flown = distanceFlownNauticalMiles ?: return null
            val remaining = distanceRemainingNauticalMiles ?: return null
            if (flown + remaining <= 0) return null
            return flown / (flown + remaining)
        }

    /** Flight time remaining, estimated from distance remaining and current ground speed. */
    val timeRemaining: Duration?
        get() {
            val speed = flight?.state?.groundSpeedKnots?.takeIf { it > 50 } ?: return null
            val remaining = distanceRemainingNauticalMiles ?: return null
            return Duration.ofSeconds((remaining / speed * 3600).toLong())
        }

    /** [timeRemaining] as "H:MM", for example "2:05". */
    val timeRemainingText: String?
        get() {
            val seconds = timeRemaining?.seconds ?: return null
            val minutes = (seconds / 60.0).roundToInt()
            return "%d:%02d".format(minutes / 60, minutes % 60)
        }

    /** Arrival estimated from distance remaining and current ground speed. */
    val estimatedArrival: Instant?
        get() {
            val flight = flight ?: return null
            return timeRemaining?.let { flight.state.timestamp.plus(it) }
        }

    /**
     * Departure estimated from distance flown and current ground speed. Used only when the
     * recorded track does not show the takeoff.
     */
    private val estimatedDeparture: Instant?
        get() {
            val flight = flight ?: return null
            val flown = distanceFlownNauticalMiles ?: return null
            val speed = flight.state.groundSpeedKnots?.takeIf { it > 50 } ?: return null
            return flight.state.timestamp.minusSeconds((flown / speed * 3600).toLong())
        }

    /** Actual takeoff time when known, otherwise the estimate. The flag says which. */
    val departure: Pair<Instant, Boolean>?
        get() {
            flight?.departureTime?.let { return it to false }
            return estimatedDeparture?.let { it to true }
        }

    // MARK: Map geometry

    fun recenter() {
        val flight = flight ?: return
        if (cameraMode == CameraMode.FollowAircraft) {
            region = null
            camera = MapCamera(flight.state.coordinate, FOLLOW_ZOOM)
            return
        }
        camera = null
        val points = listOfNotNull(flight.state.coordinate, flight.route?.origin?.coordinate,
            flight.route?.destination?.coordinate)
        region = if (points.size == 1) {
            CoordinateRegion(flight.state.coordinate, 4.0, 4.0)
        } else {
            CoordinateRegion.fitting(points, paddingFraction = 0.35)
        }
    }

    private fun rebuildGeometry() {
        val flight = flight
        if (flight == null) {
            polylines = emptyList()
            markers = emptyList()
            return
        }
        val state = flight.state
        val lines = mutableListOf<MapPolyline>()
        val pins = mutableListOf<MapMarker>()

        // Flown segment: the recorded track (or observed positions). Bridge from the filed origin
        // only to cover a short coverage gap after takeoff; if the recording starts far from that
        // airport the filed route is for a different leg, so trust the track alone.
        val flown = mutableListOf<LatLng>()
        val origin = flight.route?.origin
        val first = flight.track.firstOrNull()
        if (origin != null && first != null) {
            val gap = GreatCircle.distanceNauticalMiles(origin.coordinate, first)
            if (gap > 10 && gap < 150) flown.addAll(GreatCircle.path(origin.coordinate, first, segments = 16))
        }
        flown.addAll(flight.track)
        if (flown.size >= 2) {
            lines.add(MapPolyline(id = "flown", coordinates = flown, color = FLOWN_COLOR, widthDp = 3f,
                outlineColor = Color.argb(153, 0, 0, 0), userInfo = mapOf("segment" to "flown")))
        }

        // Remaining segment: great circle from the aircraft to the destination.
        flight.route?.destination?.let { destination ->
            lines.add(MapPolyline(id = "remaining", coordinates = GreatCircle.path(state.coordinate, destination.coordinate),
                color = REMAINING_COLOR, widthDp = 2f, dashPattern = listOf(8f, 6f),
                userInfo = mapOf("segment" to "remaining")))
        }

        for (airport in listOfNotNull(flight.route?.origin, flight.route?.destination)) {
            pins.add(MapMarker(id = "airport-${airport.icao}", coordinate = airport.coordinate,
                icon = MapMarker.Icon.Text(airport.displayCode, Color.WHITE),
                userInfo = mapOf("airport" to airport.name)))
        }
        pins.add(MapMarker(id = "aircraft", coordinate = state.coordinate, icon = MapMarker.Icon.Image(planeIcon),
            heading = state.heading, userInfo = mapOf("callsign" to state.callsign)))

        polylines = lines
        markers = pins
    }

    companion object {
        /**
         * Zoom used in follow mode. A whole-number regional zoom keeps the engine's motion-vector
         * radar morphing active (see the MapCamera documentation).
         */
        const val FOLLOW_ZOOM = 6f
        private const val POLL_INTERVAL_MS = 10_000L
        private val FLOWN_COLOR = Color.rgb(38, 217, 255)
        private val REMAINING_COLOR = Color.argb(217, 255, 255, 255)
    }
}
