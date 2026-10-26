package com.acmeaom.myradar.sdkdemo

import android.content.Intent
import com.acmeaom.myradar.sdk.LatLng
import com.acmeaom.myradar.sdk.MapStyle
import com.acmeaom.myradar.sdk.Overlay

/**
 * Test hooks read from the launch intent's extras, the counterpart of the iOS demo's launch
 * arguments. For example:
 *
 * ```
 * adb shell am start -n com.acmeaom.myradar.sdkdemo/.MainActivity \
 *     --es query "UA 1" --es overlays warnings,lightning --ef zoom 6
 * ```
 *
 * | Extra | Effect |
 * |---|---|
 * | `--es query <flight number>` | Shows a flight at launch |
 * | `--ef zoom <level>`, `--es center <lat>,<lon>` | Initial camera |
 * | `--es overlays a,b,c` | Initial overlays: warnings, lightning, flashes, hurricanes, wind, none |
 * | `--es style <name>` | Base map: gray, roads, aerial, topographic, dark, light |
 * | `--ez kiosk true` | Shows only the map, full screen |
 * | `--es mock flood` | Paints the mock flood-forecast overlay (see [FloodMock]) |
 * | `--es opacity warnings=0.9,hurricanes=0.3` | Initial overlay opacities |
 * | `--es opacity-panel <layer>` | Opens the opacity row on a layer: radar, warnings, hurricanes, wind |
 */
data class LaunchOptions(
    val query: String? = null,
    val zoom: Float? = null,
    val center: LatLng? = null,
    val overlays: Set<Overlay>? = null,
    val style: MapStyle? = null,
    val kiosk: Boolean = false,
    val floodMock: Boolean = false,
    val overlayOpacities: Map<Overlay, Float> = emptyMap(),
    val opacityPanel: String? = null,
) {
    companion object {
        fun from(intent: Intent?): LaunchOptions {
            val extras = intent?.extras ?: return LaunchOptions()
            return LaunchOptions(
                query = extras.getString("query"),
                zoom = extras.getFloat("zoom", Float.NaN).takeUnless { it.isNaN() }
                    ?: extras.getString("zoom")?.toFloatOrNull(),
                center = extras.getString("center")?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
                    ?.takeIf { it.size == 2 }?.let { LatLng(it[0], it[1]) },
                overlays = extras.getString("overlays")?.let(::parseOverlays),
                style = extras.getString("style")?.lowercase()
                    ?.let { name -> MapStyle.entries.firstOrNull { it.displayName.lowercase() == name } },
                kiosk = extras.getBoolean("kiosk"),
                floodMock = extras.getString("mock") == "flood",
                overlayOpacities = extras.getString("opacity")?.let(::parseOpacities).orEmpty(),
                opacityPanel = extras.getString("opacity-panel")?.lowercase(),
            )
        }

        private fun parseOverlays(list: String): Set<Overlay> {
            val names = list.lowercase().split(',').map { it.trim() }.toSet()
            return buildSet {
                if ("warnings" in names) add(Overlay.Warnings)
                if ("lightning" in names) add(Overlay.Lightning)
                if ("flashes" in names) add(Overlay.LightningFlashes)
                if ("hurricanes" in names) add(Overlay.Hurricanes)
                if ("wind" in names) add(Overlay.Wind)
            }
        }

        private fun parseOpacities(spec: String): Map<Overlay, Float> =
            spec.split(',').mapNotNull { item ->
                val parts = item.split('=')
                if (parts.size != 2) return@mapNotNull null
                val value = parts[1].toFloatOrNull() ?: return@mapNotNull null
                val overlay = Overlay.entries.firstOrNull { it.displayName.lowercase() == parts[0].trim().lowercase() }
                overlay?.let { it to value }
            }.toMap()
    }
}
