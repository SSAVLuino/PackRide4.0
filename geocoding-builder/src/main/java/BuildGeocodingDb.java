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

    // Collected data
    private static final Map<Long, double[]> nodeCoords = new HashMap<>();
    private static final List<PlaceRecord> places = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: BuildGeocodingDb <input.osm.pbf> <output.db>");
            System.exit(1);
        }

        String pbfPath = args[0];
        String dbPath = args[1];

        new File(dbPath).delete();

        // Pass 1: read all nodes (coords + places)
        System.out.println("Pass 1: reading nodes...");
        OsmosisReader reader1 = new OsmosisReader(new File(pbfPath));
        reader1.setSink(new Sink() {
            @Override public void initialize(Map<String, Object> metaData) {}
            @Override public void complete() {}
            @Override public void close() {}

            @Override
            public void process(EntityContainer entityContainer) {
                if (entityContainer instanceof NodeContainer nc) {
                    Node node = nc.getEntity();
                    double lat = node.getLatitude();
                    double lon = node.getLongitude();
                    nodeCoords.put(node.getId(), new double[]{lat, lon});

                    Map<String, String> tags = tagsToMap(node.getTags());
                    PlaceRecord place = extractPlace(tags, lat, lon);
                    if (place != null) places.add(place);
                }
            }
        });
        reader1.run();
        System.out.println("  Nodes: " + nodeCoords.size() + ", places from nodes: " + places.size());

        // Pass 2: read ways (streets + POI buildings)
        System.out.println("Pass 2: reading ways...");
        OsmosisReader reader2 = new OsmosisReader(new BufferedInputStream(new FileInputStream(pbfPath), 1 << 20));
        reader2.setSink(new Sink() {
            @Override public void initialize(Map<String, Object> metaData) {}
            @Override public void complete() {}
            @Override public void close() {}

            @Override
            public void process(EntityContainer entityContainer) {
                if (entityContainer instanceof WayContainer wc) {
                    Way way = wc.getEntity();
                    Map<String, String> tags = tagsToMap(way.getTags());

                    double[] centroid = wayCentroid(way.getWayNodes());
                    if (centroid == null) return;

                    PlaceRecord place = extractPlace(tags, centroid[0], centroid[1]);
                    if (place != null) places.add(place);

                    String highway = tags.get("highway");
                    String name = tags.get("name");
                    if (highway != null && name != null && !name.isBlank()) {
                        String city = tags.getOrDefault("addr:city", "");
                        places.add(new PlaceRecord(name, "street", null, centroid[0], centroid[1], city, ""));
                    }
                }
            }
        });
        reader2.run();
        System.out.println("  Total places: " + places.size());

        // Free node coords memory
        nodeCoords.clear();

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

    private static Map<String, String> tagsToMap(Collection<Tag> tags) {
        Map<String, String> map = new HashMap<>();
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
            double[] c = nodeCoords.get(wn.getNodeId());
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
