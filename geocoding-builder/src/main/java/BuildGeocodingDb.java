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
 *
 * Streaming approach — writes directly to DB, minimal memory:
 * - Pass 1: nodes → write POIs/places/addresses to DB immediately.
 *           ways  → save only tag+nodeIds for interesting ways (small).
 * - Pass 2: re-read nodes to resolve needed coords, then write ways to DB.
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

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: BuildGeocodingDb <input.osm.pbf> <output.db>");
            System.exit(1);
        }

        String pbfPath = args[0];
        String dbPath = args[1];

        new File(dbPath).delete();

        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        createSchema(conn);
        PreparedStatement insertPs = conn.prepareStatement(
            "INSERT INTO places (name, type, category, lat, lon, city, street) VALUES (?, ?, ?, ?, ?, ?, ?)");
        conn.setAutoCommit(false);

        // Pending ways (tags + node IDs) — only for interesting ways
        List<WayRecord> pendingWays = new ArrayList<>();
        Set<Long> neededNodeIds = new HashSet<>();

        // ── Pass 1: stream nodes → DB, scan ways ──
        System.out.println("Pass 1: streaming nodes to DB + scanning ways...");
        int[] counts = {0, 0, 0}; // nodeCount, wayCount, insertCount

        OsmosisReader reader1 = new OsmosisReader(new File(pbfPath));
        reader1.setSink(new Sink() {
            @Override public void initialize(Map<String, Object> metaData) {}
            @Override public void complete() {}
            @Override public void close() {}

            @Override
            public void process(EntityContainer entityContainer) {
                try {
                    if (entityContainer instanceof NodeContainer nc) {
                        Node node = nc.getEntity();
                        counts[0]++;

                        Map<String, String> tags = tagsToMap(node.getTags());
                        if (!tags.isEmpty()) {
                            PlaceRecord place = extractPlace(tags, node.getLatitude(), node.getLongitude());
                            if (place != null) {
                                bindInsert(insertPs, place);
                                insertPs.addBatch();
                                counts[2]++;
                                if (counts[2] % 10000 == 0) insertPs.executeBatch();
                            }
                        }

                        if (counts[0] % 10_000_000 == 0) {
                            System.out.println("  ... " + counts[0] / 1_000_000 + "M nodes, " +
                                counts[2] + " written, mem: " + usedMb() + "MB");
                        }

                    } else if (entityContainer instanceof WayContainer wc) {
                        Way way = wc.getEntity();
                        counts[1]++;
                        Map<String, String> tags = tagsToMap(way.getTags());
                        if (tags.isEmpty()) return;

                        boolean isPoi = extractPlace(tags, 0, 0) != null;
                        String highway = tags.get("highway");
                        String name = tags.get("name");
                        boolean isStreet = highway != null && name != null && !name.isBlank();

                        if (isPoi || isStreet) {
                            List<Long> nodeIds = new ArrayList<>(way.getWayNodes().size());
                            for (WayNode wn : way.getWayNodes()) {
                                nodeIds.add(wn.getNodeId());
                                neededNodeIds.add(wn.getNodeId());
                            }
                            pendingWays.add(new WayRecord(tags, nodeIds));
                        }
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        reader1.run();
        insertPs.executeBatch();
        conn.commit();

        System.out.println("  Nodes: " + counts[0] + ", ways scanned: " + counts[1] +
            ", written to DB: " + counts[2]);
        System.out.println("  Pending ways: " + pendingWays.size() +
            ", needed node IDs: " + neededNodeIds.size());
        System.out.println("  Memory: " + usedMb() + "MB");

        // ── Pass 2: resolve node coords for pending ways ──
        System.out.println("Pass 2: resolving node coordinates...");
        HashMap<Long, Long> nodeCoords = new HashMap<>(neededNodeIds.size() * 2);

        OsmosisReader reader2 = new OsmosisReader(new File(pbfPath));
        int[] found = {0};
        reader2.setSink(new Sink() {
            @Override public void initialize(Map<String, Object> metaData) {}
            @Override public void complete() {}
            @Override public void close() {}

            @Override
            public void process(EntityContainer entityContainer) {
                if (entityContainer instanceof NodeContainer nc) {
                    Node node = nc.getEntity();
                    if (neededNodeIds.contains(node.getId())) {
                        long encoded = ((long)(int)(node.getLatitude() * 1e7) << 32) |
                                       ((int)(node.getLongitude() * 1e7) & 0xFFFFFFFFL);
                        nodeCoords.put(node.getId(), encoded);
                        found[0]++;
                    }
                }
            }
        });
        reader2.run();
        neededNodeIds.clear();

        System.out.println("  Resolved " + found[0] + " node coords. Memory: " + usedMb() + "MB");

        // ── Write ways to DB ──
        System.out.println("Writing " + pendingWays.size() + " ways to DB...");
        int wayInserts = 0;
        Set<String> seenStreets = new HashSet<>();

        for (WayRecord wr : pendingWays) {
            double[] centroid = wayCentroid(wr.nodeIds, nodeCoords);
            if (centroid == null) continue;

            PlaceRecord place = extractPlace(wr.tags, centroid[0], centroid[1]);
            if (place != null) {
                bindInsert(insertPs, place);
                insertPs.addBatch();
                wayInserts++;
            }

            String highway = wr.tags.get("highway");
            String name = wr.tags.get("name");
            if (highway != null && name != null && !name.isBlank()) {
                String city = wr.tags.getOrDefault("addr:city", "");
                String key = name.toLowerCase() + "|" + city.toLowerCase();
                if (seenStreets.add(key)) {
                    PlaceRecord street = new PlaceRecord(name, "street", null, centroid[0], centroid[1], city, "");
                    bindInsert(insertPs, street);
                    insertPs.addBatch();
                    wayInserts++;
                }
            }

            if (wayInserts % 10000 == 0 && wayInserts > 0) insertPs.executeBatch();
        }
        insertPs.executeBatch();
        conn.commit();

        pendingWays.clear();
        nodeCoords.clear();
        seenStreets.clear();

        System.out.println("  Way inserts: " + wayInserts);
        System.out.println("  Total records: " + (counts[2] + wayInserts));

        // ── Build FTS index ──
        System.out.println("Building FTS index...");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO places_fts(places_fts) VALUES('rebuild')");
        }

        insertPs.close();
        conn.close();
        System.out.println("Done. Output: " + dbPath + " (" + new File(dbPath).length() / 1024 / 1024 + " MB)");
    }

    private static long usedMb() {
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
    }

    private static void bindInsert(PreparedStatement ps, PlaceRecord p) throws SQLException {
        ps.setString(1, p.name);
        ps.setString(2, p.type);
        ps.setString(3, p.category);
        ps.setDouble(4, p.lat);
        ps.setDouble(5, p.lon);
        ps.setString(6, p.city);
        ps.setString(7, p.street);
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
            if (type != null) return new PlaceRecord(name, type, null, lat, lon, "", "");
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
            return new PlaceRecord(addrStreet + " " + housenumber, "address", null, lat, lon, city, addrStreet);
        }

        return null;
    }

    private static double[] wayCentroid(List<Long> nodeIds, HashMap<Long, Long> nodeCoords) {
        int count = 0;
        double sumLat = 0, sumLon = 0;
        for (Long id : nodeIds) {
            Long encoded = nodeCoords.get(id);
            if (encoded != null) {
                sumLat += (encoded >> 32) / 1e7;
                sumLon += ((int)(long)encoded) / 1e7;
                count++;
            }
        }
        if (count == 0) return null;
        return new double[]{sumLat / count, sumLon / count};
    }

    record PlaceRecord(String name, String type, String category, double lat, double lon, String city, String street) {}
    record WayRecord(Map<String, String> tags, List<Long> nodeIds) {}
}
