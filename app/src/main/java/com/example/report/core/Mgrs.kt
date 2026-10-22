package com.example.report.core

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** WGS84 lat/lon → MGRS (port of the proj4js `mgrs` forward algorithm, 1 m precision). */
object Mgrs {
    private const val DEG = 0.017453292519943295 // PI / 180
    private const val A = 6378137.0 // WGS84 semi-major axis
    private const val ECC2 = 0.00669438 // eccentricity squared
    private const val ECC_P2 = 0.006739496752268451 // ECC2 / (1 - ECC2)
    private const val K0 = 0.9996

    // 100k square set origins
    private const val SET_ORIGIN_COLUMN_LETTERS = "AJSAJS"
    private const val SET_ORIGIN_ROW_LETTERS = "AFAFAF"
    private const val LA = 65 // 'A'
    private const val LI = 73 // 'I'
    private const val LO = 79 // 'O'
    private const val LV = 86 // 'V'
    private const val LZ = 90 // 'Z'

    // TODO(decomp): only `forward$default` survives, i.e. there was a trailing defaulted
    //  parameter (most likely `accuracy = 5`) that R8 constant-folded away. Note the argument
    //  order: callers pass (lng, lat).
    fun forward(lon: Double, lat: Double): String {
        val latR = lat * DEG
        val lonR = lon * DEG

        var zone = floor((lon + 180.0) / 6.0).toInt() + 1
        if (lon == 180.0) zone = 60
        if (lat >= 56.0 && lat < 64.0 && lon >= 3.0 && lon < 12.0) zone = 32
        if (lat >= 72.0 && lat < 84.0) {
            when {
                lon >= 0.0 && lon < 9.0 -> zone = 31
                lon >= 9.0 && lon < 21.0 -> zone = 33
                lon >= 21.0 && lon < 33.0 -> zone = 35
                lon >= 33.0 && lon < 42.0 -> zone = 37
            }
        }
        val lonOriginR = ((zone - 1) * 6 - 180 + 3) * DEG

        val n = A / sqrt(1.0 - ECC2 * sin(latR) * sin(latR))
        val t = tan(latR) * tan(latR)
        val c = ECC_P2 * cos(latR) * cos(latR)
        val a = cos(latR) * (lonR - lonOriginR)
        val m = A * ((1 - ECC2 / 4 - 3 * ECC2 * ECC2 / 64 - 5 * ECC2 * ECC2 * ECC2 / 256) * latR -
            (3 * ECC2 / 8 + 3 * ECC2 * ECC2 / 32 + 45 * ECC2 * ECC2 * ECC2 / 1024) * sin(2 * latR) +
            (15 * ECC2 * ECC2 / 256 + 45 * ECC2 * ECC2 * ECC2 / 1024) * sin(4 * latR) -
            (35 * ECC2 * ECC2 * ECC2 / 3072) * sin(6 * latR))
        // TODO(decomp): the APK holds these series coefficients pre-folded
        //  (0.9983242984503243, 0.002514607064228144, 2.639046602129982E-6, 3.418046101696858E-9,
        //  58*ECC_P2 = 0.39089081163157013, 330*ECC_P2 = 2.2240339282485886).

        val easting = K0 * n * (a + (1 - t + c) * a * a * a / 6.0 +
            (5 - 18 * t + t * t + 72 * c - 58 * ECC_P2) * a * a * a * a * a / 120.0) + 500000.0
        var northing = K0 * (m + n * tan(latR) * (a * a / 2 +
            (5 - t + 9 * c + 4 * c * c) * a * a * a * a / 24.0 +
            (61 - 58 * t + t * t + 600 * c - 330 * ECC_P2) * a * a * a * a * a * a / 720.0))
        if (lat < 0.0) northing += 10000000.0

        val e = Math.round(easting)
        val nn = Math.round(northing)
        val band = letterDesignator(lat)

        val setParm = (zone % 6).let { if (it == 0) 6 else it }
        val col = floor(e / 100000.0).toInt()
        val row = floor(nn / 100000.0).toInt() % 20
        val sq = letter100kId(col, row, setParm)

        return "$zone$band$sq" + "00000$e".takeLast(5) + "00000$nn".takeLast(5)
    }

