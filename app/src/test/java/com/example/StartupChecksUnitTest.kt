package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.*
import com.example.data.remote.SupabaseApi
import com.example.data.remote.SupabaseClient
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.util.VersionUtils
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StartupChecksUnitTest {

    @Test
    fun compareVersions_computesCorrectly() {
        // Exact match
        assertEquals(0, VersionUtils.compareVersions("1.0.0", "1.0.0"))
        assertEquals(0, VersionUtils.compareVersions("1.0", "1.0.0"))
        assertEquals(0, VersionUtils.compareVersions("2.1.3", "2.1.3"))

        // Current < Minimum (Needs update)
        assertTrue(VersionUtils.compareVersions("1.0.0", "1.0.1") < 0)
        assertTrue(VersionUtils.compareVersions("1.0.0", "1.1.0") < 0)
        assertTrue(VersionUtils.compareVersions("0.9.9", "1.0.0") < 0)
        assertTrue(VersionUtils.compareVersions("1.2.3", "2.0.0") < 0)

        // Current > Minimum (Valid)
        assertTrue(VersionUtils.compareVersions("1.0.1", "1.0.0") > 0)
        assertTrue(VersionUtils.compareVersions("2.0.0", "1.9.9") > 0)
        assertTrue(VersionUtils.compareVersions("1.1.0", "1.0.5") > 0)
    }

    @Test
    fun parseMaintenanceSettings_handlesDifferentJsonFormats() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = UserSessionManager(context)
        val repository = SndmartRepository(sessionManager = sessionManager)

        // Standard object format: [{"value": {"enabled": true, "message": "Upgrading database"}}]
        val jsonEnabled = """[{"value": {"enabled": true, "message": "Upgrading database"}}]"""
        val parsed1 = repository.parseMaintenanceSettings(jsonEnabled)
        assertTrue(parsed1.enabled)
        assertEquals("Upgrading database", parsed1.message)

        // Disabled format
        val jsonDisabled = """[{"value": {"enabled": false, "message": null}}]"""
        val parsed2 = repository.parseMaintenanceSettings(jsonDisabled)
        assertFalse(parsed2.enabled)

        // Empty array
        val jsonEmpty = """[]"""
        val parsed3 = repository.parseMaintenanceSettings(jsonEmpty)
        assertFalse(parsed3.enabled)

        // Malformed / null
        assertFalse(repository.parseMaintenanceSettings(null).enabled)
        assertFalse(repository.parseMaintenanceSettings("").enabled)
    }

    private fun createMockApi(
        versionResponse: List<AppVersionInfo>? = null,
        maintenanceResponseBody: String? = null
    ): SupabaseApi {
        return Proxy.newProxyInstance(
            SupabaseApi::class.java.classLoader,
            arrayOf(SupabaseApi::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getAppVersionInfo" -> {
                    Response.success<List<AppVersionInfo>>(versionResponse ?: emptyList())
                }
                "getAppSetting" -> {
                    val bodyStr = maintenanceResponseBody ?: """[{"value": {"enabled": false}}]"""
                    Response.success<ResponseBody>(bodyStr.toResponseBody("application/json".toMediaTypeOrNull()))
                }
                else -> {
                    throw UnsupportedOperationException("Mock not implemented for ${method.name}")
                }
            }
        } as SupabaseApi
    }

    @Test
    fun runStartupChecks_belowMinimumVersion_returnsBlockingUpdate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = UserSessionManager(context)
        SupabaseClient.init("https://example.supabase.co", "test-anon-key")

        val mockApi = createMockApi(
            versionResponse = listOf(
                AppVersionInfo(
                    platform = "customer_app",
                    minimumSupportedVersion = "2.0.0",
                    forceUpdate = false,
                    updateMessage = "Please update to v2.0.0 to access new features."
                )
            )
        )

        val repository = SndmartRepository(api = mockApi, sessionManager = sessionManager)
        val result = repository.runStartupChecks()

        assertTrue(result is StartupCheckResult.BlockingUpdate)
        val blocking = result as StartupCheckResult.BlockingUpdate
        assertEquals("Please update to v2.0.0 to access new features.", blocking.updateMessage)
    }

    @Test
    fun runStartupChecks_forceUpdateTrue_returnsBlockingUpdate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = UserSessionManager(context)
        SupabaseClient.init("https://example.supabase.co", "test-anon-key")

        val mockApi = createMockApi(
            versionResponse = listOf(
                AppVersionInfo(
                    platform = "customer_app",
                    minimumSupportedVersion = "1.0.0",
                    forceUpdate = true,
                    updateMessage = "Urgent security update required."
                )
            )
        )

        val repository = SndmartRepository(api = mockApi, sessionManager = sessionManager)
        val result = repository.runStartupChecks()

        assertTrue(result is StartupCheckResult.BlockingUpdate)
        val blocking = result as StartupCheckResult.BlockingUpdate
        assertEquals("Urgent security update required.", blocking.updateMessage)
    }

    @Test
    fun runStartupChecks_maintenanceModeEnabled_returnsMaintenance() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = UserSessionManager(context)
        SupabaseClient.init("https://example.supabase.co", "test-anon-key")

        val mockApi = createMockApi(
            versionResponse = listOf(
                AppVersionInfo(
                    platform = "customer_app",
                    minimumSupportedVersion = "1.0.0",
                    forceUpdate = false
                )
            ),
            maintenanceResponseBody = """[{"value": {"enabled": true, "message": "Service is under scheduled maintenance"}}]"""
        )

        val repository = SndmartRepository(api = mockApi, sessionManager = sessionManager)
        val result = repository.runStartupChecks()

        assertTrue(result is StartupCheckResult.Maintenance)
        val maintenance = result as StartupCheckResult.Maintenance
        assertEquals("Service is under scheduled maintenance", maintenance.message)
    }

    @Test
    fun runStartupChecks_allChecksPass_returnsPassed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = UserSessionManager(context)
        SupabaseClient.init("https://example.supabase.co", "test-anon-key")

        val mockApi = createMockApi(
            versionResponse = listOf(
                AppVersionInfo(
                    platform = "customer_app",
                    minimumSupportedVersion = "1.0.0",
                    forceUpdate = false
                )
            ),
            maintenanceResponseBody = """[{"value": {"enabled": false}}]"""
        )

        val repository = SndmartRepository(api = mockApi, sessionManager = sessionManager)
        val result = repository.runStartupChecks()

        assertTrue(result is StartupCheckResult.Passed)
    }
}
