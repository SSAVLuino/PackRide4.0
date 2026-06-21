package biz.cesena.packride4.map

/**
 * MapLibre style JSON for Geofabrik Shortbread 1.0 vector tiles.
 * Pass the local tile server URL (e.g. http://localhost:8787/tiles/{z}/{x}/{y}.pbf)
 * for offline use, or an OSM raster URL for online fallback.
 */
object ShortbreadStyle {

    // Glyphs (font PBFs) for fully offline rendering. The style only references the
    // "Noto Sans Regular" fontstack (see "text-font" entries below), so only that
    // fontstack needs to be bundled.
    //
    // MANUAL STEP REQUIRED: place the glyph PBF range files at:
    //   app/src/main/assets/glyphs/Noto Sans Regular/{range}.pbf
    // (ranges are 256-codepoint blocks, e.g. "0-255.pbf", "256-511.pbf", ...).
    // These can be generated with tools such as `fontnik`/`build-glyphs` or extracted
    // from any existing Shortbread/OpenMapTiles font set. They are served locally via
    // MapLibre's "asset://" URI scheme, which is bundled with the APK (no network call).
    private const val GLYPHS_URL = "asset://glyphs/{fontstack}/{range}.pbf"

    private const val SPRITE_URL = "asset://sprites/osm-liberty"

