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

class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class ParsedGpx(
    val name: String,
    val segments: List<TrackSegment>,
    val waypoints: List<NamedWaypoint>,
    val warnings: List<String>,
)

object GpxParser {

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
        if (root.local != "gpx") {
            throw GpxParseException("不是有效的 GPX 文件")
        }

        val warnings = mutableListOf<String>()
        var invalidPointCount = 0
        var shortSegmentCount = 0

        val segments = mutableListOf<TrackSegment>()
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

    private fun parseTimeMillis(raw: String): Long? =
        runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()

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
    return when (attribute) {
        "lat" -> value.takeIf { it in -90.0..90.0 }
        "lon" -> value.takeIf { it in -180.0..180.0 }
        else -> null
    }
}
