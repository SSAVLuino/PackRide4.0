import crosby.binary.osmosis.OsmosisReader;
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.container.v0_6.NodeContainer;
import org.openstreetmap.osmosis.core.container.v0_6.WayContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.Node;
import org.openstreetmap.osmosis.core.domain.v0_6.Tag;
import org.openstreetmap.osmosis.core.domain.v0_6.Way;
import org.openstreetmap.osmosis.core.domain.v0_6.WayNode;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;

import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Extracts geocoding data from an OSM PBF file and writes a SQLite FTS5 database.
 * Single-pass approach: processes nodes directly (no coord storage for all nodes).
 * For ways, stores first referenced node coord in a compact long->lat/lon map.
 *
 * Usage: BuildGeocodingDb input.osm.pbf output.db
 */
public class BuildGeocodingDb {

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

    // Compact storage: node ID -> encoded lat/lon (both as int, packed into long)
    private static final HashMap<Long, Long> nodePositions = new HashMap<>();
    private static final List<PlaceRecord> places = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: BuildGeocodingDb <input.osm.pbf> <output.db>");
            System.exit(1);
        }

        String pbfPath = args[0];
        String dbPath = args[1];

        new File(dbPath).delete();

        // Single pass: nodes are always before ways in PBF format
        System.out.println("Reading PBF (single pass)...");
        OsmosisReader reader = new OsmosisReader(new File(pbfPath));
        reader.setSink(new Sink() {
            int nodeCount = 0;
            int wayCount = 0;

            @Override public void initialize(Map<String, Object> metaData) {}
            @Override public void complete() {
                System.out.println("  Nodes processed: " + nodeCount + " (stored coords: " + nodePositions.size() + ")");
                System.out.println("  Ways processed: " + wayCount);
                System.out.println("  Places found: " + places.size());
            }
            @Override public void close() {}

            @Override
            public void process(EntityContainer entityContainer) {
                if (entityContainer instanceof NodeContainer nc) {
                    Node node = nc.getEntity();
                    nodeCount++;

                    Map<String, String> tags = tagsToMap(node.getTags());

                    // Only store position if node has relevant tags OR we store all for way lookup
                    // To save memory, we store coords compactly for ALL nodes (needed for way centroids)
                    // Using int encoding: lat*1e7 and lon*1e7 packed into one long
                    long encoded = encodeLatLon(node.getLatitude(), node.getLongitude());
                    nodePositions.put(node.getId(), encoded);

                    if (!tags.isEmpty()) {
                        PlaceRecord place = extractPlace(tags, node.getLatitude(), node.getLongitude());
                        if (place != null) places.add(place);
                    }

                    // Periodically log progress
                    if (nodeCount % 10_000_000 == 0) {
                        System.out.println("  ... " + nodeCount / 1_000_000 + "M nodes, " + places.size() + " places, mem: " +
                            (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB");
                    }

                } else if (entityContainer instanceof WayContainer wc) {
                    Way way = wc.getEntity();
                    wayCount++;
                    Map<String, String> tags = tagsToMap(way.getTags());
                    if (tags.isEmpty()) return;

                    double[] centroid = wayCentroid(way.getWayNodes());
                    if (centroid == null) return;

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
        });
        reader.run();

        // Free memory before DB writing
        nodePositions.clear();

        // Deduplicate streets
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
        places.clear();
        System.out.println("  Final count: " + finalPlaces.size());

        // Write DB
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            createSchema(conn);
            System.out.println("Writing database...");
            insertPlaces(conn, finalPlaces);
            System.out.println("Building FTS index...");
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO places_fts(places_fts) VALUES('rebuild')");
            }
        }
        System.out.println("Done. Output: " + dbPath + " (" + new File(dbPath).length() / 1024 / 1024 + " MB)");
    }

    private static long encodeLatLon(double lat, double lon) {
        int iLat = (int) (lat * 1e7);
        int iLon = (int) (lon * 1e7);
        return ((long) iLat << 32) | (iLon & 0xFFFFFFFFL);
    }

    private static double decodeLat(long encoded) {
        return (encoded >> 32) / 1e7;
    }

    private static double decodeLon(long encoded) {
        return ((int) encoded) / 1e7;
    }

    private static Map<String, String> tagsToMap(Collection<Tag> tags) {
        if (tags.isEmpty()) return Collections.emptyMap();
        Map<String, String> map = new HashMap<>(tags.size());
        for (Tag t : tags) map.put(t.getKey(), t.getValue());
        return map;
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

        String housenumber = tags.get("addr:housenumber");
        String addrStreet = tags.get("addr:street");
        if (housenumber != null && addrStreet != null) {
            String city = tags.getOrDefault("addr:city", "");
            String fullName = addrStreet + " " + housenumber;
            return new PlaceRecord(fullName, "address", null, lat, lon, city, addrStreet);
        }

        return null;
    }

    private static double[] wayCentroid(List<WayNode> wayNodes) {
        int count = 0;
        double sumLat = 0, sumLon = 0;
        for (WayNode wn : wayNodes) {
            Long encoded = nodePositions.get(wn.getNodeId());
            if (encoded != null) {
                sumLat += decodeLat(encoded);
                sumLon += decodeLon(encoded);
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
