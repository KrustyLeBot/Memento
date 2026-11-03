package app.memento.activity

import java.io.ByteArrayOutputStream

/**
 * Reader and writer for the part of the Garmin FIT format an activity needs: positions, altitude,
 * heart rate, timer events and the session summary. Written from the public format description;
 * everything else in a file (laps, device data, other messages) is skipped.
 */
object Fit {
    /** FIT counts seconds from 1989-12-31 00:00 UTC. */
    private const val EPOCH_OFFSET = 631_065_600L
    private const val SEMICIRCLE = 180.0 / 2147483648.0

    private const val MSG_FILE_ID = 0
    private const val MSG_SPORT = 12
    private const val MSG_SESSION = 18
    private const val MSG_RECORD = 20
    private const val MSG_EVENT = 21
    private const val MSG_ACTIVITY = 34
    private const val MSG_FIELD_DESCRIPTION = 206
    private const val MSG_DEVELOPER_DATA_ID = 207

    private const val DEV_GPS_ALT = "gps_altitude"
    private const val DEV_PRESSURE = "pressure"

    class FitException(message: String) : Exception(message)

    // ---------------------------------------------------------------- reading

    private class FieldDef(val num: Int, val size: Int, val type: Int)
    private class DevFieldDef(val num: Int, val size: Int, val devIndex: Int)
    private class Definition(
        val global: Int, val bigEndian: Boolean, val fields: List<FieldDef>, val dev: List<DevFieldDef>,
    ) {
        val size = fields.sumOf { it.size } + dev.sumOf { it.size }
        /** Byte offset of each field number in a message (-1: absent), so a value is read where it lies. */
        val offsets = IntArray(256) { -1 }
        val types = IntArray(256)
        val sizes = IntArray(256)
        /** Offset of the developer fields' first byte. */
        val devStart: Int
        /** Only these messages are decoded: a 60-hour race is 800 000 messages, most of them skipped. */
        val wanted = global in WANTED

        init {
            var o = 0
            for (f in fields) {
                if (offsets[f.num] < 0) { offsets[f.num] = o; types[f.num] = f.type; sizes[f.num] = f.size }
                o += f.size
            }
            devStart = o
        }
    }

    private val WANTED = setOf(MSG_FILE_ID, MSG_SPORT, MSG_SESSION, MSG_RECORD, MSG_EVENT, MSG_FIELD_DESCRIPTION)

    fun looksLikeFit(bytes: ByteArray): Boolean =
        bytes.size >= 12 && bytes[8] == '.'.code.toByte() && bytes[9] == 'F'.code.toByte() &&
            bytes[10] == 'I'.code.toByte() && bytes[11] == 'T'.code.toByte()

