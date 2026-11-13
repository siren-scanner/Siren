package app.sirenscout

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Checks the user's tracked flights every minute, even while Siren isn't scanning, and posts a
 * notification when one takes off, levels off, starts down, nears its destination, lands, squawks
 * an emergency or goes quiet. Stops by itself when no flights are left.
 */
class FlightWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_WATCH, "Tracking flights", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_UPDATES, "Flight status updates", NotificationManager.IMPORTANCE_HIGH))
        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoing("Checking your flights…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (!running) {
            running = true
            scope.launch { loop() }
        }
        return START_STICKY
    }

    private suspend fun loop() {
        while (scope.isActive) {
            if (!Flights.prune(this)) break
            val me = runCatching {
                getSystemService(LocationManager::class.java)?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }.getOrNull()
            var seen = 0
            val today = Flights.tripDay()
            val active = Flights.tracked.value.filter { Flights.isActive(it, today) }
            for (t0 in active) {
                var t = t0
                if (t.startDay.isNotEmpty() && "$today:today" !in t.notified) {
                    // A new day for a flight expected on several days: say so, and start its story afresh.
                    post(Flights.Update("$today:today", "${t.id} is expected today", "Siren will tell you when it shows up, takes off and lands."))
                    t = t.copy(notified = t.notified.filter { it.startsWith(today) }.toSet() + "$today:today", phase = "", lastSeenAt = 0)
                    Flights.save(this, Flights.tracked.value.map { if (it.id == t.id) t else it })
                }
                val live = runCatching { Flights.lookup(this, t.query) }.getOrElse {
                    AppLog.w("Flight ${t.query}: ${it.javaClass.simpleName}")
                    continue // network trouble: try again next round, without calling it "lost"
                }
                if (live != null) seen++
                Flights.live.value = Flights.live.value + (t.id to live)
                val (updates, next) = Flights.updates(
                    t, live, Airports.find(this, t.from), Airports.find(this, t.to), me?.latitude, me?.longitude,
                )
                Flights.save(this, Flights.tracked.value.map { if (it.id == t.id) next else it })
                updates.forEach(::post)
            }
            val n = active.size
            val next = Flights.tracked.value.filter { it.startDay > today }.minByOrNull { it.startDay }
            NotificationManagerCompat.from(this).runCatching {
                notify(
                    ONGOING_ID,
                    ongoing(
                        if (n > 0) "Tracking $n flight${if (n == 1) "" else "s"} today · $seen on the feed"
                        else next?.let { "Next: ${it.id} on ${it.daysText}" } ?: "Waiting for your flights' days"
                    ),
                )
            }
            // Between flight days, just look in now and then for the next day to start.
            delay(if (n > 0) POLL_MS else IDLE_MS)
        }
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun post(u: Flights.Update) {
        AlertSound.play(this, AlertSound.Kind.AIR)
        val n = NotificationCompat.Builder(this, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setContentTitle(u.title)
            .setContentText(u.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(u.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(UPDATE_ID_BASE + Math.floorMod(u.title.hashCode(), 1000), n) }
    }

    private fun ongoing(text: String): Notification = NotificationCompat.Builder(this, CH_WATCH)
        .setSmallIcon(R.drawable.ic_stat_siren)
        .setContentTitle("Siren is following your flights")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(openApp())
        .build()

    private fun openApp() = PendingIntent.getActivity(
        this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        running = false
        scope.cancel()
    }

    companion object {
        private const val CH_WATCH = "flight_watch"
        private const val CH_UPDATES = "flight_updates"
        private const val ONGOING_ID = 5000
        private const val UPDATE_ID_BASE = 5100
        private const val POLL_MS = 60_000L
        private const val IDLE_MS = 15 * 60_000L
        @Volatile private var running = false

        /** Starts following tracked flights, if there are any. Call from the foreground (the app screen). */
        fun start(context: Context) {
            if (!Flights.prune(context)) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, FlightWatchService::class.java)) }
                .onFailure { AppLog.w("Couldn't start flight tracking: $it") }
        }
    }
}
