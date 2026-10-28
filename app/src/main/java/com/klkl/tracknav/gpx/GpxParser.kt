package com.klkl.tracknav.gpx

import com.klkl.tracknav.domain.NamedWaypoint
import com.klkl.tracknav.domain.RoutePoint
import com.klkl.tracknav.domain.TrackSegment
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/** GPX 解析失败；message 直接展示给用户，因此用中文描述原因。 */
class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 解析结果。
 *
 * @property name 轨迹名（extensions > trk > metadata > 文件名 依次回退）
 * @property warnings 跳过无效点/短分段等非致命问题，用于导入后提示用户
 */
data class ParsedGpx(
    val name: String,
    val segments: List<TrackSegment>,
    val waypoints: List<NamedWaypoint>,
    val warnings: List<String>,
)

/** 把 GPX 字节流解析为领域模型；容错优先，尽量不因单个坏点整体失败。 */
object GpxParser {

    /**
     * 解析 GPX。
     *
     * 优先读 trk/trkseg；若一条轨迹都没有，再退回 rte 路径；两者皆空才报错。
     *
     * @param input 调用方负责关闭的输入流
     * @param sourceFileName 用于在 GPX 未提供名称时生成回退名称
     * @throws GpxParseException 文件格式错误或没有任何可用轨迹
     */
    fun parse(input: InputStream, sourceFileName: String): ParsedGpx {
        val document = try {
            newDocumentBuilder().parse(input)
        } catch (exception: Exception) {
            throw GpxParseException(
                "GPX 解析失败：${exception.message ?: "文件格式错误"}",
                exception,
            )
        }

        val root = document.documentElement
            ?: throw GpxParseException("GPX 文件为空")
        // 用 localName 判断，忽略命名空间前缀差异。
        if (root.local != "gpx") {
            throw GpxParseException("不是有效的 GPX 文件")
        }

        val warnings = mutableListOf<String>()
        var invalidPointCount = 0
        var shortSegmentCount = 0

        val segments = mutableListOf<TrackSegment>()
        // 兼容两种写法：trk 下直接放 trkpt，或 trk 内再分 trkseg。
        for (track in root.childrenNamed("trk")) {
            val trackSegments = track.childrenNamed("trkseg")
            if (trackSegments.isEmpty()) {
                val points = parsePoints(track.childrenNamed("trkpt")) { invalidPointCount++ }
                addSegment(points, segments) { shortSegmentCount++ }
            } else {
                for (trackSegment in trackSegments) {
                    val points = parsePoints(trackSegment.childrenNamed("trkpt")) { invalidPointCount++ }
                    addSegment(points, segments) { shortSegmentCount++ }
                }
            }
        }

        if (segments.isEmpty()) {
            // 没有 trk 时兼容规划路线（rte/rtept）。
            for (route in root.childrenNamed("rte")) {
                val points = parsePoints(route.childrenNamed("rtept")) { invalidPointCount++ }
                addSegment(points, segments) { shortSegmentCount++ }
            }
        }

        if (segments.isEmpty()) {
            throw GpxParseException("GPX 中没有可用的轨迹点（至少需要两个有效点）")
        }

        var invalidWaypointCount = 0
        val waypoints = mutableListOf<NamedWaypoint>()
        // 无名标注点无法用于提示，直接忽略且不计入警告。
        for (waypoint in root.childrenNamed("wpt")) {
            val name = waypoint.textOrNull("name") ?: continue
            val lat = waypoint.coordinate("lat")
            val lon = waypoint.coordinate("lon")
            if (lat == null || lon == null) {
                invalidWaypointCount++
                continue
            }
            waypoints += NamedWaypoint(name = name, lat = lat, lon = lon)
        }

        if (invalidPointCount > 0) {
            warnings += "跳过 $invalidPointCount 个无效轨迹点"
        }
        if (shortSegmentCount > 0) {
            warnings += "跳过 $shortSegmentCount 个不足两个点的轨迹分段"
        }
        if (invalidWaypointCount > 0) {
            warnings += "跳过 $invalidWaypointCount 个坐标无效的标注点"
        }

        // 名称优先级：扩展字段 > 第一条 trk 的 name > metadata/name > 文件名。
        val extensionName = root.firstChildNamed("extensions")
            ?.firstChildNamed("name")
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val trackName = root.childrenNamed("trk")
            .firstOrNull()
            ?.textOrNull("name")
        val metadataName = root.firstChildNamed("metadata")
            ?.textOrNull("name")
        val fallbackName = sourceFileName
            .replace(Regex("(?i)\\.gpx$"), "")
            .trim()
            .ifEmpty { "未命名轨迹" }

        return ParsedGpx(
            name = listOfNotNull(extensionName, trackName, metadataName)
                .firstOrNull { it.isNotBlank() }
                ?: fallbackName,
            segments = segments,
            waypoints = waypoints,
            warnings = warnings,
        )
    }

