package com.acmeaom.myradar.sdkdemo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cyclone
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.acmeaom.myradar.sdk.LatLng
import com.acmeaom.myradar.sdk.MapCamera
import com.acmeaom.myradar.sdk.MapStyle
import com.acmeaom.myradar.sdk.MyRadarMapView
import com.acmeaom.myradar.sdk.Overlay
import com.acmeaom.myradar.sdk.RadarProduct
import com.acmeaom.myradar.sdk.compose.MyRadarMap
import com.acmeaom.myradar.sdkdemo.FloodMock
import com.acmeaom.myradar.sdkdemo.FlightTracker
import com.acmeaom.myradar.sdkdemo.LaunchOptions
import com.acmeaom.myradar.sdkdemo.data.FlightSummary

/*
 * A one-screen flight tracker built on the MyRadar Android SDK. Controls sit above the map, the
 * map has its own panel, and the flight details card sits below. The SDK's polyline and marker
 * APIs draw the flight path and aircraft over live radar, warnings and lightning. Flight data
 * comes from free public ADS-B services (see FlightDataService.kt).
 */

/** Which layer the opacity slider controls: the radar, or an overlay with adjustable opacity. */
private sealed interface OpacityTarget {
    val title: String

    data object Radar : OpacityTarget {
        override val title = "Radar"
    }

    data class OverlayTarget(val overlay: Overlay) : OpacityTarget {
        override val title get() = overlay.displayName
    }

    companion object {
        val all: List<OpacityTarget> = listOf(Radar) + Overlay.entries.filter { it.supportsOpacity }.map(::OverlayTarget)
    }
}

