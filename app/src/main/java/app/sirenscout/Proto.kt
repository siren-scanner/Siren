package app.sirenscout

import java.io.ByteArrayOutputStream

/** Just enough of the protobuf wire format for Meshtastic's messages, without a protobuf library. */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun varint(v: Long) {
        var x = v
        while (x and 0x7FL.inv() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
        out.write(x.toInt())
    }

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun uint(field: Int, v: Long) = apply { tag(field, 0); varint(v) }
    fun bool(field: Int, v: Boolean) = apply { if (v) uint(field, 1) }
    fun fixed32(field: Int, v: Int) = apply {
        tag(field, 5)
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF); out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
    }
    fun bytes(field: Int, v: ByteArray) = apply { tag(field, 2); varint(v.size.toLong()); out.write(v) }
    fun string(field: Int, v: String) = bytes(field, v.toByteArray())
    fun message(field: Int, m: ProtoWriter) = bytes(field, m.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Reads a protobuf message's top-level fields: varints as Long, length-delimited as ByteArray, fixed32 as Int. */
internal class ProtoReader(private val b: ByteArray) {
    data class Field(val number: Int, val long: Long = 0, val bytes: ByteArray? = null)

    fun fields(): List<Field> {
        val list = ArrayList<Field>()
        var i = 0
        fun varint(): Long {
            var shift = 0; var r = 0L
            while (true) {
                val x = b[i++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
        }
        try {
            while (i < b.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> list += Field(field, varint())
                    1 -> { list += Field(field, (0 until 8).fold(0L) { a, k -> a or ((b[i + k].toLong() and 0xFF) shl (8 * k)) }); i += 8 }
                    2 -> { val n = varint().toInt(); list += Field(field, bytes = b.copyOfRange(i, i + n)); i += n }
                    5 -> { list += Field(field, (0 until 4).fold(0L) { a, k -> a or ((b[i + k].toLong() and 0xFF) shl (8 * k)) }); i += 4 }
                    else -> return list // groups aren't used by Meshtastic
                }
            }
        } catch (e: IndexOutOfBoundsException) { /* truncated: keep what was read */ }
        return list
    }

    fun first(number: Int) = fields().firstOrNull { it.number == number }
}
