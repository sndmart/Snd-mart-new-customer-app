package com.example.data.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.model.City
import com.example.data.model.Profile
import com.example.data.remote.SessionTokenProvider
import com.example.data.remote.SupabaseClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class UserSessionManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("sndmart_session", Context.MODE_PRIVATE)

    private val _userId = MutableStateFlow<String?>(prefs.getString(KEY_USER_ID, null))
    val userId: StateFlow<String?> = _userId.asStateFlow()

    private val _userEmail = MutableStateFlow<String?>(prefs.getString(KEY_USER_EMAIL, null))
    val userEmail: StateFlow<String?> = _userEmail.asStateFlow()

    private val _userName = MutableStateFlow<String?>(prefs.getString(KEY_USER_NAME, null))
    val userName: StateFlow<String?> = _userName.asStateFlow()

    private val _userPhone = MutableStateFlow<String?>(prefs.getString(KEY_USER_PHONE, null))
    val userPhone: StateFlow<String?> = _userPhone.asStateFlow()

    private val _userRole = MutableStateFlow<String?>(prefs.getString(KEY_USER_ROLE, "customer"))
    val userRole: StateFlow<String?> = _userRole.asStateFlow()

    private val _selectedCity = MutableStateFlow<City?>(loadSelectedCity())
    val selectedCity: StateFlow<City?> = _selectedCity.asStateFlow()

    private val _hasSavedAddress = MutableStateFlow(prefs.getBoolean(KEY_HAS_SAVED_ADDRESS, false))
    val hasSavedAddressState: StateFlow<Boolean> = _hasSavedAddress.asStateFlow()

    private val _defaultAddressLabel = MutableStateFlow<String?>(prefs.getString(KEY_DEFAULT_ADDRESS_LABEL, null))
    val defaultAddressLabel: StateFlow<String?> = _defaultAddressLabel.asStateFlow()

    private val _unreadNotificationCount = MutableStateFlow(0)
    val unreadNotificationCount: StateFlow<Int> = _unreadNotificationCount.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(prefs.getString(KEY_ACCESS_TOKEN, null) != null)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _sessionExpiredEvent = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val sessionExpiredEvent: SharedFlow<String> = _sessionExpiredEvent.asSharedFlow()

    private val _sessionExpiredMessage = MutableStateFlow<String?>(null)
    val sessionExpiredMessage: StateFlow<String?> = _sessionExpiredMessage.asStateFlow()

    init {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        SupabaseClient.userAccessToken = token

        val customKey = prefs.getString(KEY_CUSTOM_ANON_KEY, null)
        if (!customKey.isNullOrBlank()) {
            SupabaseClient.customAnonKey = customKey
        }

        val savedUserId = prefs.getString(KEY_USER_ID, null)
        if (!savedUserId.isNullOrBlank()) {
            try {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setUserId(savedUserId)
            } catch (e: Throwable) {}
        }

        SupabaseClient.sessionTokenProvider = object : SessionTokenProvider {
            override fun getAccessToken(): String? = this@UserSessionManager.getAccessToken()
            override fun getRefreshToken(): String? = this@UserSessionManager.getRefreshToken()
            override fun onTokensUpdated(accessToken: String, refreshToken: String?, expiresInSeconds: Long?) {
                this@UserSessionManager.updateTokens(accessToken, refreshToken, expiresInSeconds)
            }
            override fun onSessionExpired(message: String) {
                this@UserSessionManager.notifySessionExpired(message)
            }
        }
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun getExpiresAtMs(): Long? {
        val stored = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (stored > 0L) return stored
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        val jwtExp = extractJwtExpiration(token)
        if (jwtExp != null) {
            prefs.edit().putLong(KEY_EXPIRES_AT, jwtExp).apply()
            return jwtExp
        }
        return null
    }

    fun isSessionExpiredOrExpiringSoon(bufferMs: Long = 120_000L): Boolean {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        if (token.isNullOrBlank()) return true
        val expiresAt = getExpiresAtMs() ?: return false
        return System.currentTimeMillis() + bufferMs >= expiresAt
    }

    fun updateTokens(token: String, refreshToken: String?, expiresInSeconds: Long? = null) {
        val calculatedExpiresAt = if (expiresInSeconds != null && expiresInSeconds > 0) {
            System.currentTimeMillis() + (expiresInSeconds * 1000L)
        } else {
            extractJwtExpiration(token) ?: (System.currentTimeMillis() + 3600_000L)
        }

        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, token)
            if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_EXPIRES_AT, calculatedExpiresAt)
            apply()
        }
        SupabaseClient.userAccessToken = token
        _isLoggedIn.value = true
        _sessionExpiredMessage.value = null
    }

    fun notifySessionExpired(message: String = "Your session expired, please log in again") {
        val wasLoggedIn = _isLoggedIn.value || !getAccessToken().isNullOrBlank()
        Log.w("UserSessionManager", "Session expired: $message (wasLoggedIn=$wasLoggedIn)")
        logout()
        if (wasLoggedIn) {
            _sessionExpiredMessage.value = message
            _sessionExpiredEvent.tryEmit(message)
        }
    }

    fun clearSessionExpiredMessage() {
        _sessionExpiredMessage.value = null
    }

    private fun extractJwtExpiration(token: String?): Long? {
        if (token.isNullOrBlank()) return null
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = String(
                android.util.Base64.decode(
                    parts[1],
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                )
            )
            val json = JSONObject(payload)
            val exp = json.optLong("exp", 0L)
            if (exp > 0L) exp * 1000L else null
        } catch (e: Exception) {
            null
        }
    }

    fun saveSession(
        token: String?,
        refreshToken: String?,
        userId: String?,
        email: String?,
        name: String? = null,
        phone: String? = null,
        expiresInSeconds: Long? = null
    ) {
        val calculatedExpiresAt = if (expiresInSeconds != null && expiresInSeconds > 0) {
            System.currentTimeMillis() + (expiresInSeconds * 1000L)
        } else {
            extractJwtExpiration(token) ?: (System.currentTimeMillis() + 3600_000L)
        }

        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, token)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_EXPIRES_AT, calculatedExpiresAt)
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_EMAIL, email)
            if (name != null) putString(KEY_USER_NAME, name)
            if (phone != null) putString(KEY_USER_PHONE, phone)
            apply()
        }
        SupabaseClient.userAccessToken = token
        _userId.value = userId
        _userEmail.value = email
        if (name != null) _userName.value = name
        if (phone != null) _userPhone.value = phone
        _isLoggedIn.value = !token.isNullOrBlank()
        _sessionExpiredMessage.value = null

        if (!userId.isNullOrBlank()) {
            try {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setUserId(userId)
            } catch (e: Throwable) {}
        }
    }

    fun hasSavedSession(): Boolean {
        return !prefs.getString(KEY_ACCESS_TOKEN, null).isNullOrBlank()
    }

    fun updateProfileInfo(profile: Profile) {
        prefs.edit().apply {
            putString(KEY_USER_NAME, profile.fullName)
            putString(KEY_USER_PHONE, profile.phone)
            putString(KEY_USER_ROLE, profile.role)
            apply()
        }
        _userName.value = profile.fullName
        _userPhone.value = profile.phone
        _userRole.value = profile.role
    }

    fun setSelectedCity(city: City) {
        prefs.edit().apply {
            putString(KEY_CITY_ID, city.id)
            putString(KEY_CITY_NAME, city.name)
            putString(KEY_CITY_STATE, city.state)
            apply()
        }
        _selectedCity.value = city
    }

    private fun loadSelectedCity(): City? {
        val id = prefs.getString(KEY_CITY_ID, null) ?: return null
        val name = prefs.getString(KEY_CITY_NAME, null) ?: return null
        val state = prefs.getString(KEY_CITY_STATE, null)
        return City(id = id, name = name, state = state, status = "active")
    }

    fun saveCustomAnonKey(key: String) {
        prefs.edit().putString(KEY_CUSTOM_ANON_KEY, key).apply()
        SupabaseClient.customAnonKey = key
    }

    fun getCustomAnonKey(): String? {
        return prefs.getString(KEY_CUSTOM_ANON_KEY, null)
    }

    fun hasSavedAddress(): Boolean = prefs.getBoolean(KEY_HAS_SAVED_ADDRESS, false)

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    fun setHasSavedAddress(has: Boolean, label: String? = null) {
        prefs.edit().apply {
            putBoolean(KEY_HAS_SAVED_ADDRESS, has)
            if (label != null) putString(KEY_DEFAULT_ADDRESS_LABEL, label)
            apply()
        }
        _hasSavedAddress.value = has
        if (label != null) _defaultAddressLabel.value = label
    }

    fun getDefaultAddressLabel(): String? = prefs.getString(KEY_DEFAULT_ADDRESS_LABEL, null)

    fun setUnreadNotificationCount(count: Int) {
        _unreadNotificationCount.value = count.coerceAtLeast(0)
    }

    fun decrementUnreadNotificationCount() {
        _unreadNotificationCount.value = (_unreadNotificationCount.value - 1).coerceAtLeast(0)
    }

    fun logout() {
        prefs.edit().apply {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_EXPIRES_AT)
            remove(KEY_USER_ID)
            remove(KEY_USER_EMAIL)
            remove(KEY_USER_NAME)
            remove(KEY_USER_PHONE)
            remove(KEY_USER_ROLE)
            remove(KEY_CITY_ID)
            remove(KEY_CITY_NAME)
            remove(KEY_CITY_STATE)
            remove(KEY_HAS_SAVED_ADDRESS)
            remove(KEY_DEFAULT_ADDRESS_LABEL)
            apply()
        }
        SupabaseClient.userAccessToken = null
        _userId.value = null
        _userEmail.value = null
        _userName.value = null
        _userPhone.value = null
        _userRole.value = "customer"
        _selectedCity.value = null
        _hasSavedAddress.value = false
        _defaultAddressLabel.value = null
        _unreadNotificationCount.value = 0
        _isLoggedIn.value = false
        _sessionExpiredMessage.value = null

        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setUserId("")
        } catch (e: Throwable) {}
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_PHONE = "user_phone"
        private const val KEY_USER_ROLE = "user_role"
        private const val KEY_CITY_ID = "city_id"
        private const val KEY_CITY_NAME = "city_name"
        private const val KEY_CITY_STATE = "city_state"
        private const val KEY_CUSTOM_ANON_KEY = "custom_anon_key"
        private const val KEY_HAS_SAVED_ADDRESS = "has_saved_address"
        private const val KEY_DEFAULT_ADDRESS_LABEL = "default_address_label"
        private const val KEY_DEVICE_ID = "sndmart_device_id"
        private const val KEY_RATING_POPUP_SHOWN_COUNT = "rating_popup_shown_count"
        private const val KEY_RATING_POPUP_DISMISSED_FOREVER = "rating_popup_dismissed_forever"
        private const val KEY_HAS_RATED = "has_rated_app"
    }

    /**
     * Retrieves or generates a persistent device UUID representing this device.
     * Stored in SharedPreferences under "sndmart_device_id".
     */
    fun getOrCreateDeviceId(): String {
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId.isNullOrBlank()) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    fun getDeviceId(): String = getOrCreateDeviceId()

    fun shouldShowRatingPopup(completedOrderCount: Int): Boolean {
        if (prefs.getBoolean(KEY_HAS_RATED, false)) return false
        if (prefs.getBoolean(KEY_RATING_POPUP_DISMISSED_FOREVER, false)) return false
        val timesShown = prefs.getInt(KEY_RATING_POPUP_SHOWN_COUNT, 0)
        return completedOrderCount >= 3 && (completedOrderCount - 3) % 5 == 0 && timesShown < 3
    }

    fun recordRatingPopupShown() {
        prefs.edit().putInt(KEY_RATING_POPUP_SHOWN_COUNT, prefs.getInt(KEY_RATING_POPUP_SHOWN_COUNT, 0) + 1).apply()
    }

    fun recordRatingPopupDismissedForever() {
        prefs.edit().putBoolean(KEY_RATING_POPUP_DISMISSED_FOREVER, true).apply()
    }

    fun recordUserRated() {
        prefs.edit().putBoolean(KEY_HAS_RATED, true).apply()
    }
}
