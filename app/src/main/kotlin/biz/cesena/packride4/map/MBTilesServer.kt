package biz.cesena.packride4.map

import android.database.sqlite.SQLiteDatabase
import biz.cesena.packride4.debug.DebugLog
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/**
 * Lightweight HTTP server that serves vector tiles from local MBTiles (SQLite) files.
 * MapLibre style points to http://localhost:8787/tiles/{z}/{x}/{y}.pbf
 *
 * MBTiles uses TMS tile addressing (Y axis flipped vs XYZ), so we convert on the fly.
 * Tiles are already gzip-compressed PBF — we forward them with Content-Encoding: gzip.
 */
class MBTilesServer(port: Int = 8787) : NanoHTTPD(port) {

    // A single SQLiteDatabase handle serializes all queries through one native connection
    // (Android only pools connections in WAL journal mode, which a read-only distributed
    // file isn't opened with here). MapLibre issues several tile requests concurrently, so
    // one connection per file becomes a queue -- under a burst (fast pan/zoom) that's still
    // enough delay for the client to cancel before its turn, even after removing the
    // app-level lock. Opening several independent read-only connections to the same file
    // lets genuinely concurrent reads happen (SQLite supports multiple readers with no
    // writer), so we round-robin requests across a small per-file pool instead.
    private val CONNECTIONS_PER_FILE = 4

    private data class MapDb(val file: File, val connections: List<SQLiteDatabase>)

    private val maps = mutableListOf<MapDb>()
    private val nextConn = AtomicInteger(0)

    // Small in-memory cache of decompressed tile bytes, keyed by "z/x/y". MapLibre
    // routinely re-requests the same tile multiple times during a single pan/zoom
    // gesture (prefetch at a coarser zoom, then the real tile, then again after a
    // camera-idle refresh) -- serving repeats straight from memory means only the
    // first request per tile ever touches SQLite/gzip, shrinking the window where a
    // slow response races a client-side cancellation. Cache is safe for the whole
    // process lifetime of a loaded file set since mbtiles content never changes at
    // runtime; cleared on loadMaps() since the file set itself can change.
    private val MAX_CACHE_ENTRIES = 1000
    private val tileCache: MutableMap<String, ByteArray> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    )

    fun loadMaps(files: List<File>) {
        DebugLog.log("loadMaps: ${files.map { "${it.name} exists=${it.exists()} size=${it.length()}" }}")
        synchronized(maps) {
            maps.forEach { m -> m.connections.forEach { runCatching { it.close() } } }
            maps.clear()
            tileCache.clear()
            files.filter { it.exists() }.forEach { file ->
                runCatching {
                    val connections = (1..CONNECTIONS_PER_FILE).map {
                        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    }
                    maps.add(MapDb(file, connections))
                }.onFailure { DebugLog.log("loadMaps: failed to open ${file.name}: ${it.message}") }
            }
            DebugLog.log("loadMaps: ${maps.size} file(s), $CONNECTIONS_PER_FILE connection(s) each")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        // Expected path: /tiles/{z}/{x}/{y}.pbf
        val parts = session.uri.removePrefix("/").split("/")
        if (parts.size != 4 || parts[0] != "tiles") return notFound()

        val z = parts[1].toIntOrNull() ?: return notFound()
        val x = parts[2].toIntOrNull() ?: return notFound()
        val y = parts[3].removeSuffix(".pbf").toIntOrNull() ?: return notFound()
        val tmsY = (1 shl z) - 1 - y
        val cacheKey = "$z/$x/$y"

        val cached = tileCache[cacheKey]
        if (cached != null) {
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/x-protobuf",
                cached.inputStream(),
                cached.size.toLong()
            ).apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }
        }

        // Only the list reference needs the lock (loadMaps()/stop() mutate it); the
        // actual SQLite queries and response writing must happen outside it, otherwise
        // every concurrent tile request from MapLibre's multiple HTTP worker threads
        // gets serialized behind a single global lock.
        val snapshot = synchronized(maps) { maps.toList() }
        if (snapshot.isEmpty()) {
            DebugLog.log("tile $z/$x/$y -> 404 (no mbtiles loaded)")
            return notFound()
        }
        val connIndex = nextConn.getAndIncrement()
        for (m in snapshot) {
            val conn = m.connections[Math.floorMod(connIndex, m.connections.size)]
            val tile = queryTile(conn, z, x, tmsY) ?: continue
            val decompressed = decompressIfGzip(tile)
            tileCache[cacheKey] = decompressed
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/x-protobuf",
                decompressed.inputStream(),
                decompressed.size.toLong()
            ).apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }
        }
        DebugLog.log("tile $z/$x/$y -> 404 (not in any loaded mbtiles)")
        return notFound()
    }

    private fun queryTile(db: SQLiteDatabase, z: Int, x: Int, y: Int): ByteArray? =
        runCatching {
            db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                arrayOf(z.toString(), x.toString(), y.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getBlob(0) else null
            }
        }.getOrNull()

    private fun decompressIfGzip(data: ByteArray): ByteArray {
        // gzip magic number: 0x1f 0x8b
        if (data.size < 2 || data[0] != 0x1f.toByte() || data[1] != 0x8b.toByte()) return data
        return runCatching {
            GZIPInputStream(data.inputStream()).use { it.readBytes() }
        }.getOrDefault(data)
    }


    private fun notFound() =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")

    override fun stop() {
        super.stop()
        synchronized(maps) {
            maps.forEach { m -> m.connections.forEach { runCatching { it.close() } } }
            maps.clear()
        }
    }
}