    /**
     * MGRS → (lat, lon) of the referenced square's centre (port of proj4js `mgrs` toPoint).
     * Accepts spaces / lower case, 1..5-digit precision. A reference without the zone
     * ("DQ 1234 5678") takes [defaultZone] (e.g. "37U" from the map centre). null if invalid.
     */
    fun inverse(ref: String, defaultZone: String? = null): Pair<Double, Double>? {
        var s = ref.replace(Regex("\\s+"), "").uppercase()
        if (s.isEmpty()) return null
        if (!s[0].isDigit() && defaultZone != null) s = defaultZone + s
        val m = Regex("^(\\d{1,2})([C-HJ-NP-X])([A-HJ-NP-Z])([A-HJ-NP-V])(\\d*)$").find(s) ?: return null
        val zone = m.groupValues[1].toInt()
        if (zone !in 1..60) return null
        val band = m.groupValues[2][0]
        val digits = m.groupValues[5]
        if (digits.length % 2 != 0 || digits.length > 10) return null
        val set = (zone % 6).let { if (it == 0) 6 else it }
        val e100 = eastingFromChar(m.groupValues[3][0], set) ?: return null
        var n100 = northingFromChar(m.groupValues[4][0], set) ?: return null
        val minN = minNorthing(band) ?: return null
        while (n100 < minN) n100 += 2_000_000.0
        val half = digits.length / 2
        val acc = if (half == 0) 100_000.0 else Math.pow(10.0, (5 - half).toDouble())
        val e = (if (half == 0) 0.0 else digits.substring(0, half).toDouble() * acc) + e100 + acc / 2
        val n = (if (half == 0) 0.0 else digits.substring(half).toDouble() * acc) + n100 + acc / 2
        return utmToLatLon(e, n, zone, band)
    }

    private fun eastingFromChar(c: Char, set: Int): Double? {
        var cur = SET_ORIGIN_COLUMN_LETTERS[set - 1].code
        var v = 100_000.0
        var rewind = false
        while (cur != c.code) {
            cur++
            if (cur == LI) cur++
            if (cur == LO) cur++
            if (cur > LZ) { if (rewind) return null; cur = LA; rewind = true }
            v += 100_000.0
        }
        return v
    }

    private fun northingFromChar(c: Char, set: Int): Double? {
        if (c.code > LV) return null
        var cur = SET_ORIGIN_ROW_LETTERS[set - 1].code
        var v = 0.0
        var rewind = false
        while (cur != c.code) {
            cur++
            if (cur == LI) cur++
            if (cur == LO) cur++
            if (cur > LV) { if (rewind) return null; cur = LA; rewind = true }
            v += 100_000.0
        }
        return v
    }

    private fun minNorthing(band: Char): Double? = when (band) {
        'C' -> 1_100_000.0; 'D' -> 2_000_000.0; 'E' -> 2_800_000.0; 'F' -> 3_700_000.0
        'G' -> 4_600_000.0; 'H' -> 5_500_000.0; 'J' -> 6_400_000.0; 'K' -> 7_300_000.0
        'L' -> 8_200_000.0; 'M' -> 9_100_000.0; 'N' -> 0.0; 'P' -> 800_000.0
        'Q' -> 1_700_000.0; 'R' -> 2_600_000.0; 'S' -> 3_500_000.0; 'T' -> 4_400_000.0
        'U' -> 5_300_000.0; 'V' -> 6_200_000.0; 'W' -> 7_000_000.0; 'X' -> 7_900_000.0
        else -> null
    }

