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

/**
 * 当前进程内正在写入的记录文件绝对路径。
 *
 * [RecordRecovery] 靠它跳过“仍在写入”的 .gpx.part，避免把活跃记录当成崩溃残留去修复。
 */
private val activeRecordingFiles = ConcurrentHashMap.newKeySet<String>()

/**
 * 一次记录的汇总，导航结束后用于总结页。
 *
 * @property exportFile 导出到 exports/ 的文件；无有效点或导出失败时为 null
 */
data class RecordSummary(
    val exportFile: File?,
    val pointCount: Int,
    val distanceMeters: Double,
    val startedAtMillis: Long?,
    val endedAtMillis: Long?,
) {
    /** 记录时长（毫秒），由首末有效点时间差得出。 */
    val durationMillis: Long
        get() = if (startedAtMillis != null && endedAtMillis != null) {
            (endedAtMillis - startedAtMillis).coerceAtLeast(0L)
        } else {
            0L
        }
}

/**
 * 边走边写 GPX 的记录器。
 *
 * 写入 recordings/xxx.gpx.part，每个点后 flush，因此进程被杀也能保留已走轨迹；
 * [finish] 时补全结束标签并复制到 exports/，再把 .part 删除。
 *
 * 写入前会过滤噪声点：精度差、时间倒流、步长过小（静止拖尾）、速度离谱（飞点）。
 */
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
        // 先登记活跃文件，即使写头失败也能在 finally 中移除。
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
        // 精度差于 50 m 的点会显著拉歪轨迹，直接丢弃。
        if (fix.accuracyMeters <= 0f || fix.accuracyMeters > 50f) return
        val previousTime = lastAcceptedTimeMillis
        if (previousTime != null && fix.timeMillis <= previousTime) return

        val previous = lastPoint
        if (previous != null) {
            val step = haversineMeters(previous.lat, previous.lon, fix.lat, fix.lon)
            // 小于 3 m 的位移视为原地抖动，不计入轨迹也不写盘。
            if (step < 3.0) return
            if (previousTime != null) {
                val seconds = (fix.timeMillis - previousTime) / 1_000.0
                // 超过 30 m/s（约 108 km/h）必为定位飞点。
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

    /**
     * 结束记录：补全 GPX 尾部、关闭文件并导出。
     *
     * 无有效点时删除临时文件并返回空汇总；重复调用返回已统计的结果，不重复写文件。
     */
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
        // 同名文件已存在时追加 _2、_3……，不覆盖历史导出。
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

/**
 * 进程死亡后的记录修复：把 .gpx.part 截到最后一个完整 trkpt 并补全尾部标签。
 *
 * 修复后用 XML 解析验证；验证或删除失败则回滚刚写出的导出文件，避免留下坏数据。
 */
object RecordRecovery {

    /** @return 成功恢复并导出的文件列表 */
    fun recover(recordingsDirectory: File, exportsDirectory: File): List<File> {
        val parts = recordingsDirectory
            .listFiles { file -> file.isFile && file.name.endsWith(".gpx.part") }
            .orEmpty()
        if (parts.isEmpty()) return emptyList()
        if (!exportsDirectory.exists() && !exportsDirectory.mkdirs()) return emptyList()

        val recovered = mutableListOf<File>()
        for (part in parts) {
            // 本进程正在写的文件不算崩溃残留。
            if (part.absolutePath in activeRecordingFiles) continue
            var target: File? = null
            runCatching {
                val text = part.readText(Charsets.UTF_8)
                val lastCompletePoint = text.lastIndexOf("</trkpt>")
                // 连一个完整点都没有，说明开表后立刻崩溃，直接清理。
                if (lastCompletePoint < 0) {
                    part.delete()
                    return@runCatching
                }
                val recoveredText = if (text.trimEnd().endsWith("</gpx>")) {
                    text
                } else {
                    // 截掉可能写了一半的 trkpt，再补全闭合标签。
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

/** 用 XML 解析器验证恢复结果，避免把半截文件当成可用导出。 */
private fun isWellFormedXml(file: File): Boolean = runCatching {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }
    factory.newDocumentBuilder().parse(file)
    true
}.getOrDefault(false)

private val fileTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.CHINA)

/**
 * 生成导出文件名（不含扩展名）：路线名去掉非法字符并截断到 40 字，再拼本地时间。
 */
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

/** 路线名来自用户文件，写入 XML 前必须转义，否则会生成非法 GPX。 */
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
