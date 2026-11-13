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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FlightsDialog(
    onClose: () -> Unit,
    onShowOnMap: (lat: Double, lon: Double, title: String) -> Unit,
    /** Open on this tracked flight (from a notification) instead of the last search. */
    initialFlight: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The last search stays in the boxes when you come back.
    var recent by remember { mutableStateOf(Flights.recent(context)) }
    val opening = initialFlight?.let { id -> Flights.tracked.value.firstOrNull { it.id == id } ?: TrackedFlight(id) }
    var query by remember { mutableStateOf(opening?.query ?: recent.firstOrNull()?.query.orEmpty()) }
    var from by remember { mutableStateOf(opening?.from ?: recent.firstOrNull()?.from.orEmpty()) }
    var to by remember { mutableStateOf(opening?.to ?: recent.firstOrNull()?.to.orEmpty()) }
    var firstDay by remember { mutableStateOf(java.time.LocalDate.parse(Flights.tripDay())) }
    var lastDay by remember { mutableStateOf(firstDay) }
    /** Which date is being picked: "first" or "last". */
    var picking by remember { mutableStateOf<String?>(null) }
    val dayFormat = remember { java.time.format.DateTimeFormatter.ofPattern("EEE MMM d") }

    picking?.let { which ->
        val zone = java.time.ZoneOffset.UTC // the date picker works in UTC midnights
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = (if (which == "first") firstDay else lastDay).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
                        if (which == "first") {
                            firstDay = d
                            if (lastDay < d) lastDay = d
                        } else {
                            lastDay = if (d < firstDay) firstDay else d
                        }
                    }
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        ) { androidx.compose.material3.DatePicker(state) }
    }
    var result by remember { mutableStateOf<LiveFlight?>(null) }
    var message by remember { mutableStateOf("") }
    val tracked by Flights.tracked.collectAsStateWithLifecycle()
    val live by Flights.live.collectAsStateWithLifecycle()

    fun look() = scope.launch {
        recent = Flights.remember(context, Flights.Search(query.trim().uppercase(), from.trim().uppercase(), to.trim().uppercase()))
        message = "Looking up ${query.trim().uppercase()}…"
        result = null
        runCatching { withContext(Dispatchers.IO) { Flights.lookup(context, query) } }
            .onSuccess {
                result = it
                message = if (it == null) "Not on the feed right now. It may not have taken off yet, may have landed, or may be out of receiver coverage - you can still track it, and you'll be told when it appears." else ""
            }
            .onFailure { message = "Couldn't reach the flight feed (${it.javaClass.simpleName}). Is mobile data on?" }
    }

    // Coming back to the screen shows the last flight again.
    LaunchedEffect(Unit) { if (query.isNotBlank()) look() }

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
                    Text("Expected on", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { picking = "first" }, modifier = Modifier.weight(1f)) { Text(firstDay.format(dayFormat)) }
                        Text("to")
                        OutlinedButton(onClick = { picking = "last" }, modifier = Modifier.weight(1f)) { Text(lastDay.format(dayFormat)) }
                    }
                    Text(
                        "Siren only follows the flight on these days, and starts afresh each day - handy for a flight that runs daily. A day runs until 4 am, so a late flight still counts.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { look() }, enabled = query.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Look up") }
                        Button(
                            onClick = {
                                val t = TrackedFlight(
                                    query.trim().uppercase(), from.trim().uppercase(), to.trim().uppercase(),
                                    startDay = firstDay.toString(), endDay = lastDay.toString(),
                                )
                                Flights.track(context, t)
                                recent = Flights.remember(context, Flights.Search(t.id, t.from, t.to))
                                message = "Tracking ${t.id} on ${t.daysText}. You'll get a notification each of those days when it shows up, takes off, levels off, starts down, nears ${to.trim().uppercase().ifEmpty { "its destination" }}, lands, or has an emergency."
                            },
                            enabled = query.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text("Track & notify") }
                    }
                    if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    result?.let { f ->
                        FlightCard(f, Airports.find(context, from), Airports.find(context, to), onShowOnMap)
                    }

                    if (recent.size > 1 || (recent.size == 1 && !recent[0].query.equals(query.trim(), ignoreCase = true))) {
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent searches", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { Flights.clearRecent(context); recent = emptyList() }) { Text("Clear") }
                        }
                        recent.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    r.query + listOf(r.from, r.to).filter { it.isNotEmpty() }.joinToString(" → ", prefix = "   "),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f).clickable {
                                        query = r.query; from = r.from; to = r.to
                                        look()
                                    }.padding(vertical = 6.dp),
                                )
                                TextButton(onClick = { recent = Flights.forget(context, r.query) }) { Text("Remove") }
                            }
                        }
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
                                if (t.daysText.isNotEmpty()) Text(t.daysText, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                val today = Flights.tripDay()
                                if (f != null && Flights.isActive(t, today)) FlightCard(f, Airports.find(context, t.from), Airports.find(context, t.to), onShowOnMap)
                                else Text(
                                    when {
                                        t.startDay > today -> "Waiting - Siren starts following it on ${t.daysText.substringBefore(" –")}."
                                        t.id in live -> "Not on the feed right now - checked every minute."
                                        else -> "Checking…"
                                    },
                                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                    Text(
                        "Live data from the free adsb.lol ADS-B feed (volunteer receivers, so remote areas and oceans can have gaps); " +
                            "aircraft type and owner from hexdb.io. Departure and arrival airports aren't broadcast by aircraft - enter them " +
                            "to see distance and time remaining. Tracked flights are checked every minute on their days, even when Siren isn't " +
                            "scanning, and are dropped after their last day.",
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

/** Everything known about a drone or aircraft from the map or the Air tab. */
@Composable
fun AirDetailDialog(c: AirContact, onClose: () -> Unit, onShowOnMap: (lat: Double, lon: Double, title: String) -> Unit) {
    val context = LocalContext.current
    val hex = when {
        c.key.startsWith("aircraft|") -> c.key.substringAfter('|')
        c.key.startsWith("flight|") -> Flights.live.collectAsStateWithLifecycle().value[c.key.substringAfter('|')]?.hex
        else -> null
    }
    // Aircraft: the live feed's full picture, refreshed while open.
    val live by androidx.compose.runtime.produceState<LiveFlight?>(null, hex) {
        while (hex != null) {
            runCatching { withContext(Dispatchers.IO) { Flights.lookupHex(context, hex) } }.getOrNull()?.let { value = it }
            delay(15_000)
        }
    }
    val registration = remember(hex) { hex?.let { Adsb.registrations(context)[it] } }
    var tracked by remember { mutableStateOf(false) }

    FullScreenPage(onDismiss = onClose) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Close") }
                }
                HorizontalDivider()
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(c.kind.label, style = MaterialTheme.typography.titleSmall, color = airColor(c.kind))
                    registration?.let { r ->
                        Text(
                            "Registered to ${r.owner}" + if (r.police) " - a law-enforcement agency." else
                                " - a city, county or state government, so possibly police, fire or rescue.",
                        )
                        Text(listOf(r.tail, r.model).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    if (hex != null) {
                        val f = live
                        if (f != null) FlightCard(f, null, null, onShowOnMap)
                        else Text("Getting the latest from the feed…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Button(
                            onClick = {
                                val query = f?.callsign?.trim()?.takeIf { it.isNotEmpty() } ?: hex.uppercase()
                                val today = Flights.tripDay()
                                Flights.track(context, TrackedFlight(query, startDay = today, endDay = today))
                                tracked = true
                            },
                            enabled = !tracked && c.kind != AirContact.Kind.TRACKED_FLIGHT,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (tracked) "Tracking - see Air tab > Search & track flights" else "Track & notify") }
                    } else {
                        // A drone: what its Remote ID said.
                        val miles = usesMiles()
                        listOfNotNull(
                            c.detail,
                            c.altitudeText,
                            c.speedMs?.let { if (miles) "%.0f mph".format(it * 2.23694) else "%.0f km/h".format(it * 3.6) },
                            c.headingDeg?.let { "heading ${compass(it.toFloat())} (%.0f°)".format(it) },
                            c.distanceM?.let { formatShortDistance(it) + " from you" },
                            c.rssi?.let { "signal $it dBm (${proximity(it)})" },
                        ).forEach { Text(it) }
                        if (c.operatorLat != null && c.operatorLon != null) {
                            val fromDrone = if (c.lat != null && c.lon != null) " · " + formatShortDistance(Odometer.distanceM(c.lat, c.lon, c.operatorLat, c.operatorLon)) + " from the drone" else ""
                            Text(
                                "Operator at %.5f, %.5f$fromDrone · tap to show on the map".format(c.operatorLat, c.operatorLon),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { onShowOnMap(c.operatorLat, c.operatorLon, "Drone operator") },
                            )
                        }
                        Text(
                            "Remote ID doesn't say who owns a drone. The serial number and model are the best clue: Skydio, BRINC and " +
                                "\"drone as first responder\" programs are mostly police and fire.",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                        )
                    }
                    if (c.lat != null && c.lon != null) {
                        OutlinedButton(onClick = { onShowOnMap(c.lat, c.lon, c.title) }, modifier = Modifier.fillMaxWidth()) { Text("Show on map") }
                    }
                    Text(
                        "First seen ${((System.currentTimeMillis() - c.firstSeen) / 60_000)} min ago",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
            }
        }
    }
}
