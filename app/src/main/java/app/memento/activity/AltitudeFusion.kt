package app.memento.activity

import kotlin.math.pow

/**
 * Altitude from the barometer, kept honest by the GPS: the pressure gives smooth, precise changes,
 * the GPS gives the absolute level. The gap between both is followed very slowly, so weather drift
 * is absorbed without GPS noise getting through.
 */
class AltitudeFusion(private val seaLevelHpa: Double = 1013.25) {
    private var offset: Double? = null
    private var lastTime = 0L

    /** Altitude (m) from pressure with the standard atmosphere. */
    fun fromPressure(hpa: Double): Double = 44330.0 * (1 - (hpa / seaLevelHpa).pow(1 / 5.255))

    /**
     * [pressure] (hPa) and [gpsAlt] may each be missing. Returns the best altitude, or null when
     * neither is available.
     */
    fun update(time: Long, pressure: Double?, gpsAlt: Double?, gpsAccuracy: Float?): Double? {
        if (pressure == null) return gpsAlt
        val baro = fromPressure(pressure)
        val off = offset
        if (gpsAlt == null || (gpsAccuracy != null && gpsAccuracy > 30f)) {
            return if (off == null) null else baro + off
        }
        if (off == null) {
            offset = gpsAlt - baro
        } else {
            // time constant of about 5 minutes
            val dt = ((time - lastTime).coerceAtLeast(0)) / 1000.0
            val k = (dt / 300.0).coerceIn(0.0, 1.0)
            offset = off + k * ((gpsAlt - baro) - off)
        }
        lastTime = time
        return baro + offset!!
    }
}
