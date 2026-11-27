package app.sirenscout

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Siren's built-in RTL-SDR driver: talks to the USB dongle itself (librtlsdr + libusb, built into
 * the app) instead of needing the separate "RTL2832U driver" app. It serves the dongle as rtl_tcp
 * on 127.0.0.1, so the radio code that reads it (RtlTcpMonitor) is the same either way.
 */
object SdrDriver {
    const val ACTION_PERMISSION = "app.sirenscout.USB_PERMISSION"

    /** RTL2832U dongles: RTL-SDR Blog V3/V4 and most generic ones (0BDA:2838), and others librtlsdr knows. */
    private val KNOWN = setOf(
        0x0BDA to 0x2832, 0x0BDA to 0x2838, 0x0413 to 0x6680, 0x0413 to 0x6F0F, 0x0458 to 0x707F,
        0x0CCD to 0x00A9, 0x0CCD to 0x00B3, 0x0CCD to 0x00D3, 0x0CCD to 0x00E0, 0x1554 to 0x5020,
        0x15F4 to 0x0131, 0x15F4 to 0x0133, 0x185B to 0x0620, 0x185B to 0x0650, 0x1B80 to 0xD393,
        0x1B80 to 0xD394, 0x1B80 to 0xD395, 0x1B80 to 0xD39D, 0x1F4D to 0xB803, 0x1F4D to 0xC803,
        0x1D19 to 0x1101, 0x1D19 to 0x1102, 0x1D19 to 0x1103,
    )

    /** Whether the built-in driver could be loaded on this phone. */
    val available: Boolean by lazy { runCatching { System.loadLibrary("sirensdr") }.isSuccess }

    fun isDongle(d: UsbDevice) = (d.vendorId to d.productId) in KNOWN

    fun findDongle(context: Context): UsbDevice? =
        context.getSystemService(UsbManager::class.java)?.deviceList?.values?.firstOrNull(::isDongle)

    fun hasPermission(context: Context, d: UsbDevice) = context.getSystemService(UsbManager::class.java)?.hasPermission(d) == true

    /** Shows Android's "Allow Siren to access the USB device?"; the answer arrives as [ACTION_PERMISSION]. */
    fun requestPermission(context: Context, d: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        context.getSystemService(UsbManager::class.java)?.requestPermission(d, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    @Volatile private var thread: Thread? = null

    /**
     * This run's secret: the built-in server only serves a client that sends it first, so other
     * apps on the phone can't listen to or control the dongle through 127.0.0.1.
     */
    @Volatile var token: ByteArray? = null
        private set

    /** The built-in server is running (a client should send [token] first). */
    val running get() = thread?.isAlive == true
    @Volatile private var connection: UsbDeviceConnection? = null

    /**
     * Starts serving the plugged-in dongle on 127.0.0.1:[port]. Returns null when running (or
     * already running), else why not.
     */
    @Synchronized
    fun start(context: Context, port: Int): String? {
        if (thread?.isAlive == true) return null
        if (!available) return "built-in driver not available on this phone"
        val d = findDongle(context) ?: return "dongle not connected"
        val usb = context.getSystemService(UsbManager::class.java) ?: return "no USB support"
        if (!usb.hasPermission(d)) return "allow USB access to the dongle (tap Start again)"
        val conn = usb.openDevice(d) ?: return "couldn't open the dongle"
        connection = conn
        val secret = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        token = secret
        nativePrepare() // here, not in the thread: a stop() right after start() must not be lost
        thread = Thread({
            val r = runCatching { nativeServe(conn.fileDescriptor, port, secret) }.getOrElse { -3 }
            if (r != 0) AppLog.w("Built-in SDR driver stopped with error $r")
            conn.close()
            connection = null
        }, "siren-sdr").apply { isDaemon = true; start() }
        AppLog.i("Built-in SDR driver started: ${d.productName ?: "RTL-SDR"} (%04x:%04x)".format(d.vendorId, d.productId))
        return null
    }

    @Synchronized
    fun stop() {
        if (thread == null) return
        runCatching { nativeStop() }
        thread?.join(3_000)
        // Still winding down: keep it, so start() doesn't open the dongle a second time meanwhile.
        if (thread?.isAlive != true) thread = null
    }

    @JvmStatic private external fun nativePrepare()
    @JvmStatic private external fun nativeServe(usbFd: Int, port: Int, token: ByteArray): Int
    @JvmStatic private external fun nativeStop()
}
