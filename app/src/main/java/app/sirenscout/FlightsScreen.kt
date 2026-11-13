package app.sirenscout

import android.location.LocationManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Look up any flight by number, callsign or tail number, see everything the feed knows, and track it. */
@Composable
fun FlightsDialog(onClose: () -> Unit, onShowOnMap: (lat: Double, lon: Double, title: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<LiveFlight?>(null) }
    var message by remember { mutableStateOf("") }
    val tracked by Flights.tracked.collectAsStateWithLifecycle()
    val live by Flights.live.collectAsStateWithLifecycle()

    fun look() = scope.launch {
        message = "Looking up ${query.trim().uppercase()}…"
        result = null
        runCatching { withContext(Dispatchers.IO) { Flights.lookup(context, query) } }
            .onSuccess {
                result = it
                message = if (it == null) "Not on the feed right now. It may not have taken off yet, may have landed, or may be out of receiver coverage - you can still track it, and you'll be told when it appears." else ""
            }
            .onFailure { message = "Couldn't reach the flight feed (${it.javaClass.simpleName}). Is mobile data on?" }
    }

    // Keep the open result fresh.
    LaunchedEffect(result?.hex) {
        while (result != null) {
            delay(30_000)
            runCatching { withContext(Dispatchers.IO) { Flights.lookup(context, query) } }.getOrNull()?.let { result = it }
        }
    }

    FullScreenPage(onDismiss = onClose) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Flights", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Close") }
                }
                HorizontalDivider()
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it.take(12) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Flight number, callsign or tail number") },
                        supportingText = { Text("For example AA100, DAL2895 or N12345") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = from, onValueChange = { from = it.take(4) }, singleLine = true, modifier = Modifier.weight(1f),
                            label = { Text("From (optional)") }, supportingText = { Text(Airports.find(context, from)?.label ?: "e.g. DFW") },
                        )
                        OutlinedTextField(
                            value = to, onValueChange = { to = it.take(4) }, singleLine = true, modifier = Modifier.weight(1f),
                            label = { Text("To (optional)") }, supportingText = { Text(Airports.find(context, to)?.label ?: "e.g. JFK") },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { look() }, enabled = query.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Look up") }
                        Button(
                            onClick = {
                                Flights.track(context, TrackedFlight(query.trim().uppercase(), from.trim().uppercase(), to.trim().uppercase()))
                                message = "Tracking ${query.trim().uppercase()}. You'll get a notification when it takes off, levels off, starts down, nears ${to.trim().uppercase().ifEmpty { "its destination" }}, lands, or has an emergency."
                            },
                            enabled = query.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text("Track & notify") }
                    }
                    if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    result?.let { f ->
                        FlightCard(f, Airports.find(context, from), Airports.find(context, to), onShowOnMap)
                    }

                    if (tracked.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Tracking", style = MaterialTheme.typography.titleSmall)
                        tracked.forEach { t ->
                            val f = live[t.id]
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        t.id + listOf(t.from, t.to).filter { it.isNotEmpty() }.joinToString(" → ", prefix = "  ").takeIf { t.from.isNotEmpty() || t.to.isNotEmpty() }.orEmpty(),
                                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { Flights.untrack(context, t.id) }) { Text("Stop") }
                                }
                                if (f != null) FlightCard(f, Airports.find(context, t.from), Airports.find(context, t.to), onShowOnMap)
                                else Text(
                                    if (t.id in live) "Not on the feed right now - checked every minute." else "Checking…",
                                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                    Text(
                        "Live data from the free adsb.lol ADS-B feed (volunteer receivers, so remote areas and oceans can have gaps); " +
                            "aircraft type and owner from hexdb.io. Departure and arrival airports aren't broadcast by aircraft - enter them " +
                            "to see distance and time remaining. Tracked flights are checked every minute, even when Siren isn't scanning, " +
                            "and are dropped after 36 hours.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
            }
        }
    }
}

/** Everything known about a flight right now. */
@Composable
private fun FlightCard(f: LiveFlight, from: Airport?, to: Airport?, onShowOnMap: (Double, Double, String) -> Unit) {
    val context = LocalContext.current
    val details by androidx.compose.runtime.produceState<Pair<String?, String?>>(null to null, f.hex) {
        value = withContext(Dispatchers.IO) { Flights.aircraftDetails(f.hex) }
    }
    val me = remember {
        runCatching { context.getSystemService(LocationManager::class.java)?.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
    }
    val miles = usesMiles()
    fun speed(kt: Double) = if (miles) "%.0f mph".format(kt * 1.15078) else "%.0f km/h".format(kt * 1.852)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                listOfNotNull(f.callsign, Flights.airline(f.callsign)).joinToString(" · ").ifEmpty { f.hex.uppercase() },
                style = MaterialTheme.typography.titleMedium,
            )
            f.emergency?.let {
                Text("EMERGENCY: $it (squawk ${f.squawk})", color = Color(0xFFE53935), fontWeight = FontWeight.Bold)
            }
            Text(Flights.phase(f).replaceFirstChar(Char::uppercase) + listOfNotNull(
                f.altitudeFt?.let { "%,d ft".format(it) }.takeIf { !f.onGround },
                f.verticalRateFpm?.takeIf { kotlin.math.abs(it) >= 300 }?.let { (if (it > 0) "up " else "down ") + "%,d ft/min".format(kotlin.math.abs(it)) },
                f.selectedAltitudeFt?.takeIf { !f.onGround }?.let { "heading for %,d ft".format(it) },
            ).joinToString(" · ", prefix = " · ").takeIf { it.length > 3 }.orEmpty())
            Text(listOfNotNull(
                f.speedKt?.let { speed(it) + " (%.0f kt)".format(it) },
                f.trackDeg?.let { "heading ${compass(it.toFloat())} (%.0f°)".format(it) },
            ).joinToString(" · "))
            if (from != null || to != null) {
                Text(listOfNotNull(from?.let { "From ${it.label}" }, to?.let { "to ${it.label}" }).joinToString(" "))
                Flights.describeTo(f, to)?.let { Text(it) }
            }
            Text(
                listOfNotNull(f.registration, details.first ?: f.typeCode, details.second).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF90A4AE),
            )
            if (f.lat != null && f.lon != null) {
                val away = me?.let { formatShortDistance(Odometer.distanceM(it.latitude, it.longitude, f.lat, f.lon)) + " from you · " }.orEmpty()
                Text(
                    "$away%.4f, %.4f · tap to show on the map".format(f.lat, f.lon),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onShowOnMap(f.lat, f.lon, f.callsign ?: f.hex) },
                )
            }
            Row {
                Text(
                    listOfNotNull(f.squawk?.let { "squawk $it" }, "ICAO ${f.hex.uppercase()}", f.secondsSinceSeen?.let { "seen %.0fs ago".format(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                Spacer(Modifier.weight(1f))
            }
        }
    }
}
