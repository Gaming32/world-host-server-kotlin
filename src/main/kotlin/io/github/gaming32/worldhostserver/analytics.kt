package io.github.gaming32.worldhostserver

import io.github.oshai.KotlinLogging
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

@OptIn(DelicateCoroutinesApi::class)
fun WorldHostServer.runAnalytics() = GlobalScope.launch {
    if (!config.analyticsTime.isPositive()) {
        logger.info("Analytics disabled by request.")
        return@launch
    }
    logger.info("Starting analytics system to update every ${config.analyticsTime}")
    val file = File("analytics.csv")
    while (true) {
        delay(config.analyticsTime)
        if (file.length() <= 0) {
            logger.info("Creating new analytics.csv")
            file.writeText("timestamp,total,countries\n")
        }
        logger.info("Updating analytics.csv")
        val timestamp = OffsetDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)
        var total = 0
        val byCountry = mutableMapOf<String, Int>()
        whConnections.forEach { connection ->
            connection.country?.let { byCountry[it.code] = (byCountry[it.code] ?: 0) + 1 }
            total++
        }
        file.appendText("$timestamp,$total,${byCountry.entries.joinToString(";") { "${it.key}:${it.value}" }}\n")
    }
}
