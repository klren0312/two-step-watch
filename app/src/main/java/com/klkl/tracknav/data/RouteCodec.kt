package com.klkl.tracknav.data

import com.klkl.tracknav.domain.NamedWaypoint
import com.klkl.tracknav.domain.Route
import com.klkl.tracknav.domain.RoutePoint
import com.klkl.tracknav.domain.TrackSegment
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

object RouteCodec {

    private const val MAGIC = 0x54524B31
    private const val VERSION = 1
    private const val MAX_SEGMENTS = 10_000
    private const val MAX_POINTS_PER_SEGMENT = 2_000_000
    private const val MAX_WAYPOINTS = 100_000

    fun write(route: Route, output: OutputStream) {
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeInt(VERSION)
        data.writeUTF(route.id)
        data.writeUTF(route.name)
        data.writeLong(route.importedAtMillis)
        data.writeUTF(route.sourceFileName)

        data.writeInt(route.segments.size)
        for (segment in route.segments) {
            data.writeInt(segment.points.size)
            for (point in segment.points) {
                data.writeDouble(point.lat)
                data.writeDouble(point.lon)
                data.writeBoolean(point.elevationMeters != null)
                point.elevationMeters?.let(data::writeDouble)
                data.writeBoolean(point.timeMillis != null)
                point.timeMillis?.let(data::writeLong)
            }
        }

        data.writeInt(route.waypoints.size)
        for (waypoint in route.waypoints) {
            data.writeUTF(waypoint.name)
            data.writeDouble(waypoint.lat)
            data.writeDouble(waypoint.lon)
        }
        data.flush()
    }

    fun read(input: InputStream): Route {
        val data = DataInputStream(input)
        require(data.readInt() == MAGIC) { "不是有效的路线文件" }
        require(data.readInt() == VERSION) { "不支持的路线文件版本" }

        val id = data.readUTF()
        val name = data.readUTF()
        val importedAtMillis = data.readLong()
        val sourceFileName = data.readUTF()

        val segmentCount = data.readInt()
        require(segmentCount in 1..MAX_SEGMENTS) { "路线分段数量无效" }
        val segments = ArrayList<TrackSegment>(segmentCount)
        repeat(segmentCount) {
            val pointCount = data.readInt()
            require(pointCount in 2..MAX_POINTS_PER_SEGMENT) { "轨迹点数量无效" }
            val points = ArrayList<RoutePoint>(pointCount)
            repeat(pointCount) {
                val lat = data.readDouble()
                val lon = data.readDouble()
                require(lat in -90.0..90.0 && lon in -180.0..180.0) { "路线坐标无效" }
                val elevation = if (data.readBoolean()) data.readDouble() else null
                val time = if (data.readBoolean()) data.readLong() else null
                points += RoutePoint(lat = lat, lon = lon, elevationMeters = elevation, timeMillis = time)
            }
            segments += TrackSegment(points)
        }

        val waypointCount = data.readInt()
        require(waypointCount in 0..MAX_WAYPOINTS) { "标注点数量无效" }
        val waypoints = ArrayList<NamedWaypoint>(waypointCount)
        repeat(waypointCount) {
            val waypointName = data.readUTF()
            val lat = data.readDouble()
            val lon = data.readDouble()
            require(lat in -90.0..90.0 && lon in -180.0..180.0) { "标注点坐标无效" }
            waypoints += NamedWaypoint(name = waypointName, lat = lat, lon = lon)
        }

        return Route(
            id = id,
            name = name,
            segments = segments,
            waypoints = waypoints,
            importedAtMillis = importedAtMillis,
            sourceFileName = sourceFileName,
        )
    }
}
