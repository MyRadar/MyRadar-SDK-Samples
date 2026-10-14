package com.acmeaom.myradar.sdkdemo.data

import com.acmeaom.myradar.sdk.LatLng
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Spherical-earth helpers for distances, bearings and route interpolation. */
object GreatCircle {
    const val EARTH_RADIUS_NAUTICAL_MILES = 3440.065

    private fun Double.radians() = Math.toRadians(this)
    private fun Double.degrees() = Math.toDegrees(this)

    /** Shortest surface distance between two coordinates in nautical miles. */
    fun distanceNauticalMiles(a: LatLng, b: LatLng): Double {
        val lat1 = a.latitude.radians()
        val lat2 = b.latitude.radians()
        val dLat = (b.latitude - a.latitude).radians()
        val dLon = (b.longitude - a.longitude).radians()
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_NAUTICAL_MILES * asin(minOf(1.0, sqrt(h)))
    }

    /** Initial bearing from [a] toward [b], in degrees clockwise from north. */
    fun initialBearing(a: LatLng, b: LatLng): Double {
        val lat1 = a.latitude.radians()
        val lat2 = b.latitude.radians()
        val dLon = (b.longitude - a.longitude).radians()
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val bearing = atan2(y, x).degrees()
        return if (bearing < 0) bearing + 360 else bearing
    }

    /** The point a fraction of the way along the great circle from [a] to [b]. */
    fun interpolate(a: LatLng, b: LatLng, fraction: Double): LatLng {
        val lat1 = a.latitude.radians()
        val lon1 = a.longitude.radians()
        val lat2 = b.latitude.radians()
        val lon2 = b.longitude.radians()
        val d = distanceNauticalMiles(a, b) / EARTH_RADIUS_NAUTICAL_MILES
        if (d <= 1e-9) return a
        val sa = sin((1 - fraction) * d) / sin(d)
        val sb = sin(fraction * d) / sin(d)
        val x = sa * cos(lat1) * cos(lon1) + sb * cos(lat2) * cos(lon2)
        val y = sa * cos(lat1) * sin(lon1) + sb * cos(lat2) * sin(lon2)
        val z = sa * sin(lat1) + sb * sin(lat2)
        return LatLng(atan2(z, sqrt(x * x + y * y)).degrees(), atan2(y, x).degrees())
    }

    /** A polyline approximating the great circle from [a] to [b]. */
    fun path(a: LatLng, b: LatLng, segments: Int = 64): List<LatLng> {
        if (segments <= 1) return listOf(a, b)
        return (0..segments).map { interpolate(a, b, it.toDouble() / segments) }
    }
}