    fun offline(tilesUrl: String = "http://localhost:8787/tiles/{z}/{x}/{y}.pbf"): String = """
    {
      "version": 8,
      "glyphs": "$GLYPHS_URL",
      "sprite": "$SPRITE_URL",
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
        { "id": "landuse-forest", "type": "fill", "source": "sb", "source-layer": "land",
          "filter": ["in", ["get", "kind"], ["literal", ["forest","wood"]]],
          "paint": { "fill-color": "#add19e", "fill-opacity": 0.6 } },
        { "id": "landuse-green", "type": "fill", "source": "sb", "source-layer": "land",
          "filter": ["in", ["get", "kind"], ["literal", ["park","garden","nature_reserve","meadow","grass","village_green","recreation_ground","golf_course"]]],
          "paint": { "fill-color": "#c8dfc8", "fill-opacity": 0.7 } },
        { "id": "landuse-farm", "type": "fill", "source": "sb", "source-layer": "land",
          "filter": ["in", ["get", "kind"], ["literal", ["farmland","orchard","vineyard","allotments"]]],
          "paint": { "fill-color": "#dde6c6", "fill-opacity": 0.5 } },
        { "id": "landuse-residential", "type": "fill", "source": "sb", "source-layer": "land",
          "filter": ["==", ["get", "kind"], "residential"],
          "paint": { "fill-color": "#e8e0d8", "fill-opacity": 0.5 } },
        { "id": "landuse-commercial", "type": "fill", "source": "sb", "source-layer": "land",
          "filter": ["in", ["get", "kind"], ["literal", ["commercial","retail","industrial"]]],
          "paint": { "fill-color": "#ddd0c8", "fill-opacity": 0.4 } },
        { "id": "landuse-cemetery", "type": "fill", "source": "sb", "source-layer": "land",
          "filter": ["==", ["get", "kind"], "cemetery"],
          "paint": { "fill-color": "#c8d8c0", "fill-opacity": 0.5 } },
        { "id": "boundaries", "type": "line", "source": "sb", "source-layer": "boundaries",
          "filter": ["==", ["get", "admin_level"], 2],
          "paint": { "line-color": "#9090a8", "line-width": 1.5, "line-dasharray": [4, 2] } },
        { "id": "roads-path", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["in", ["get", "kind"], ["literal", ["path","footway","cycleway","track"]]],
          "minzoom": 14,
          "paint": { "line-color": "#c8b8a0",
            "line-width": ["interpolate",["linear"],["zoom"],14,1,17,3,18,5],
            "line-dasharray": [3, 2] } },
        { "id": "roads-minor-casing", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["in", ["get", "kind"], ["literal", ["residential","unclassified","living_street","service"]]],
          "minzoom": 12,
          "paint": { "line-color": "#c4bca8",
            "line-width": ["interpolate",["linear"],["zoom"],12,2,14,4,16,10,18,20] } },
        { "id": "roads-minor", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["in", ["get", "kind"], ["literal", ["residential","unclassified","living_street","service"]]],
          "minzoom": 12,
          "paint": { "line-color": "#ffffff",
            "line-width": ["interpolate",["linear"],["zoom"],12,1,14,3,16,8,18,16] } },
        { "id": "roads-tertiary-casing", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["==", ["get", "kind"], "tertiary"],
          "paint": { "line-color": "#d4a840",
            "line-width": ["interpolate",["linear"],["zoom"],10,1,13,3,14,6,16,13,18,22] } },
        { "id": "roads-tertiary", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["==", ["get", "kind"], "tertiary"],
          "paint": { "line-color": "#e8c878",
            "line-width": ["interpolate",["linear"],["zoom"],10,0.5,13,2,14,4,16,10,18,18] } },
        { "id": "roads-secondary-casing", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["==", ["get", "kind"], "secondary"],
          "paint": { "line-color": "#c8b040",
            "line-width": ["interpolate",["linear"],["zoom"],9,1,12,3,14,7,16,15,18,26] } },
        { "id": "roads-secondary", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["==", ["get", "kind"], "secondary"],
          "paint": { "line-color": "#f5dfa0",
            "line-width": ["interpolate",["linear"],["zoom"],9,0.5,12,2,14,5,16,12,18,22] } },
        { "id": "roads-primary-casing", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["==", ["get", "kind"], "primary"],
          "paint": { "line-color": "#c8a030",
            "line-width": ["interpolate",["linear"],["zoom"],8,1,12,4,14,8,16,17,18,30] } },
        { "id": "roads-primary", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["==", ["get", "kind"], "primary"],
          "paint": { "line-color": "#fcd68a",
            "line-width": ["interpolate",["linear"],["zoom"],8,0.5,12,3,14,6,16,14,18,26] } },
        { "id": "roads-trunk-casing", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["==", ["get", "kind"], "trunk"],
          "paint": { "line-color": "#c06030",
            "line-width": ["interpolate",["linear"],["zoom"],7,1,11,4,14,10,16,20,18,33] } },
        { "id": "roads-trunk", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["==", ["get", "kind"], "trunk"],
          "paint": { "line-color": "#f9a060",
            "line-width": ["interpolate",["linear"],["zoom"],7,0.5,11,3,14,7,16,16,18,28] } },
        { "id": "roads-motorway-casing", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["==", ["get", "kind"], "motorway"],
          "paint": { "line-color": "#b05030",
            "line-width": ["interpolate",["linear"],["zoom"],6,1,10,3,14,11,16,22,18,37] } },
        { "id": "roads-motorway", "type": "line", "source": "sb", "source-layer": "streets",
          "filter": ["==", ["get", "kind"], "motorway"],
          "paint": { "line-color": "#e8825a",
            "line-width": ["interpolate",["linear"],["zoom"],6,0.5,10,2,14,8,16,18,18,32] } },
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
          "paint": { "text-color": "#666666", "text-halo-color": "#ffffff", "text-halo-width": 1 } },
        { "id": "pois-amenity", "type": "symbol", "source": "sb", "source-layer": "pois",
          "minzoom": 14,
          "filter": ["has", "amenity"],
          "layout": {
            "icon-image": ["match", ["get","amenity"],
              "fuel", "fuel_11", "pharmacy", "pharmacy_11", "hospital", "hospital_11",
              "clinic", "doctors_11", "doctors", "doctors_11", "dentist", "dentist_11",
              "veterinary", "veterinary_11", "restaurant", "restaurant_11",
              "fast_food", "fast_food_11", "cafe", "cafe_11", "bar", "bar_11",
              "bank", "bank_11", "atm", "bank_11", "parking", "parking_11",
              "police", "police_11", "fire_station", "fire_station_11",
              "post_office", "post_11", "school", "school_11", "university", "college_11",
              "kindergarten", "school_11", "library", "library_11",
              "theatre", "theatre_11", "cinema", "cinema_11",
              "place_of_worship", "place_of_worship_11", "ice_cream", "ice_cream_11",
              "drinking_water", "drinking_water_11", "grave_yard", "cemetery_11",
              "townhall", "town_hall_11", "fountain", "drinking_water_11",
              "circle_11"],
            "icon-size": ["interpolate",["linear"],["zoom"],14,0.7,17,1],
            "icon-allow-overlap": false,
            "text-field": ["step",["zoom"],"",16,["get","name"]],
            "text-font": ["Noto Sans Regular"],
            "text-size": 10, "text-offset": [0, 1.2], "text-anchor": "top", "text-optional": true
          },
          "paint": { "icon-opacity": 0.9, "text-color": "#555555", "text-halo-color": "#ffffff", "text-halo-width": 1 } },
        { "id": "pois-shop", "type": "symbol", "source": "sb", "source-layer": "pois",
          "minzoom": 14,
          "filter": ["has", "shop"],
          "layout": {
            "icon-image": ["match", ["get","shop"],
              "supermarket", "grocery_11", "convenience", "grocery_11",
              "bakery", "bakery_11", "butcher", "butcher_11",
              "clothes", "clothing_store_11", "hairdresser", "hairdresser_11",
              "florist", "florist_11",
              "circle_11"],
            "icon-size": ["interpolate",["linear"],["zoom"],14,0.7,17,1],
            "icon-allow-overlap": false,
            "text-field": ["step",["zoom"],"",16,["get","name"]],
            "text-font": ["Noto Sans Regular"],
            "text-size": 10, "text-offset": [0, 1.2], "text-anchor": "top", "text-optional": true
          },
          "paint": { "icon-opacity": 0.9, "text-color": "#555555", "text-halo-color": "#ffffff", "text-halo-width": 1 } },
        { "id": "pois-tourism", "type": "symbol", "source": "sb", "source-layer": "pois",
          "minzoom": 14,
          "filter": ["has", "tourism"],
          "layout": {
            "icon-image": ["match", ["get","tourism"],
              "hotel", "lodging_11", "motel", "lodging_11", "hostel", "lodging_11",
              "guest_house", "lodging_11", "camp_site", "campsite_11",
              "museum", "museum_11", "viewpoint", "monument_11",
              "information", "information_11",
              "circle_11"],
            "icon-size": ["interpolate",["linear"],["zoom"],14,0.7,17,1],
            "icon-allow-overlap": false,
            "text-field": ["step",["zoom"],"",16,["get","name"]],
            "text-font": ["Noto Sans Regular"],
            "text-size": 10, "text-offset": [0, 1.2], "text-anchor": "top", "text-optional": true
          },
          "paint": { "icon-opacity": 0.9, "text-color": "#555555", "text-halo-color": "#ffffff", "text-halo-width": 1 } },
        { "id": "pois-leisure", "type": "symbol", "source": "sb", "source-layer": "pois",
          "minzoom": 14,
          "filter": ["has", "leisure"],
          "layout": {
            "icon-image": ["match", ["get","leisure"],
              "playground", "playground_11", "swimming_pool", "swimming_11",
              "sports_centre", "stadium_11", "pitch", "stadium_11",
              "circle_11"],
            "icon-size": ["interpolate",["linear"],["zoom"],14,0.7,17,1],
            "icon-allow-overlap": false,
            "text-field": ["step",["zoom"],"",16,["get","name"]],
            "text-font": ["Noto Sans Regular"],
            "text-size": 10, "text-offset": [0, 1.2], "text-anchor": "top", "text-optional": true
          },
          "paint": { "icon-opacity": 0.9, "text-color": "#555555", "text-halo-color": "#ffffff", "text-halo-width": 1 } }
      ]
    }
    """.trimIndent()

    // tile.openstreetmap.org enforces a strict usage policy and often returns 403
    // (blank/white tiles) for traffic without a registered User-Agent. CartoDB's
    // basemaps are free to use from apps and don't have this restriction.
    val online: String = """
    {
      "version": 8,
      "sources": {
        "osm": {
          "type": "raster",
          "tiles": ["https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png"],
          "tileSize": 256,
          "attribution": "© OpenStreetMap contributors © CARTO"
        }
      },
      "layers": [{ "id": "osm", "type": "raster", "source": "osm" }]
    }
    """.trimIndent()
}
