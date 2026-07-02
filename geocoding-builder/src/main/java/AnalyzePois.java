import crosby.binary.osmosis.OsmosisReader;
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.container.v0_6.NodeContainer;
import org.openstreetmap.osmosis.core.container.v0_6.WayContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.Entity;
import org.openstreetmap.osmosis.core.domain.v0_6.Tag;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;

import java.io.File;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Scans an OSM PBF file and tallies how many nodes/ways carry each value of the
 * amenity/shop/tourism/leisure tags actually used by ShortbreadStyle.kt, plus a
 * few drill-downs (tourism=information subtypes, leisure=swimming_pool access,
 * stray advertising=* tags) to help decide which POI categories are worth keeping.
 *
 * Usage: AnalyzePois <input.osm.pbf> <output-report.txt>
 */
public class AnalyzePois {

    // Every OSM "primary feature" key relevant to POIs a rider might care about.
    // (Deliberately excludes area/administrative keys like landuse, building, boundary,
    // and highway, which mostly classify roads/parcels rather than point-like POIs.)
    private static final String[] TOP_KEYS = {
        "amenity", "shop", "tourism", "leisure", "historic", "man_made", "office",
        "craft", "emergency", "healthcare", "natural", "waterway", "aeroway",
        "railway", "power", "military", "barrier", "advertising", "sport", "club"
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: AnalyzePois <input.osm.pbf> <output-report.txt>");
            System.exit(1);
        }
        String pbfPath = args[0];
        String outPath = args[1];

        Map<String, Map<String, long[]>> counts = new HashMap<>(); // key -> value -> [nodeCount, wayCount]
        for (String k : TOP_KEYS) counts.put(k, new HashMap<>());

        Map<String, long[]> informationSubtag = new HashMap<>();   // information= value -> [nodeCount, wayCount]
        Map<String, long[]> poolAccess = new HashMap<>();          // access= value (for leisure=swimming_pool) -> [n,w]
        Map<String, long[]> manMadeAdvertising = new HashMap<>();  // man_made=advertising sub-values (advertising=)-> [n,w]

        long[] totals = {0, 0}; // nodes, ways

        OsmosisReader reader = new OsmosisReader(new File(pbfPath));
        reader.setSink(new Sink() {
            @Override public void initialize(Map<String, Object> metaData) {}
            @Override public void complete() {}
            @Override public void close() {}

            @Override
            public void process(EntityContainer entityContainer) {
                Entity entity;
                boolean isWay;
                if (entityContainer instanceof NodeContainer nc) {
                    entity = nc.getEntity();
                    isWay = false;
                    totals[0]++;
                    if (totals[0] % 20_000_000 == 0) {
                        System.out.println("  ... " + totals[0] / 1_000_000 + "M nodes, " + totals[1] + " ways, mem: " + usedMb() + "MB");
                    }
                } else if (entityContainer instanceof WayContainer wc) {
                    entity = wc.getEntity();
                    isWay = true;
                    totals[1]++;
                    if (totals[1] % 5_000_000 == 0) {
                        System.out.println("  ... " + totals[0] / 1_000_000 + "M nodes, " + totals[1] + " ways, mem: " + usedMb() + "MB");
                    }
                } else {
                    return;
                }

                Collection<Tag> tags = entity.getTags();
                if (tags.isEmpty()) return;
                Map<String, String> tagMap = tagsToMap(tags);

                for (String key : TOP_KEYS) {
                    String value = tagMap.get(key);
                    if (value != null) {
                        bump(counts.get(key), value, isWay);
                    }
                }

                String tourism = tagMap.get("tourism");
                if ("information".equals(tourism)) {
                    String info = tagMap.getOrDefault("information", "(untagged)");
                    bump(informationSubtag, info, isWay);
                }

                String leisure = tagMap.get("leisure");
                if ("swimming_pool".equals(leisure)) {
                    String access = tagMap.getOrDefault("access", "(untagged)");
                    bump(poolAccess, access, isWay);
                }

                String manMade = tagMap.get("man_made");
                if ("advertising".equals(manMade)) {
                    String adType = tagMap.getOrDefault("advertising", "(untagged)");
                    bump(manMadeAdvertising, adType, isWay);
                }
            }
        });
        reader.run();

        System.out.println("Done scanning. Nodes: " + totals[0] + ", ways: " + totals[1]);

        try (PrintWriter w = new PrintWriter(outPath, "UTF-8")) {
            w.println("POI tag analysis — " + pbfPath);
            w.println("Nodes scanned: " + totals[0] + ", ways scanned: " + totals[1]);
            w.println();

            for (String key : TOP_KEYS) {
                w.println("==================== " + key + "=* ====================");
                printSorted(w, counts.get(key));
                w.println();
            }

            w.println("==================== tourism=information -> information=* subtag ====================");
            printSorted(w, informationSubtag);
            w.println();

            w.println("==================== leisure=swimming_pool -> access=* ====================");
            printSorted(w, poolAccess);
            w.println();

            w.println("==================== man_made=advertising -> advertising=* ====================");
            printSorted(w, manMadeAdvertising);
            w.println();
        }

        System.out.println("Report written to " + outPath);
    }

    private static void bump(Map<String, long[]> map, String value, boolean isWay) {
        long[] c = map.computeIfAbsent(value, k -> new long[2]);
        if (isWay) c[1]++; else c[0]++;
    }

    private static void printSorted(PrintWriter w, Map<String, long[]> map) {
        map.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, long[]>>comparingLong(e -> e.getValue()[0] + e.getValue()[1]).reversed())
            .forEach(e -> {
                long total = e.getValue()[0] + e.getValue()[1];
                w.printf("%-30s total=%-9d nodes=%-9d ways=%-9d%n", e.getKey(), total, e.getValue()[0], e.getValue()[1]);
            });
    }

    private static Map<String, String> tagsToMap(Collection<Tag> tags) {
        Map<String, String> map = new HashMap<>(tags.size());
        for (Tag t : tags) map.put(t.getKey(), t.getValue());
        return map;
    }

    private static long usedMb() {
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
    }
}
