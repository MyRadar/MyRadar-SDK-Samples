package com.acmeaom.myradar.sdkdemo.data

import android.content.Context
import com.acmeaom.myradar.sdk.LatLng
import org.json.JSONArray
import kotlin.math.abs

/**
 * Offline airport lookup backed by `assets/airports.json`, a filtered copy of the OurAirports
 * dataset (public domain): large airports plus medium airports with scheduled service. Used to
 * work out where a recorded track actually began.
 */
class AirportIndex(private val airports: List<Airport>) {

    /** The airport closest to a coordinate, if one lies within [radiusNauticalMiles]. */
    fun nearest(coordinate: LatLng, radiusNauticalMiles: Double = 15.0): Airport? {
        var best: Airport? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (airport in airports) {
            // Cheap bounding-box reject before the spherical distance.
            if (abs(airport.coordinate.latitude - coordinate.latitude) >= 1 ||
                abs(airport.coordinate.longitude - coordinate.longitude) >= 1.5
            ) continue
            val distance = GreatCircle.distanceNauticalMiles(airport.coordinate, coordinate)
            if (distance <= radiusNauticalMiles && distance < bestDistance) {
                best = airport
                bestDistance = distance
            }
        }
        return best
    }

    /** Looks up an airport by ICAO or IATA code. */
    fun airport(code: String): Airport? {
        val upper = code.uppercase()
        return airports.firstOrNull { it.icao == upper || it.iata == upper }
    }

    companion object {
        val EMPTY = AirportIndex(emptyList())

        /** Reads the bundled dataset. Call off the main thread; it parses about 375 KB of JSON. */
        fun load(context: Context): AirportIndex = runCatching {
            val text = context.assets.open("airports.json").bufferedReader().use { it.readText() }
            parse(text)
        }.getOrDefault(EMPTY)

        fun parse(json: String): AirportIndex {
            val array = JSONArray(json)
            val airports = (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                Airport(
                    icao = o.optString("icao").ifEmpty { return@mapNotNull null },
                    iata = o.optStringOrNull("iata"),
                    name = o.optString("name"),
                    city = o.optStringOrNull("city"),
                    coordinate = LatLng(o.optDouble("lat"), o.optDouble("lon")),
                )
            }
            return AirportIndex(airports)
        }
    }
}
