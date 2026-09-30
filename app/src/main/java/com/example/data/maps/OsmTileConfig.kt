package com.example.data.maps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.data.repository.SndmartRepository

/** Configurable OSM tile source URLs (app_settings `osm_tile_url` / `osm_satellite_tile_url`). */
data class OsmTileConfig(val tileUrlBase: String?, val satelliteTileUrlBase: String?)

/**
 * Process-lifetime cache mirroring [MapProviderRemoteCache], so every map screen shares one
 * fetch instead of re-querying `app_settings` per screen.
 */
object OsmTileConfigCache {
    @Volatile private var cached: OsmTileConfig? = null

    suspend fun get(repository: SndmartRepository): OsmTileConfig {
        cached?.let { return it }
        val tileUrl = repository.getAppSettingStringValue("osm_tile_url")
        val satelliteUrl = repository.getAppSettingStringValue("osm_satellite_tile_url")
        val config = OsmTileConfig(tileUrl, satelliteUrl)
        cached = config
        return config
    }

    fun peek(): OsmTileConfig? = cached
}

@Composable
fun rememberOsmTileConfig(repository: SndmartRepository): OsmTileConfig {
    var config by remember { mutableStateOf(OsmTileConfigCache.peek() ?: OsmTileConfig(null, null)) }
    LaunchedEffect(Unit) {
        config = OsmTileConfigCache.get(repository)
    }
    return config
}
