package com.example

import com.example.data.maps.MapProvider
import com.example.data.maps.MapProviderUserOverride
import com.example.data.maps.RemoteMapProviderFlag
import com.example.data.maps.decideMapProvider
import com.example.data.maps.isGoogleLoadFailureActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapProviderDeciderTest {

    @Test
    fun remoteFlagOsm_alwaysReturnsOsm_regardlessOfOtherInputs() {
        assertEquals(
            MapProvider.OSM,
            decideMapProvider(
                remoteFlag = RemoteMapProviderFlag.OSM,
                googleKeyConfigured = true,
                playServicesOk = true,
                googleLoadFailed = false,
                userOverride = MapProviderUserOverride.AUTO
            )
        )
    }

    @Test
    fun remoteFlagGoogle_withKeyConfigured_returnsGoogle() {
        assertEquals(
            MapProvider.GOOGLE,
            decideMapProvider(
                remoteFlag = RemoteMapProviderFlag.GOOGLE,
                googleKeyConfigured = true,
                playServicesOk = true,
                googleLoadFailed = false,
                userOverride = MapProviderUserOverride.AUTO
            )
        )
    }

    @Test
    fun autoFlag_withPlaceholderKey_fallsBackToOsm() {
        assertEquals(
            MapProvider.OSM,
            decideMapProvider(
                remoteFlag = RemoteMapProviderFlag.AUTO,
                googleKeyConfigured = false,
                playServicesOk = true,
                googleLoadFailed = false,
                userOverride = MapProviderUserOverride.AUTO
            )
        )
    }

    @Test
    fun autoFlag_playServicesMissing_fallsBackToOsm() {
        assertEquals(
            MapProvider.OSM,
            decideMapProvider(
                remoteFlag = RemoteMapProviderFlag.AUTO,
                googleKeyConfigured = true,
                playServicesOk = false,
                googleLoadFailed = false,
                userOverride = MapProviderUserOverride.AUTO
            )
        )
    }

    @Test
    fun googleLoadFailed_fallsBackToOsm_evenWithEverythingElseOk() {
        assertEquals(
            MapProvider.OSM,
            decideMapProvider(
                remoteFlag = RemoteMapProviderFlag.AUTO,
                googleKeyConfigured = true,
                playServicesOk = true,
                googleLoadFailed = true,
                userOverride = MapProviderUserOverride.AUTO
            )
        )
    }

    @Test
    fun userOverride_alwaysWins_evenOverRemoteOsmFlag() {
        assertEquals(
            MapProvider.GOOGLE,
            decideMapProvider(
                remoteFlag = RemoteMapProviderFlag.OSM,
                googleKeyConfigured = true,
                playServicesOk = true,
                googleLoadFailed = false,
                userOverride = MapProviderUserOverride.GOOGLE
            )
        )
        assertEquals(
            MapProvider.OSM,
            decideMapProvider(
                remoteFlag = RemoteMapProviderFlag.GOOGLE,
                googleKeyConfigured = true,
                playServicesOk = true,
                googleLoadFailed = false,
                userOverride = MapProviderUserOverride.OSM
            )
        )
    }

    @Test
    fun googleFailure_activeWithin6Hours() {
        val failedAt = 1_000_000L
        val justUnder6h = failedAt + (6 * 60 * 60 * 1000L) - 1
        assertTrue(isGoogleLoadFailureActive(failedAt, justUnder6h))
    }

    @Test
    fun googleFailure_expiresAfter6Hours_googleIsRetried() {
        val failedAt = 1_000_000L
        val exactly6h = failedAt + (6 * 60 * 60 * 1000L)
        assertFalse(isGoogleLoadFailureActive(failedAt, exactly6h))

        // decideMapProvider should pick Google again once the failure has expired.
        assertEquals(
            MapProvider.GOOGLE,
            decideMapProvider(
                remoteFlag = RemoteMapProviderFlag.AUTO,
                googleKeyConfigured = true,
                playServicesOk = true,
                googleLoadFailed = isGoogleLoadFailureActive(failedAt, exactly6h),
                userOverride = MapProviderUserOverride.AUTO
            )
        )
    }

    @Test
    fun noFailureRecorded_isNeverActive() {
        assertFalse(isGoogleLoadFailureActive(null, System.currentTimeMillis()))
        assertFalse(isGoogleLoadFailureActive(0L, System.currentTimeMillis()))
    }
}