@Composable
fun DemoScreen(options: LaunchOptions, tracker: FlightTracker = viewModel()) {
    val context = LocalContext.current

    // Map configuration, driven by the controls.
    var mapStyle by remember { mutableStateOf(options.style ?: MapStyle.Gray) }
    var radarProduct by remember { mutableStateOf(RadarProduct.Radar) }
    var radarEnabled by remember { mutableStateOf(true) }
    var overlays by remember { mutableStateOf(options.overlays ?: setOf(Overlay.Warnings, Overlay.Lightning)) }
    var radarOpacity by remember { mutableStateOf(if (options.floodMock) 0.4f else 0.65f) }
    val overlayOpacities = remember { mutableStateMapOf<Overlay, Float>().apply { putAll(options.overlayOpacities) } }
    var showsOpacityControl by remember { mutableStateOf(false) }
    var opacityTarget by remember { mutableStateOf<OpacityTarget>(OpacityTarget.Radar) }
    var showAttributions by remember { mutableStateOf(false) }
    var tappedMarkerMessage by remember { mutableStateOf<String?>(null) }

    // Radar morphing with motion vectors needs a regional view, so start closer than a
    // whole-continent zoom.
    val initialCamera = remember {
        MapCamera(
            center = options.center ?: if (options.floodMock) FloodMock.center else LatLng(37.5, -96.0),
            zoom = options.zoom ?: if (options.floodMock) FloodMock.ZOOM else 5f,
        )
    }
    val floodPolygons = remember { if (options.floodMock) FloodMock.polygons(initialCamera.center) else emptyList() }
    val floodMarkers = remember { if (options.floodMock) FloodMock.markers(context, initialCamera.center) else emptyList() }

    LaunchedEffect(Unit) {
        if (options.sample) {
            tracker.performLoadSample()
        } else if (options.query != null) {
            tracker.searchText = options.query
            tracker.performSearch()
            tracker.results.firstOrNull()?.let { tracker.select(it) }
        }
        if (options.routeMode) tracker.updateCameraMode(FlightTracker.CameraMode.WholeRoute)
    }

    // One map instance, moved between the portrait and landscape layouts so rotating never tears
    // down and recreates the engine.
    val map = remember {
        movableContentOf { modifier: Modifier ->
            MyRadarMap(
                modifier = modifier,
                initialCamera = initialCamera,
                mapStyle = mapStyle,
                radarProduct = radarProduct,
                radarEnabled = radarEnabled,
                radarOpacity = radarOpacity,
                overlays = overlays,
                overlayOpacities = overlayOpacities.toMap(),
                polygons = floodPolygons,
                polylines = tracker.polylines,
                markers = tracker.markers + floodMarkers,
                camera = tracker.camera,
                visibleRegion = tracker.region,
                onMarkerTap = { marker ->
                    tappedMarkerMessage = marker.userInfo["airport"] ?: marker.userInfo["callsign"]
                        ?: marker.userInfo["depth"]?.let { "Water depth $it" }
                },
            )
        }
    }

    if (options.kiosk) {
        map(Modifier.fillMaxSize())
        return
    }

    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Clearing focus alone leaves the soft keyboard up; hide it while the field still has focus.
    val dismissKeyboard = {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SearchBar(tracker, Modifier.weight(1f), onSubmit = {
                    dismissKeyboard()
                    tracker.search()
                })
                IconButton(onClick = {
                    dismissKeyboard()
                    tracker.loadSample()
                }) {
                    Icon(Icons.Filled.AirplanemodeActive, contentDescription = "Load sample flight (offline)")
                }
                LayersMenu(mapStyle, { mapStyle = it }, radarProduct, { radarProduct = it })
                IconButton(onClick = { showsOpacityControl = !showsOpacityControl }) {
                    Icon(Icons.Filled.Opacity, contentDescription = "Layer opacity",
                        tint = if (showsOpacityControl) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LayerChips(
                radarEnabled = radarEnabled,
                onRadarChange = { radarEnabled = it },
                overlays = overlays,
                onOverlaysChange = { overlays = it },
            )
            AnimatedVisibility(showsOpacityControl) {
                val value = when (val target = opacityTarget) {
                    OpacityTarget.Radar -> radarOpacity
                    is OpacityTarget.OverlayTarget ->
                        overlayOpacities[target.overlay] ?: target.overlay.defaultOpacity ?: 1f
                }
                OpacityRow(opacityTarget, { opacityTarget = it }, value) { newValue ->
                    when (val target = opacityTarget) {
                        OpacityTarget.Radar -> radarOpacity = newValue
                        is OpacityTarget.OverlayTarget -> overlayOpacities[target.overlay] = newValue
                    }
                }
            }
            if (tracker.results.isNotEmpty()) {
                ResultsList(tracker, onSelect = { result ->
                    dismissKeyboard()
                    tracker.selectResult(result)
                })
            }
            tracker.errorMessage?.let { Banner(it, Icons.Outlined.WarningAmber) }
            tappedMarkerMessage?.let { message ->
                Banner(message, Icons.Filled.TouchApp, Modifier.clickable { tappedMarkerMessage = null })
            }
        }
    }

    val infoPanel: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val flight = tracker.flight
            if (flight != null) {
                FlightCard(tracker, flight, onToggleAttributions = { showAttributions = !showAttributions })
            } else {
                Hint()
            }
            if (showAttributions) AttributionsCard()
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(16.dp),
        ) {
            val panel = Modifier.clip(RoundedCornerShape(16.dp))
            val columnWidth = (maxWidth * 0.42f).coerceIn(320.dp, 420.dp)
            if (maxWidth > maxHeight) {
                // Landscape: controls and card in a left column, map filling the rest.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        Modifier
                            .width(columnWidth)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        controls()
                        infoPanel()
                    }
                    map(panel.weight(1f).fillMaxSize())
                }
            } else {
                // Portrait: controls above the map, flight card below.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    controls()
                    map(panel.weight(1f).fillMaxWidth())
                    infoPanel()
                }
            }
        }
    }
}

// MARK: - Controls

