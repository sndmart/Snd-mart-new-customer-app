package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.maps.RemoteMapProviderFlag
import com.example.data.remote.SupabaseApi
import com.example.data.remote.SupabaseClient
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MapProviderSettingUnitTest {

    private fun repository(): SndmartRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = UserSessionManager(context)
        return SndmartRepository(sessionManager = sessionManager)
    }

    @Test
    fun parseMapProviderSetting_handlesRawStringValues() {
        val repo = repository()
        assertEquals(RemoteMapProviderFlag.OSM, repo.parseMapProviderSetting("""[{"value": "osm"}]"""))
        assertEquals(RemoteMapProviderFlag.GOOGLE, repo.parseMapProviderSetting("""[{"value": "google"}]"""))
        assertEquals(RemoteMapProviderFlag.AUTO, repo.parseMapProviderSetting("""[{"value": "auto"}]"""))
    }

    @Test
    fun parseMapProviderSetting_handlesWrappedObjectValues() {
        val repo = repository()
        assertEquals(
            RemoteMapProviderFlag.OSM,
            repo.parseMapProviderSetting("""[{"value": {"provider": "osm"}}]""")
        )
    }

    @Test
    fun parseMapProviderSetting_unknownOrMissing_defaultsToAuto() {
        val repo = repository()
        assertEquals(RemoteMapProviderFlag.AUTO, repo.parseMapProviderSetting(null))
        assertEquals(RemoteMapProviderFlag.AUTO, repo.parseMapProviderSetting(""))
        assertEquals(RemoteMapProviderFlag.AUTO, repo.parseMapProviderSetting("[]"))
        assertEquals(RemoteMapProviderFlag.AUTO, repo.parseMapProviderSetting("""[{"value": "bogus"}]"""))
        assertEquals(RemoteMapProviderFlag.AUTO, repo.parseMapProviderSetting("not json"))
    }

    @Test
    fun getMapProviderRemoteFlag_appSettingsFetchFails_defaultsToAuto() = runBlocking {
        SupabaseClient.init("https://example.supabase.co", "test-anon-key")
        val mockApi = Proxy.newProxyInstance(
            SupabaseApi::class.java.classLoader,
            arrayOf(SupabaseApi::class.java)
        ) { _, method, _ ->
            if (method.name == "getAppSetting") {
                throw java.io.IOException("network error")
            } else {
                throw UnsupportedOperationException("Mock not implemented for ${method.name}")
            }
        } as SupabaseApi

        val sessionManager = UserSessionManager(ApplicationProvider.getApplicationContext())
        val repo = SndmartRepository(api = mockApi, sessionManager = sessionManager)
        assertEquals(RemoteMapProviderFlag.AUTO, repo.getMapProviderRemoteFlag())
    }

    @Test
    fun getMapProviderRemoteFlag_httpErrorResponse_defaultsToAuto() = runBlocking {
        SupabaseClient.init("https://example.supabase.co", "test-anon-key")
        val mockApi = Proxy.newProxyInstance(
            SupabaseApi::class.java.classLoader,
            arrayOf(SupabaseApi::class.java)
        ) { _, method, _ ->
            if (method.name == "getAppSetting") {
                val errorBody = "not found".toResponseBody("text/plain".toMediaTypeOrNull())
                Response.error<ResponseBody>(404, errorBody)
            } else {
                throw UnsupportedOperationException("Mock not implemented for ${method.name}")
            }
        } as SupabaseApi

        val sessionManager = UserSessionManager(ApplicationProvider.getApplicationContext())
        val repo = SndmartRepository(api = mockApi, sessionManager = sessionManager)
        assertEquals(RemoteMapProviderFlag.AUTO, repo.getMapProviderRemoteFlag())
    }

    @Test
    fun getMapProviderRemoteFlag_success_returnsParsedValue() = runBlocking {
        SupabaseClient.init("https://example.supabase.co", "test-anon-key")
        val mockApi = Proxy.newProxyInstance(
            SupabaseApi::class.java.classLoader,
            arrayOf(SupabaseApi::class.java)
        ) { _, method, _ ->
            if (method.name == "getAppSetting") {
                val body = """[{"value": "osm"}]""".toResponseBody("application/json".toMediaTypeOrNull())
                Response.success<ResponseBody>(body)
            } else {
                throw UnsupportedOperationException("Mock not implemented for ${method.name}")
            }
        } as SupabaseApi

        val sessionManager = UserSessionManager(ApplicationProvider.getApplicationContext())
        val repo = SndmartRepository(api = mockApi, sessionManager = sessionManager)
        assertEquals(RemoteMapProviderFlag.OSM, repo.getMapProviderRemoteFlag())
    }
}
