package biz.cesena.packride4.map

/**
 * MapLibre style JSON for Geofabrik Shortbread 1.0 vector tiles.
 * Pass the local tile server URL (e.g. http://localhost:8787/tiles/{z}/{x}/{y}.pbf)
 * for offline use, or an OSM raster URL for online fallback.
 */
object ShortbreadStyle {

    fun offline(tilesUrl: String = "http://localhost:8787/tiles/{z}/{x}/{y}.pbf"): String = """
    {
      "version": 8,
      "glyphs": "https://fonts.openmaptiles.org/{fontstack}/{range}.pbf",
      "sources": {
        "sb": {
          "type": "vector",
          "tiles": ["$tilesUrl"],
          "minzoom": 0,
          "maxzoom": 14
        }
      },
      "layers": [
        { "id": "bg",    "type": "background", "paint": { "background-color": "#f5f3ef" } },
        { "id": "land",  "type": "fill", "source": "sb", "source-layer": "land",
          "paint": { "fill-color": "#f5f3ef" } },
        { "id": "water", "type": "fill", "source": "sb", "source-layer": "water_polygons",
          "paint": { "fill-color": "#aad3df" } },
        { "id": "water-lines", "type": "line", "source": "sb", "source-layer": "water_lines",
          "paint": { "line-color": "#aad3df", "line-width": 1 } },
        { "id": "landuse-green", "type": "fill", "source": "sb", "source-layer": "landuse",
          "filter": ["in", ["get", "kind"], ["literal", ["park","garden","forest","nature_reserve","wood","meadow","grass"]]],
          "paint": { "fill-color": "#c8dfc8", "fill-opacity": 0.7 } },
        { "id": "boundaries", "type": "line", "source": "sb", "source-layer": "boundaries",
          "filter": ["==", ["get", "admin_level"], 2],
          "paint": { "line-color": "#9090a8", "line-width": 1.5, "line-dasharray": [4, 2] } },
        { "id": "roads-path", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["in", ["get", "kind"], ["literal", ["path","footway","cycleway","track"]]],
          "minzoom": 14,
          "paint": { "line-color": "#c8b8a0", "line-width": 1, "line-dasharray": [3, 2] } },
        { "id": "roads-minor", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["in", ["get", "kind"], ["literal", ["residential","unclassified","living_street","service"]]],
          "minzoom": 12,
          "paint": { "line-color": "#ffffff",
            "line-width": ["interpolate",["linear"],["zoom"],12,1,16,4] } },
        { "id": "roads-tertiary", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["==", ["get", "kind"], "tertiary"],
          "paint": { "line-color": "#f0ece4",
            "line-width": ["interpolate",["linear"],["zoom"],10,0.5,14,3,16,5] } },
        { "id": "roads-secondary", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["==", ["get", "kind"], "secondary"],
          "paint": { "line-color": "#f5dfa0",
            "line-width": ["interpolate",["linear"],["zoom"],9,0.5,13,3,16,6] } },
        { "id": "roads-primary", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["==", ["get", "kind"], "primary"],
          "paint": { "line-color": "#fcd68a",
            "line-width": ["interpolate",["linear"],["zoom"],8,0.5,13,4,16,8] } },
        { "id": "roads-trunk", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["==", ["get", "kind"], "trunk"],
          "paint": { "line-color": "#f9a060",
            "line-width": ["interpolate",["linear"],["zoom"],7,0.5,12,4,16,10] } },
        { "id": "roads-motorway", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["==", ["get", "kind"], "motorway"],
          "paint": { "line-color": "#e8825a",
            "line-width": ["interpolate",["linear"],["zoom"],6,0.5,11,3,16,10] } },
        { "id": "buildings", "type": "fill", "source": "sb", "source-layer": "buildings",
          "minzoom": 13,
          "paint": { "fill-color": "#d8cfc8", "fill-outline-color": "#c0b8b0" } },
        { "id": "place-city", "type": "symbol", "source": "sb", "source-layer": "place_labels",
          "filter": ["in", ["get", "kind"], ["literal", ["city","town"]]],
          "layout": {
            "text-field": ["get", "name"],
            "text-font": ["Noto Sans Regular"],
            "text-size": ["interpolate",["linear"],["zoom"],6,10,12,16],
            "text-max-width": 8
          },
          "paint": { "text-color": "#333333", "text-halo-color": "#ffffff", "text-halo-width": 1.5 } },
        { "id": "place-village", "type": "symbol", "source": "sb", "source-layer": "place_labels",
          "filter": ["in", ["get", "kind"], ["literal", ["village","suburb","hamlet"]]],
          "minzoom": 10,
          "layout": {
            "text-field": ["get", "name"],
            "text-font": ["Noto Sans Regular"],
            "text-size": ["interpolate",["linear"],["zoom"],10,10,14,13]
          },
          "paint": { "text-color": "#555555", "text-halo-color": "#ffffff", "text-halo-width": 1 } },
        { "id": "roads-labels", "type": "symbol", "source": "sb", "source-layer": "street_labels",
          "minzoom": 13,
          "layout": {
            "text-field": ["get", "name"],
            "text-font": ["Noto Sans Regular"],
            "text-size": 11,
            "symbol-placement": "line",
            "text-max-angle": 30
          },
          "paint": { "text-color": "#666666", "text-halo-color": "#ffffff", "text-halo-width": 1 } }
      ]
    }
    """.trimIndent()

    val online: String = """
    {
      "version": 8,
      "sources": {
        "osm": {
          "type": "raster",
          "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
          "tileSize": 256,
          "attribution": "© OpenStreetMap contributors"
        }
      },
      "layers": [{ "id": "osm", "type": "raster", "source": "osm" }]
    }
    """.trimIndent()
}
