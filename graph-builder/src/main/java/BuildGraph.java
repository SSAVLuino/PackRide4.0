import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;

/**
 * Builds a GraphHopper routing graph from an OSM PBF file.
 *
 * Usage: BuildGraph input.osm.pbf output-dir
 *
 * The output directory will contain the prebuilt graph files that can be
 * loaded by the Android app's RoutingManager.loadPrebuiltGraph().
 */
public class BuildGraph {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: BuildGraph <input.osm.pbf> <output-dir>");
            System.exit(1);
        }

        String pbfPath = args[0];
        String graphDir = args[1];

        System.out.println("Building routing graph...");
        System.out.println("  Input:  " + pbfPath);
        System.out.println("  Output: " + graphDir);

        Profile profile = new Profile("car")
            .setVehicle("car")
            .setWeighting("fastest");

        GraphHopper gh = new GraphHopper();
        gh.setOSMFile(pbfPath);
        gh.setGraphHopperLocation(graphDir);
        gh.setProfiles(profile);
        gh.setElevation(false);
        gh.setEncodedValuesString("max_speed,road_class,road_environment");
        gh.importOrLoad();

        System.out.println("Graph built successfully at: " + graphDir);
    }
}
