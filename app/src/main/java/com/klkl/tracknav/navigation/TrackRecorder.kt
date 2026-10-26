package com.klkl.tracknav.navigation

import com.klkl.tracknav.domain.RoutePoint
import com.klkl.tracknav.domain.haversineMeters
import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

private val activeRecordingFiles = ConcurrentHashMap.newKeySet<String>()

data class RecordSummary(
    val exportFile: File?,
    val pointCount: Int,
    val distanceMeters: Double,
    val startedAtMillis: Long?,
    val endedAtMillis: Long?,
) {
    val durationMillis: Long
        get() = if (startedAtMillis != null && endedAtMillis != null) {
            (endedAtMillis - startedAtMillis).coerceAtLeast(0L)
        } else {
            0L
        }
}

class TrackRecorder(
    private val recordingsDirectory: File,
    private val exportsDirectory: File,
    private val routeName: String,
    sessionStartMillis: Long,
) {

    private val fileNameBase = buildFileName(routeName, sessionStartMillis)
    private val recordingFile = File(recordingsDirectory, "$fileNameBase.gpx.part")
    private var writer: BufferedWriter? = null
    private var finished = false

    private var lastPoint: RoutePoint? = null
    private var lastAcceptedTimeMillis: Long? = null
    private var pointCount = 0
    private var distanceMeters = 0.0
    private var startedAtMillis: Long? = null
    private var endedAtMillis: Long? = null

    init {
        activeRecordingFiles += recordingFile.absolutePath
        try {
            if (!recordingsDirectory.exists() && !recordingsDirectory.mkdirs()) {
                error("无法创建记录目录")
            }
            writer = recordingFile.bufferedWriter(Charsets.UTF_8).apply {
                write(
                    """<?xml version="1.0" encoding="UTF-8"?>
                        |<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="TrackNav">
                        |<metadata><name>${escapeXml(routeName)} 记录</name><time>${isoTime(sessionStartMillis)}</time></metadata>
                        |<trk><name>${escapeXml(routeName)}</name><trkseg>
                    """.trimMargin(),
                )
                flush()
            }
        } catch (error: Throwable) {
            activeRecordingFiles -= recordingFile.absolutePath
            throw error
        }
    }

    fun addFix(fix: LocationFix) {
        if (finished || writer == null) return
        if (fix.accuracyMeters <= 0f || fix.accuracyMeters > 50f) return
        val previousTime = lastAcceptedTimeMillis
        if (previousTime != null && fix.timeMillis <= previousTime) return

        val previous = lastPoint
        if (previous != null) {
            val step = haversineMeters(previous.lat, previous.lon, fix.lat, fix.lon)
            if (step < 3.0) return
            if (previousTime != null) {
                val seconds = (fix.timeMillis - previousTime) / 1_000.0
                if (step / seconds > 30.0) return
            }
            distanceMeters += step
        }

        val point = RoutePoint(fix.lat, fix.lon, fix.elevationMeters, fix.timeMillis)
        lastPoint = point
        lastAcceptedTimeMillis = fix.timeMillis
        if (startedAtMillis == null) startedAtMillis = fix.timeMillis
        endedAtMillis = fix.timeMillis
        pointCount++

        writer?.apply {
            write("""<trkpt lat="${fix.lat}" lon="${fix.lon}">""")
            fix.elevationMeters?.let { write("<ele>$it</ele>") }
            write("<time>${isoTime(fix.timeMillis)}</time>")
            write("</trkpt>\n")
            flush()
        }
    }

    fun finish(): RecordSummary {
        if (finished) {
            return RecordSummary(
                exportFile = null,
                pointCount = pointCount,
                distanceMeters = distanceMeters,
                startedAtMillis = startedAtMillis,
                endedAtMillis = endedAtMillis,
            )
        }
        finished = true
        writer?.apply {
            write("</trkseg></trk></gpx>\n")
            flush()
            close()
        }
        writer = null
        activeRecordingFiles -= recordingFile.absolutePath

        if (pointCount == 0) {
            recordingFile.delete()
            return RecordSummary(null, 0, 0.0, null, null)
        }

        if (!exportsDirectory.exists() && !exportsDirectory.mkdirs()) {
            return RecordSummary(null, pointCount, distanceMeters, startedAtMillis, endedAtMillis)
        }
        var target = File(exportsDirectory, "$fileNameBase.gpx")
        var suffix = 2
        while (target.exists()) {
            target = File(exportsDirectory, "${fileNameBase}_$suffix.gpx")
            suffix++
        }
        val exported = runCatching {
            recordingFile.copyTo(target, overwrite = false)
            recordingFile.delete()
            target
        }.getOrNull()

        return RecordSummary(
            exportFile = exported,
            pointCount = pointCount,
            distanceMeters = distanceMeters,
            startedAtMillis = startedAtMillis,
            endedAtMillis = endedAtMillis,
        )
    }
}

object RecordRecovery {

    fun recover(recordingsDirectory: File, exportsDirectory: File): List<File> {
        val parts = recordingsDirectory
            .listFiles { file -> file.isFile && file.name.endsWith(".gpx.part") }
            .orEmpty()
        if (parts.isEmpty()) return emptyList()
        if (!exportsDirectory.exists() && !exportsDirectory.mkdirs()) return emptyList()

        val recovered = mutableListOf<File>()
        for (part in parts) {
            if (part.absolutePath in activeRecordingFiles) continue
            var target: File? = null
            runCatching {
                val text = part.readText(Charsets.UTF_8)
                val lastCompletePoint = text.lastIndexOf("</trkpt>")
                if (lastCompletePoint < 0) {
                    part.delete()
                    return@runCatching
                }
                val recoveredText = if (text.trimEnd().endsWith("</gpx>")) {
                    text
                } else {
                    text.substring(0, lastCompletePoint + "</trkpt>".length) +
                        "</trkseg></trk></gpx>\n"
                }
                val base = part.name.removeSuffix(".part")
                var output = File(exportsDirectory, base)
                var suffix = 2
                while (output.exists()) {
                    output = File(exportsDirectory, "${base.removeSuffix(".gpx")}_$suffix.gpx")
                    suffix++
                }
                target = output
                output.writeText(recoveredText, Charsets.UTF_8)
                if (!isWellFormedXml(output) || !part.delete()) {
                    output.delete()
                    return@runCatching
                }
                recovered += output
            }.onFailure {
                target?.delete()
            }
        }
        return recovered
    }
}

private fun isWellFormedXml(file: File): Boolean = runCatching {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }
    factory.newDocumentBuilder().parse(file)
    true
}.getOrDefault(false)

private val fileTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.CHINA)

private fun buildFileName(routeName: String, startMillis: Long): String {
    val safeName = routeName
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .take(40)
        .ifBlank { "轨迹" }
    val timestamp = Instant.ofEpochMilli(startMillis)
        .atZone(ZoneId.systemDefault())
        .format(fileTimestampFormatter)
    return "${safeName}_$timestamp"
}

private fun isoTime(millis: Long): String = Instant.ofEpochMilli(millis).toString()

private fun escapeXml(value: String): String = buildString(value.length) {
    for (character in value) {
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(character)
        }
    }
}
