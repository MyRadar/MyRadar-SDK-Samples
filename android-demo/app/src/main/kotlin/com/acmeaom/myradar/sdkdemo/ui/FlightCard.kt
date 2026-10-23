package com.acmeaom.myradar.sdkdemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.acmeaom.myradar.sdk.FlightAirport
import com.acmeaom.myradar.sdk.FlightInfo
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Compact card describing the flight the map shows, built from the SDK's FlightInfo. */
@Composable
fun FlightCard(flight: FlightInfo, onClose: () -> Unit) {
    PanelCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Header(flight, onClose)
            RouteRow(flight)
            StatsRow(flight)
        }
    }
}

private val accent = Color(0xFF26D9FF)
private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

@Composable
private fun Header(flight: FlightInfo, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(flight.ident, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        // "B789 · N61101 · En Route / On Time"
        Text(
            listOfNotNull(flight.aircraftType, flight.registration, flight.status.ifEmpty { null }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        StatusBadge(flight)
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Cancel, contentDescription = "Stop tracking", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusBadge(flight: FlightInfo) {
    val (text, color) = when {
        flight.isCancelled -> "Cancelled" to Color(0xFFE53935)
        flight.hasLanded -> "Landed" to Color(0xFFFB8C00)
        flight.isAirborne -> "In the air" to Color(0xFF43A047)
        else -> "Scheduled" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.2f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun RouteRow(flight: FlightInfo) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AirportColumn(flight.origin, flight.actualDeparture ?: flight.scheduledDeparture, Alignment.Start)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            LinearProgressIndicator(
                progress = { if (flight.hasLanded) 1f else (flight.progress ?: 0.0).toFloat() },
                color = accent,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.padding(top = 3.dp))
            Text(progressCaption(flight), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        AirportColumn(
            flight.destination,
            flight.actualArrival ?: flight.estimatedArrival ?: flight.scheduledArrival,
            Alignment.End,
        )
    }
}

/** "2 h 10 min to go", noting when the aircraft's position is estimated (common over oceans). */
private fun progressCaption(flight: FlightInfo): String {
    val toGo = flight.estimatedArrival?.takeIf { flight.isAirborne }?.let {
        val minutes = Duration.between(Instant.now(), it).toMinutes().coerceAtLeast(0)
        if (minutes < 60) "$minutes min to go" else "${minutes / 60} h ${minutes % 60} min to go"
    }
    val estimated = "estimated position".takeIf { flight.isAirborne && flight.position?.isEstimated == true }
    return listOfNotNull(toGo, estimated).joinToString(" · ")
}

@Composable
private fun AirportColumn(airport: FlightAirport?, time: Instant?, alignment: Alignment.Horizontal) {
    Column(Modifier.defaultMinSize(minWidth = 64.dp), horizontalAlignment = alignment) {
        Text(airport?.code ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(time?.let(timeFormat::format) ?: airport?.city ?: "—", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatsRow(flight: FlightInfo) {
    // After landing the last position is on the taxiway, so the in-flight stats are blank.
    val position = flight.position.takeUnless { flight.hasLanded }
    val numbers = NumberFormat.getIntegerInstance()
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Stat("Alt", position?.altitudeFeet?.let { "${numbers.format(it.toLong())} ft" } ?: "—")
        Stat("Speed", position?.groundSpeedKnots?.let { "${numbers.format((it * 1.15078).toLong())} mph" } ?: "—")
        Stat("Hdg", position?.heading?.let { "${it.toInt()}°" } ?: "—")
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}