    fun read(bytes: ByteArray): Track {
        if (!looksLikeFit(bytes)) throw FitException("Ce n'est pas un fichier FIT")
        val points = ArrayList<TrackPoint>()
        val hrs = HeartRates()
        val events = ArrayList<Pair<Long, Int>>() // (time, timer event type)
        var sport: Int? = null
        var start: Long? = null
        var elapsed: Long? = null
        var timer: Long? = null
        var distance: Double? = null
        var manufacturer: Int? = null

        var pos = 0
        while (pos + 12 <= bytes.size && looksLikeFit(bytes.copyOfRange(pos, pos + 12))) {
            val headerSize = bytes[pos].toInt() and 0xFF
            val dataSize = u(bytes, pos + 4, 4, false)
            var p = pos + headerSize
            val end = minOf(bytes.size.toLong(), p + dataSize).toInt()
            val defs = arrayOfNulls<Definition>(16)
            val msg = Msg(bytes)
            val devNames = DevNames()
            var lastTs = 0L

            while (p < end) {
                val header = bytes[p++].toInt() and 0xFF
                if (header and 0x80 != 0) { // compressed timestamp header
                    val local = (header shr 5) and 3
                    val def = defs[local] ?: throw FitException("Fichier FIT corrompu")
                    val offset = (header and 0x1F).toLong()
                    var ts = (lastTs and 0x1F.inv().toLong()) + offset
                    if (offset < (lastTs and 0x1F)) ts += 32
                    lastTs = ts
                    if (p + def.size > end) break
                    if (def.wanted) handle(def.global, msg.at(p, def, ts), points, hrs, events, devNames)
                    p += def.size
                    continue
                }
                val local = header and 0x0F
                if (header and 0x40 != 0) { // definition
                    val hasDev = header and 0x20 != 0
                    if (p + 5 > end) break
                    val big = bytes[p + 1].toInt() == 1
                    val global = u(bytes, p + 2, 2, big).toInt()
                    val count = bytes[p + 4].toInt() and 0xFF
                    p += 5
                    if (p + count * 3 > end) break
                    val fields = ArrayList<FieldDef>(count)
                    repeat(count) {
                        fields.add(FieldDef(bytes[p].toInt() and 0xFF, bytes[p + 1].toInt() and 0xFF, bytes[p + 2].toInt() and 0xFF))
                        p += 3
                    }
                    val dev = ArrayList<DevFieldDef>()
                    if (hasDev) {
                        val dc = bytes[p++].toInt() and 0xFF
                        repeat(dc) {
                            dev.add(DevFieldDef(bytes[p].toInt() and 0xFF, bytes[p + 1].toInt() and 0xFF, bytes[p + 2].toInt() and 0xFF))
                            p += 3
                        }
                    }
                    defs[local] = Definition(global, big, fields, dev)
                } else {
                    val def = defs[local] ?: throw FitException("Fichier FIT corrompu")
                    if (p + def.size > end) break
                    val values = msg.at(p, def, null)
                    p += def.size
                    // a skipped message still has its timestamp read: it anchors the compressed ones that follow
                    if (values.read(253)) lastTs = values.value
                    if (!def.wanted) continue
                    val summary = handle(def.global, values, points, hrs, events, devNames)
                    if (summary != null) {
                        when (def.global) {
                            MSG_SESSION -> {
                                values.num(5)?.let { sport = it.toInt() }
                                values.num(2)?.let { start = (it + EPOCH_OFFSET) * 1000 }
                                values.num(7)?.let { elapsed = it }
                                values.num(8)?.let { timer = it }
                                values.num(9)?.let { distance = it / 100.0 }
                            }
                            MSG_SPORT -> if (sport == null) values.num(0)?.let { sport = it.toInt() }
                            MSG_FILE_ID -> values.num(1)?.let { manufacturer = it.toInt() }
                        }
                    }
                }
            }
            pos = end + 2 // the file CRC
        }

        // Timer stops/starts → pauses. A trailing stop is the end of the activity, not a pause.
        val pauses = ArrayList<Pause>()
        var stoppedAt: Long? = null
        for ((t, type) in events.sortedBy { it.first }) {
            when (type) {
                1, 4, 8 -> if (stoppedAt == null) stoppedAt = t // stop, stop_all, stop_disable_all
                0 -> { // start
                    stoppedAt?.let { if (t > it) pauses.add(Pause(it, t)) }
                    stoppedAt = null
                }
            }
        }
        return Track(
            // a watch writes its records in order: no second list of every point unless they really are not
            points = if ((1 until points.size).all { points[it - 1].time <= points[it].time }) points else points.sortedBy { it.time },
            pauses = pauses,
            fitSport = sport,
            startTime = start ?: points.minOfOrNull { it.time },
            sessionElapsedMs = elapsed,
            sessionTimerMs = timer,
            sessionDistance = distance,
            hrAvg = if (hrs.count > 0) Math.round(hrs.sum.toDouble() / hrs.count).toInt() else null,
            hrMax = if (hrs.count > 0) hrs.max else null,
            manufacturer = manufacturer,
        )
    }

    /**
     * Names of the developer fields by (developer index, field number), both bytes in a record: a table, so looking
     * one up for every record allocates no key.
     */
    private class DevNames {
        private var names: Array<String?>? = null
        val any: Boolean get() = names != null

        operator fun get(idx: Int, num: Int): String? =
            if (idx in 0..255 && num in 0..255) names?.get(idx * 256 + num) else null

        /** A description outside byte range can never match a record's field: it is not kept. */
        operator fun set(idx: Int, num: Int, name: String) {
            if (idx !in 0..255 || num !in 0..255) return
            val table = names ?: arrayOfNulls<String>(256 * 256).also { names = it }
            table[idx * 256 + num] = name
        }
    }

