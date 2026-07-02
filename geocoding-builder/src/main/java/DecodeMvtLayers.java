import java.io.ByteArrayOutputStream;
import java.io.File;
import java.sql.*;
import java.util.zip.GZIPInputStream;

/**
 * Minimal MVT (Mapbox/MapLibre Vector Tile) protobuf decoder -- just enough to
 * report, for one tile, each layer's name, feature count, and extent. Written
 * by hand (no protobuf lib dependency) using the well-known MVT wire format:
 *   Tile    { repeated Layer layers = 3; }
 *   Layer   { string name = 1; repeated Feature features = 2; uint32 extent = 5; }
 *   Feature { uint64 id = 1; repeated uint32 tags = 2; GeomType type = 3; repeated uint32 geometry = 4; }
 *
 * Usage: DecodeMvtLayers <file.mbtiles> <lon> <lat> <zoom>
 */
public class DecodeMvtLayers {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: DecodeMvtLayers <file.mbtiles> <lon> <lat> <zoom>");
            System.exit(1);
        }
        String path = args[0];
        double lon = Double.parseDouble(args[1]);
        double lat = Double.parseDouble(args[2]);
        int z = Integer.parseInt(args[3]);

        long n = 1L << z;
        int xtile = (int) Math.floor((lon + 180.0) / 360.0 * n);
        double latRad = Math.toRadians(lat);
        int ytile = (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
        int tmsY = (int) (n - 1 - ytile);

        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        PreparedStatement ps = conn.prepareStatement(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?");
        ps.setInt(1, z);
        ps.setInt(2, xtile);
        ps.setInt(3, tmsY);
        ResultSet rs = ps.executeQuery();
        if (!rs.next()) {
            System.out.println("z=" + z + " xyz=(" + xtile + "," + ytile + ") -> NO TILE IN DB");
            return;
        }
        byte[] raw = decompressIfGzip(rs.getBytes(1));
        System.out.println("z=" + z + " xyz=(" + xtile + "," + ytile + ") tile size=" + raw.length + " bytes");
        System.out.println();

        Reader r = new Reader(raw);
        while (r.pos < raw.length) {
            long tag = r.readVarint();
            int field = (int) (tag >>> 3);
            int wireType = (int) (tag & 7);
            if (field == 3 && wireType == 2) {
                int len = (int) r.readVarint();
                int end = r.pos + len;
                decodeLayer(r, end);
            } else {
                r.skip(wireType);
            }
        }
        conn.close();
    }

    private static void decodeLayer(Reader r, int end) {
        String name = null;
        int featureCount = 0;
        int extent = -1;
        while (r.pos < end) {
            long tag = r.readVarint();
            int field = (int) (tag >>> 3);
            int wireType = (int) (tag & 7);
            if (field == 1 && wireType == 2) {
                int len = (int) r.readVarint();
                name = new String(r.data, r.pos, len, java.nio.charset.StandardCharsets.UTF_8);
                r.pos += len;
            } else if (field == 2 && wireType == 2) {
                int len = (int) r.readVarint();
                featureCount++;
                r.pos += len; // skip feature body, we only need the count
            } else if (field == 5 && wireType == 0) {
                extent = (int) r.readVarint();
            } else {
                r.skip(wireType);
            }
        }
        System.out.println("layer \"" + name + "\": features=" + featureCount + " extent=" + extent);
    }

    private static byte[] decompressIfGzip(byte[] data) throws Exception {
        if (data.length < 2 || data[0] != (byte) 0x1f || data[1] != (byte) 0x8b) return data;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPInputStream gis = new GZIPInputStream(new java.io.ByteArrayInputStream(data));
        byte[] buf = new byte[8192];
        int n;
        while ((n = gis.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static class Reader {
        final byte[] data;
        int pos = 0;
        Reader(byte[] data) { this.data = data; }

        long readVarint() {
            long result = 0;
            int shift = 0;
            while (true) {
                byte b = data[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return result;
        }

        void skip(int wireType) {
            switch (wireType) {
                case 0: readVarint(); break; // varint
                case 1: pos += 8; break; // 64-bit
                case 2: int len = (int) readVarint(); pos += len; break; // length-delimited
                case 5: pos += 4; break; // 32-bit
                default: throw new IllegalStateException("Unknown wire type " + wireType);
            }
        }
    }
}
