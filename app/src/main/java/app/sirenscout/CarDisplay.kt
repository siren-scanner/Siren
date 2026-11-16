package app.sirenscout

import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * A small web page served from the phone, so another screen - like a car's browser on the
 * phone's hotspot - can show Siren's alerts live. It only answers on the hotspot and USB
 * tethering (and Wi-Fi networks, if the user allows that), only to clients that know the secret
 * code in the address, and ignores an address after repeated wrong codes.
 */
object CarDisplay {
    /** 8080 for Siren, 8081 for Siren Test, so both can serve a page at once. */
    val PORT = BuildConfig.CAR_PORT

    data class Alert(val title: String, val text: String, val at: Long)

    @Volatile var lastAlert: Alert? = null
        private set

    private var server: ServerSocket? = null
    private var job: Job? = null
    /** Its own scope, so the settings switch can start or stop it without restarting scanning. */
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True while the page is being served; shown in Settings. */
    @Volatile var serving = false
        private set
    @Volatile var error: String? = null
        private set

    /** Also answer on Wi-Fi networks the phone has joined (off: hotspot and USB tethering only). */
    @Volatile var allowWifi = false

    /** Wrong codes per address; after this many, that address is ignored until the page restarts. */
    private const val MAX_FAILURES = 10
    private val failures = HashMap<String, Int>()

    /** Records an alert for the page to show; called wherever a notification goes out. */
    fun publishAlert(title: String, text: String) {
        lastAlert = Alert(title, text, System.currentTimeMillis())
    }

