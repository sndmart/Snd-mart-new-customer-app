package com.example.ui.components.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.maps.MapProviderState
import com.example.data.maps.MapProviderUserOverride

/**
 * Small "Map provider" switch (Auto / Google / OpenStreetMap) — task spec item B. Screens
 * place this in whatever corner suits their existing FAB layout, next to the zoom/re-centre
 * controls that already sit on top of the map.
 */
@Composable
fun MapProviderSwitchButton(
    mapProviderState: MapProviderState,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        SmallFloatingActionButton(
            onClick = { expanded = true },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = CircleShape,
            modifier = Modifier
                .size(36.dp)
                .testTag("map_provider_switch_button")
        ) {
            Icon(Icons.Default.Layers, contentDescription = "Map provider")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val options = listOf(
                MapProviderUserOverride.AUTO to "Auto",
                MapProviderUserOverride.GOOGLE to "Google Maps",
                MapProviderUserOverride.OSM to "OpenStreetMap"
            )
            options.forEach { (choice, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        mapProviderState.setUserOverride(choice)
                        expanded = false
                    },
                    trailingIcon = {
                        if (mapProviderState.userOverride == choice) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
            }
        }
    }
}

/**
 * Normal/Satellite toggle — task spec item C. Pass `satelliteAvailable = false` to hide it
 * entirely (e.g. OSM with no `osm_satellite_tile_url` configured).
 */
@Composable
fun MapTypeToggleButton(
    mapType: SndmartMapType,
    onToggle: (SndmartMapType) -> Unit,
    modifier: Modifier = Modifier,
    satelliteAvailable: Boolean = true
) {
    if (!satelliteAvailable) return
    SmallFloatingActionButton(
        onClick = { onToggle(if (mapType == SndmartMapType.NORMAL) SndmartMapType.SATELLITE else SndmartMapType.NORMAL) },
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        modifier = modifier
            .size(36.dp)
            .testTag("map_type_toggle_button")
    ) {
        Icon(
            imageVector = if (mapType == SndmartMapType.NORMAL) Icons.Default.Satellite else Icons.Default.Map,
            contentDescription = "Toggle satellite view"
        )
    }
}