    /** Count, sum and maximum of the heart rates: a list of 200 000 boxed values only ever served these. */
    private class HeartRates {
        var count = 0
        var sum = 0L
        var max = Int.MIN_VALUE

        fun add(hr: Int) {
            count++
            sum += hr
            if (hr > max) max = hr
        }
    }

    /**
     * One message, read in place: no map per message (there can be 800 000 of them). A single instance is moved from
     * message to message ([at]) and [read] leaves the value in [value], so reading a record boxes nothing.
     */
    private class Msg(val b: ByteArray) {
        var start = 0
            private set
        lateinit var def: Definition
            private set
        private var ts = 0L
        private var hasTs = false
        /** The value of the last successful [read]. */
        var value = 0L
            private set

        fun at(start: Int, def: Definition, ts: Long?): Msg {
            this.start = start
            this.def = def
            hasTs = ts != null
            this.ts = ts ?: 0L
            return this
        }

        /** Reads field [n] into [value]; false when the message lacks it or holds its invalid marker. */
        fun read(n: Int): Boolean {
            if (n == 253 && hasTs) { value = ts; return true }
            val o = def.offsets[n]
            if (o < 0) return false
            val type = def.types[n]
            val size = baseSize(type)
            if (size !in 1..8 || def.sizes[n] < size) return false
            val raw = u(b, start + o, size, def.bigEndian)
            if (isInvalid(type, raw, size)) return false
            value = if (isSigned(type)) signed(raw, size) else raw
            return true
        }

        fun num(n: Int): Long? = if (read(n)) value else null

        fun str(n: Int): String? {
            val o = def.offsets[n]
            if (o < 0 || def.types[n] and 0x1F != 7) return null
            var e = start + o
            val limit = start + o + def.sizes[n]
            while (e < limit && b[e].toInt() != 0) e++
            return String(b, start + o, e - start - o, Charsets.UTF_8)
        }

        /** Calls [f] with (developer index, field number, value) of each 4-byte developer field. */
        inline fun devFloats(f: (Int, Int, Float) -> Unit) {
            var p = start + def.devStart
            for (d in def.dev) {
                if (d.size == 4) f(d.devIndex, d.num, Float.fromBits(u(b, p, 4, def.bigEndian).toInt()))
                p += d.size
            }
        }
    }

    /** Returns non-null for messages that carry summary data handled by the caller. */
    private fun handle(
        global: Int, v: Msg, points: MutableList<TrackPoint>, hrs: HeartRates,
        events: MutableList<Pair<Long, Int>>, devNames: DevNames,
    ): Unit? {
        when (global) {
            MSG_RECORD -> {
                if (!v.read(253)) return null
                val time = (v.value + EPOCH_OFFSET) * 1000
                val hr = if (v.read(3)) v.value.toInt() else null
                if (hr != null) hrs.add(hr)
                if (!v.read(0)) return null
                val lat = v.value
                if (!v.read(1)) return null
                val lon = v.value
                val alt = if (v.read(78)) v.value / 5.0 - 500 else if (v.read(2)) v.value / 5.0 - 500 else null
                var gpsAlt: Double? = null
                var pressure: Double? = null
                if (devNames.any) v.devFloats { idx, num, value ->
                    when (devNames[idx, num]) {
                        DEV_GPS_ALT -> if (!value.isNaN()) gpsAlt = value.toDouble()
                        DEV_PRESSURE -> if (!value.isNaN()) pressure = value.toDouble()
                    }
                }
                points.add(TrackPoint(time, lat * SEMICIRCLE, lon * SEMICIRCLE, alt, hr, gpsAlt, pressure))
            }
            MSG_EVENT -> {
                val ts = v.num(253) ?: return null
                if ((v.num(0) ?: -1L) == 0L) v.num(1)?.let { events.add((ts + EPOCH_OFFSET) * 1000 to it.toInt()) }
            }
            MSG_FIELD_DESCRIPTION -> {
                val idx = v.num(0)?.toInt() ?: return null
                val num = v.num(1)?.toInt() ?: return null
                v.str(3)?.let { devNames[idx, num] = it }
            }
            MSG_SESSION, MSG_SPORT, MSG_FILE_ID -> return Unit
        }
        return null
    }

    private fun baseSize(type: Int): Int = when (type and 0x1F) {
        0, 1, 2, 10, 13 -> 1
        3, 4, 11 -> 2
        5, 6, 8, 12 -> 4
        9, 14, 15, 16 -> 8
        else -> 0
    }

