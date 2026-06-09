package biz.cesena.packride4.map

import android.database.sqlite.SQLiteDatabase
import fi.iki.elonen.NanoHTTPD
import java.io.File

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
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/x-protobuf",
                    tile.inputStream(),
                    tile.size.toLong()
                ).apply {
                    addHeader("Content-Encoding", "gzip")
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
