import java.io.ByteArrayOutputStream;
import java.io.File;
import java.sql.*;
import java.util.zip.GZIPInputStream;

/**
 * Inspects a Shortbread .mbtiles file: dumps metadata, and for a given lon/lat
 * checks which zoom levels / tiles actually contain data, and which vector
 * source-layers (raw protobuf layer-name strings) are present in each tile.
 *
 * Usage: InspectMbtiles <file.mbtiles> <lon> <lat> [minZoom] [maxZoom]
 */
public class InspectMbtiles {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: InspectMbtiles <file.mbtiles> <lon> <lat> [minZoom] [maxZoom]");
            System.exit(1);
        }
        String path = args[0];
        double lon = Double.parseDouble(args[1]);
        double lat = Double.parseDouble(args[2]);
        int minZoom = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int maxZoom = args.length > 4 ? Integer.parseInt(args[4]) : 14;

        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path);

        System.out.println("=== metadata ===");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name, value FROM metadata")) {
            while (rs.next()) {
                String v = rs.getString("value");
                if (v != null && v.length() > 200) v = v.substring(0, 200) + "...";
                System.out.println(rs.getString("name") + " = " + v);
            }
        } catch (Exception e) {
            System.out.println("(no metadata table or error: " + e.getMessage() + ")");
        }

        System.out.println();
        System.out.println("=== tiles for lon=" + lon + " lat=" + lat + " ===");
        for (int z = minZoom; z <= maxZoom; z++) {
            long n = 1L << z;
            int xtile = (int) Math.floor((lon + 180.0) / 360.0 * n);
            double latRad = Math.toRadians(lat);
            int ytile = (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
            int tmsY = (int) (n - 1 - ytile);

            PreparedStatement ps = conn.prepareStatement(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?");
            ps.setInt(1, z);
            ps.setInt(2, xtile);
            ps.setInt(3, tmsY);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                System.out.println("z=" + z + " xyz=(" + xtile + "," + ytile + ") tms_row=" + tmsY + " -> NO TILE IN DB");
                continue;
            }
            byte[] blob = rs.getBytes(1);
            byte[] raw = decompressIfGzip(blob);
            String layers = findLayerNames(raw);
            System.out.println("z=" + z + " xyz=(" + xtile + "," + ytile + ") tms_row=" + tmsY +
                " -> tile present, " + blob.length + " bytes compressed, " + raw.length + " bytes raw, layers~=[" + layers + "]");
        }
        conn.close();
    }

    private static byte[] decompressIfGzip(byte[] data) {
        if (data.length < 2 || data[0] != (byte) 0x1f || data[1] != (byte) 0x8b) return data;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            GZIPInputStream gis = new GZIPInputStream(new java.io.ByteArrayInputStream(data));
            byte[] buf = new byte[8192];
            int n;
            while ((n = gis.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return data;
        }
    }

    // Crude scan: MVT layer names appear as short printable ASCII strings preceded by
    // protobuf field/length bytes. Rather than a full protobuf parser, just look for
    // known Shortbread layer name substrings directly in the raw bytes -- vector tile
    // layer names are stored as literal UTF-8 strings in the protobuf, so a substring
    // search reliably detects presence even without decoding field structure.
    private static final String[] KNOWN_LAYERS = {
        "ocean", "water_polygons", "water_polygons_labels", "water_lines", "water_lines_labels",
        "dam_lines", "dam_polygons", "pier_lines", "pier_polygons", "boundaries", "boundary_labels",
        "place_labels", "land", "sites", "buildings", "addresses", "streets", "street_polygons",
        "street_labels", "streets_polygons_labels", "street_labels_points", "bridges", "aerialways",
        "ferries", "public_transport", "pois"
    };

    private static String findLayerNames(byte[] raw) {
        String s = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1);
        StringBuilder sb = new StringBuilder();
        for (String layer : KNOWN_LAYERS) {
            if (s.contains(layer)) {
                if (sb.length() > 0) sb.append(",");
                sb.append(layer);
            }
        }
        return sb.toString();
    }
}