    private fun utmToLatLon(easting: Double, northing: Double, zone: Int, band: Char): Pair<Double, Double> {
        val e1 = (1 - sqrt(1 - ECC2)) / (1 + sqrt(1 - ECC2))
        val x = easting - 500_000.0
        var y = northing
        if (band < 'N') y -= 10_000_000.0
        val lonOrigin = (zone - 1) * 6 - 180 + 3
        val mm = y / K0
        val mu = mm / (A * (1 - ECC2 / 4 - 3 * ECC2 * ECC2 / 64 - 5 * ECC2 * ECC2 * ECC2 / 256))
        val phi1 = mu + (3 * e1 / 2 - 27 * e1 * e1 * e1 / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1 * e1 * e1 * e1 / 32) * sin(4 * mu) +
            (151 * e1 * e1 * e1 / 96) * sin(6 * mu)
        val n1 = A / sqrt(1 - ECC2 * sin(phi1) * sin(phi1))
        val t1 = tan(phi1) * tan(phi1)
        val c1 = ECC_P2 * cos(phi1) * cos(phi1)
        val r1 = A * (1 - ECC2) / Math.pow(1 - ECC2 * sin(phi1) * sin(phi1), 1.5)
        val d = x / (n1 * K0)
        var lat = phi1 - (n1 * tan(phi1) / r1) * (d * d / 2 -
            (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ECC_P2) * d * d * d * d / 24 +
            (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ECC_P2 - 3 * c1 * c1) * d * d * d * d * d * d / 720)
        lat /= DEG
        val lon = lonOrigin + (d - (1 + 2 * t1 + c1) * d * d * d / 6 +
            (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ECC_P2 + 24 * t1 * t1) * d * d * d * d * d / 120) / cos(phi1) / DEG
        return Pair(lat, lon)
    }

    private fun letterDesignator(lat: Double): Char = when {
        lat in 72.0..84.0 -> 'X'
        lat >= 64.0 && lat < 72.0 -> 'W'
        lat >= 56.0 && lat < 64.0 -> 'V'
        lat >= 48.0 && lat < 56.0 -> 'U'
        lat >= 40.0 && lat < 48.0 -> 'T'
        lat >= 32.0 && lat < 40.0 -> 'S'
        lat >= 24.0 && lat < 32.0 -> 'R'
        lat >= 16.0 && lat < 24.0 -> 'Q'
        lat >= 8.0 && lat < 16.0 -> 'P'
        lat >= 0.0 && lat < 8.0 -> 'N'
        lat >= -8.0 && lat < 0.0 -> 'M'
        lat >= -16.0 && lat < -8.0 -> 'L'
        lat >= -24.0 && lat < -16.0 -> 'K'
        lat >= -32.0 && lat < -24.0 -> 'J'
        lat >= -40.0 && lat < -32.0 -> 'H'
        lat >= -48.0 && lat < -40.0 -> 'G'
        lat >= -56.0 && lat < -48.0 -> 'F'
        lat >= -64.0 && lat < -56.0 -> 'E'
        lat >= -72.0 && lat < -64.0 -> 'D'
        lat >= -80.0 && lat < -72.0 -> 'C'
        else -> 'Z'
    }

    private fun letter100kId(column: Int, row: Int, parm: Int): String {
        val index = parm - 1
        val colOrigin = SET_ORIGIN_COLUMN_LETTERS[index].code
        val rowOrigin = SET_ORIGIN_ROW_LETTERS[index].code

        var colInt = colOrigin + column - 1
        var rowInt = rowOrigin + row
        var rollover = false

        if (colInt > LZ) {
            colInt = colInt - LZ + LA - 1
            rollover = true
        }
        if (colInt == LI || (colOrigin < LI && colInt > LI) || ((colInt > LI || colOrigin < LI) && rollover)) colInt++
        if (colInt == LO || (colOrigin < LO && colInt > LO) || ((colInt > LO || colOrigin < LO) && rollover)) {
            colInt++
            if (colInt == LI) colInt++
        }
        if (colInt > LZ) colInt = colInt - LZ + LA - 1

        if (rowInt > LV) {
            rowInt = rowInt - LV + LA - 1
            rollover = true
        } else {
            rollover = false
        }
        if (rowInt == LI || (rowOrigin < LI && rowInt > LI) || ((rowInt > LI || rowOrigin < LI) && rollover)) rowInt++
        if (rowInt == LO || (rowOrigin < LO && rowInt > LO) || ((rowInt > LO || rowOrigin < LO) && rollover)) {
            rowInt++
            if (rowInt == LI) rowInt++
        }
        if (rowInt > LV) rowInt = rowInt - LV + LA - 1

        return "${colInt.toChar()}${rowInt.toChar()}"
    }
}
