import de.topobyte.osm4j.core.access.OsmIterator;
import de.topobyte.osm4j.core.model.iface.*;
import de.topobyte.osm4j.core.model.util.OsmModelUtil;
import de.topobyte.osm4j.pbf.seq.PbfIterator;

import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Extracts geocoding data from an OSM PBF file and writes a SQLite FTS5 database.
 * Usage: BuildGeocodingDb <input.osm.pbf> <output.db>
 */
public class BuildGeocodingDb {

    private static final Set<String> POI_TAGS = Set.of(
        "amenity", "shop", "tourism", "leisure", "healthcare", "office"
    );

    private static final Map<String, String> AMENITY_CATEGORIES = Map.ofEntries(
        Map.entry("fuel", "fuel"),
        Map.entry("pharmacy", "pharmacy"),
        Map.entry("hospital", "hospital"),
        Map.entry("clinic", "clinic"),
        Map.entry("restaurant", "restaurant"),
        Map.entry("fast_food", "restaurant"),
        Map.entry("cafe", "cafe"),
        Map.entry("bar", "bar"),
        Map.entry("bank", "bank"),
        Map.entry("atm", "atm"),
        Map.entry("parking", "parking"),
        Map.entry("police", "police"),
        Map.entry("fire_station", "fire_station"),
        Map.entry("post_office", "post_office"),
        Map.entry("school", "school"),
        Map.entry("university", "university"),
        Map.entry("kindergarten", "school"),
        Map.entry("library", "library"),
        Map.entry("theatre", "theatre"),
        Map.entry("cinema", "cinema"),
        Map.entry("place_of_worship", "place_of_worship"),
        Map.entry("supermarket", "supermarket"),
        Map.entry("hotel", "hotel")
    );

    private static final Map<String, String> SHOP_CATEGORIES = Map.ofEntries(
        Map.entry("supermarket", "supermarket"),
        Map.entry("convenience", "convenience"),
        Map.entry("bakery", "bakery"),
        Map.entry("butcher", "butcher"),
        Map.entry("clothes", "clothes"),
        Map.entry("electronics", "electronics"),
        Map.entry("hardware", "hardware"),
        Map.entry("car_repair", "car_repair"),
        Map.entry("car", "car_dealer")
    );

