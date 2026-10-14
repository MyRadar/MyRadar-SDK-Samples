package com.acmeaom.myradar.sdkdemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap
import com.acmeaom.myradar.sdk.LatLng
import com.acmeaom.myradar.sdk.MapMarker
import com.acmeaom.myradar.sdk.MapPolygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mock flood-forecast overlay for marketing captures. There is no flood API behind this: it
 * draws procedurally generated water-depth bands with the SDK's polygon API and a depth marker
 * with the marker API, to show what a flood product could look like on the map. Enabled with the
 * `mock flood` launch extra.
 */
object FloodMock {

    /** Default location: downtown Tampa, along the Hillsborough River. Override with `center`. */
    val center = LatLng(27.9506, -82.4572)
    const val ZOOM = 12f

    private class Band(val depth: String, val radiusMeters: Double, val color: Int)

    /** Depth bands from shallow (outer) to deep (inner), with the marker placed in the 2 ft band. */
    fun polygons(around: LatLng = center): List<MapPolygon> {
        val bands = listOf(
            Band("1 ft", 4200.0, Color.argb(102, 115, 191, 255)),
            Band("2 ft", 2700.0, Color.argb(140, 51, 128, 242)),
            Band("3 ft", 1400.0, Color.argb(179, 13, 64, 191)),
        )
        val bayou = LatLng(around.latitude - 0.045, around.longitude + 0.036)
        return bands.flatMapIndexed { index, band ->
            // Two flooded pockets: the river bend downtown and a low area to the south-east.
            listOf(
                MapPolygon(
                    id = "flood-river-$index",
                    coordinates = blob(around, band.radiusMeters, stretch = 1.6, rotation = 0.35, seed = index + 1.0),
                    fillColor = band.color,
                    strokeColor = if (index == bands.lastIndex) Color.argb(153, 255, 255, 255) else null,
                    strokeWidthDp = 1f,
                    userInfo = mapOf("depth" to band.depth),
                ),
                MapPolygon(
                    id = "flood-bayou-$index",
                    coordinates = blob(bayou, band.radiusMeters * 0.6, stretch = 1.2, rotation = -0.8, seed = index + 7.0),
                    fillColor = band.color,
                    userInfo = mapOf("depth" to band.depth),
                ),
            )
        }
    }

    /** A "2 ft" depth marker inside the 2 ft band. */
    fun markers(context: Context, around: LatLng = center): List<MapMarker> {
        val spot = LatLng(around.latitude + 0.010, around.longitude - 0.020)
        return listOf(MapMarker("flood-depth", spot, MapMarker.Icon.Image(depthPill(context, "2 ft")),
            userInfo = mapOf("depth" to "2 ft")))
    }

    /** An irregular, roughly elliptical ring around a center. */
    private fun blob(c: LatLng, radiusMeters: Double, stretch: Double, rotation: Double, seed: Double): List<LatLng> {
        val points = 36
        val metersPerDegreeLat = 111_320.0
        val metersPerDegreeLon = metersPerDegreeLat * cos(c.latitude * PI / 180)
        return (0 until points).map { i ->
            val t = i.toDouble() / points * 2 * PI
            // Low-frequency wobble so the edge looks like terrain, not a circle.
            val wobble = 1 + 0.18 * sin(3 * t + seed) + 0.10 * sin(5 * t - seed * 1.3) + 0.06 * sin(8 * t + seed * 0.7)
            val r = radiusMeters * wobble
            val x = r * stretch * cos(t)
            val y = r * sin(t)
            val xr = x * cos(rotation) - y * sin(rotation)
            val yr = x * sin(rotation) + y * cos(rotation)
            LatLng(c.latitude + yr / metersPerDegreeLat, c.longitude + xr / metersPerDegreeLon)
        }
    }

    /** Material Symbols "water_drop", in a 24×24 viewport. */
    private const val DROP_PATH = "M12,2c-5.33,4.55 -8,8.48 -8,11.8c0,4.98 3.8,8.2 8,8.2s8,-3.22 8,-8.2C20,10.48 17.33,6.55 12,2z"

    /** A rounded pill with a water-drop glyph and the depth text, pointing north (no rotation needed). */
    private fun depthPill(context: Context, text: String): Bitmap {
        val d = context.resources.displayMetrics.density
        val bitmap = createBitmap((92 * d).toInt(), (40 * d).toInt())
        val canvas = Canvas(bitmap)
        val pill = RectF(3 * d, 3 * d, bitmap.width - 3 * d, bitmap.height - 3 * d)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(13, 77, 204)
            setShadowLayer(4 * d, 0f, 1 * d, Color.argb(153, 0, 0, 0))
        }
        canvas.drawRoundRect(pill, 17 * d, 17 * d, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * d
        }
        canvas.drawRoundRect(pill, 17 * d, 17 * d, stroke)

        val drop = PathParser.createPathFromPathData(DROP_PATH)
        drop.transform(Matrix().apply {
            setScale(18 * d / 24, 18 * d / 24)
            postTranslate(12 * d, 11 * d)
        })
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawPath(drop, white)

        white.textSize = 17 * d
        white.typeface = Typeface.DEFAULT_BOLD
        val baseline = bitmap.height / 2f - (white.descent() + white.ascent()) / 2
        canvas.drawText(text, 36 * d, baseline, white)
        return bitmap
    }
}