    private fun isSigned(type: Int) = when (type and 0x1F) { 1, 3, 5, 14 -> true; else -> false }

    private fun isInvalid(type: Int, raw: Long, size: Int): Boolean = when (type and 0x1F) {
        0, 2, 13 -> raw == 0xFFL
        1 -> raw == 0x7FL
        3 -> raw == 0x7FFFL
        4 -> raw == 0xFFFFL
        5 -> raw == 0x7FFFFFFFL
        6 -> raw == 0xFFFFFFFFL
        10, 11, 12 -> raw == 0L
        8, 9 -> false
        else -> size == 8 && raw == -1L
    }

    private fun signed(raw: Long, size: Int): Long = when (size) {
        1 -> raw.toByte().toLong()
        2 -> raw.toShort().toLong()
        4 -> raw.toInt().toLong()
        else -> raw
    }

    private fun u(b: ByteArray, off: Int, size: Int, big: Boolean): Long {
        var v = 0L
        if (big) for (i in 0 until size) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        else for (i in size - 1 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    // ---------------------------------------------------------------- writing

    private class Out {
        val buf = ByteArrayOutputStream()
        fun u8(v: Int) = buf.write(v and 0xFF)
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { u8(v.toInt()); u8((v shr 8).toInt()); u8((v shr 16).toInt()); u8((v shr 24).toInt()) }
        fun bytes(b: ByteArray) = buf.write(b)
        fun str(s: String, size: Int) {
            val raw = s.toByteArray(Charsets.UTF_8).copyOf(size - 1)
            bytes(raw); u8(0)
        }
    }

    private const val T_ENUM = 0
    private const val T_U8 = 2
    private const val T_S32 = 5
    private const val T_U16 = 4
    private const val T_U32 = 6
    private const val T_STRING = 7
    private const val T_FLOAT32 = 8
    private const val T_BYTE = 13

    private fun definition(o: Out, local: Int, global: Int, fields: List<Triple<Int, Int, Int>>, dev: List<Triple<Int, Int, Int>> = emptyList()) {
        o.u8(0x40 or local or (if (dev.isNotEmpty()) 0x20 else 0))
        o.u8(0); o.u8(0) // reserved, little endian
        o.u16(global)
        o.u8(fields.size)
        for ((n, s, t) in fields) { o.u8(n); o.u8(s); o.u8(t) }
        if (dev.isNotEmpty()) {
            o.u8(dev.size)
            for ((n, s, i) in dev) { o.u8(n); o.u8(s); o.u8(i) }
        }
    }

    private fun fitTime(ms: Long) = ms / 1000 - EPOCH_OFFSET

    /**
     * Encodes [track] as an activity file. Points keep their consolidated altitude as the standard
     * field and the raw GPS altitude and pressure as developer fields.
     */
    fun write(track: Track, sport: Sport, totalDistance: Double): ByteArray {
        val o = Out()
        val first = track.points.firstOrNull()?.time ?: track.startTime ?: System.currentTimeMillis()
        val last = track.points.lastOrNull()?.time ?: first
        val elapsed = track.sessionElapsedMs ?: (last - first)
        val timer = track.sessionTimerMs ?: elapsed

        // file_id
        definition(o, 0, MSG_FILE_ID, listOf(Triple(0, 1, T_ENUM), Triple(1, 2, T_U16), Triple(2, 2, T_U16), Triple(4, 4, T_U32)))
        o.u8(0); o.u8(4); o.u16(255); o.u16(0); o.u32(fitTime(first))

        // developer data description
        definition(o, 1, MSG_DEVELOPER_DATA_ID, listOf(Triple(1, 16, T_BYTE), Triple(3, 1, T_U8)))
        o.u8(1)
        o.bytes(APP_ID); o.u8(0)
        definition(o, 2, MSG_FIELD_DESCRIPTION, listOf(Triple(0, 1, T_U8), Triple(1, 1, T_U8), Triple(2, 1, T_U8), Triple(3, 16, T_STRING), Triple(8, 8, T_STRING)))
        for ((num, name, unit) in listOf(Triple(0, DEV_GPS_ALT, "m"), Triple(1, DEV_PRESSURE, "hPa"))) {
            o.u8(2); o.u8(0); o.u8(num); o.u8(0x88); o.str(name, 16); o.str(unit, 8)
        }

        // timer events: start, then one stop/start per pause
        definition(o, 3, MSG_EVENT, listOf(Triple(253, 4, T_U32), Triple(0, 1, T_ENUM), Triple(1, 1, T_ENUM)))
        fun event(time: Long, type: Int) { o.u8(3); o.u32(fitTime(time)); o.u8(0); o.u8(type) }
        event(first, 0)

        definition(
            o, 4, MSG_RECORD,
            listOf(
                Triple(253, 4, T_U32), Triple(0, 4, T_S32), Triple(1, 4, T_S32), Triple(2, 2, T_U16),
                Triple(78, 4, T_U32), Triple(3, 1, T_U8),
            ),
            listOf(Triple(0, 4, 0), Triple(1, 4, 0)),
        )
        val pauses = track.pauses.sortedBy { it.start }
        var next = 0
        fun invalidFloat() = Float.NaN
        for (p in track.points) {
            while (next < pauses.size && pauses[next].start <= p.time) {
                event(pauses[next].start, 4)
                event(pauses[next].end, 0)
                next++
            }
            o.u8(4)
            o.u32(fitTime(p.time))
            o.u32((p.lat / SEMICIRCLE).toLong() and 0xFFFFFFFFL)
            o.u32((p.lon / SEMICIRCLE).toLong() and 0xFFFFFFFFL)
            val altRaw = p.alt?.let { ((it + 500) * 5).toLong().coerceIn(0, 0xFFFE) }
            o.u16((altRaw ?: 0xFFFF).toInt())
            o.u32(p.alt?.let { ((it + 500) * 5).toLong().coerceIn(0, 0xFFFFFFFEL) } ?: 0xFFFFFFFFL)
            o.u8(p.hr ?: 0xFF)
            o.u32((p.gpsAlt?.toFloat() ?: invalidFloat()).toRawBits().toLong() and 0xFFFFFFFFL)
            o.u32((p.pressure?.toFloat() ?: invalidFloat()).toRawBits().toLong() and 0xFFFFFFFFL)
        }
        while (next < pauses.size) { // pauses after the last point: still part of the timeline
            event(pauses[next].start, 4); event(pauses[next].end, 0); next++
        }
        event(last, 4)

        definition(
            o, 5, MSG_SESSION,
            listOf(
                Triple(253, 4, T_U32), Triple(2, 4, T_U32), Triple(5, 1, T_ENUM), Triple(6, 1, T_ENUM),
                Triple(7, 4, T_U32), Triple(8, 4, T_U32), Triple(9, 4, T_U32), Triple(254, 2, T_U16),
            ),
        )
        o.u8(5); o.u32(fitTime(last)); o.u32(fitTime(first)); o.u8(Sport.toFit(sport)); o.u8(0)
        o.u32(elapsed.coerceAtLeast(0)); o.u32(timer.coerceAtLeast(0)); o.u32(Math.round(totalDistance * 100)); o.u16(0)

        definition(o, 6, MSG_ACTIVITY, listOf(Triple(253, 4, T_U32), Triple(0, 4, T_U32), Triple(1, 2, T_U16), Triple(2, 1, T_ENUM), Triple(3, 1, T_ENUM), Triple(4, 1, T_ENUM)))
        o.u8(6); o.u32(fitTime(last)); o.u32(timer.coerceAtLeast(0)); o.u16(1); o.u8(0); o.u8(26); o.u8(1)

        val data = o.buf.toByteArray()
        val h = Out()
        h.u8(14); h.u8(0x20); h.u16(21_00); h.u32(data.size.toLong())
        h.bytes(".FIT".toByteArray())
        val headerNoCrc = h.buf.toByteArray()
        h.u16(crc(headerNoCrc))
        val file = ByteArrayOutputStream()
        file.write(h.buf.toByteArray())
        file.write(data)
        val bytes = file.toByteArray()
        val c = crc(bytes)
        return bytes + byteArrayOf(c.toByte(), (c shr 8).toByte())
    }

    private val APP_ID = ByteArray(16) { (0x47 + it * 7).toByte() }

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    fun crc(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            val b = byte.toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[b and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(b shr 4) and 0xF]
        }
        return crc
    }
}
