package biz.cesena.packride4.map

import android.database.sqlite.SQLiteDatabase
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Lightweight HTTP server that serves vector tiles from local MBTiles (SQLite) files.
 * MapLibre style points to http://localhost:8787/tiles/{z}/{x}/{y}.pbf
 *
 * MBTiles uses TMS tile addressing (Y axis flipped vs XYZ), so we convert on the fly.
 * Tiles are already gzip-compressed PBF — we forward them with Content-Encoding: gzip.
 */
class MBTilesServer(port: Int = 8787) : NanoHTTPD(port) {

    private val databases = mutableListOf<SQLiteDatabase>()

    fun loadMaps(files: List<File>) {
        synchronized(databases) {
            databases.forEach { runCatching { it.close() } }
            databases.clear()
            files.filter { it.exists() }.forEach { file ->
                runCatching {
                    databases.add(
                        SQLiteDatabase.openDatabase(
                            file.absolutePath, null, SQLiteDatabase.OPEN_READONLY
                        )
                    )
                }
            }
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

        synchronized(databases) {
            for (db in databases) {
                val tile = queryTile(db, z, x, tmsY) ?: continue
                val decompressed = decompressIfGzip(tile)
                if (z >= 10) {
                    android.util.Log.d("MBTilesServer", "tile $z/$x/$y layers=${layerNames(decompressed)}")
                }
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/x-protobuf",
                    decompressed.inputStream(),
                    decompressed.size.toLong()
                ).apply {
                    addHeader("Access-Control-Allow-Origin", "*")
                }
            }
        }
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

    /**
     * Minimal protobuf scan to extract vector-tile layer names (Tile.layers = field 3,
     * Layer.name = field 1) for debugging which source-layer names are actually present.
     */
    private fun layerNames(data: ByteArray): List<String> {
        val names = mutableListOf<String>()
        var i = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = data[i++].toInt() and 0xFF
                result = result or ((b.toLong() and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }
        try {
            while (i < data.size) {
                val tag = readVarint()
                val field = (tag ushr 3).toInt()
                val wireType = (tag and 0x7).toInt()
                when (wireType) {
                    0 -> readVarint()
                    2 -> {
                        val len = readVarint().toInt()
                        if (field == 3) {
                            // parse Layer submessage for its name (field 1, length-delimited)
                            val end = i + len
                            var j = i
                            while (j < end) {
                                val ltag = data[j++].toInt() and 0xFF
                                val lField = ltag ushr 3
                                val lWire = ltag and 0x7
                                when (lWire) {
                                    0 -> { while (data[j++].toInt() and 0x80 != 0) {} }
                                    2 -> {
                                        var llen = 0
                                        var shift = 0
                                        while (true) {
                                            val b = data[j++].toInt() and 0xFF
                                            llen = llen or ((b and 0x7F) shl shift)
                                            if (b and 0x80 == 0) break
                                            shift += 7
                                        }
                                        if (lField == 1) names.add(String(data, j, llen, Charsets.UTF_8))
                                        j += llen
                                    }
                                    else -> {}
                                }
                            }
                        }
                        i += len
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            return names + "ERROR:${e.message}"
        }
        return names
    }

    private fun notFound() =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")

    override fun stop() {
        super.stop()
        synchronized(databases) {
            databases.forEach { runCatching { it.close() } }
            databases.clear()
        }
    }
}