@Composable
private fun SearchBar(tracker: FlightTracker, modifier: Modifier, onSubmit: () -> Unit) {
    OutlinedTextField(
        value = tracker.searchText,
        onValueChange = { tracker.searchText = it },
        modifier = modifier,
        placeholder = { Text("Flight, airport or route", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            when {
                tracker.isSearching -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                tracker.searchText.isNotEmpty() -> IconButton(onClick = { tracker.searchText = "" }) {
                    Icon(Icons.Filled.Cancel, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
    )
}

/** Base map and radar product pickers. Layer on/off switches live in [LayerChips]. */
@Composable
private fun LayersMenu(
    mapStyle: MapStyle,
    onMapStyleChange: (MapStyle) -> Unit,
    radarProduct: RadarProduct,
    onRadarProductChange: (RadarProduct) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Layers, contentDescription = "Base map and radar product")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuHeader("Base Map")
            for (style in MapStyle.entries) {
                CheckItem(style.displayName, style == mapStyle) {
                    onMapStyleChange(style)
                    expanded = false
                }
            }
            HorizontalDivider()
            MenuHeader("Radar Product")
            for (product in RadarProduct.entries) {
                CheckItem(product.displayName, product == radarProduct) {
                    onRadarProductChange(product)
                    expanded = false
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun CheckItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        leadingIcon = { if (checked) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
    )
}

/** One tappable chip per layer, wrapping onto as many lines as needed: filled when on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayerChips(
    radarEnabled: Boolean,
    onRadarChange: (Boolean) -> Unit,
    overlays: Set<Overlay>,
    onOverlaysChange: (Set<Overlay>) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LayerChip("Radar", Icons.Filled.Umbrella, radarEnabled) { onRadarChange(!radarEnabled) }
        for (overlay in Overlay.entries) {
            val on = overlay in overlays
            LayerChip(shortName(overlay), icon(overlay), on) {
                onOverlaysChange(if (on) overlays - overlay else overlays + overlay)
            }
        }
    }
}

@Composable
private fun LayerChip(title: String, icon: ImageVector, isOn: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = isOn,
        onClick = onToggle,
        label = { Text(title) },
        leadingIcon = { Icon(icon, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.semantics { contentDescription = "$title ${if (isOn) "on" else "off"}" },
    )
}

private fun shortName(overlay: Overlay) = if (overlay == Overlay.LightningFlashes) "Flashes" else overlay.displayName

private fun icon(overlay: Overlay): ImageVector = when (overlay) {
    Overlay.Warnings -> Icons.Filled.Warning
    Overlay.Lightning -> Icons.Filled.Bolt
    Overlay.LightningFlashes -> Icons.Filled.FlashOn
    Overlay.Hurricanes -> Icons.Filled.Cyclone
    Overlay.Wind -> Icons.Filled.Air
}

/** Layer picker plus slider, driving the SDK's radar and overlay opacity APIs. */
@Composable
private fun OpacityRow(
    target: OpacityTarget,
    onTargetChange: (OpacityTarget) -> Unit,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    PanelCard(shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                TextButton(
                    onClick = { expanded = true },
                    modifier = Modifier.semantics { contentDescription = "Opacity layer: ${target.title}" },
                ) { Text(target.title) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    for (option in OpacityTarget.all) {
                        CheckItem(option.title, option == target) {
                            onTargetChange(option)
                            expanded = false
                        }
                    }
                }
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "${target.title} opacity" },
            )
            Text("${(value * 100).toInt()}%", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End,
                modifier = Modifier.width(44.dp).padding(end = 8.dp))
        }
    }
}

@Composable
private fun ResultsList(tracker: FlightTracker, onSelect: (FlightSummary) -> Unit) {
    PanelCard(shape = RoundedCornerShape(12.dp)) {
        LazyColumn(Modifier.heightIn(max = 200.dp)) {
            items(tracker.results, key = { it.id }) { result ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(result) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(result.title, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                    Text(result.subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Banner(text: String, icon: ImageVector, modifier: Modifier = Modifier) {
    PanelCard(modifier = modifier, shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// MARK: - Hint and attributions

@Composable
private fun Hint() {
    PanelCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Track a flight over live radar", style = MaterialTheme.typography.titleMedium)
            Text(
                "Search by flight number (UA 1, DAL100), by airport (SFO, KJFK) to list arrivals and departures, " +
                    "or by route (SFO-LAX). The airplane button loads an offline sample flight. Tap a layer chip " +
                    "to turn it on or off; the layers button picks the base map and radar product.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AttributionsCard() {
    val context = LocalContext.current
    val mapAttributions = remember { MyRadarMapView.licensesAndAttributions(context).joinToString(", ") { it.name } }
    PanelCard(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Data attributions", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(
                "Flight positions, routes and airports: adsb.lol contributors (ODbL). Airline names: adsbdb.com. " +
                    "Flight times are estimates from distance and ground speed.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Map: $mapAttributions", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PanelCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        content()
    }
}
