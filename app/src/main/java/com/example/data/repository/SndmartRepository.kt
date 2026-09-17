package com.example.data.repository

import android.util.Log
import com.example.data.model.*
import com.example.data.remote.ApiException
import com.example.data.remote.SessionExpiredException
import com.example.data.remote.SupabaseApi
import com.example.data.remote.SupabaseClient
import com.example.data.session.UserSessionManager
import com.example.util.PhoneUtils
import com.example.util.VersionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed class AddToCartResult {
    object Success : AddToCartResult()
    data class HotelConflict(val existingVendorId: String, val newVendorId: String, val pendingItem: CartItem) : AddToCartResult()
}

class SndmartRepository(
    private val api: SupabaseApi = SupabaseClient.api,
    val sessionManager: UserSessionManager
) {
    private val TAG = "SndmartRepository"

    val unreadNotificationCount: StateFlow<Int>
        get() = sessionManager.unreadNotificationCount

    val currentCity: com.example.data.model.City?
        get() = sessionManager.selectedCity.value

    val currentUserId: String?
        get() = sessionManager.userId.value

    // In-memory cart items (strictly NO price column, only productId, vendorId, cityId, quantity)
    private val _groceryCart = MutableStateFlow<List<CartItem>>(emptyList())
    val groceryCart: StateFlow<List<CartItem>> = _groceryCart.asStateFlow()

    private val _hotelCart = MutableStateFlow<List<CartItem>>(emptyList())
    val hotelCart: StateFlow<List<CartItem>> = _hotelCart.asStateFlow()

    // Background scope used to mirror the in-memory cart to the cart_items table.
    private val cartSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var persistJob: Job? = null

    // Local in-memory state for offline/demo operation when Supabase key is unconfigured or offline
    private val _localAddresses = MutableStateFlow<List<CustomerAddress>>(emptyList())

    // Rule 6: In-memory caches for slow-changing data
    private var cachedCities: List<City>? = null
    private var cachedGroceryCategories: List<Category>? = null
    private val cachedProfiles = java.util.concurrent.ConcurrentHashMap<String, Profile>()
    private val cachedVendorNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun clearCaches() {
        cachedCities = null
        cachedGroceryCategories = null
        cachedProfiles.clear()
        cachedVendorNames.clear()
    }

    // --- SESSION MANAGEMENT ---
    suspend fun refreshSession(): Result<SupabaseAuthResponse> {
        val refreshToken = sessionManager.getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            sessionManager.notifySessionExpired("Your session expired, please log in again")
            return Result.failure(SessionExpiredException("No refresh token stored"))
        }
        return try {
            val response = api.refreshSession(mapOf("refresh_token" to refreshToken))
            if (response.isSuccessful && response.body() != null) {
                val auth = response.body()!!
                val newAccessToken = auth.accessToken
                val newRefreshToken = auth.refreshToken ?: refreshToken
                if (!newAccessToken.isNullOrBlank()) {
                    sessionManager.updateTokens(newAccessToken, newRefreshToken, auth.expiresIn)
                    Log.i(TAG, "Session refreshed successfully via SndmartRepository.")
                    Result.success(auth)
                } else {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    Result.failure(SessionExpiredException("Empty access token in refresh response"))
                }
            } else {
                val code = response.code()
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "Refresh session failed HTTP $code: $error")
                sessionManager.notifySessionExpired("Your session expired, please log in again")
                Result.failure(SessionExpiredException("Session expired: $error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during refreshSession: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun ensureValidSession(): Result<Boolean> {
        if (!sessionManager.isLoggedIn.value) {
            return Result.success(false)
        }
        if (sessionManager.isSessionExpiredOrExpiringSoon(bufferMs = 120_000L)) {
            Log.d(TAG, "Session token is expired or expiring soon; proactively refreshing session...")
            val res = refreshSession()
            if (!res.isSuccess) {
                return Result.failure(res.exceptionOrNull() ?: SessionExpiredException())
            }
        }
        // Verify active device session
        checkStillActiveDevice()
        return Result.success(true)
    }

    private fun getFallbackGroceryProducts(categoryId: String?, searchQuery: String? = null): List<ResolvedProduct> {
        val filtered = when {
            !searchQuery.isNullOrBlank() ->
                DemoCatalog.GROCERY_PRODUCTS.filter { it.name.contains(searchQuery, ignoreCase = true) }
            categoryId.isNullOrBlank() ->
                DemoCatalog.GROCERY_PRODUCTS
            else ->
                DemoCatalog.GROCERY_PRODUCTS.filter { it.categoryId == categoryId }
        }
        return filtered.map { prod ->
            ResolvedProduct(
                baseProduct = prod,
                effectivePrice = prod.price,
                effectiveMrp = prod.mrp,
                effectiveStock = prod.stockQty ?: 50,
                effectiveIsAvailable = prod.isAvailable
            )
        }.sortedWith(
            compareByDescending<ResolvedProduct> { it.isInStockAndActive }
                .thenBy { it.name.lowercase() }
        )
    }

    // Grocery categories: vendor_type IN (grocery,vegetable,fruit), vendor_id IS NULL,
    // scoped to the customer's city. Demo fallback ignores city_id (demo cats have none).
    private val demoGroceryCategories: List<Category>
        get() = DemoCatalog.CATEGORIES.filter {
            val vt = it.vendorType?.lowercase()
            vt in listOf("grocery", "vegetable", "fruit") && it.vendorId == null && it.isActive
        }

    private fun getFallbackHotelMenu(vendorId: String): Pair<List<Category>, List<ResolvedProduct>> {
        val cats = DemoCatalog.HOTEL_CATEGORIES.filter { it.vendorId == vendorId }
        val prods = DemoCatalog.HOTEL_PRODUCTS.filter { it.vendorId == vendorId || vendorId.isBlank() }
        val resolved = prods.map { prod ->
            ResolvedProduct(
                baseProduct = prod,
                effectivePrice = prod.price,
                effectiveMrp = prod.mrp,
                effectiveStock = prod.stockQty ?: 50,
                effectiveIsAvailable = prod.isAvailable
            )
        }.sortedWith(
            compareBy<ResolvedProduct> { prod ->
                val isAvail = prod.isHotelItemAvailable
                when {
                    isAvail && prod.isFeatured -> 0
                    isAvail -> 1
                    else -> 2
                }
            }.thenBy { it.name.lowercase() }
        )
        return Pair(cats, resolved)
    }

    private fun findFallbackProduct(productId: String): ResolvedProduct? {
        val p = (DemoCatalog.GROCERY_PRODUCTS + DemoCatalog.HOTEL_PRODUCTS).firstOrNull { it.id == productId }
            ?: return null
        return ResolvedProduct(
            baseProduct = p,
            effectivePrice = p.price,
            effectiveMrp = p.mrp,
            effectiveStock = p.stockQty ?: 50,
            effectiveIsAvailable = p.isAvailable
        )
    }

    // --- CITIES ---
    suspend fun getActiveCities(): Result<List<City>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Supabase API key is not configured. Configure key to load live cities."))
        }
        return try {
            val response = api.getCities(status = "eq.active", order = "name.asc")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "Failed to fetch cities from backend: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching cities from backend: ${e.message}", e)
            Result.failure(e)
        }
    }

    // --- CITY DETECTION (BACKEND RPC) ---
    suspend fun findCityForLocation(lat: Double, lng: Double): Result<City?> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Supabase API key is not configured"))
        }
        return try {
            val response = api.findCityForLocation(mapOf("p_lat" to lat, "p_lng" to lng))
            if (response.isSuccessful && response.body() != null) {
                val results = response.body()!!
                if (results.isNotEmpty()) {
                    val r = results.first()
                    Result.success(City(id = r.cityId, name = r.cityName, status = "active"))
                } else {
                    Result.success(null) // Location not serviceable
                }
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "find_city_for_location RPC failed: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception calling find_city_for_location: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getCities(forceRefresh: Boolean = false): Result<List<City>> {
        if (!SupabaseClient.isKeyConfigured()) return Result.success(emptyList())
        if (!forceRefresh && cachedCities != null) {
            return Result.success(cachedCities!!)
        }
        return try {
            val response = api.getCities(status = "eq.active", order = "name.asc")
            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                cachedCities = list
                Result.success(list)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "getCities failed HTTP ${response.code()}: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching cities: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun findCityAndDistanceForLocation(lat: Double, lng: Double): Result<CityLocationResult?> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Supabase API key is not configured"))
        }
        return try {
            val response = api.findCityForLocation(mapOf("p_lat" to lat, "p_lng" to lng))
            if (response.isSuccessful && response.body() != null) {
                val results = response.body()!!
                Result.success(results.firstOrNull())
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "find_city_for_location RPC failed: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception calling find_city_for_location: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun updateProfileCityId(userId: String, cityId: String): Result<Unit> {
        return try {
            val response = api.updateProfile("eq.$userId", mapOf("city_id" to cityId))
            if (response.isSuccessful) {
                cachedProfiles.remove(userId)
                Result.success(Unit)
            } else {
                Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception updating profile city_id: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Edit profile (name + phone) via PATCH /rest/v1/profiles?id=eq.{userId}.
    suspend fun updateProfile(userId: String, fullName: String?, phone: String?): Result<Unit> {
        return try {
            val body = mutableMapOf<String, Any?>()
            if (fullName != null) body["full_name"] = fullName
            if (phone != null) {
                body["phone"] = if (phone.isNotBlank()) PhoneUtils.toE164(phone) else null
            }
            val response = api.updateProfile("eq.$userId", body)
            if (response.isSuccessful) {
                cachedProfiles.remove(userId)
                Result.success(Unit)
            } else {
                Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- PHONE OTP AUTH & PROFILES ---

    suspend fun signInWithPhoneOtp(rawPhone: String): Result<Unit> {
        val e164Phone = PhoneUtils.toE164(rawPhone)
        return try {
            val response = api.signInWithOtp(mapOf("phone" to e164Phone))
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "signInWithOtp failed for $e164Phone: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in signInWithPhoneOtp: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun verifyPhoneOtp(rawPhone: String, token: String): Result<SupabaseAuthResponse> {
        val e164Phone = PhoneUtils.toE164(rawPhone)
        return try {
            val response = api.verifyOtp(
                mapOf(
                    "type" to "sms",
                    "phone" to e164Phone,
                    "token" to token
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "verifyOtp failed for $e164Phone: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in verifyPhoneOtp: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getProfile(userId: String, forceRefresh: Boolean = false): Result<Profile?> {
        if (!forceRefresh) {
            val cached = cachedProfiles[userId]
            if (cached != null) return Result.success(cached)
        }
        return try {
            val response = api.getProfile("eq.$userId")
            if (response.isSuccessful) {
                val profile = response.body()?.firstOrNull()
                if (profile != null) {
                    cachedProfiles[userId] = profile
                }
                Result.success(profile)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "getProfile failed for $userId: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting profile for $userId: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun createProfile(profile: Profile): Result<Profile> {
        val normalizedProfile = if (!profile.phone.isNullOrBlank()) {
            profile.copy(phone = PhoneUtils.toE164(profile.phone))
        } else {
            profile
        }
        return try {
            val response = api.createProfile(normalizedProfile)
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                Result.success(response.body()!!.first())
            } else if (response.isSuccessful) {
                Result.success(normalizedProfile)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "createProfile failed for ${profile.id}: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating profile for ${profile.id}: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Single Device Login:
     * On successful login, write this device id to profiles.current_device_session
     * and revoke refresh tokens for every other active session of this account via signOut(scope = "others").
     */
    suspend fun registerDeviceSession(userId: String): Result<Unit> {
        if (!SupabaseClient.isKeyConfigured()) return Result.success(Unit)
        return try {
            val deviceId = sessionManager.getOrCreateDeviceId()
            Log.i(TAG, "Registering device session $deviceId for user $userId")

            val updateRes = api.updateProfile(
                idQuery = "eq.$userId",
                profile = mapOf("current_device_session" to deviceId)
            )
            if (!updateRes.isSuccessful) {
                Log.w(TAG, "updateProfile current_device_session returned ${updateRes.code()}: ${updateRes.errorBody()?.string()}")
            }

            // Revoke refresh tokens for every OTHER active session of this account
            try {
                val signOutOthersRes = api.signOut(scope = "others")
                if (!signOutOthersRes.isSuccessful) {
                    Log.w(TAG, "signOut(scope=others) returned ${signOutOthersRes.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "signOut(scope=others) exception: ${e.message}")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in registerDeviceSession: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Signs out the current session or other sessions on Supabase backend.
     */
    suspend fun signOut(scope: String? = null): Result<Unit> {
        if (!SupabaseClient.isKeyConfigured()) return Result.success(Unit)
        return try {
            val res = api.signOut(scope = scope)
            if (res.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(SupabaseClient.parseErrorMessage(res)))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during signOut(scope=$scope): ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Checks whether THIS device is still the account's registered device.
     * If someone logged in on a different device (current_device_session !== myDeviceId),
     * forces logout on this device with a clear notification message.
     */
    suspend fun checkStillActiveDevice(): Boolean {
        val uid = sessionManager.getUserId()
        if (uid.isNullOrBlank() || !sessionManager.isLoggedIn.value) return true
        if (!SupabaseClient.isKeyConfigured()) return true

        return try {
            val profileRes = getProfile(uid)
            val profile = profileRes.getOrNull()
            val myDeviceId = sessionManager.getOrCreateDeviceId()

            if (profile != null && !profile.currentDeviceSession.isNullOrBlank() && profile.currentDeviceSession != myDeviceId) {
                Log.w(TAG, "Device session conflict! Active device: ${profile.currentDeviceSession}, This device: $myDeviceId. Forcing logout.")
                try {
                    signOut(scope = "local")
                } catch (e: Exception) {
                    Log.w(TAG, "Error in local signOut: ${e.message}")
                }
                sessionManager.notifySessionExpired("You were logged out because your account was used on another device.")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkStillActiveDevice check failed: ${e.message}")
            true
        }
    }

    /**
     * Reads the logged-in user's profiles.city_id and resolves it to an active City
     * (with name). Returns null when the user has no city assigned yet (first-time flow)
     * or when the backend is unavailable — the caller then falls back to GPS detection.
     */
    suspend fun resolveUserCity(userId: String): Result<City?> {
        if (!SupabaseClient.isKeyConfigured()) return Result.success(null)
        return try {
            val profileRes = api.getProfile("eq.$userId")
            if (!profileRes.isSuccessful || profileRes.body().isNullOrEmpty()) {
                return Result.success(null)
            }
            val cityId = profileRes.body()!!.first().cityId
                ?: return Result.success(null)
            val citiesRes = api.getCities(status = "eq.active", order = "name.asc")
            if (!citiesRes.isSuccessful || citiesRes.body() == null) {
                return Result.success(null)
            }
            val city = citiesRes.body()!!.firstOrNull { it.id == cityId }
            Result.success(city)
        } catch (e: Exception) {
            Log.w(TAG, "Exception resolving user city from profile: ${e.message}", e)
            Result.success(null)
        }
    }

    // --- CATEGORIES ---
    suspend fun getCategories(): Result<List<Category>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(DemoCatalog.CATEGORIES)
        }
        return try {
            val response = api.getCategories()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "Could not fetch categories: $error")
                if (response.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                Result.failure(ApiException(response.code(), error))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching categories: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Grocery categories: vendor_type IN (grocery,vegetable,fruit),
    // vendor_id IS NULL, is_active=true, ordered by sort_order.
    suspend fun getGroceryCategories(forceRefresh: Boolean = false): Result<List<Category>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(demoGroceryCategories)
        }
        if (!forceRefresh && cachedGroceryCategories != null) {
            return Result.success(cachedGroceryCategories!!)
        }
        return try {
            val response = api.getCategories(
                select = "id,name,image_url,sort_order",
                isActive = "eq.true",
                order = "sort_order.asc",
                vendorType = "in.(grocery,vegetable,fruit)",
                vendorId = "is.null",
                cityId = null
            )
            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                cachedGroceryCategories = list
                Log.i(TAG, "Categories fetched successfully: ${list.size} categories found: ${list.map { it.name }}")
                Result.success(list)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "Categories fetch error: HTTP ${response.code()} $error")
                if (response.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                // Fallback attempt without select parameter in case of schema column selection difference
                val fallbackResponse = api.getCategories(
                    isActive = "eq.true",
                    order = "sort_order.asc",
                    vendorType = "in.(grocery,vegetable,fruit)",
                    vendorId = "is.null",
                    cityId = null
                )
                if (fallbackResponse.isSuccessful && fallbackResponse.body() != null) {
                    val fallbackList = fallbackResponse.body()!!
                    cachedGroceryCategories = fallbackList
                    Log.i(TAG, "Categories fetched via fallback: ${fallbackList.size} categories found: ${fallbackList.map { it.name }}")
                    Result.success(fallbackList)
                } else {
                    val fallbackError = SupabaseClient.parseErrorMessage(fallbackResponse)
                    Log.e(TAG, "Categories fetch error: $fallbackError")
                    Result.failure(ApiException(response.code(), error))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Categories fetch error: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Overload for backward compatibility with existing callers passing cityId
    suspend fun getGroceryCategories(cityId: String): Result<List<Category>> = getGroceryCategories()

    // --- VENDORS (HOTELS) ---
    // Fetches hotels (vendor_type=hotel) for a city.
    // Rule 1: Hotel list: 20 at a time.
    // Rule 2: Selective column fetching.
    suspend fun getHotels(
        cityId: String,
        searchQuery: String? = null,
        limit: Int = 20,
        offset: Int = 0
    ): Result<List<Vendor>> {
        if (!SupabaseClient.isKeyConfigured()) {
            val demo = if (!searchQuery.isNullOrBlank())
                DemoCatalog.HOTELS.filter { it.name.contains(searchQuery, ignoreCase = true) }
            else DemoCatalog.HOTELS
            val paged = demo.drop(offset).take(limit)
            return Result.success(paged)
        }
        return try {
            val nameQuery = searchQuery?.takeIf { it.isNotBlank() }?.let {
                "ilike.*" + java.net.URLEncoder.encode(it.trim(), "UTF-8").replace("+", "%20") + "*"
            }
            val response = api.getVendors(
                cityId = "eq.$cityId",
                vendorType = "eq.hotel",
                name = nameQuery,
                select = "id,name,vendor_type,image_url,banner_url,rating,is_open,is_active,is_featured,city_id,opening_time,closing_time",
                limit = limit,
                offset = offset
            )
            if (response.isSuccessful && response.body() != null) {
                val sorted = response.body()!!.sortedWith(
                    compareBy<Vendor> { vendor ->
                        when {
                            vendor.isActive && vendor.isFeatured == true -> 0
                            vendor.isActive -> 1
                            else -> 2
                        }
                    }.thenBy { it.name.lowercase() }
                )
                Result.success(sorted)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "Could not fetch hotels: $error")
                if (response.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                Result.failure(ApiException(response.code(), error))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching hotels: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Average vendor rating computed client-side from vendor_reviews (select=rating).
    // Returns 0.0 when there are no reviews yet or the backend is unavailable.
    suspend fun getVendorAverageRating(vendorId: String): Result<Double> {
        if (!SupabaseClient.isKeyConfigured()) return Result.success(0.0)
        return try {
            val response = api.getVendorReviews(vendorId = "eq.$vendorId", select = "rating")
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                val ratings = response.body()!!.mapNotNull { it.rating.takeIf { r -> r > 0 } }
                Result.success(if (ratings.isNotEmpty()) ratings.average() else 0.0)
            } else {
                Result.success(0.0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching vendor rating: ${e.message}", e)
            Result.success(0.0)
        }
    }

    // Rule 3: Batch resolve vendor names to avoid N+1 queries when rendering order lists
    suspend fun getVendorNames(vendorIds: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val missing = mutableListOf<String>()
        for (vid in vendorIds) {
            val name = cachedVendorNames[vid]
            if (name != null) {
                result[vid] = name
            } else {
                missing.add(vid)
            }
        }
        if (missing.isNotEmpty()) {
            val distinctMissing = missing.distinct()
            try {
                val res = api.getVendorsByIds(
                    idInQuery = "in.(${distinctMissing.joinToString(",")})",
                    select = "id,name"
                )
                if (res.isSuccessful && res.body() != null) {
                    for (v in res.body()!!) {
                        cachedVendorNames[v.id] = v.name
                        result[v.id] = v.name
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception batch fetching vendor names: ${e.message}")
            }
        }
        return result
    }

    // Fetch a single vendor's display name with caching
    suspend fun getVendorName(vendorId: String): Result<String?> {
        val cached = cachedVendorNames[vendorId]
        if (cached != null) return Result.success(cached)
        return try {
            val response = api.getVendorById(idQuery = "eq.$vendorId")
            val name = response.body()?.firstOrNull()?.name
            if (name != null) {
                cachedVendorNames[vendorId] = name
            }
            Result.success(name)
        } catch (e: Exception) {
            Result.success(null)
        }
    }

    suspend fun getVendor(vendorId: String): Result<Vendor?> {
        return try {
            val response = api.getVendorById(idQuery = "eq.$vendorId")
            Result.success(response.body()?.firstOrNull())
        } catch (e: Exception) {
            Result.success(null)
        }
    }

    // --- PRODUCTS & CITY STOCK RESOLUTION ---
    // Rule 1: Grocery product grid: 30 at a time.
    // Rule 2: Selective column fetching.
    // Rule 3: Batch fetch city stock overrides in ONE single request using .in().
    suspend fun getResolvedGroceryProducts(
        cityId: String,
        categoryId: String? = null,
        searchQuery: String? = null,
        limit: Int = 30,
        offset: Int = 0
    ): Result<List<ResolvedProduct>> {
        if (!SupabaseClient.isKeyConfigured()) {
            val demo = getFallbackGroceryProducts(categoryId, searchQuery)
            val paged = demo.drop(offset).take(limit)
            return Result.success(paged)
        }
        return try {
            // When searching, look across ALL city groceries (ignore category) using
            // name=ilike.*query*; otherwise filter by the selected category.
            val searching = !searchQuery.isNullOrBlank()
            val catQuery = if (searching) null else categoryId?.let { "eq.$it" }
            val nameQuery = searchQuery?.takeIf { it.isNotBlank() }?.let {
                "ilike.*" + java.net.URLEncoder.encode(it.trim(), "UTF-8").replace("+", "%20") + "*"
            }
            val prodResponse = api.getProducts(
                isActive = null,
                categoryId = catQuery,
                vendorId = "is.null",
                name = nameQuery,
                select = "id,category_id,vendor_id,name,description,image_url,price,mrp,unit,stock_qty,is_available,is_active,is_featured," +
                    "product_variants(id,label,is_active,product_variant_city_stock(city_id,price,stock_qty,is_available))",
                limit = limit,
                offset = offset
            )
            if (!prodResponse.isSuccessful || prodResponse.body() == null) {
                val error = SupabaseClient.parseErrorMessage(prodResponse)
                Log.w(TAG, "Could not fetch products: $error")
                if (prodResponse.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    return Result.failure(SessionExpiredException("Your session expired, please log in again"))
                }
                return Result.failure(ApiException(prodResponse.code(), error))
            }
            val products = prodResponse.body()!!

            // Rule 3: Single batch request for ONLY the products returned on this page
            val productIds = products.map { it.id }.filter { it.isNotBlank() }
            val stockMap = if (productIds.isNotEmpty()) {
                val stockResponse = api.getProductCityStockBatch(
                    cityId = "eq.$cityId",
                    productIdsQuery = "in.(${productIds.joinToString(",")})",
                    select = "product_id,price,mrp,stock_qty,is_available"
                )
                if (stockResponse.isSuccessful && stockResponse.body() != null) {
                    stockResponse.body()!!.associateBy { it.productId }
                } else {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

            val resolved = products.map { prod ->
                val override = stockMap[prod.id]
                val price = override?.price ?: prod.price
                val mrp = override?.mrp ?: prod.mrp
                val stock = override?.stockQty ?: (prod.stockQty ?: (prod.stockQuantity ?: 0))
                val isAvail = override?.isAvailable ?: prod.isAvailable
                ResolvedProduct(
                    baseProduct = prod,
                    effectivePrice = price,
                    effectiveMrp = mrp,
                    effectiveStock = stock,
                    effectiveIsAvailable = isAvail,
                    variants = resolveVariantsForCity(prod.variants, cityId)
                )
            }.sortedWith(
                compareByDescending<ResolvedProduct> { it.isInStockAndActive }
                    .thenBy { it.name.lowercase() }
            )
            Result.success(resolved)
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching grocery products: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Resolves a product's variants down to the given city's price/stock, filtering out
    // inactive variants and sorting by price ascending (cheapest first, for the picker).
    private fun resolveVariantsForCity(variants: List<ProductVariant>?, cityId: String): List<ResolvedVariant> {
        if (variants.isNullOrEmpty()) return emptyList()
        return variants
            .filter { it.isActive }
            .mapNotNull { variant ->
                val cityStock = variant.cityStock?.firstOrNull { it.cityId == cityId } ?: return@mapNotNull null
                ResolvedVariant(
                    id = variant.id,
                    label = variant.label,
                    price = cityStock.price,
                    stock = cityStock.stockQty ?: 0,
                    isAvailable = cityStock.isAvailable
                )
            }
            .sortedBy { it.price }
    }

    // Rule 1: Hotel menu items are paginated (30 at a time), like the grocery grid, so a
    // hotel with a large menu doesn't load its entire catalog on open. Categories are
    // fetched server-side scoped to this vendor only (not the whole categories table) and
    // only on the first page — they don't change across pages.
    suspend fun getHotelMenu(
        vendorId: String,
        cityId: String,
        limit: Int = 30,
        offset: Int = 0
    ): Result<Pair<List<Category>, List<ResolvedProduct>>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(getFallbackHotelMenu(vendorId))
        }
        return try {
            // Rule 4: Run independent category and product requests in parallel
            val (categories, products) = kotlinx.coroutines.coroutineScope {
                val catDeferred = async {
                    if (offset == 0) api.getCategories(vendorId = "eq.$vendorId", order = "sort_order.asc") else null
                }
                val prodDeferred = async {
                    api.getProducts(
                        isActive = null,
                        vendorId = "eq.$vendorId",
                        select = "id,category_id,vendor_id,name,description,image_url,price,mrp,unit,stock_qty,is_available,is_active,is_featured",
                        limit = limit,
                        offset = offset
                    )
                }
                val catResponse = catDeferred.await()
                val cats = if (catResponse != null) {
                    if (!catResponse.isSuccessful) {
                        val error = SupabaseClient.parseErrorMessage(catResponse)
                        Log.w(TAG, "Could not fetch hotel categories: $error")
                        if (catResponse.code() == 401) {
                            sessionManager.notifySessionExpired("Your session expired, please log in again")
                            throw SessionExpiredException("Your session expired, please log in again")
                        }
                        throw ApiException(catResponse.code(), error)
                    }
                    catResponse.body() ?: emptyList()
                } else {
                    emptyList()
                }

                val prodResponse = prodDeferred.await()
                if (!prodResponse.isSuccessful || prodResponse.body() == null) {
                    val error = SupabaseClient.parseErrorMessage(prodResponse)
                    Log.w(TAG, "Could not fetch hotel products: $error")
                    if (prodResponse.code() == 401) {
                        sessionManager.notifySessionExpired("Your session expired, please log in again")
                        throw SessionExpiredException("Your session expired, please log in again")
                    }
                    throw ApiException(prodResponse.code(), error)
                }
                val prods = prodResponse.body()!!
                Pair(cats, prods)
            }

            // Rule 3: Single batch request for products of this hotel
            val productIds = products.map { it.id }.filter { it.isNotBlank() }
            val stockMap = if (productIds.isNotEmpty()) {
                val stockResponse = api.getProductCityStockBatch(
                    cityId = "eq.$cityId",
                    productIdsQuery = "in.(${productIds.joinToString(",")})",
                    select = "product_id,price,mrp,stock_qty,is_available"
                )
                if (stockResponse.isSuccessful && stockResponse.body() != null) {
                    stockResponse.body()!!.associateBy { it.productId }
                } else {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

            val resolved = products.map { prod ->
                val override = stockMap[prod.id]
                val price = override?.price ?: prod.price
                val mrp = override?.mrp ?: prod.mrp
                val stock = override?.stockQty ?: (prod.stockQty ?: (prod.stockQuantity ?: 0))
                val isAvail = override?.isAvailable ?: prod.isAvailable
                ResolvedProduct(
                    baseProduct = prod,
                    effectivePrice = price,
                    effectiveMrp = mrp,
                    effectiveStock = stock,
                    effectiveIsAvailable = isAvail
                )
            }.sortedWith(
                compareBy<ResolvedProduct> { prod ->
                    val isAvail = prod.isHotelItemAvailable
                    when {
                        isAvail && prod.isFeatured -> 0
                        isAvail -> 1
                        else -> 2
                    }
                }.thenBy { it.name.lowercase() }
            )

            Result.success(Pair(categories, resolved))
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching hotel menu", e)
            Result.failure(e)
        }
    }

    // --- FRESH PRICING FOR CART ITEMS ---
    // Rule: Every render: re-fetch current price per item fresh — never trust a previously-fetched price.
    // Rule 3 & 4: Batch fetch products and city stock overrides in parallel instead of looping N times!
    suspend fun getFreshCartItems(isHotel: Boolean, cityId: String): Result<List<CartItemUi>> {
        return try {
            val rawCart = if (isHotel) _hotelCart.value else _groceryCart.value
            if (rawCart.isEmpty()) {
                return Result.success(emptyList())
            }

            val productIds = rawCart.map { it.productId }.filter { it.isNotBlank() }.distinct()
            if (productIds.isEmpty()) {
                return Result.success(emptyList())
            }

            val (productsMap, stockMap) = kotlinx.coroutines.coroutineScope {
                val prodDeferred = async {
                    api.getProductsByIds(
                        idInQuery = "in.(${productIds.joinToString(",")})",
                        select = "id,category_id,vendor_id,name,description,image_url,price,mrp,unit,stock_qty,is_available,is_active,is_featured," +
                            "product_variants(id,label,is_active,product_variant_city_stock(city_id,price,stock_qty,is_available))"
                    )
                }
                val stockDeferred = async {
                    api.getProductCityStockBatch(
                        cityId = "eq.$cityId",
                        productIdsQuery = "in.(${productIds.joinToString(",")})",
                        select = "product_id,price,mrp,stock_qty,is_available"
                    )
                }
                val pRes = prodDeferred.await()
                val sRes = stockDeferred.await()
                val pMap = if (pRes.isSuccessful && pRes.body() != null) {
                    pRes.body()!!.associateBy { it.id }
                } else emptyMap()
                val sMap = if (sRes.isSuccessful && sRes.body() != null) {
                    sRes.body()!!.associateBy { it.productId }
                } else emptyMap()
                Pair(pMap, sMap)
            }

            val list = mutableListOf<CartItemUi>()
            for (item in rawCart) {
                var resolvedProd: ResolvedProduct? = null
                val prod = productsMap[item.productId]
                if (prod != null) {
                    val override = stockMap[prod.id]
                    val price = override?.price ?: prod.price
                    val mrp = override?.mrp ?: prod.mrp
                    val stock = override?.stockQty ?: (prod.stockQty ?: (prod.stockQuantity ?: 0))
                    val isAvail = override?.isAvailable ?: prod.isAvailable
                    resolvedProd = ResolvedProduct(
                        baseProduct = prod,
                        effectivePrice = price,
                        effectiveMrp = mrp,
                        effectiveStock = stock,
                        effectiveIsAvailable = isAvail,
                        variants = resolveVariantsForCity(prod.variants, cityId)
                    )
                }

                if (resolvedProd == null) {
                    resolvedProd = findFallbackProduct(item.productId)
                }

                if (resolvedProd != null) {
                    val matchedVariant = item.variantId?.let { vId ->
                        resolvedProd.variants.firstOrNull { it.id == vId }
                    }
                    list.add(CartItemUi(cartItem = item, product = resolvedProd, variant = matchedVariant))
                }
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting fresh cart items", e)
            val cartSnapshot = if (isHotel) _hotelCart.value else _groceryCart.value
            val fallbackList = cartSnapshot.mapNotNull { item ->
                findFallbackProduct(item.productId)?.let { CartItemUi(cartItem = item, product = it) }
            }
            Result.success(fallbackList)
        }
    }

    // --- CART BACKEND SYNC ---
    // The in-memory StateFlows are the UI source of truth. When a Supabase key is
    // configured we mirror the cart to the cart_items table (so it survives across
    // sessions / is visible to the backend) and rehydrate it on session start.
    // Prices are NEVER stored in cart_items — they are always re-fetched fresh
    // (see getFreshCartItems); cart_items only holds user_id/product_id/variant_id/
    // vendor_id/city_id/quantity.
    private fun persistCartToBackend() {
        if (!SupabaseClient.isKeyConfigured()) return
        val userId = sessionManager.userId.value ?: return
        val snapshot = _groceryCart.value + _hotelCart.value
        persistJob?.cancel()
        persistJob = cartSyncScope.launch {
            try {
                delay(300) // coalesce rapid stepper taps into one write
                api.clearCartForUser(userIdQuery = "eq.$userId")
                for (item in snapshot) {
                    api.insertCartItem(item.copy(id = null))
                }
            } catch (e: CancellationException) {
                // Debounce cancellation when user rapidly updates cart - normal lifecycle, ignore
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist cart to backend: ${e.message}")
            }
        }
    }

    suspend fun syncCartFromBackend() {
        if (!SupabaseClient.isKeyConfigured()) return
        val userId = sessionManager.userId.value ?: return
        try {
            val res = api.getCartItems(userId = "eq.$userId")
            if (res.isSuccessful && res.body() != null) {
                val items = res.body()!!
                _groceryCart.value = items.filter { it.vendorId == null }
                _hotelCart.value = items.filter { it.vendorId != null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync cart from backend", e)
        }
    }

    // --- CART OPERATIONS ---
    // A distinct (productId, variantId) pair is its own cart line — e.g. 500g and 1kg of
    // the same grocery product are always separate lines with separate quantities.
    fun addToCart(
        productId: String,
        vendorId: String?,
        cityId: String?,
        quantityDelta: Int = 1,
        isHotel: Boolean,
        variantId: String? = null
    ): AddToCartResult {
        val currentUserId = sessionManager.userId.value ?: "guest"
        if (isHotel) {
            val currentList = _hotelCart.value
            val existingVendor = currentList.firstOrNull { it.vendorId != null }?.vendorId
            if (existingVendor != null && vendorId != null && existingVendor != vendorId) {
                // Single-hotel rule conflict!
                val pending = CartItem(
                    id = UUID.randomUUID().toString(),
                    userId = currentUserId,
                    productId = productId,
                    variantId = variantId,
                    vendorId = vendorId,
                    cityId = cityId,
                    quantity = quantityDelta
                )
                return AddToCartResult.HotelConflict(existingVendor, vendorId, pending)
            }
            val existing = currentList.find { it.productId == productId && it.variantId == variantId }
            if (existing != null) {
                val newQty = existing.quantity + quantityDelta
                if (newQty <= 0) {
                    _hotelCart.value = currentList.filter { it != existing }
                } else {
                    _hotelCart.value = currentList.map {
                        if (it == existing) it.copy(quantity = newQty) else it
                    }
                }
            } else if (quantityDelta > 0) {
                val newItem = CartItem(
                    id = UUID.randomUUID().toString(),
                    userId = currentUserId,
                    productId = productId,
                    variantId = variantId,
                    vendorId = vendorId,
                    cityId = cityId,
                    quantity = quantityDelta
                )
                _hotelCart.value = currentList + newItem
            }
        } else {
            val currentList = _groceryCart.value
            val existing = currentList.find { it.productId == productId && it.variantId == variantId }
            if (existing != null) {
                val newQty = existing.quantity + quantityDelta
                if (newQty <= 0) {
                    _groceryCart.value = currentList.filter { it != existing }
                } else {
                    _groceryCart.value = currentList.map {
                        if (it == existing) it.copy(quantity = newQty) else it
                    }
                }
            } else if (quantityDelta > 0) {
                val newItem = CartItem(
                    id = UUID.randomUUID().toString(),
                    userId = currentUserId,
                    productId = productId,
                    variantId = variantId,
                    vendorId = null,
                    cityId = cityId,
                    quantity = quantityDelta
                )
                _groceryCart.value = currentList + newItem
            }
        }
        persistCartToBackend()
        return AddToCartResult.Success
    }

    fun forceClearHotelCartAndAdd(item: CartItem) {
        _hotelCart.value = listOf(item)
        persistCartToBackend()
    }

    fun updateCartItemQuantity(productId: String, isHotel: Boolean, newQty: Int, variantId: String? = null) {
        if (isHotel) {
            val current = _hotelCart.value
            if (newQty <= 0) {
                _hotelCart.value = current.filter { !(it.productId == productId && it.variantId == variantId) }
            } else {
                _hotelCart.value = current.map {
                    if (it.productId == productId && it.variantId == variantId) it.copy(quantity = newQty) else it
                }
            }
        } else {
            val current = _groceryCart.value
            if (newQty <= 0) {
                _groceryCart.value = current.filter { !(it.productId == productId && it.variantId == variantId) }
            } else {
                _groceryCart.value = current.map {
                    if (it.productId == productId && it.variantId == variantId) it.copy(quantity = newQty) else it
                }
            }
        }
        persistCartToBackend()
    }

    fun clearCart(isHotel: Boolean) {
        if (isHotel) {
            _hotelCart.value = emptyList()
        } else {
            _groceryCart.value = emptyList()
        }
        persistCartToBackend()
    }

    suspend fun clearCartDirectly(isHotel: Boolean) {
        if (isHotel) {
            _hotelCart.value = emptyList()
        } else {
            _groceryCart.value = emptyList()
        }
        val userId = sessionManager.userId.value ?: return
        if (SupabaseClient.isKeyConfigured()) {
            try {
                val remaining = _groceryCart.value + _hotelCart.value
                api.clearCartForUser(userIdQuery = "eq.$userId")
                for (item in remaining) {
                    api.insertCartItem(item.copy(id = null))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear cart items on backend: ${e.message}")
            }
        }
    }

    fun clearAllCarts() {
        _groceryCart.value = emptyList()
        _hotelCart.value = emptyList()
        persistCartToBackend()
    }

    fun getCartCount(): Int {
        val g = _groceryCart.value.sumOf { it.quantity }
        val h = _hotelCart.value.sumOf { it.quantity }
        return g + h
    }

    // --- DELIVERY SLOTS & COUPONS ---
    // Fallback slot configurations matching the city's delivery slot setup if backend table query lacks select permissions
    fun getCityDefaultSlots(cityId: String): List<DeliverySlot> {
        val cleanCityId = cityId.removePrefix("eq.")
        return listOf(
            DeliverySlot(
                id = "b0000001-0000-0000-0000-000000000001",
                cityId = cleanCityId,
                name = "Morning (9 AM - 12 PM)",
                startTime = "09:00",
                endTime = "12:00",
                start = "09:00",
                end = "12:00",
                minOrderAmount = 0.0,
                isFreeDelivery = false,
                deliveryFee = 30.0,
                isActive = true
            ),
            DeliverySlot(
                id = "b0000001-0000-0000-0000-000000000002",
                cityId = cleanCityId,
                name = "Afternoon (12 PM - 4 PM)",
                startTime = "12:00",
                endTime = "16:00",
                start = "12:00",
                end = "16:00",
                minOrderAmount = 0.0,
                isFreeDelivery = false,
                deliveryFee = 30.0,
                isActive = true
            ),
            DeliverySlot(
                id = "b0000001-0000-0000-0000-000000000003",
                cityId = cleanCityId,
                name = "Evening (4 PM - 9 PM)",
                startTime = "16:00",
                endTime = "21:00",
                start = "16:00",
                end = "21:00",
                minOrderAmount = 199.0,
                isFreeDelivery = true,
                deliveryFee = 30.0,
                isActive = true
            )
        )
    }

    suspend fun getDeliverySlots(cityId: String): Result<List<DeliverySlot>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(getCityDefaultSlots(cityId))
        }
        return try {
            val queryCityId = if (cityId.startsWith("eq.")) cityId else "eq.$cityId"
            val response = api.getDeliverySlots(cityId = queryCityId, order = "start_time.asc")
            if (response.isSuccessful && response.body() != null) {
                val slots = response.body()!!.filter { it.isActive != false }
                Result.success(slots)
            } else {
                val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                Log.w(TAG, "Delivery slots unavailable from backend ($err)")
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching delivery slots: ${e.message}")
            Result.success(emptyList())
        }
    }

    suspend fun getExpressDeliverySettings(cityId: String): Result<ExpressDeliverySettings?> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(null)
        }
        return try {
            val queryCityId = if (cityId.startsWith("eq.")) cityId else "eq.$cityId"
            val response = api.getExpressDeliverySettings(cityId = queryCityId)
            if (response.isSuccessful && response.body() != null) {
                val settings = response.body()!!.firstOrNull { it.isActive }
                Result.success(settings)
            } else {
                val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                Log.w(TAG, "Express delivery settings unavailable: $err")
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching express delivery settings: ${e.message}", e)
            Result.success(null)
        }
    }

    suspend fun getCity(cityId: String): Result<City?> {
        val cleanId = cityId.removePrefix("eq.")
        val cached = getCities().getOrNull()?.find { it.id == cleanId }
        if (cached != null && cached.centerLat != null && cached.centerLng != null) {
            return Result.success(cached)
        }
        if (!SupabaseClient.isKeyConfigured()) return Result.success(cached)
        return try {
            val response = api.getCityById(idQuery = "eq.$cleanId")
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                Result.success(response.body()!!.first())
            } else {
                Result.success(cached)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception fetching city $cleanId: ${e.message}", e)
            Result.success(cached)
        }
    }

    /**
     * Resolves the pickup point according to the Express Delivery distance calculation rule:
     * - Hotel/food orders (vendor_id is set): measure distance from the hotel's own location (vendors.latitude, vendors.longitude).
     * - Grocery orders (vendor_id is null): measure distance from the city's center point (cities.center_lat, cities.center_lng for the customer's city_id).
     *   Treat this as the city's virtual dispatch hub, since grocery items aren't tied to one physical store.
     */
    suspend fun getPickupPoint(vendorId: String?, cityId: String?): PickupPoint? {
        val cleanVendorId = vendorId?.removePrefix("eq.")?.takeIf { it.isNotBlank() }
        if (!cleanVendorId.isNullOrBlank()) {
            val vendorRes = getVendor(cleanVendorId)
            val vendor = vendorRes.getOrNull()
            if (vendor?.latitude != null && vendor.longitude != null) {
                return PickupPoint(lat = vendor.latitude, lng = vendor.longitude)
            }
        }

        val cleanCityId = cityId?.removePrefix("eq.")?.takeIf { it.isNotBlank() }
        if (!cleanCityId.isNullOrBlank()) {
            val cityRes = getCity(cleanCityId)
            val city = cityRes.getOrNull()
            if (city?.centerLat != null && city.centerLng != null) {
                return PickupPoint(lat = city.centerLat, lng = city.centerLng)
            }
        }

        return null
    }

    suspend fun getPickupPoint(order: Order): PickupPoint? {
        return getPickupPoint(vendorId = order.vendorId, cityId = order.cityId)
    }

    suspend fun resolveDeliveryDistanceKm(
        address: CustomerAddress?,
        cityId: String?,
        vendorId: String?,
        isHotel: Boolean
    ): Double {
        if (address == null || address.lat == null || address.lng == null) {
            return 1.0
        }
        val userLat = address.lat
        val userLng = address.lng

        // Authoritative pickup point resolution:
        // Hotel/food orders (vendor_id is set): measure distance from hotel's own location
        // Grocery orders (vendor_id is null): measure distance from city's center point (virtual dispatch hub)
        val effectiveVendorId = if (isHotel) vendorId?.takeIf { it.isNotBlank() } else null
        val pickup = getPickupPoint(vendorId = effectiveVendorId, cityId = cityId)
        if (pickup != null) {
            val dist = calculateHaversineDistanceKm(userLat, userLng, pickup.lat, pickup.lng)
            if (dist > 0.0) {
                return dist
            }
        }

        // Fallback to RPC findCityAndDistanceForLocation if available
        val locRes = findCityAndDistanceForLocation(userLat, userLng).getOrNull()
        if (locRes != null && locRes.distanceKm != null && locRes.distanceKm > 0.0) {
            return locRes.distanceKm
        }

        // Fallback to regional hub coordinates (e.g. Sindhanur fulfillment warehouse: 15.7667, 76.7583)
        val cleanCityId = cityId?.removePrefix("eq.")
        val cities = getCities().getOrNull() ?: emptyList()
        val city = cities.find { it.id == cleanCityId } ?: cities.firstOrNull()
        if (city != null && city.centerLat != null && city.centerLng != null) {
            return calculateHaversineDistanceKm(userLat, userLng, city.centerLat, city.centerLng)
        }

        val knownCoords = mapOf(
            "sindhanur" to Pair(15.7667, 76.7583),
            "raichur" to Pair(16.2120, 77.3439),
            "bellary" to Pair(15.1394, 76.9214),
            "ballari" to Pair(15.1394, 76.9214),
            "gangavathi" to Pair(15.4326, 76.5312),
            "manvi" to Pair(15.9922, 77.0506),
            "koppal" to Pair(15.3524, 76.1557)
        )
        val cityName = city?.name?.trim()?.lowercase(java.util.Locale.ROOT) ?: "sindhanur"
        val hubCoords = knownCoords[cityName] ?: knownCoords["sindhanur"]
        if (hubCoords != null) {
            return calculateHaversineDistanceKm(userLat, userLng, hubCoords.first, hubCoords.second)
        }

        return 1.0
    }

    suspend fun getCustomerDeliveryOptions(cityId: String): Result<CustomerDeliveryOptions> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Supabase API key is not configured"))
        }
        return try {
            val cleanCityId = if (cityId.startsWith("eq.")) cityId.removePrefix("eq.") else cityId
            val response = api.getCustomerDeliveryOptions(mapOf("p_city_id" to cleanCityId))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "Failed to get customer delivery options: $err")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception getting customer delivery options: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun calculateCityDeliveryCharge(
        cityId: String,
        deliveryType: String,
        distanceKm: Double,
        orderAmount: Double
    ): Result<DeliveryChargeResult> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.failure(Exception("Supabase API key is not configured"))
        }
        return try {
            val cleanCityId = if (cityId.startsWith("eq.")) cityId.removePrefix("eq.") else cityId
            val payload = mapOf(
                "p_city_id" to cleanCityId,
                "p_delivery_type" to deliveryType,
                "p_distance_km" to distanceKm,
                "p_order_amount" to orderAmount
            )
            val response = api.calculateCityDeliveryCharge(payload)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = SupabaseClient.parseErrorMessage(response)
                Log.w(TAG, "calculate_city_delivery_charge failed: $err")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception in calculate_city_delivery_charge: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun validateAndApplyCoupon(
        code: String,
        cityId: String,
        subtotal: Double,
        userId: String?
    ): Result<CouponValidationResult> {
        val cleanCityId = if (cityId.startsWith("eq.")) cityId.removePrefix("eq.") else cityId
        val trimmedCode = code.trim().uppercase()
        if (trimmedCode.isBlank()) {
            return Result.success(CouponValidationResult(isValid = false, errorMessage = "Please enter a coupon code"))
        }

        // 1. Authoritative check with Supabase coupons table
        return try {
            val response = api.getCoupons(cityId = "eq.$cleanCityId", code = "eq.$trimmedCode")
            val coupon = response.body()?.firstOrNull()
            if (coupon == null) {
                return Result.success(
                    CouponValidationResult(
                        isValid = false,
                        errorMessage = "Invalid or inactive coupon for this city"
                    )
                )
            }

            if (!coupon.isActive) {
                return Result.success(
                    CouponValidationResult(isValid = false, coupon = coupon, errorMessage = "This coupon is no longer active")
                )
            }

            val now = System.currentTimeMillis()
            val starts = parseIsoTime(coupon.startsAt)
            if (starts != null && now < starts) {
                return Result.success(
                    CouponValidationResult(isValid = false, coupon = coupon, errorMessage = "Coupon is not active yet")
                )
            }

            val expires = parseIsoTime(coupon.expiresAt)
            if (expires != null && now > expires) {
                return Result.success(
                    CouponValidationResult(isValid = false, coupon = coupon, errorMessage = "Coupon has expired")
                )
            }

            val minOrder = coupon.minOrderAmount ?: 0.0
            if (subtotal < minOrder) {
                return Result.success(
                    CouponValidationResult(
                        isValid = false,
                        coupon = coupon,
                        errorMessage = "Minimum order of ₹${"%.0f".format(minOrder)} required for this coupon"
                    )
                )
            }

            val limit = coupon.usageLimit
            if (limit != null && (coupon.usedCount ?: 0) >= limit) {
                return Result.success(
                    CouponValidationResult(isValid = false, coupon = coupon, errorMessage = "Coupon usage limit reached")
                )
            }

            var discount = if (coupon.discountType == "percentage") {
                (subtotal * coupon.discountValue) / 100.0
            } else {
                coupon.discountValue
            }
            if (coupon.maxDiscountAmount != null && discount > coupon.maxDiscountAmount) {
                discount = coupon.maxDiscountAmount
            }
            discount = discount.coerceAtMost(subtotal)

            Result.success(
                CouponValidationResult(
                    isValid = true,
                    coupon = coupon,
                    discountAmount = discount
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Coupon validation failed", e)
            Result.failure(e)
        }
    }

    suspend fun getCoupons(cityId: String): Result<List<Coupon>> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(DemoCatalog.COUPONS)
        }
        return try {
            val response = api.getCoupons(cityId = "eq.$cityId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(DemoCatalog.COUPONS)
            }
        } catch (e: Exception) {
            Result.success(DemoCatalog.COUPONS)
        }
    }

    // Look up a single active coupon by code for a city (server-side filter).
    suspend fun getCouponByCode(code: String, cityId: String): Result<Coupon?> {
        if (!SupabaseClient.isKeyConfigured()) {
            return Result.success(DemoCatalog.COUPONS.find { it.code.equals(code, ignoreCase = true) })
        }
        return try {
            val response = api.getCoupons(cityId = "eq.$cityId", code = "eq.$code")
            val remote = response.body()?.firstOrNull()
            Result.success(remote ?: DemoCatalog.COUPONS.find { it.code.equals(code, ignoreCase = true) })
        } catch (e: Exception) {
            Result.success(DemoCatalog.COUPONS.find { it.code.equals(code, ignoreCase = true) })
        }
    }

    // Full client-side validation before applying/using a coupon. Returns an error
    // message when invalid, or null when the coupon may be applied.
    fun validateCoupon(coupon: Coupon, subtotal: Double): String? {
        val now = System.currentTimeMillis()
        val starts = parseIsoTime(coupon.startsAt)
        if (starts != null && now < starts) return "Coupon is not active yet"
        val expires = parseIsoTime(coupon.expiresAt)
        if (expires != null && now > expires) return "Coupon has expired"
        val limit = coupon.usageLimit
        if (limit != null && (coupon.usedCount ?: 0) >= limit) return "Coupon usage limit reached"
        val min = coupon.minOrderAmount ?: 0.0
        if (subtotal < min) return "Minimum order ₹${"%.0f".format(min)} required"
        return null
    }

    private fun parseIsoTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            val core = if (s.length >= 19) s.substring(0, 19) else s
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.parse(core)?.time
        } catch (e: Exception) { null }
    }

    suspend fun recordCouponUsage(couponId: String, userId: String, orderId: String) {
        if (!SupabaseClient.isKeyConfigured()) return
        try {
            api.insertCouponUsage(CouponUsage(couponId = couponId, userId = userId, orderId = orderId))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record coupon usage", e)
        }
    }

    // --- CUSTOMER ADDRESSES ---
    suspend fun getAddresses(userId: String): Result<List<CustomerAddress>> {
        return try {
            val response = api.getAddresses(userId = "eq.$userId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else if (response.code() == 401) {
                sessionManager.notifySessionExpired("Your session expired, please log in again")
                Result.failure(SessionExpiredException("Your session expired, please log in again"))
            } else {
                Result.success(_localAddresses.value)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAddressById(addressId: String): Result<CustomerAddress?> {
        return try {
            val response = api.getAddressById(idQuery = "eq.$addressId")
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                Result.success(response.body()!!.first())
            } else {
                Result.success(_localAddresses.value.find { it.id == addressId })
            }
        } catch (e: Exception) {
            Result.success(_localAddresses.value.find { it.id == addressId })
        }
    }

    suspend fun addAddress(address: CustomerAddress): Result<CustomerAddress> {
        return try {
            val response = api.insertAddress(address)
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                val saved = response.body()!!.first()
                _localAddresses.value = _localAddresses.value + saved
                Result.success(saved)
            } else if (response.isSuccessful) {
                _localAddresses.value = _localAddresses.value + address
                Result.success(address)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "insertAddress failed HTTP ${response.code()}: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception adding address: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun deleteAddress(id: String): Result<Unit> {
        return try {
            val response = api.deleteAddress(idQuery = "eq.$id")
            _localAddresses.value = _localAddresses.value.filter { it.id != id }
            Result.success(Unit)
        } catch (e: Exception) {
            _localAddresses.value = _localAddresses.value.filter { it.id != id }
            Result.success(Unit)
        }
    }

    // Edit an existing address.
    suspend fun updateAddress(id: String, fields: Map<String, Any?>): Result<Unit> {
        return try {
            val response = api.updateAddress(idQuery = "eq.$id", body = fields)
            if (response.isSuccessful) {
                response.body()?.firstOrNull()?.let { updated ->
                    _localAddresses.value = _localAddresses.value.map { if (it.id == id) updated else it }
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Set one address as default: unset the previous default first so only one stays true.
    suspend fun setDefaultAddress(userId: String, addressId: String): Result<Unit> {
        return try {
            val res = api.getAddresses(userId = "eq.$userId")
            val current = res.body().orEmpty()
            // 1. Unset any existing default (other than the one being promoted).
            current.filter { it.isDefault && it.id != addressId }.forEach { prev ->
                prev.id?.let { api.updateAddress(idQuery = "eq.$it", body = mapOf("is_default" to false)) }
            }
            // 2. Set the chosen address as default.
            api.updateAddress(idQuery = "eq.$addressId", body = mapOf("is_default" to true))
            _localAddresses.value = _localAddresses.value.map {
                it.copy(isDefault = (it.id == addressId))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- SAFE CONCURRENT CHECKOUT & ORDER PLACEMENT ---
    // Direct customer inserts into `orders` or `order_items` are strictly disabled per RLS security policy.
    // Instead, backend-authoritative RPC functions (checkout_grocery_order / checkout_food_order)
    // are used exclusively. They compute all pricing, totals, and commission atomically on the database.
    suspend fun placeOrder(
        userId: String,
        isHotel: Boolean,
        vendorId: String?,
        cityId: String,
        addressId: String,
        slotId: String?,
        paymentMethod: String,
        coupon: Coupon? = null,
        deliveryType: String = "free_slot",
        deliveryDistanceKm: Double? = null,
        deliveryOptionSnapshot: Map<String, Any?>? = null
    ): Result<Order> {
        return try {
            val rawCart = (if (isHotel) _hotelCart.value else _groceryCart.value).filter { it.quantity > 0 }
            if (rawCart.isEmpty()) {
                return Result.failure(Exception("Cart is empty"))
            }

            val cleanAddressId = addressId.removePrefix("eq.").trim()
            if (cleanAddressId.isBlank()) {
                return Result.failure(Exception("Please select a delivery address"))
            }

            val mappedPaymentMethod = when (paymentMethod.lowercase().trim()) {
                "cod", "cash" -> "cash"
                "upi" -> "upi"
                "card" -> "card"
                "online" -> "online"
                else -> "cash"
            }

            // Demo fallback if Supabase key is unconfigured
            if (!SupabaseClient.isKeyConfigured()) {
                val demoOrder = Order(
                    id = "demo-order-${System.currentTimeMillis()}",
                    orderNumber = "SND-DEMO-${System.currentTimeMillis().toString().takeLast(6)}",
                    customerId = userId,
                    vendorId = vendorId,
                    addressId = cleanAddressId,
                    paymentMethod = mappedPaymentMethod,
                    status = "confirmed"
                )
                clearCartDirectly(isHotel)
                return Result.success(demoOrder)
            }

            // Re-check maintenance mode right before checkout
            val maintenance = checkMaintenanceMode()
            if (maintenance.enabled) {
                val msg = maintenance.message ?: "Service temporarily unavailable. Please try again shortly."
                return Result.failure(Exception(msg))
            }

            // Build p_items array: ONLY product_id, variant_id (or null), and quantity
            val itemsArray = JSONArray()
            for (item in rawCart) {
                val itemObj = JSONObject().apply {
                    put("product_id", item.productId)
                    if (!item.variantId.isNullOrBlank()) {
                        put("variant_id", item.variantId)
                    } else {
                        put("variant_id", JSONObject.NULL)
                    }
                    put("quantity", item.quantity)
                }
                itemsArray.put(itemObj)
            }

            val payloadJson = JSONObject().apply {
                if (isHotel) {
                    val effectiveVendorId = vendorId?.takeIf { it.isNotBlank() }
                        ?: rawCart.firstOrNull { !it.vendorId.isNullOrBlank() }?.vendorId
                        ?: return Result.failure(Exception("Hotel / Vendor ID is missing for this order"))
                    put("p_vendor_id", effectiveVendorId)
                }
                put("p_address_id", cleanAddressId)
                put("p_payment_method", mappedPaymentMethod)
                put("p_items", itemsArray)
                put("p_notes", JSONObject.NULL)
            }

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = payloadJson.toString().toRequestBody(mediaType)

            val rpcResult = callCheckoutRpcWithRetry(isHotel, requestBody)
            if (rpcResult.isSuccess) {
                val placedOrder = rpcResult.getOrNull()!!
                // Clear the checked-out cart_items rows directly from backend & memory
                clearCartDirectly(isHotel)

                if (coupon != null && coupon.code.isNotBlank() && !placedOrder.id.isNullOrBlank()) {
                    try {
                        recordCouponUsage(coupon.id, userId, placedOrder.id)
                    } catch (e: Exception) {
                        Log.w(TAG, "Coupon usage recording skipped: ${e.message}")
                    }
                }

                Result.success(placedOrder)
            } else {
                Result.failure(rpcResult.exceptionOrNull() ?: Exception("Failed to place order"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during placeOrder", e)
            Result.failure(e)
        }
    }

    /**
     * Executes the checkout RPC function (checkout_food_order or checkout_grocery_order)
     * with transient network retry. Real errors (e.g. stock, invalid address) return immediately.
     */
    private suspend fun callCheckoutRpcWithRetry(
        isHotel: Boolean,
        requestBody: okhttp3.RequestBody,
        maxRetries: Int = 1
    ): Result<Order> {
        var lastException: Exception = Exception("Checkout failed")

        for (attempt in 0..maxRetries) {
            try {
                val response = if (isHotel) {
                    api.checkoutFoodOrder(requestBody)
                } else {
                    api.checkoutGroceryOrder(requestBody)
                }

                if (response.isSuccessful && response.body() != null) {
                    val bodyStr = response.body()!!.string()
                    val order = parseOrderFromRpcResponse(bodyStr)
                    return Result.success(order)
                } else {
                    val rawError = response.errorBody()?.string().orEmpty()
                    val cleanMessage = extractCleanErrorMessage(rawError, response.code())
                    Log.w(TAG, "Checkout RPC error HTTP ${response.code()}: $cleanMessage (raw: $rawError)")
                    // HTTP errors are authoritative database/validation responses — do not retry
                    return Result.failure(Exception(cleanMessage))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Checkout RPC network exception (attempt $attempt): ${e.message}")
                if (attempt == maxRetries) {
                    return Result.failure(e)
                }
                lastException = e
            }

            delay(400L * (attempt + 1))
        }

        return Result.failure(lastException)
    }

    private fun parseOrderFromRpcResponse(bodyStr: String): Order {
        val trimmed = bodyStr.trim()
        if (trimmed.isEmpty()) {
            return Order(id = "ord-${System.currentTimeMillis()}")
        }

        // Check if response is a plain UUID or quoted UUID string
        val unquoted = trimmed.removeSurrounding("\"").trim()
        if (unquoted.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))) {
            return Order(id = unquoted, status = "confirmed")
        }

        val moshi = SupabaseClient.moshi
        try {
            if (trimmed.startsWith("[")) {
                val listType = com.squareup.moshi.Types.newParameterizedType(List::class.java, Order::class.java)
                val adapter = moshi.adapter<List<Order>>(listType)
                val parsed = adapter.fromJson(trimmed)?.firstOrNull()
                if (parsed != null && !parsed.id.isNullOrBlank()) {
                    return parsed
                }
            } else if (trimmed.startsWith("{")) {
                val adapter = moshi.adapter(Order::class.java)
                val parsed = adapter.fromJson(trimmed)
                if (parsed != null && !parsed.id.isNullOrBlank()) {
                    return parsed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Moshi failed to parse order from RPC response: ${e.message}, parsing with JSONObject")
        }

        return try {
            val obj = if (trimmed.startsWith("[")) {
                JSONArray(trimmed).optJSONObject(0)
            } else if (trimmed.startsWith("{")) {
                JSONObject(trimmed)
            } else null

            if (obj != null) {
                Order(
                    id = obj.optString("id", obj.optString("order_id", unquoted)),
                    orderNumber = obj.optString("order_number", ""),
                    customerId = obj.optString("customer_id", ""),
                    vendorId = if (obj.has("vendor_id") && !obj.isNull("vendor_id")) obj.optString("vendor_id") else null,
                    addressId = if (obj.has("address_id") && !obj.isNull("address_id")) obj.optString("address_id") else null,
                    status = obj.optString("status", "confirmed"),
                    paymentMethod = obj.optString("payment_method", "cash"),
                    paymentStatus = obj.optString("payment_status", "pending"),
                    subtotal = obj.optDouble("subtotal", 0.0),
                    totalAmount = obj.optDouble("total_amount", 0.0),
                    deliveryFee = obj.optDouble("delivery_fee", 0.0),
                    placedAt = if (obj.has("placed_at") && !obj.isNull("placed_at")) obj.optString("placed_at") else null
                )
            } else {
                Order(id = if (unquoted.isNotBlank()) unquoted else "ord-${System.currentTimeMillis()}")
            }
        } catch (e: Exception) {
            Order(id = if (unquoted.isNotBlank()) unquoted else "ord-${System.currentTimeMillis()}")
        }
    }

    private fun extractCleanErrorMessage(errorBody: String, httpCode: Int = 0): String {
        if (errorBody.isBlank()) {
            return if (httpCode > 0) "Server error ($httpCode). Please try again." else "Order placement failed. Please try again."
        }
        return try {
            val json = JSONObject(errorBody)
            val msg = listOf("message", "msg", "error_description", "error", "details")
                .mapNotNull { key ->
                    if (json.has(key) && !json.isNull(key)) {
                        val v = json.optString(key).trim()
                        v.takeIf { it.isNotBlank() && it != "null" }
                    } else null
                }.firstOrNull()

            if (!msg.isNullOrBlank()) msg else errorBody
        } catch (e: Exception) {
            errorBody
        }
    }

    // --- ORDERS (LIST & DETAIL) ---
    suspend fun getOrders(userId: String, limit: Int? = null, offset: Int? = null): Result<List<Order>> {
        return try {
            val response = api.getOrders(customerId = "eq.$userId", order = "placed_at.desc", limit = limit, offset = offset)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else if (response.code() == 401) {
                sessionManager.notifySessionExpired("Your session expired, please log in again")
                Result.failure(SessionExpiredException("Your session expired, please log in again"))
            } else if (response.code() == 400) {
                // If placed_at column is not recognized, fallback to created_at.desc
                val fallback = api.getOrders(customerId = "eq.$userId", order = "created_at.desc", limit = limit, offset = offset)
                if (fallback.isSuccessful && fallback.body() != null) {
                    Result.success(fallback.body()!!)
                } else if (fallback.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    Result.failure(SessionExpiredException("Your session expired, please log in again"))
                } else {
                    Result.failure(Exception("Failed to fetch orders: ${fallback.code()} ${fallback.message()}"))
                }
            } else {
                Result.failure(Exception("Failed to fetch orders: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMyOrders(userId: String, limit: Int? = null, offset: Int? = null): Result<List<Order>> =
        getOrders(userId, limit, offset)

    suspend fun getOrderById(orderId: String): Result<Order> {
        return try {
            val selectJoin = "*,delivery_partners(id,name,phone,latitude,longitude,vehicle_type,vehicle_number)"
            val response = api.getOrderById(idQuery = "eq.$orderId", select = selectJoin)
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                Result.success(response.body()!!.first())
            } else if (response.code() == 401) {
                sessionManager.notifySessionExpired("Your session expired, please log in again")
                Result.failure(SessionExpiredException("Your session expired, please log in again"))
            } else {
                // Fallback without join
                val fallback = api.getOrderById(idQuery = "eq.$orderId")
                if (fallback.isSuccessful && !fallback.body().isNullOrEmpty()) {
                    Result.success(fallback.body()!!.first())
                } else if (fallback.code() == 401) {
                    sessionManager.notifySessionExpired("Your session expired, please log in again")
                    Result.failure(SessionExpiredException("Your session expired, please log in again"))
                } else {
                    Result.failure(Exception("Order not found: $orderId"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOrderItems(orderId: String): Result<List<OrderItem>> {
        return try {
            val response = api.getOrderItems(orderId = "eq.$orderId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOrderStatusHistory(orderId: String): Result<List<OrderStatusHistory>> {
        return try {
            val response = api.getOrderStatusHistory(orderId = "eq.$orderId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDeliveryAssignment(orderId: String): Result<DeliveryAssignment?> {
        return try {
            val selectFields = "status,accepted_at,estimated_delivery_minutes,estimated_delivery_at,delivery_otp,delivery_partner_id,order_id"
            val response = api.getDeliveryAssignment(
                orderId = "eq.$orderId",
                order = "created_at.desc",
                limit = 1,
                select = selectFields
            )
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                Result.success(response.body()!!.first())
            } else {
                val fallback = api.getDeliveryAssignment(orderId = "eq.$orderId", order = "created_at.desc", limit = 1)
                if (fallback.isSuccessful && !fallback.body().isNullOrEmpty()) {
                    Result.success(fallback.body()!!.first())
                } else {
                    Result.success(null)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDeliveryPartner(partnerId: String): Result<DeliveryPartner?> {
        return try {
            val response = api.getDeliveryPartner(partnerId = "eq.$partnerId")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.firstOrNull())
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Reorder: copies order_items back into cart_items for this user (re-check is_active/is_available first,
    // skip unavailable items with a summary message; apply single-hotel-cart rule if relevant)
    // Rule 3 & 4: Batch fetch products and city stock overrides in parallel instead of looping
    suspend fun reorder(orderItems: List<OrderItem>, cityId: String): Result<String> {
        return try {
            val productIds = orderItems.map { it.productId }.filter { it.isNotBlank() }.distinct()
            if (productIds.isEmpty()) {
                return Result.success("No items to reorder.")
            }

            val (productsMap, stockMap) = kotlinx.coroutines.coroutineScope {
                val prodDeferred = async {
                    api.getProductsByIds(
                        idInQuery = "in.(${productIds.joinToString(",")})",
                        select = "id,category_id,vendor_id,name,description,image_url,price,mrp,unit,stock_qty,is_available,is_active,is_featured"
                    )
                }
                val stockDeferred = async {
                    api.getProductCityStockBatch(
                        cityId = "eq.$cityId",
                        productIdsQuery = "in.(${productIds.joinToString(",")})",
                        select = "product_id,price,mrp,stock_qty,is_available"
                    )
                }
                val pRes = prodDeferred.await()
                val sRes = stockDeferred.await()
                val pMap = if (pRes.isSuccessful && pRes.body() != null) {
                    pRes.body()!!.associateBy { it.id }
                } else emptyMap()
                val sMap = if (sRes.isSuccessful && sRes.body() != null) {
                    sRes.body()!!.associateBy { it.productId }
                } else emptyMap()
                Pair(pMap, sMap)
            }

            var addedCount = 0
            val skipped = mutableListOf<String>()

            for (item in orderItems) {
                val prod = productsMap[item.productId]
                if (prod != null) {
                    val override = stockMap[prod.id]
                    val isAvail = override?.isAvailable ?: prod.isAvailable
                    if (prod.isActive && isAvail) {
                        val isHotel = item.vendorId != null
                        val result = addToCart(
                            productId = item.productId,
                            vendorId = item.vendorId,
                            cityId = cityId,
                            quantityDelta = item.quantity,
                            isHotel = isHotel
                        )
                        if (result is AddToCartResult.HotelConflict) {
                            skipped.add("'${item.productName}' conflicts with your current hotel cart.")
                        } else {
                            addedCount++
                        }
                    } else {
                        skipped.add("'${item.productName}' is no longer available.")
                    }
                } else {
                    skipped.add("'${item.productName}' is no longer available.")
                }
            }

            val total = orderItems.size
            val msg = buildString {
                append("$addedCount of $total items added to cart.")
                if (skipped.isNotEmpty()) {
                    append(" ${skipped.joinToString(" ")}")
                }
            }
            Result.success(msg)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- REVIEWS ---
    suspend fun submitVendorReview(review: VendorReview): Result<Unit> {
        return try {
            val response = api.submitVendorReview(review)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitDeliveryPartnerReview(review: DeliveryPartnerReview): Result<Unit> {
        return try {
            val response = api.submitDeliveryPartnerReview(review)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Reviews the customer has written (read-only lists for "My Reviews").
    // Rule 1: Paginate by 20 at a time.
    suspend fun getMyVendorReviews(customerId: String, limit: Int = 20, offset: Int = 0): Result<List<VendorReview>> {
        return try {
            val response = api.getMyReviews(customerId = "eq.$customerId", limit = limit, offset = offset)
            if (response.isSuccessful) Result.success(response.body() ?: emptyList())
            else Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMyDeliveryPartnerReviews(customerId: String, limit: Int = 20, offset: Int = 0): Result<List<DeliveryPartnerReview>> {
        return try {
            val response = api.getMyDeliveryPartnerReviews(customerId = "eq.$customerId", limit = limit, offset = offset)
            if (response.isSuccessful) Result.success(response.body() ?: emptyList())
            else Result.failure(Exception(SupabaseClient.parseErrorMessage(response)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Order ids the customer has already reviewed (vendor + delivery partner), used to
    // decide whether to show the "Rate this order" prompt on a delivered order.
    suspend fun getReviewedOrderIds(customerId: String): Result<Set<String>> {
        return try {
            val ids = mutableSetOf<String>()
            getMyVendorReviews(customerId).getOrNull()?.forEach { it.orderId?.let { id -> ids.add(id) } }
            getMyDeliveryPartnerReviews(customerId).getOrNull()?.forEach { it.orderId?.let { id -> ids.add(id) } }
            Result.success(ids)
        } catch (e: Exception) {
            Result.success(emptySet())
        }
    }

    // --- WALLET (READ-ONLY) ---
    // Rule 1: Paginate by 20 at a time.
    suspend fun getWalletTransactions(customerId: String, limit: Int = 20, offset: Int = 0): Result<List<CustomerWalletTransaction>> {
        return try {
            val response = api.getWalletTransactions(customerId = "eq.$customerId", limit = limit, offset = offset)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- DEVICE TOKENS (PUSH NOTIFICATIONS) ---
    suspend fun registerDeviceToken(userId: String, token: String): Result<Unit> {
        return try {
            val deviceToken = DeviceToken(
                userId = userId,
                userType = "customer",
                fcmToken = token,
                active = true
            )
            val response = api.registerDeviceToken(deviceToken)
            if (response.isSuccessful) {
                Log.d(TAG, "Device token registered successfully for $userId")
                Result.success(Unit)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "Failed to register device token: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception registering device token", e)
            Result.failure(e)
        }
    }

    // --- CUSTOMER NOTIFICATIONS INBOX ---
    // Rule 1: Paginate by 20 at a time.
    suspend fun getCustomerNotifications(userId: String, limit: Int = 20, offset: Int = 0): Result<List<CustomerNotification>> {
        return try {
            val response = api.getCustomerNotifications(userIdQuery = "eq.$userId", limit = limit, offset = offset)
            if (response.isSuccessful) {
                val list = response.body() ?: emptyList()
                val unread = list.count { !it.isRead }
                sessionManager.setUnreadNotificationCount(unread)
                Result.success(list)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Log.e(TAG, "Failed to get customer notifications: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting customer notifications", e)
            Result.failure(e)
        }
    }

    suspend fun markNotificationAsRead(notificationId: String): Result<Unit> {
        return try {
            val response = api.markNotificationAsRead(idQuery = "eq.$notificationId")
            if (response.isSuccessful) {
                sessionManager.decrementUnreadNotificationCount()
                Result.success(Unit)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception marking notification as read", e)
            Result.failure(e)
        }
    }

    suspend fun markAllNotificationsAsRead(userId: String): Result<Unit> {
        return try {
            val response = api.markAllNotificationsAsRead(userIdQuery = "eq.$userId")
            if (response.isSuccessful) {
                sessionManager.setUnreadNotificationCount(0)
                Result.success(Unit)
            } else {
                val error = SupabaseClient.parseErrorMessage(response)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception marking all notifications as read", e)
            Result.failure(e)
        }
    }

    suspend fun refreshUnreadNotificationCount(userId: String) {
        try {
            val response = api.getCustomerNotifications(
                userIdQuery = "eq.$userId",
                limit = 100
            )
            if (response.isSuccessful) {
                val unread = (response.body() ?: emptyList()).count { !it.isRead }
                sessionManager.setUnreadNotificationCount(unread)
            }
        } catch (e: Exception) {
            // Ignored
        }
    }

    // --- STARTUP CHECKS: APP VERSION & MAINTENANCE MODE ---

    fun parseMaintenanceSettings(jsonString: String?): MaintenanceSettings {
        if (jsonString.isNullOrBlank()) return MaintenanceSettings(enabled = false)
        return try {
            val trimmed = jsonString.trim()
            val jsonArray = JSONArray(trimmed)
            if (jsonArray.length() == 0) return MaintenanceSettings(enabled = false)
            val firstObj = jsonArray.getJSONObject(0)
            if (!firstObj.has("value")) return MaintenanceSettings(enabled = false)

            val valueObj = firstObj.optJSONObject("value")
            if (valueObj != null) {
                val enabled = valueObj.optBoolean("enabled", false)
                val message = valueObj.optString("message", null)?.takeIf { it.isNotBlank() && it != "null" }
                MaintenanceSettings(enabled = enabled, message = message)
            } else {
                val boolVal = firstObj.optBoolean("value", false)
                MaintenanceSettings(enabled = boolVal)
            }
        } catch (e: Exception) {
            MaintenanceSettings(enabled = false)
        }
    }

    suspend fun checkMaintenanceMode(): MaintenanceSettings {
        if (!SupabaseClient.isKeyConfigured()) {
            return MaintenanceSettings(enabled = false)
        }
        return try {
            val response = api.getAppSetting(key = "eq.maintenance_mode", limit = 1)
            if (response.isSuccessful && response.body() != null) {
                val str = response.body()!!.string()
                parseMaintenanceSettings(str)
            } else {
                MaintenanceSettings(enabled = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkMaintenanceMode error: ${e.message}")
            MaintenanceSettings(enabled = false)
        }
    }

    suspend fun runStartupChecks(): StartupCheckResult {
        if (!SupabaseClient.isKeyConfigured()) {
            return StartupCheckResult.Passed
        }

        // 1. App version check
        try {
            val versionResponse = api.getAppVersionInfo(platform = "eq.customer_app", limit = 1)
            if (versionResponse.isSuccessful) {
                val versionInfo = versionResponse.body()?.firstOrNull()
                if (versionInfo != null) {
                    val minVersion = versionInfo.minimumSupportedVersion
                    val isBelowMinimum = !minVersion.isNullOrBlank() &&
                            VersionUtils.compareVersions(VersionUtils.CURRENT_APP_VERSION, minVersion) < 0
                    if (isBelowMinimum || versionInfo.forceUpdate) {
                        val msg = versionInfo.updateMessage
                            ?: "A new version of Sndmart is available. Please update the app to continue."
                        return StartupCheckResult.BlockingUpdate(
                            updateMessage = msg,
                            updateUrl = versionInfo.updateUrl
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Version check failed to query backend: ${e.message}")
        }

        // 2. Maintenance mode check
        try {
            val maintenance = checkMaintenanceMode()
            if (maintenance.enabled) {
                val msg = maintenance.message ?: "Service temporarily unavailable. Please try again shortly."
                return StartupCheckResult.Maintenance(msg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Maintenance check failed: ${e.message}")
        }

        return StartupCheckResult.Passed
    }
}

fun calculateHaversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0 // Earth's radius in kilometers
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return r * c
}
