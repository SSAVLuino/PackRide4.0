# Prebuilt routing graphs

PackRide downloads a prebuilt GraphHopper graph per region instead of
building it on-device (see `RoutingManager.loadPrebuiltGraph`).

To (re)generate a graph for a region:

1. Download the region's OSM data extract, e.g. from
   [Geofabrik](https://download.geofabrik.de/) (`switzerland-latest.osm.pbf`,
   `italy-latest.osm.pbf`, ...).
2. Run `./build-graph.sh <region-id> <path-to-extract.osm.pbf>`.
   This produces a `graph-<region-id>/` directory using the same `car`
   profile/custom model as the app (`config.yml`, kept in sync with
   `RoutingManager.carProfile()`).
3. Zip the *contents* of `graph-<region-id>/` (not the folder itself) into
   `graph-<region-id>.zip`.
4. Upload the zip as a GitHub release asset (e.g. tag
   `routing-graph-<region-id>-v1`).
5. Update `routingGraphUrl` for that region in
   `app/src/main/kotlin/biz/cesena/packride4/map/MapDownloadManager.kt`
   (`AVAILABLE_REGIONS`).

## Updating an existing region's graph

Repeat the same steps with a fresher OSM extract, upload under a new tag
(e.g. `routing-graph-svizzera-v2`) and bump `routingGraphUrl` accordingly.
Bumping the URL/version is important so already-installed apps know to
re-download the graph (compare with the stored version, if tracked).

## Keeping config.yml in sync

If `RoutingManager.carProfile()` changes (profile name, vehicle, custom
model), update `config.yml` to match before regenerating graphs, otherwise
`gh.load()` may fail or silently use a different weighting than expected.
