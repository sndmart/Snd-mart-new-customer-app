# Maps & Geocoding — Privacy / Play Store notes

The customer app now uses a **dual map provider**: Google Maps first, automatically falling
back to **OpenStreetMap (OSM)** — rendered with [osmdroid](https://github.com/osmdroid/osmdroid) —
when Google Maps isn't usable (no API key configured, Play Services missing/outdated, or the
Google map fails to actually load). See `decideMapProvider()` in
`app/src/main/java/com/example/data/maps/MapProviderTypes.kt` for the exact selection logic.

This introduces new third-party network destinations that were not present before. Use this
page to update the Play Console **Data safety** section and the app's privacy policy.

## Third-party services that receive location coordinates

| Service | When it's used | What it receives | Notes |
|---|---|---|---|
| **Google Maps SDK / Places / Geocoding API** | Default provider, whenever a Google Maps API key is configured and usable | Map tile requests (approximate viewport), place search queries, lat/lng for reverse geocoding | Already covered by existing Google Maps Platform terms; no change from before this feature. |
| **OpenStreetMap tile servers** (`tile.openstreetmap.org` by default, or a custom `osm_tile_url` / `osm_satellite_tile_url`) | Automatic fallback when Google Maps isn't usable, or when the user manually switches to "OpenStreetMap" in the on-map provider switch | Map tile requests — the visible map viewport (a bounding box of lat/lng), **not** the user's precise GPS location directly, though the viewport is centered on it | See "OSM tile usage policy" below — **the default public tile server is not meant for production app traffic at scale.** |
| **Nominatim** (`nominatim.openstreetmap.org` by default, or a custom endpoint) | Reverse geocoding (pin position → address) and address search, **only** when Google's Places/Geocoder/Geocoding-REST are unconfigured or fail | Raw lat/lng (for reverse geocoding) or the user's typed search text (for address search) | See "Nominatim usage policy" below. |
| **Photon** (`photon.komoot.io`) | Reserved as an alternative address-search endpoint to Nominatim (see `OsmGeocodingConfig` in `app/src/main/java/com/example/util/NominatimGeocoder.kt`) — not wired up by default, since Nominatim `/search` already covers this fallback | The user's typed search text | Not currently called; documented here in case a future change switches the search fallback to Photon. |

## What to add to Data Safety / Privacy Policy

- Disclose that **approximate/precise location** may be shared with OpenStreetMap tile
  servers and Nominatim, in addition to Google, when the app falls back away from Google
  Maps (no Google account or personal data is attached to these requests — they're
  anonymous HTTP calls from the device).
- Disclose the **new destinations**: `*.tile.openstreetmap.org` (or whatever custom tile
  host is configured), `nominatim.openstreetmap.org` (or a custom Nominatim host).
- No new Android permissions were added — `INTERNET` was already declared and is the only
  permission osmdroid needs. (osmdroid's own library manifest asks for a legacy storage
  permission for its default SD-card tile cache; this app points it at the app's own
  `cacheDir` instead and strips that permission via manifest merger — see
  `AndroidManifest.xml`.)

## OSM tile usage policy — IMPORTANT before production scale

The default OSM "standard" tile server (`tile.openstreetmap.org`, used automatically when
`osm_tile_url` / `osm_satellite_tile_url` are not set in `app_settings`) is run by
volunteers under the **OSMF Tile Usage Policy**
(<https://operations.osmfoundation.org/policies/tiles/>). It explicitly:

- Disallows heavy/bulk use from production apps at scale.
- Requires a valid HTTP `User-Agent` (osmdroid is configured with the app's package name —
  see `OsmMapImpl.kt`).
- May rate-limit or block traffic that doesn't comply, with no advance notice.

**Before this OSM fallback path sees real production volume**, set the `osm_tile_url`
(and optionally `osm_satellite_tile_url`) key in the `app_settings` table to a provider
meant for production traffic, e.g.:

- A paid tile provider: [MapTiler](https://www.maptiler.com/), [Stadia Maps](https://stadiamaps.com/),
  [Thunderforest](https://www.thunderforest.com/), [Mapbox](https://www.mapbox.com/) (via a
  raster tile endpoint).
- A self-hosted tile server (e.g. [tileserver-gl](https://github.com/maptiler/tileserver-gl)
  or [OpenMapTiles](https://openmaptiles.org/)) fed from a planet/regional OSM extract.

The value should be a base URL that osmdroid appends `{z}/{x}/{y}.png` to (see
`buildTileSource()` in `OsmMapImpl.kt`), e.g. `https://api.maptiler.com/maps/streets/`.

## Nominatim usage policy

The default Nominatim instance (`nominatim.openstreetmap.org`) is also a free public
service, under <https://operations.osmfoundation.org/policies/nominatim/>:

- **Max ~1 request/second** — enforced in code via `shouldAllowNominatimRequest()`
  (`app/src/main/java/com/example/util/NominatimGeocoder.kt`), which self-throttles both the
  reverse-geocode and search fallbacks.
- **No autocomplete-as-you-type.** The search fallback (`searchViaNominatim`) is only ever
  called for queries of 3+ characters, on top of each screen's existing debounce for the
  Google Places path (300–350ms) — never on every keystroke.
- **Requires a descriptive `User-Agent`** identifying the app and a contact point — see
  `OsmGeocodingConfig.nominatimUserAgent()`. Update the contact email there if
  `sndmartt@gmail.com` stops being monitored.

Like the tile server, if this fallback sees meaningful production traffic, consider
switching `OsmGeocodingConfig.nominatimBaseUrl` to a commercial or self-hosted Nominatim
instance instead of the free public one.

## Known scope trim

`OsmGeocodingConfig.nominatimBaseUrl` / `photonBaseUrl` are currently simple in-code
defaults (not yet wired to an `app_settings` key like `osm_tile_url` is). If these need to
be changed without an app release, add `nominatim_base_url` handling the same way
`OsmTileConfigCache` reads `osm_tile_url` (`app/src/main/java/com/example/data/maps/OsmTileConfig.kt`)
and set `OsmGeocodingConfig.nominatimBaseUrl` from it at startup.