    fun newCode(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789" // no look-alikes, easy to type on a car screen
        val rnd = SecureRandom()
        return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    @Synchronized
    fun start(code: String, port: Int = PORT) {
        if (job?.isActive == true) return
        job = ownScope.launch {
            val socket = runCatching { ServerSocket(port) }.getOrElse {
                error = "couldn't open port $port (${it.message})"
                AppLog.w("Car display couldn't open port $port: $it")
                return@launch
            }
            server = socket
            serving = true
            error = null
            AppLog.i("Car display serving on port $port")
            try {
                while (isActive) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    launch { runCatching { client.use { handle(it, code) } } }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                serving = false
                runCatching { socket.close() }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        serving = false
        synchronized(failures) { failures.clear() }
        job?.cancel()
        job = null
    }

    /** Applies a settings change straight away while scanning. */
    fun restart(code: String) {
        stop()
        start(code)
    }

    /** Remote addresses already logged, so a page polling every second doesn't flood the log. */
    private val loggedClients = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun handle(client: Socket, code: String) {
        client.soTimeout = 5_000
        val from = client.inetAddress.hostAddress ?: "?"
        if (loggedClients.add(from)) {
            AppLog.i("Car display: connection from $from to ${client.localAddress.hostAddress}${if (isPrivate(client.inetAddress)) "" else " (refused: not a private network)"}")
        }
        if (!isPrivate(client.inetAddress)) return // not from a local / hotspot device
        if (!allowedHere(client.localAddress)) return // e.g. someone else on a café's Wi-Fi
        if ((synchronized(failures) { failures[from] } ?: 0) >= MAX_FAILURES) return
        val reader = client.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        while (true) { val h = reader.readLine() ?: break; if (h.isEmpty()) break } // skip headers
        val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: return
        val out = client.getOutputStream()
        fun send(status: String, type: String, body: String, extraHeaders: String = "") {
            val bytes = body.toByteArray()
            out.write(
                ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n$extraHeaders\r\n").toByteArray()
            )
            out.write(bytes)
            out.flush()
        }
        when (path) {
            // The addresses Siren shows have no trailing slash; without one, the page's own
            // requests would resolve outside the code. Send the browser to the slash form.
            "/$code" -> send("302 Found", "text/plain", "", "Location: /$code/\r\n")
            "/$code/" -> send("200 OK", "text/html; charset=utf-8", PAGE)
            "/$code/state" -> send("200 OK", "application/json", state().toString())
            else -> {
                synchronized(failures) { failures[from] = (failures[from] ?: 0) + 1 }
                send("404 Not Found", "text/plain", "Not found")
            }
        }
    }

    /** Whether the connection came in over the hotspot, USB tethering, or Wi-Fi if allowed. */
    private fun allowedHere(local: InetAddress): Boolean {
        if (local.isLoopbackAddress) return true
        val ip = local.hostAddress ?: return false
        val name = runCatching { NetworkInterface.getByInetAddress(local)?.name }.getOrNull() ?: return false
        return when (kind(name, ip)) {
            "Hotspot", "USB tethering", "Other" -> true
            "Wi-Fi" -> allowWifi
            else -> false // Tailscale, mobile data
        }
    }

    /** The addresses the page can be opened on right now. */
    fun servingAddresses(): List<Pair<String, String>> =
        addresses().filter { (label, _) -> !label.startsWith("Tailscale") && (allowWifi || !label.startsWith("Wi-Fi")) }

    /** Loopback, private LAN, link-local and Tailscale's 100.64.0.0/10 range. */
    private fun isPrivate(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        val b = a.address
        return b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
    }

    private fun state(): JSONObject {
        val now = System.currentTimeMillis()
        val status = Detections.status.value
        val hidden = Hidden.rules.value
        val visible = Detections.items.value.values.filterNot(hidden::hides)
        val matches = visible.filter { it.signature != null }.sortedByDescending { it.lastSeen }
        val active = matches.filter {
            it.signature?.category != Category.TRACKER && now - it.lastSeen < Detections.ACTIVE_WINDOW_MS
        }
        fun item(d: Detection) = JSONObject()
            .put("title", d.title)
            .put("category", d.signature?.category?.label ?: "")
            .put("color", "#%06X".format(d.signature?.category.color()?.toArgb()?.and(0xFFFFFF) ?: 0x616161))
            .put("source", d.source.label)
            .put("rssi", d.rssi)
            .put("proximity", if (d.source == Source.RF) d.name ?: "" else distanceText(d))
            .put("secondsAgo", (now - d.lastSeen) / 1000)
        return JSONObject()
            .put("running", status.running)
            .put("status", JSONObject().put("ble", status.ble).put("bluetooth", status.classic).put("wifi", status.wifi).put("rf", status.rf))
            .put("active", JSONArray(active.take(8).map(::item)))
            .put("recent", JSONArray(matches.take(15).map(::item)))
            .put("nearbyCount", visible.size)
            .put("alert", lastAlert?.let { JSONObject().put("title", it.title).put("text", it.text).put("secondsAgo", (now - it.at) / 1000) })
    }

    /**
     * Every IPv4 address the phone has right now, labelled by what kind of connection it is,
     * with the interface's own name so a wrong guess can be spotted.
     */
    fun addresses(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nic.name to it.hostAddress!! } }
            .mapNotNull { (name, ip) -> kind(name, ip)?.let { "$it ($name)" to ip } }
            .sortedBy { (label, _) -> ORDER.indexOfFirst { label.startsWith(it) }.let { if (it < 0) 99 else it } }
    }.getOrDefault(emptyList())

    private val ORDER = listOf("Hotspot", "USB tethering", "Wi-Fi", "Tailscale", "Other")

    private fun kind(name: String, ip: String): String? = when {
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("seth") -> null // mobile data
        name.startsWith("v4-") || ip.startsWith("192.0.0.") -> null // IPv4-over-IPv6 shim for mobile data
        ip.startsWith("100.") && name.startsWith("tun") -> "Tailscale"
        name.startsWith("rndis") || name.startsWith("ncm") || name.startsWith("usb") -> "USB tethering"
        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") || name == "wlan1" -> "Hotspot"
        name.startsWith("wlan") -> "Wi-Fi"
        else -> "Other"
    }

    private val PAGE = """
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Siren</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; font: 22px system-ui, sans-serif; background: #0e1424; color: #eee; }
  header { display: flex; align-items: center; gap: 16px; padding: 16px 24px; background: #14213D; }
  header h1 { margin: 0; font-size: 30px; flex: 1; }
  #conn { font-size: 18px; color: #8a93a8; }
  button { font: inherit; font-size: 18px; padding: 10px 18px; border-radius: 12px; border: 0; background: #2a3657; color: #eee; }
  #banner { margin: 20px 24px; padding: 28px; border-radius: 18px; background: #262d40; font-size: 34px; font-weight: 600; }
  #banner.hot { background: #E53935; animation: pulse 1s ease-in-out 3; }
  #banner small { display: block; font-size: 22px; font-weight: 400; margin-top: 8px; opacity: .9; }
  @keyframes pulse { 50% { filter: brightness(1.4); } }
  #alert { margin: 0 24px 16px; padding: 18px 24px; border-radius: 14px; background: #3a2a10; display: none; }
  .grid { display: grid; grid-template-columns: 2fr 1fr; gap: 20px; padding: 0 24px 24px; }
  .card { background: #1a2236; border-radius: 16px; padding: 16px 20px; }
  .card h2 { margin: 0 0 10px; font-size: 20px; color: #8a93a8; font-weight: 500; }
  .row { display: flex; align-items: center; gap: 14px; padding: 10px 0; border-top: 1px solid #263049; }
  .dot { width: 16px; height: 16px; border-radius: 50%; flex: none; }
  .row .t { flex: 1; } .row .m { color: #8a93a8; font-size: 18px; }
  .status div { padding: 4px 0; font-size: 18px; color: #c5cbe0; }
</style></head>
<body>
<header><h1>Siren</h1><span id="conn">connecting…</span><button id="sound">Enable sound</button></header>
<div id="banner">Connecting to your phone…</div>
<div id="alert"></div>
<div class="grid">
  <div class="card"><h2>Matches</h2><div id="list"></div></div>
  <div class="card status"><h2>Scanners</h2><div id="status"></div></div>
</div>
<script>
let audio = null, lastAlertKey = null, fails = 0;
document.getElementById('sound').onclick = () => {
  audio = audio || new (window.AudioContext || window.webkitAudioContext)();
  beep(); document.getElementById('sound').textContent = 'Sound on';
};
function beep() {
  if (!audio) return;
  [0, 0.25].forEach(d => { const o = audio.createOscillator(), g = audio.createGain();
    o.frequency.value = 880; g.gain.value = 0.25; o.connect(g); g.connect(audio.destination);
    o.start(audio.currentTime + d); o.stop(audio.currentTime + d + 0.15); });
}
const esc = s => String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
function row(d) {
  return '<div class="row"><span class="dot" style="background:' + d.color + '"></span><span class="t">' + esc(d.title) +
    '<div class="m">' + esc(d.category) + ' · ' + esc(d.source) + '</div></span><span class="m">' +
    esc(d.proximity) + '<br>' + d.secondsAgo + 's ago</span></div>';
}
async function tick() {
  try {
    const base = location.pathname.endsWith('/') ? location.pathname : location.pathname + '/';
    const s = await (await fetch(base + 'state', {cache: 'no-store'})).json();
    fails = 0;
    document.getElementById('conn').textContent = 'live';
    const b = document.getElementById('banner');
    if (!s.running) { b.className = ''; b.innerHTML = 'Not scanning<small>Press Start in Siren on your phone.</small>'; }
    else if (s.active.length) {
      const top = s.active[0];
      b.className = 'hot';
      b.innerHTML = 'Emergency equipment nearby (' + s.active.length + ')<small>' + esc(top.title) + ' - ' + esc(top.proximity) + '</small>';
    } else { b.className = ''; b.innerHTML = 'No emergency equipment detected<small>' + s.nearbyCount + ' devices nearby</small>'; }
    const a = document.getElementById('alert');
    if (s.alert && s.alert.secondsAgo < 120) {
      a.style.display = 'block'; a.innerHTML = '<b>' + esc(s.alert.title) + '</b><br>' + esc(s.alert.text);
      const key = s.alert.title + s.alert.text + Math.round(Date.now() / 1000 - s.alert.secondsAgo);
      if (key !== lastAlertKey) { if (lastAlertKey !== null) beep(); lastAlertKey = key; }
    } else a.style.display = 'none';
    document.getElementById('list').innerHTML = s.recent.length ? s.recent.map(row).join('') : '<div class="m">Nothing matched yet.</div>';
    const st = s.status;
    document.getElementById('status').innerHTML = ['BLE: ' + st.ble, 'Bluetooth: ' + st.bluetooth, 'Wi-Fi: ' + st.wifi, 'RF: ' + st.rf]
      .map(x => '<div>' + esc(x) + '</div>').join('');
  } catch (e) {
    fails++;
    document.getElementById('conn').textContent = fails > 3 ? 'lost connection to phone - retrying' : 'reconnecting…';
  }
}
tick(); setInterval(tick, 1000);
</script>
</body></html>
""".trimIndent()
}
