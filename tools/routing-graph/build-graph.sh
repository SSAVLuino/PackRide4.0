#!/usr/bin/env bash
# Builds a prebuilt GraphHopper routing graph for a region, ready to be
# zipped and uploaded as a GitHub release asset (see MapDownloadManager's
# AVAILABLE_REGIONS -> routingGraphUrl).
#
# Usage:
#   ./build-graph.sh <region-id> <path-to-region.osm.pbf>
#
# Example:
#   ./build-graph.sh svizzera switzerland-latest.osm.pbf
#   ./build-graph.sh italia italy-latest.osm.pbf
#
# Output:
#   graph-<region-id>/   (directory to zip as graph-<region-id>.zip)
#
# Requirements:
#   - Java 17+
#   - graphhopper-web-<version>.jar (or the CLI jar) matching the version
#     in gradle/libs.versions.toml (graphhopper = "8.0")
#     Download from: https://github.com/graphhopper/graphhopper/releases
#
# The GRAPHHOPPER_JAR env var can point to a custom jar location.

set -euo pipefail

REGION_ID="${1:?Usage: build-graph.sh <region-id> <path-to-region.osm.pbf>}"
OSM_PBF="${2:?Usage: build-graph.sh <region-id> <path-to-region.osm.pbf>}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRAPHHOPPER_VERSION="8.0"
GRAPHHOPPER_JAR="${GRAPHHOPPER_JAR:-$SCRIPT_DIR/graphhopper-web-${GRAPHHOPPER_VERSION}.jar}"
OUTPUT_DIR="graph-${REGION_ID}"

if [[ ! -f "$GRAPHHOPPER_JAR" ]]; then
  echo "GraphHopper jar not found at $GRAPHHOPPER_JAR" >&2
  echo "Download graphhopper-web-${GRAPHHOPPER_VERSION}.jar from" \
       "https://github.com/graphhopper/graphhopper/releases" >&2
  exit 1
fi

rm -rf "$OUTPUT_DIR"

java -Ddw.graphhopper.datareader.file="$OSM_PBF" \
     -Ddw.graphhopper.graph.location="$OUTPUT_DIR" \
     -jar "$GRAPHHOPPER_JAR" \
     import "$SCRIPT_DIR/config.yml"

echo "Graph built in $OUTPUT_DIR/"
echo "Next steps:"
echo "  cd $OUTPUT_DIR && zip -r ../graph-${REGION_ID}.zip . && cd .."
echo "  Upload graph-${REGION_ID}.zip as a GitHub release asset" \
     "(e.g. routing-graph-${REGION_ID}-vN) and update routingGraphUrl" \
     "for '${REGION_ID}' in MapDownloadManager.kt"
