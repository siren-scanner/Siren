package app.sirenscout

/**
 * Recognises a Tesla's phone-key Bluetooth signal. Every Tesla broadcasts the name "S" + 16 hex
 * digits worked out from its VIN + a letter (see VehicleLocalName in
 * github.com/teslamotors/vehicle-command), so the name alone says it's a Tesla.
 */
object TeslaKey {
    private val NAME = Regex("^S[0-9a-f]{16}[A-Z]$")
    const val LABEL = "Tesla car key"

    fun isTesla(name: String?) = name != null && NAME.matches(name)

    fun guess(name: String?): String? = if (isTesla(name)) LABEL else null
}