    private static final Map<String, String> TOURISM_CATEGORIES = Map.of(
        "hotel", "hotel",
        "motel", "hotel",
        "hostel", "hotel",
        "guest_house", "hotel",
        "museum", "museum",
        "viewpoint", "viewpoint",
        "camp_site", "camping"
    );

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: BuildGeocodingDb <input.osm.pbf> <output.db>");
            System.exit(1);
        }

        String pbfPath = args[0];
        String dbPath = args[1];

        new File(dbPath).delete();

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            createSchema(conn);

            // First pass: collect node coordinates (needed for way centroids)
            System.out.println("Pass 1: reading nodes...");
            Map<Long, double[]> nodeCoords = new HashMap<>();
            List<PlaceRecord> places = new ArrayList<>();

            try (InputStream is = new BufferedInputStream(new FileInputStream(pbfPath), 1 << 20)) {
                OsmIterator iter = new PbfIterator(is, true);
                for (var container : iter) {
                    if (container.getType() == de.topobyte.osm4j.core.model.iface.EntityType.Node) {
                        OsmNode node = (OsmNode) container.getEntity();
                        Map<String, String> tags = OsmModelUtil.getTagsAsMap(node);

                        // Store coordinates for way resolution
                        nodeCoords.put(node.getId(), new double[]{node.getLatitude(), node.getLongitude()});

                        // Extract named places from nodes
                        PlaceRecord place = extractPlace(tags, node.getLatitude(), node.getLongitude());
                        if (place != null) places.add(place);
                    }
                }
            }
            System.out.println("  Nodes: " + nodeCoords.size() + ", places from nodes: " + places.size());

            // Second pass: ways (streets, POI buildings)
            System.out.println("Pass 2: reading ways...");
            try (InputStream is = new BufferedInputStream(new FileInputStream(pbfPath), 1 << 20)) {
                OsmIterator iter = new PbfIterator(is, true);
                for (var container : iter) {
                    if (container.getType() == de.topobyte.osm4j.core.model.iface.EntityType.Way) {
                        OsmWay way = (OsmWay) container.getEntity();
                        Map<String, String> tags = OsmModelUtil.getTagsAsMap(way);

                        double[] centroid = wayCentroid(way, nodeCoords);
                        if (centroid == null) continue;

                        PlaceRecord place = extractPlace(tags, centroid[0], centroid[1]);
                        if (place != null) places.add(place);

                        // Named streets
                        String highway = tags.get("highway");
                        String name = tags.get("name");
                        if (highway != null && name != null && !name.isBlank()) {
                            String city = tags.getOrDefault("addr:city", "");
                            places.add(new PlaceRecord(name, "street", null, centroid[0], centroid[1], city, ""));
                        }
                    }
                }
            }
            System.out.println("  Total places: " + places.size());

            // Deduplicate streets by name+city
            System.out.println("Deduplicating streets...");
            Map<String, PlaceRecord> streetDedup = new LinkedHashMap<>();
            List<PlaceRecord> finalPlaces = new ArrayList<>();
            for (PlaceRecord p : places) {
                if ("street".equals(p.type)) {
                    String key = p.name.toLowerCase() + "|" + p.city.toLowerCase();
                    streetDedup.putIfAbsent(key, p);
                } else {
                    finalPlaces.add(p);
                }
            }
            finalPlaces.addAll(streetDedup.values());
            System.out.println("  Final count: " + finalPlaces.size());

            // Insert into DB
            System.out.println("Writing database...");
            insertPlaces(conn, finalPlaces);

            // Build FTS index
            System.out.println("Building FTS index...");
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO places_fts(places_fts) VALUES('rebuild')");
            }

            System.out.println("Done. Output: " + dbPath);
        }
    }

    private static void createSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=OFF");
            stmt.execute("PRAGMA synchronous=OFF");
            stmt.execute("""
                CREATE TABLE places (
                    id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    type TEXT,
                    category TEXT,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    city TEXT,
                    street TEXT
                )
            """);
            stmt.execute("""
                CREATE VIRTUAL TABLE places_fts USING fts5(
                    name, city, street,
                    content=places, content_rowid=id
                )
            """);
        }
    }

    private static PlaceRecord extractPlace(Map<String, String> tags, double lat, double lon) {
        String name = tags.get("name");

        // Place nodes: city, town, village, hamlet
        String placeType = tags.get("place");
        if (placeType != null && name != null && !name.isBlank()) {
            String type = switch (placeType) {
                case "city", "town" -> "city";
                case "village" -> "village";
                case "hamlet", "suburb", "neighbourhood" -> "hamlet";
                default -> null;
            };
            if (type != null) {
                return new PlaceRecord(name, type, null, lat, lon, "", "");
            }
        }

        // POI
        if (name != null && !name.isBlank()) {
            String category = null;

            String amenity = tags.get("amenity");
            if (amenity != null) category = AMENITY_CATEGORIES.get(amenity);

            if (category == null) {
                String shop = tags.get("shop");
                if (shop != null) category = SHOP_CATEGORIES.getOrDefault(shop, "shop");
            }

            if (category == null) {
                String tourism = tags.get("tourism");
                if (tourism != null) category = TOURISM_CATEGORIES.get(tourism);
            }

            if (category == null && tags.get("healthcare") != null) category = "healthcare";

            if (category != null) {
                String city = tags.getOrDefault("addr:city", "");
                String street = tags.getOrDefault("addr:street", "");
                return new PlaceRecord(name, "poi", category, lat, lon, city, street);
            }
        }

        // Address nodes (housenumber)
        String housenumber = tags.get("addr:housenumber");
        String addrStreet = tags.get("addr:street");
        if (housenumber != null && addrStreet != null) {
            String city = tags.getOrDefault("addr:city", "");
            String fullName = addrStreet + " " + housenumber;
            return new PlaceRecord(fullName, "address", null, lat, lon, city, addrStreet);
        }

        return null;
    }

    private static double[] wayCentroid(OsmWay way, Map<Long, double[]> nodeCoords) {
        int count = 0;
        double sumLat = 0, sumLon = 0;
        for (int i = 0; i < way.getNumberOfNodes(); i++) {
            double[] c = nodeCoords.get(way.getNodeId(i));
            if (c != null) {
                sumLat += c[0];
                sumLon += c[1];
                count++;
            }
        }
        if (count == 0) return null;
        return new double[]{sumLat / count, sumLon / count};
    }

    private static void insertPlaces(Connection conn, List<PlaceRecord> places) throws SQLException {
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO places (name, type, category, lat, lon, city, street) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            int batch = 0;
            for (PlaceRecord p : places) {
                ps.setString(1, p.name);
                ps.setString(2, p.type);
                ps.setString(3, p.category);
                ps.setDouble(4, p.lat);
                ps.setDouble(5, p.lon);
                ps.setString(6, p.city);
                ps.setString(7, p.street);
                ps.addBatch();
                if (++batch % 10000 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    record PlaceRecord(String name, String type, String category, double lat, double lon, String city, String street) {}
}
