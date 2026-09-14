package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.SupabaseAuthResponse
import com.example.data.remote.SupabaseApi
import com.example.data.remote.SupabaseClient
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.util.PhoneUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhoneNormalizationUnitTest {

    @Test
    fun toE164_normalizesCorrectlyAcrossAllFormats() {
        // Test Case 1: The real bug reported by the user - number starting with '91' as national number
        val inputNoCountryCode = "9110604033"
        val inputWithCountryCode = "919110604033"
        val inputWithPlusAndCountryCode = "+919110604033"
        val inputWithSpaces = "+91 91106 04033"
        val inputWithDashes = "91106-04033"

        val expected = "+919110604033"

        assertEquals(expected, PhoneUtils.toE164(inputNoCountryCode))
        assertEquals(expected, PhoneUtils.toE164(inputWithCountryCode))
        assertEquals(expected, PhoneUtils.toE164(inputWithPlusAndCountryCode))
        assertEquals(expected, PhoneUtils.toE164(inputWithSpaces))
        assertEquals(expected, PhoneUtils.toE164(inputWithDashes))

        // Confirm length is always exactly 13 chars: "+91" + 10 digits
        assertEquals(13, PhoneUtils.toE164(inputNoCountryCode).length)
        assertEquals(13, PhoneUtils.toE164(inputWithCountryCode).length)

        // Test Case 2: Standard 10-digit Indian mobile number (not starting with 91)
        assertEquals("+919876543210", PhoneUtils.toE164("9876543210"))
        assertEquals("+919876543210", PhoneUtils.toE164("919876543210"))
        assertEquals("+919876543210", PhoneUtils.toE164("+919876543210"))
        assertEquals("+919876543210", PhoneUtils.toE164("+91 98765-43210"))
    }

    @Test
    fun to10Digits_extractsNationalNumberCorrectly() {
        assertEquals("9110604033", PhoneUtils.to10Digits("9110604033"))
        assertEquals("9110604033", PhoneUtils.to10Digits("919110604033"))
        assertEquals("9110604033", PhoneUtils.to10Digits("+919110604033"))
        assertEquals("9110604033", PhoneUtils.to10Digits("+91 91106 04033"))

        assertEquals("9876543210", PhoneUtils.to10Digits("9876543210"))
        assertEquals("9876543210", PhoneUtils.to10Digits("919876543210"))
        assertEquals("9876543210", PhoneUtils.to10Digits("+91 98765 43210"))
    }

    @Test
    fun isValidPhoneNumber_validates10Digits() {
        assertTrue(PhoneUtils.isValidPhoneNumber("9110604033"))
        assertTrue(PhoneUtils.isValidPhoneNumber("919110604033"))
        assertTrue(PhoneUtils.isValidPhoneNumber("+919110604033"))
        assertTrue(PhoneUtils.isValidPhoneNumber("9876543210"))

        assertFalse(PhoneUtils.isValidPhoneNumber("12345"))
        assertFalse(PhoneUtils.isValidPhoneNumber(""))
        assertFalse(PhoneUtils.isValidPhoneNumber("91911060403399")) // Too long
    }

    @Test
    fun repository_signInWithPhoneOtp_sendsByteForByteIdenticalE164() = runBlocking {
        val capturedPhones = mutableListOf<String>()

        val mockApi = Proxy.newProxyInstance(
            SupabaseApi::class.java.classLoader,
            arrayOf(SupabaseApi::class.java)
        ) { _, method, args ->
            when (method.name) {
                "signInWithOtp" -> {
                    @Suppress("UNCHECKED_CAST")
                    val body = args[0] as Map<String, Any>
                    capturedPhones.add(body["phone"] as String)
                    Response.success(okhttp3.ResponseBody.create(null, "{}"))
                }
                else -> throw UnsupportedOperationException("Not mocked")
            }
        } as SupabaseApi

        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = UserSessionManager(context)
        SupabaseClient.init("https://example.supabase.co", "test-anon-key")
        val repository = SndmartRepository(api = mockApi, sessionManager = sessionManager)

        // Calling with raw 10 digits
        repository.signInWithPhoneOtp("9110604033")
        // Calling with 91 prefix without plus
        repository.signInWithPhoneOtp("919110604033")
        // Calling with +91 prefix
        repository.signInWithPhoneOtp("+919110604033")

        // All three calls MUST send the exact same byte-for-byte "+919110604033" string
        assertEquals(3, capturedPhones.size)
        assertEquals("+919110604033", capturedPhones[0])
        assertEquals("+919110604033", capturedPhones[1])
        assertEquals("+919110604033", capturedPhones[2])
    }

    @Test
    fun repository_verifyPhoneOtp_sendsByteForByteIdenticalE164() = runBlocking {
        val capturedPhones = mutableListOf<String>()

        val mockApi = Proxy.newProxyInstance(
            SupabaseApi::class.java.classLoader,
            arrayOf(SupabaseApi::class.java)
        ) { _, method, args ->
            when (method.name) {
                "verifyOtp" -> {
                    @Suppress("UNCHECKED_CAST")
                    val body = args[0] as Map<String, String>
                    capturedPhones.add(body["phone"] ?: "")
                    val authResponse = SupabaseAuthResponse(
                        accessToken = "test-token",
                        refreshToken = "test-refresh",
                        expiresIn = 3600L
                    )
                    Response.success(authResponse)
                }
                else -> throw UnsupportedOperationException("Not mocked")
            }
        } as SupabaseApi

        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = UserSessionManager(context)
        SupabaseClient.init("https://example.supabase.co", "test-anon-key")
        val repository = SndmartRepository(api = mockApi, sessionManager = sessionManager)

        // Calling with raw 10 digits
        repository.verifyPhoneOtp("9110604033", "123456")
        // Calling with 91 prefix
        repository.verifyPhoneOtp("919110604033", "123456")

        assertEquals(2, capturedPhones.size)
        assertEquals("+919110604033", capturedPhones[0])
        assertEquals("+919110604033", capturedPhones[1])
    }
}
