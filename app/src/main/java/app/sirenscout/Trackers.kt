package app.sirenscout

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Recognizes item trackers (AirTag, Tile, SmartTag, Google Find Hub tags, DULT-standard tags)
 * from their BLE advertisements. Only broadcasts that mean "away from its owner" count where
 * the format says so, since a tracker next to its owner's phone isn't tracking you.
 */
object Trackers {
    private fun tracker(id: String, label: String) = Signature(
        id = id,
        label = label,
        category = Category.TRACKER,
        confidence = Confidence.HIGH,
        sources = setOf(Source.BLE),
    )

    val APPLE = tracker("tracker-apple", "AirTag / Find My device away from its owner")
    val TILE = tracker("tracker-tile", "Tile tracker")
    val SMARTTAG = tracker("tracker-smarttag", "Samsung SmartTag")
    val GOOGLE = tracker("tracker-google", "Google Find Hub tracker")
    val DULT = tracker("tracker-dult", "Location tracker")

    val ALL = listOf(APPLE, TILE, SMARTTAG, GOOGLE, DULT)

    /** Why each kind of tracker counts, for the details screen. */
    val REASONS = mapOf(
        APPLE.id to "Apple Find My broadcast in \u201caway from owner\u201d mode",
        TILE.id to "Advertises Tile's Bluetooth service",
        SMARTTAG.id to "Advertises Samsung SmartTag's Bluetooth service",
        GOOGLE.id to "Google Find Hub broadcast in \u201caway from owner\u201d (unwanted tracking protection) mode",
        DULT.id to "Advertises the standard unwanted-tracker detection service (DULT)",
    )

    private const val APPLE_COMPANY = 0x004C
    private const val FIND_MY_TYPE = 0x12
    /** Find My's long "offline finding" payload, sent once separated from the owner's devices. */
    private const val FIND_MY_SEPARATED_LEN = 0x19

    private val TILE_UUIDS = setOf(0xFEED, 0xFEEC, 0xFD84)
    private const val SMARTTAG_UUID = 0xFD5A
    /** A SmartTag not yet set up with an account (after RF Sentinel's list; arXiv 2210.14702). */
    private const val SMARTTAG_SETUP_UUID = 0xFD59
    /** Google's Find Hub network reuses the Eddystone service with its own frame types. */
    private const val EDDYSTONE_UUID = 0xFEAA
    /**
     * Frame 0x41 is Find Hub's "unwanted tracking protection" mode, used once a tag is away from
     * its owner (and its address then changes only daily). Frame 0x40 is a tag near its owner,
     * which also changes address every ~15 minutes - counting those made an owner's own tag
     * look like a stream of new trackers.
     */
    private const val FIND_HUB_SEPARATED_FRAME = 0x41
    /** IETF "Detecting Unwanted Location Trackers" standard service. */
    private const val DULT_UUID = 0xFCB2

    /**
     * @param manufacturer manufacturer-specific data by Bluetooth company ID
     * @param serviceData service data by 16-bit service UUID
     * @param services advertised 16-bit service UUIDs
     */
    fun classify(
        manufacturer: Map<Int, ByteArray>,
        serviceData: Map<Int, ByteArray>,
        services: Set<Int>,
    ): Signature? {
        manufacturer[APPLE_COMPANY]?.let { data ->
            if (data.size >= 2 && data[0].toInt() and 0xFF == FIND_MY_TYPE &&
                data[1].toInt() and 0xFF == FIND_MY_SEPARATED_LEN
            ) return APPLE
        }
        val uuids = services + serviceData.keys
        if (DULT_UUID in uuids) return DULT
        if (uuids.any { it in TILE_UUIDS }) return TILE
        if (SMARTTAG_UUID in uuids || SMARTTAG_SETUP_UUID in uuids) return SMARTTAG
        serviceData[EDDYSTONE_UUID]?.firstOrNull()?.let { frame ->
            if (frame.toInt() and 0xFF == FIND_HUB_SEPARATED_FRAME) return GOOGLE
        }
        return null
    }

    /** The Find My "separated" prefix, for a screen-off scan filter. */
    val appleFilterData = byteArrayOf(FIND_MY_TYPE.toByte(), FIND_MY_SEPARATED_LEN.toByte())
    val filterUuids16 = TILE_UUIDS + SMARTTAG_UUID + SMARTTAG_SETUP_UUID + DULT_UUID
}

/** Tracker types the user has said are theirs; their alerts are muted for a day. */
object TrackerMutes {
    private const val MUTE_MS = 24 * 3600_000L
    private val _muted = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Signature id to the time the mute ends. */
    val muted: StateFlow<Map<String, Long>> = _muted.asStateFlow()
    private var prefs: SharedPreferences? = null

    @Synchronized
    private fun prefs(context: Context): SharedPreferences = prefs ?: context.applicationContext
        .getSharedPreferences("tracker_mutes", Context.MODE_PRIVATE).also { sp ->
            prefs = sp
            _muted.value = sp.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
        }

    fun init(context: Context) {
        prefs(context)
    }

    fun isMuted(signatureId: String, now: Long = System.currentTimeMillis()) = (_muted.value[signatureId] ?: 0L) > now

    fun mute(context: Context, signatureId: String) {
        val until = System.currentTimeMillis() + MUTE_MS
        prefs(context).edit().putLong(signatureId, until).apply()
        _muted.update { it + (signatureId to until) }
        AppLog.i("Muted $signatureId tracker alerts for 24 h")
    }

    fun unmute(context: Context, signatureId: String) {
        prefs(context).edit().remove(signatureId).apply()
        _muted.update { it - signatureId }
    }
}
