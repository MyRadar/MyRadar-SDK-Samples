package com.acmeaom.myradar.sdkdemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.acmeaom.myradar.sdkdemo.FlightTracker
import com.acmeaom.myradar.sdkdemo.data.Airport
import com.acmeaom.myradar.sdkdemo.data.TrackedFlight
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

private val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

private fun Instant.shortTime(): String = timeFormatter.format(this)

/** Compact card describing the tracked flight. */
@Composable
fun FlightCard(tracker: FlightTracker, flight: TrackedFlight, onToggleAttributions: () -> Unit) {
    PanelCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Header(tracker, flight)
            RouteRow(tracker, flight)
            StatsRow(flight)
            Footer(tracker, onToggleAttributions)
        }
    }
}

@Composable
private fun Header(tracker: FlightTracker, flight: TrackedFlight) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(flight.displayNumber, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        if (flight.displayNumber != flight.state.callsign) {
            Text(flight.state.callsign, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        // "MyRadar Air · Boeing 737-800 · N123MR"
        Text(
            listOfNotNull(flight.airline?.name, flight.aircraftDescription).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        StatusBadge(flight)
        IconButton(onClick = tracker::clearFlight, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Cancel, contentDescription = "Stop tracking", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusBadge(flight: TrackedFlight) {
    val rate = flight.state.verticalRateFeetPerMinute
    val (text, color) = when {
        flight.state.onGround -> "On ground" to Color(0xFFFF9500)
        rate != null && rate > 300 -> "Climbing" to Color(0xFF34C759)
        rate != null && rate < -300 -> "Descending" to Color(0xFFFFCC00)
        else -> "En route" to Color(0xFF34C759)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.2f), CircleShape)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun RouteRow(tracker: FlightTracker, flight: TrackedFlight) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val departure = tracker.departure
        AirportColumn(flight.route?.origin, departure?.first, isEstimate = departure?.second ?: true, Alignment.Start)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            LinearProgressIndicator(
                progress = { (tracker.progress ?: 0.0).toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = FlightAccent,
            )
            Text(tracker.timeRemainingText?.let { "$it to go" } ?: " ", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AirportColumn(flight.route?.destination, tracker.estimatedArrival, isEstimate = true, Alignment.End)
    }
}

@Composable
private fun AirportColumn(airport: Airport?, time: Instant?, isEstimate: Boolean, alignment: Alignment.Horizontal) {
    Column(Modifier.defaultMinSize(minWidth = 64.dp), horizontalAlignment = alignment, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(airport?.displayCode ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            time?.let { (if (isEstimate) "≈ " else "") + it.shortTime() } ?: (airport?.city ?: "—"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatsRow(flight: TrackedFlight) {
    val state = flight.state
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Stat("Alt", state.altitudeFeet?.let { "${NumberFormat.getIntegerInstance().format(it.toInt())} ft" } ?: "—")
        Stat("Speed", state.groundSpeedKnots?.let { "${(it * 1.15078).roundToInt()} mph" } ?: "—")
        Stat("Hdg", state.heading?.let { "${it.toInt()}°" } ?: "—")
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Footer(tracker: FlightTracker, onToggleAttributions: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val modes = FlightTracker.CameraMode.entries
        SingleChoiceSegmentedButtonRow(Modifier.width(170.dp)) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = tracker.cameraMode == mode,
                    onClick = { tracker.updateCameraMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    icon = {},
                ) {
                    Text(mode.title, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        IconButton(onClick = tracker::recenter) {
            Icon(Icons.Filled.CenterFocusStrong, contentDescription = "Recenter")
        }
        Spacer(Modifier.weight(1f))
        tracker.lastUpdate?.let { updated ->
            // Last position update time; the clock glyph replaces the word "Updated".
            Icon(Icons.Outlined.Schedule, contentDescription = null, Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(updated.shortTime(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onToggleAttributions) {
            Icon(Icons.Outlined.Info, contentDescription = "Data attributions")
        }
    }
}