    /** 满两个点才构成一段；只有一个点时计入“过短分段”警告。 */
    private fun addSegment(
        points: List<RoutePoint>,
        destination: MutableList<TrackSegment>,
        onShortSegment: () -> Unit,
    ) {
        if (points.size >= 2) {
            destination += TrackSegment(points)
        } else if (points.isNotEmpty()) {
            onShortSegment()
        }
    }

    private fun parsePoints(
        elements: List<Element>,
        onInvalid: () -> Unit,
    ): List<RoutePoint> {
        val points = ArrayList<RoutePoint>(elements.size)
        for (element in elements) {
            points += parsePoint(element, onInvalid) ?: continue
        }
        return points
    }

    /** 解析单个 trkpt/rtept；坐标缺失或超范围时回调 onInvalid 并返回 null。 */
    private fun parsePoint(element: Element, onInvalid: () -> Unit): RoutePoint? {
        val lat = element.coordinate("lat")
        val lon = element.coordinate("lon")
        if (lat == null || lon == null) {
            onInvalid()
            return null
        }
        return RoutePoint(
            lat = lat,
            lon = lon,
            elevationMeters = element.textOrNull("ele")?.toDoubleOrNull(),
            timeMillis = element.textOrNull("time")?.let(::parseTimeMillis),
        )
    }

    /** 依次尝试 ISO-8601 瞬时格式与带偏移量的格式，均失败则视为无时间。 */
    private fun parseTimeMillis(raw: String): Long? =
        runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()

    /**
     * 构造禁用 DTD/外部实体的 DocumentBuilder，防止 XXE 注入。
     *
     * 各厂商 XML 实现支持的特性名不完全一致，因此逐项 runCatching 忽略不支持项。
     */
    private fun newDocumentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        runCatching { factory.isXIncludeAware = false }
        runCatching { factory.isExpandEntityReferences = false }
        return factory.newDocumentBuilder()
    }
}

private val Element.local: String
    get() = localName ?: tagName.substringAfter(':')

/** 取直接子元素（忽略文本节点、注释等）。 */
private fun Element.childElements(): List<Element> {
    val result = ArrayList<Element>(childNodes.length)
    for (index in 0 until childNodes.length) {
        val node: Node = childNodes.item(index)
        if (node is Element) {
            result += node
        }
    }
    return result
}

private fun Element.childrenNamed(name: String): List<Element> =
    childElements().filter { it.local == name }

private fun Element.firstChildNamed(name: String): Element? =
    childElements().firstOrNull { it.local == name }

private fun Element.textOrNull(name: String): String? =
    firstChildNamed(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

private fun Element.coordinate(attribute: String): Double? {
    val value = getAttribute(attribute).toDoubleOrNull() ?: return null
    // 经纬度超范围的值直接丢弃，避免污染后续距离计算。
    return when (attribute) {
        "lat" -> value.takeIf { it in -90.0..90.0 }
        "lon" -> value.takeIf { it in -180.0..180.0 }
        else -> null
    }
}
