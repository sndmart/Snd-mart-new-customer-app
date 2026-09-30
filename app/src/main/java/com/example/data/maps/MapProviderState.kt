package com.example.data.maps

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.BuildConfig
import com.example.data.remote.GoogleMapsConfig
import com.example.data.repository.SndmartRepository
import kotlinx.coroutines.delay

/**
 * Process-lifetime cache for the `map_provider` app_setting so it's fetched from Supabase
 * once per app run (per task spec: "ek baar startup par fetch karo, cache karo"), not once
 * per map screen.
 */
object MapProviderRemoteCache {
    @Volatile private var cached: RemoteMapProviderFlag? = null

    suspend fun get(repository: SndmartRepository): RemoteMapProviderFlag {
        cached?.let { return it }
        val value = repository.getMapProviderRemoteFlag()
        cached = value
        return value
    }

    fun peek(): RemoteMapProviderFlag? = cached
}

/**
 * Live, observable resolution of [decideMapProvider] for one map screen: recomputes whenever
 * the remote flag finishes loading, the user flips the on-map switch, or a Google Maps load
 * failure is detected mid-session.
 */
class MapProviderState internal constructor(
    private val prefs: MapProviderPrefs,
    initialRemoteFlag: RemoteMapProviderFlag,
    private val playServicesOk: Boolean
) {
    private var remoteFlag: RemoteMapProviderFlag = initialRemoteFlag

    var provider by mutableStateOf(computeProvider())
        private set

    var switchedMessage by mutableStateOf<String?>(null)
        private set

    val userOverride: MapProviderUserOverride get() = prefs.userOverride

    private fun computeProvider(): MapProvider = decideMapProvider(
        remoteFlag = remoteFlag,
        googleKeyConfigured = GoogleMapsConfig.isConfigured,
        playServicesOk = playServicesOk,
        googleLoadFailed = prefs.isGoogleFailureActive(),
        userOverride = prefs.userOverride
    )

    internal fun updateRemoteFlag(flag: RemoteMapProviderFlag) {
        remoteFlag = flag
        provider = computeProvider()
    }

    /** Called from the on-map Auto/Google/OpenStreetMap switch. */
    fun setUserOverride(choice: MapProviderUserOverride) {
        prefs.userOverride = choice
        provider = computeProvider()
    }

    /**
     * Called when Google Maps is currently rendering but fails to actually come up: the
     * onMapLoaded callback doesn't arrive within the timeout, or map init throws. Switches
     * this screen to OSM immediately and remembers the failure for [GOOGLE_FAILURE_TTL_MS]
     * so later screens don't pay the same wait again.
     */
    fun reportGoogleFailure(reason: String) {
        if (BuildConfig.DEBUG) {
            Log.d("MapProviderState", "Google Maps failed to load ($reason) — switching to OSM backup")
        }
        prefs.recordGoogleFailure()
        val wasGoogle = provider == MapProvider.GOOGLE
        provider = computeProvider()
        if (wasGoogle) {
            switchedMessage = "Map switched to backup mode"
        }
    }

    internal fun clearSwitchedMessage() {
        switchedMessage = null
    }
}

/**
 * Remembers a [MapProviderState] for the current map screen: fetches (or reuses the cached)
 * remote `map_provider` flag, checks Play Services availability once, and recomputes the
 * live decision whenever those inputs change.
 */
@Composable
fun rememberMapProviderState(repository: SndmartRepository): MapProviderState {
    val context = LocalContext.current
    val prefs = remember { MapProviderPrefs(context) }
    val playServicesOk = remember { isPlayServicesAvailable(context) }

    val state = remember {
        MapProviderState(
            prefs = prefs,
            initialRemoteFlag = MapProviderRemoteCache.peek() ?: RemoteMapProviderFlag.AUTO,
            playServicesOk = playServicesOk
        )
    }

    LaunchedEffect(Unit) {
        val fetched = MapProviderRemoteCache.get(repository)
        state.updateRemoteFlag(fetched)
        if (BuildConfig.DEBUG) {
            Log.d(
                "MapProviderState",
                "remoteFlag=$fetched googleKeyConfigured=${GoogleMapsConfig.isConfigured} " +
                    "playServicesOk=$playServicesOk userOverride=${state.userOverride} -> provider=${state.provider}"
            )
        }
    }

    LaunchedEffect(state.switchedMessage) {
        if (state.switchedMessage != null) {
            delay(3000)
            state.clearSwitchedMessage()
        }
    }

    return state
}
