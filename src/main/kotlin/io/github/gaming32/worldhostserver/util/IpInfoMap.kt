package io.github.gaming32.worldhostserver.util

import io.github.gaming32.worldhostserver.LatitudeLongitude
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.InetAddress
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.time.measureTimedValue

val COMPRESSED_GEOLITE_CITY_FILES = listOf(
    URL("https://github.com/sapics/ip-location-db/raw/main/geolite2-city/geolite2-city-ipv4-num.csv.gz"),
    URL("https://github.com/sapics/ip-location-db/raw/main/geolite2-city/geolite2-city-ipv6-num.csv.gz"),
)

data class IpInfo(val country: String, val latLong: LatitudeLongitude) {
    companion object {
        private const val FIXED_11_SHIFT = 11
        private const val FIXED_11_MAGNITUDE = (1 shl FIXED_11_SHIFT).toDouble()
        private const val FIXED_11_MASK = (1 shl FIXED_11_SHIFT) - 1
        private const val COUNTRY_CHAR_BASE = 'A'.code
        private const val COUNTRY_CHAR_SHIFT = 5
        private const val COUNTRY_CHAR_MASK = (1 shl COUNTRY_CHAR_SHIFT) - 1
        private const val LAT_LONG_SHIFT = COUNTRY_CHAR_SHIFT * 2
        private const val COUNTRY_MASK = (1 shl LAT_LONG_SHIFT) - 1

        private fun fixed11ToDouble(fixed: Int) = (fixed * 360 / FIXED_11_MAGNITUDE) - 180

        private fun doubleToFixed11(double: Double) = ((double + 180) / 360 * FIXED_11_MAGNITUDE).toInt()

        private fun fixed22ToLatLong(fixed: Int): LatitudeLongitude {
            val lat = fixed11ToDouble(fixed ushr FIXED_11_SHIFT and FIXED_11_MASK)
            val long = fixed11ToDouble(fixed and FIXED_11_MASK)
            return LatitudeLongitude(lat, long)
        }

        private fun latLongToFixed22(latLong: LatitudeLongitude): Int {
            val lat = doubleToFixed11(latLong.lat)
            val long = doubleToFixed11(latLong.long)
            return lat shl FIXED_11_SHIFT or long
        }

        private fun countryCharToInt(char: Char) = char.code - COUNTRY_CHAR_BASE

        private fun countryIntToChar(char: Int) = (char + COUNTRY_CHAR_BASE).toChar()

        private fun countryToInt(country: String): Int {
            return countryCharToInt(country[0]) shl COUNTRY_CHAR_SHIFT or countryCharToInt(country[1])
        }

        private fun intToCountry(int: Int): String {
            return String(charArrayOf(
                countryIntToChar(int ushr COUNTRY_CHAR_SHIFT and COUNTRY_CHAR_MASK),
                countryIntToChar(int and COUNTRY_CHAR_MASK)
            ))
        }

        fun fromInt(int: Int): IpInfo {
            val latLong = fixed22ToLatLong(int ushr LAT_LONG_SHIFT)
            val country = intToCountry(int and COUNTRY_MASK)
            return IpInfo(country, latLong)
        }
    }

    init {
        require(country.length == 2 && country.all { it in 'A'..'Z' }) {
            "Country must be Alpha 2 country code, was $country"
        }
    }

    fun toInt(): Int {
        val latLong = latLongToFixed22(latLong)
        val country = countryToInt(country)
        return latLong shl LAT_LONG_SHIFT or country
    }
}

class IpInfoMap(
    private val fourMap: U32ToIntRangeMap,
    private val sixMap: U128ToIntRangeMap
) {
    companion object {
        private val UINT_MAX = UInt.MAX_VALUE.toLong().toBigInteger()

        fun loadFromCompressedGeoliteCityFiles(vararg urls: URL): IpInfoMap {
            val fourMap = U32ToIntRangeMap()
            val sixMap = U128ToIntRangeMap()
            for (url in urls) {
                url.openStream()
                    .let(::GZIPInputStream)
                    .let { InputStreamReader(it, Charsets.US_ASCII) }
                    .let(::BufferedReader)
                    .let { CSVParser.parse(it, CSVFormat.DEFAULT) }
                    .use { csv ->
                        for (record in csv) {
                            try {
                                if (record[7].isEmpty() || record[8].isEmpty()) {
                                    // Some IPs don't have coordinates for some reason
                                    continue
                                }
                                val startOfRange = record[0].toBigInteger()
                                val endOfRange = record[1].toBigInteger()
                                val country = record[2]
                                val lat = record[7].toDouble()
                                val long = record[8].toDouble()
                                val ipInfo = IpInfo(country, LatitudeLongitude(lat, long)).toInt()
                                if (endOfRange <= UINT_MAX) {
                                    fourMap.put(startOfRange.toInt(), endOfRange.toInt(), ipInfo)
                                } else {
                                    sixMap.put(startOfRange, endOfRange, ipInfo)
                                }
                            } catch (e: Exception) {
                                throw IllegalArgumentException(
                                    "Failed to parse line ${record.recordNumber}: ${record.values().contentToString()}",
                                    e
                                )
                            }
                        }
                    }
            }
            fourMap.trimToSize()
            sixMap.trimToSize()
            return IpInfoMap(fourMap, sixMap)
        }
    }

    operator fun get(addr: InetAddress): IpInfo? {
        val addrNum = BigInteger(1, addr.address)
        return if (addrNum <= UINT_MAX) {
            fourMap[addrNum.toInt()]
        } else {
            sixMap[addrNum]
        }?.let(IpInfo::fromInt)
    }
}

fun main() {
    fun printMemory() {
        System.gc()
        val memoryBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        println("Memory usage: ${memoryBytes / 1024.0 / 1024.0} MiB")
    }
    printMemory()
    val (result, time) = measureTimedValue {
        IpInfoMap.loadFromCompressedGeoliteCityFiles(*COMPRESSED_GEOLITE_CITY_FILES.toTypedArray())
    }
    println("Loaded data in $time")
    printMemory()
    println("Info for 26.0.0.1: ${result[InetAddress.getByName("26.0.0.1")]}")
    println("Info for 127.0.0.1: ${result[InetAddress.getByName("127.0.0.1")]}")
    println("Info for ::1: ${result[InetAddress.getByName("::1")]}")
    println("Info for 8.8.8.8: ${result[InetAddress.getByName("8.8.8.8")]}")
    println("Info for 1.1.1.1: ${result[InetAddress.getByName("1.1.1.1")]}")
}
