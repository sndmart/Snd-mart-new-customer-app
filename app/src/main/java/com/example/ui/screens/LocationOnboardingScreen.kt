package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.location.HighAccuracyLocationManager
import com.example.data.location.LocationDetector
import com.example.data.model.City
import com.example.data.model.CustomerAddress
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.ui.components.ErrorCard
import com.example.ui.theme.*
import com.example.util.MapLocationHelper
import com.example.util.PhoneUtils
import com.example.util.PlaceSearchResult
import com.example.util.isValidIndianCoordinate
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "LocationOnboarding"

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun checkHasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

private fun openAppSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open settings: ${e.message}", e)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationOnboardingScreen(
    repository: SndmartRepository,
    sessionManager: UserSessionManager,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val locationDetector = remember { LocationDetector(context) }

    val userId by sessionManager.userId.collectAsState()
    val userName by sessionManager.userName.collectAsState()
    val userPhone by sessionManager.userPhone.collectAsState()

    // Location Permission state
    var hasLocationPermission by remember { mutableStateOf(checkHasLocationPermission(context)) }
    var permissionDenied by remember { mutableStateOf(false) }
    var hasRequestedPermissionOnce by remember { mutableStateOf(false) }

    // Observe lifecycle resume to auto-detect if permission was granted in OS Settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = checkHasLocationPermission(context)
                if (granted) {
                    hasLocationPermission = true
                    permissionDenied = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            hasLocationPermission = true
            permissionDenied = false
        } else {
            hasLocationPermission = false
            permissionDenied = true
        }
    }

    // GPS & City Detection state
    var isDetectingGps by remember { mutableStateOf(false) }
    var detectedCity by remember { mutableStateOf<City?>(sessionManager.selectedCity.value) }
    var detectedCityMessage by remember { mutableStateOf<String?>(null) }
    var isUnsupportedArea by remember { mutableStateOf(false) }
    var showManualCityPicker by remember { mutableStateOf(false) }

    var currentLat by remember { mutableStateOf<Double?>(null) }
    var currentLng by remember { mutableStateOf<Double?>(null) }
    var detectedLocalityText by remember { mutableStateOf<String?>(null) }

    // Interactive Map Pin state
    val defaultMapCenter = remember(detectedCity) {
        if (currentLat != null && currentLng != null) {
            LatLng(currentLat!!, currentLng!!)
        } else if (detectedCity?.centerLat != null && detectedCity?.centerLng != null) {
            LatLng(detectedCity!!.centerLat!!, detectedCity!!.centerLng!!)
        } else {
            LatLng(15.7667, 76.7583) // Sindhanur default
        }
    }
    var pinLatLng by remember { mutableStateOf<LatLng?>(null) }
    val markerState = rememberMarkerState(position = defaultMapCenter)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(defaultMapCenter, 16.5f)
    }

    var hasUserManuallyEditedAddress by remember { mutableStateOf(false) }
    var lastReverseGeocodedAddress by remember { mutableStateOf<String?>(null) }
    var isReverseGeocoding by remember { mutableStateOf(false) }

    // Places Autocomplete search state
    var searchQuery by remember { mutableStateOf("") }
    var searchSuggestions by remember { mutableStateOf<List<PlaceSearchResult>>(emptyList()) }
    var isSearchingPlaces by remember { mutableStateOf(false) }

    // Address Form state
    var selectedLabel by remember { mutableStateOf("Home") }
    var recipientName by remember { mutableStateOf(userName.orEmpty()) }
    var phone by remember { mutableStateOf(userPhone.orEmpty()) }
    var addressLine by remember { mutableStateOf("") }
    var landmark by remember { mutableStateOf("") }

    var isSaving by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    // Active cities list for manual fallback
    var allCitiesList by remember { mutableStateOf<List<City>>(emptyList()) }
    var allCitiesError by remember { mutableStateOf<String?>(null) }
    var isLoadingCities by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoadingCities = true
        try {
            val res = repository.getCities()
            if (res.isSuccess) {
                allCitiesList = res.getOrNull() ?: emptyList()
            } else {
                allCitiesError = res.exceptionOrNull()?.message ?: "Could not load active cities."
            }
        } finally {
            isLoadingCities = false
        }
    }

    // Keep name/phone synced if session loads late
    LaunchedEffect(userName) {
        if (recipientName.isBlank() && !userName.isNullOrBlank()) {
            recipientName = userName!!
        }
    }
    LaunchedEffect(userPhone) {
        if (phone.isBlank() && !userPhone.isNullOrBlank()) {
            phone = userPhone!!
        }
    }

    // Debounced Places autocomplete search
    LaunchedEffect(searchQuery) {
        if (searchQuery.length < 3) {
            searchSuggestions = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        isSearchingPlaces = true
        try {
            searchSuggestions = MapLocationHelper.searchPlaces(context, searchQuery)
        } catch (e: Exception) {
            Log.e(TAG, "Places search failed: ${e.message}", e)
        } finally {
            isSearchingPlaces = false
        }
    }

    // Reverse geocode helper when pin moves
    fun triggerPinReverseGeocode(latLng: LatLng) {
        pinLatLng = latLng
        coroutineScope.launch {
            isReverseGeocoding = true
            try {
                val geocoded = MapLocationHelper.reverseGeocode(context, latLng.latitude, latLng.longitude)
                if (geocoded != null) {
                    lastReverseGeocodedAddress = geocoded.addressLine
                    if (!hasUserManuallyEditedAddress || addressLine.isBlank()) {
                        addressLine = geocoded.addressLine
                    }
                    if (landmark.isBlank() && !geocoded.featureName.isNullOrBlank() && geocoded.featureName != geocoded.subLocality) {
                        landmark = geocoded.featureName
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reverse geocoding error: ${e.message}", e)
            } finally {
                isReverseGeocoding = false
            }
        }
    }

    // Listen to marker drag events to update the pin position
    LaunchedEffect(markerState.isDragging) {
        if (!markerState.isDragging) {
            triggerPinReverseGeocode(markerState.position)
        }
    }

    // GPS & City Detection function - ONLY run once location permission is granted
    fun performGpsDetection() {
        coroutineScope.launch {
            isDetectingGps = true
            isUnsupportedArea = false
            formError = null

            try {
                val coords = HighAccuracyLocationManager.getAccurateGpsLocation(context) { /* status updates */ }
                val lat = coords?.latitude
                val lng = coords?.longitude

                // Fall back to city center ONLY as a map starting point, NEVER as a saved value
                val defaultLat = detectedCity?.centerLat ?: 15.7667
                val defaultLng = detectedCity?.centerLng ?: 76.7583
                val initialLat = lat ?: defaultLat
                val initialLng = lng ?: defaultLng
                val initialPos = LatLng(initialLat, initialLng)

                currentLat = lat
                currentLng = lng
                pinLatLng = initialPos
                markerState.position = initialPos

                coroutineScope.launch {
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(initialPos, 16.8f))
                }

                if (lat != null && lng != null) {
                    // Call backend RPC to identify serviceable city
                    val rpcResult = repository.findCityForLocation(lat, lng)
                    val rpcCity = rpcResult.getOrNull()

                    if (rpcResult.isSuccess && rpcCity != null) {
                        detectedCity = rpcCity
                        detectedCityMessage = "Detected: ${rpcCity.name}"
                        sessionManager.setSelectedCity(rpcCity)
                        val uid = userId
                        if (!uid.isNullOrBlank()) {
                            try { repository.updateProfileCityId(uid, rpcCity.id) } catch (_: Exception) {}
                        }
                        triggerPinReverseGeocode(initialPos)
                        val (street, locality) = locationDetector.getFullAddressFromCoordinates(lat, lng)
                        detectedLocalityText = locality ?: street
                    } else {
                        // Fallback check against active cities
                        val citiesRes = repository.getActiveCities()
                        val activeCities = citiesRes.getOrNull()?.filter { it.status == "active" } ?: emptyList()
                        val (street, locality) = locationDetector.getFullAddressFromCoordinates(lat, lng)
                        val matched = locationDetector.matchWithBackendCities(locality, lat, lng, activeCities)
                        if (matched != null) {
                            detectedCity = matched
                            detectedCityMessage = "Detected: ${matched.name}"
                            sessionManager.setSelectedCity(matched)
                            val uid = userId
                            if (!uid.isNullOrBlank()) {
                                try { repository.updateProfileCityId(uid, matched.id) } catch (_: Exception) {}
                            }
                            triggerPinReverseGeocode(initialPos)
                        } else {
                            isUnsupportedArea = true
                            detectedLocalityText = locality ?: street
                            triggerPinReverseGeocode(initialPos)
                        }
                    }
                } else {
                    // Fallback to initial map position
                    triggerPinReverseGeocode(initialPos)
                }
            } catch (e: Exception) {
                Log.e(TAG, "GPS detection exception", e)
                val fallbackPos = LatLng(detectedCity?.centerLat ?: 15.7667, detectedCity?.centerLng ?: 76.7583)
                pinLatLng = fallbackPos
                markerState.position = fallbackPos
                triggerPinReverseGeocode(fallbackPos)
            } finally {
                isDetectingGps = false
            }
        }
    }

    // Auto-detect GPS once permission is granted
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            performGpsDetection()
        }
    }

    // Step 4: Save address handler - ONLY called on manual "Confirm Location" tap with validation
    fun handleSaveAddress() {
        // Final pin position after customer dragging or searching
        val finalLat = pinLatLng?.latitude ?: markerState.position.latitude
        val finalLng = pinLatLng?.longitude ?: markerState.position.longitude

        // Mandatory coordinate validation check (Step 4)
        if (!isValidIndianCoordinate(finalLat, finalLng)) {
            formError = "This location looks incorrect. Please adjust the pin and try again."
            return
        }

        val city = detectedCity ?: allCitiesList.firstOrNull()
        if (city == null) {
            formError = "Please select or detect your delivery city first."
            return
        }
        if (recipientName.isBlank()) {
            formError = "Please enter the recipient's name."
            return
        }
        if (phone.isBlank()) {
            formError = "Please enter a valid phone number."
            return
        }
        if (addressLine.isBlank()) {
            formError = "Please enter your complete street address or house number."
            return
        }

        val uid = userId
        if (uid.isNullOrBlank()) {
            formError = "Session error: user ID missing. Please sign in again."
            return
        }

        coroutineScope.launch {
            isSaving = true
            formError = null
            try {
                val formattedPhone = if (phone.isNotBlank()) PhoneUtils.toE164(phone.trim()) else phone.trim()
                val newAddress = CustomerAddress(
                    userId = uid,
                    label = selectedLabel.ifBlank { "Home" },
                    recipientName = recipientName.trim(),
                    phone = formattedPhone,
                    addressLine = addressLine.trim(),
                    landmark = landmark.trim().ifBlank { null },
                    lat = finalLat,
                    lng = finalLng,
                    cityId = city.id,
                    isDefault = true
                )

                // 1. Insert into customer_addresses table
                val saveRes = repository.addAddress(newAddress)
                if (saveRes.isFailure) {
                    val addressErr = saveRes.exceptionOrNull()?.message ?: "Failed to save delivery address."
                    Log.e(TAG, "Address save error: $addressErr")
                    formError = addressErr
                    isSaving = false
                    return@launch
                }

                // 2. Update city_id on profiles table
                val cityRes = repository.updateProfileCityId(uid, city.id)
                if (cityRes.isFailure) {
                    val cityErr = cityRes.exceptionOrNull()?.message ?: "Failed to update delivery city on profile."
                    Log.e(TAG, "City save error: $cityErr")
                    formError = cityErr
                    isSaving = false
                    return@launch
                }

                val saved = saveRes.getOrNull()
                if (saved?.id != null) {
                    try { repository.setDefaultAddress(uid, saved.id) } catch (_: Exception) {}
                }
                sessionManager.setSelectedCity(city)
                sessionManager.setHasSavedAddress(true, selectedLabel.ifBlank { "Home" })
                isSaving = false
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Address save error: ${e.message}", e)
                formError = e.message ?: "An unexpected error occurred while saving your address."
                isSaving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (!hasLocationPermission) "Location Required" else "Set Delivery Location",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (!hasLocationPermission) {
                if (!permissionDenied) {
                    // STEP 1: Dedicated Location Permission Required Screen
                    LocationPermissionRequiredContent(
                        onRequestPermission = {
                            hasRequestedPermissionOnce = true
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    )
                } else {
                    // STEP 2: Blocking Permission Denied Screen
                    val activity = context.findActivity()
                    val isPermanentlyDenied = hasRequestedPermissionOnce && activity != null &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION) &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_COARSE_LOCATION)

                    LocationPermissionDeniedContent(
                        isPermanentlyDenied = isPermanentlyDenied,
                        onTryAgain = {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        },
                        onOpenSettings = {
                            openAppSettings(context)
                        }
                    )
                }
            } else {
                // STEP 3 & STEP 4: Full Interactive Map Pin Screen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header Card
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = PastelSage,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = NaturalPrimary,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Where should we deliver?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = NaturalPrimary
                    )
                    Text(
                        text = "Confirm your delivery location and pin your exact doorstep to view fresh groceries & hotel menus in your area.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // GPS Detecting Indicator
                    if (isDetectingGps) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = SurfaceVariantLight),
                            border = BorderStroke(1.dp, NaturalPrimary.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    color = NaturalPrimary,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text = "Detecting your location via GPS...",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Locating nearest Sndmart delivery hub",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Success Detection Pill
                    if (!isDetectingGps && detectedCity != null && detectedCityMessage != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = PastelSage.copy(alpha = 0.5f)),
                            border = BorderStroke(1.dp, NaturalPrimary.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = NaturalPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = detectedCityMessage ?: "City Detected",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = NaturalPrimary
                                        )
                                        if (detectedLocalityText != null) {
                                            Text(
                                                text = detectedLocalityText!!,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                }
                                TextButton(
                                    onClick = { showManualCityPicker = true },
                                    modifier = Modifier.testTag("change_detected_city_button")
                                ) {
                                    Text("Change", fontWeight = FontWeight.SemiBold, color = NaturalPrimary)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Unsupported Area Warning
                    if (isUnsupportedArea) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = PastelCoral.copy(alpha = 0.4f)),
                            border = BorderStroke(1.dp, TangerineOrange.copy(alpha = 0.4f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.Info,
                                        contentDescription = null,
                                        tint = TangerineOrange,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "We're not available in your area yet",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Sndmart is actively serving select hubs. You can pick an active city manually to explore and order.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { showManualCityPicker = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("pick_different_city_button")
                                ) {
                                    Icon(Icons.Default.LocationCity, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Pick a Different City")
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Active cities selection if city is not yet set
                    if (detectedCity == null && !isDetectingGps) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("active_cities_selection_card"),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.LocationCity,
                                        contentDescription = null,
                                        tint = NaturalPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Select Your Delivery City",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "Tap your city below to proceed to address confirmation",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                if (allCitiesError != null) {
                                    Text(
                                        text = allCitiesError!!,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }

                                if (isLoadingCities) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 20.dp),
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = NaturalPrimary)
                                    }
                                } else {
                                    allCitiesList.forEach { city ->
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable {
                                                    detectedCity = city
                                                    detectedCityMessage = "Selected: ${city.name}"
                                                    isUnsupportedArea = false
                                                    sessionManager.setSelectedCity(city)
                                                    val uid = userId
                                                    if (!uid.isNullOrBlank()) {
                                                        coroutineScope.launch {
                                                            val res = repository.updateProfileCityId(uid, city.id)
                                                            if (res.isFailure) {
                                                                Log.e(TAG, "profiles update city_id error: ${res.exceptionOrNull()?.message}")
                                                            }
                                                        }
                                                    }
                                                    val newCenter = if (city.centerLat != null && city.centerLng != null) {
                                                        LatLng(city.centerLat!!, city.centerLng!!)
                                                    } else {
                                                        LatLng(15.7667, 76.7583)
                                                    }
                                                    pinLatLng = newCenter
                                                    markerState.position = newCenter
                                                    coroutineScope.launch {
                                                        cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(newCenter, 16.5f))
                                                    }
                                                    triggerPinReverseGeocode(newCenter)
                                                }
                                                .testTag("city_item_${city.id}"),
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Place,
                                                    contentDescription = null,
                                                    tint = NaturalPrimary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Text(
                                                    text = city.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = TextPrimary,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Icon(
                                                    imageVector = Icons.Default.ArrowForwardIos,
                                                    contentDescription = null,
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // --- STEP 3 & 4: MAP PIN & CONFIRM DELIVERY ADDRESS FORM ---
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp)
                        ) {
                            Text(
                                text = "Confirm Delivery Address",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Pin your exact doorstep & confirm delivery details",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Places Autocomplete Search Box
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        tint = NaturalPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        placeholder = {
                                            Text(
                                                "Search for area, street...",
                                                fontSize = 13.sp,
                                                color = TextSecondary
                                            )
                                        },
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("places_search_input"),
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            disabledContainerColor = Color.Transparent,
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent
                                        )
                                    )
                                    if (isSearchingPlaces) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = NaturalPrimary
                                        )
                                    } else if (searchQuery.isNotBlank()) {
                                        IconButton(
                                            onClick = {
                                                searchQuery = ""
                                                searchSuggestions = emptyList()
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Clear",
                                                modifier = Modifier.size(16.dp),
                                                tint = TextSecondary
                                            )
                                        }
                                    }
                                }
                            }

                            // Autocomplete Suggestions Dropdown
                            AnimatedVisibility(visible = searchSuggestions.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    shadowElevation = 6.dp,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 180.dp)
                                        .padding(top = 4.dp)
                                ) {
                                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                        searchSuggestions.forEachIndexed { index, item ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        searchQuery = item.primaryText
                                                        searchSuggestions = emptyList()
                                                        coroutineScope.launch {
                                                            isSearchingPlaces = true
                                                            val target = MapLocationHelper.fetchPlaceLatLng(context, item)
                                                            isSearchingPlaces = false
                                                            if (target != null) {
                                                                pinLatLng = target
                                                                markerState.position = target
                                                                cameraPositionState.animate(
                                                                    CameraUpdateFactory.newLatLngZoom(target, 17f)
                                                                )
                                                                triggerPinReverseGeocode(target)
                                                            }
                                                        }
                                                    }
                                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                                                    .testTag("places_suggestion_item_$index"),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.Place,
                                                    contentDescription = null,
                                                    tint = NaturalPrimary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = item.primaryText,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = TextPrimary
                                                    )
                                                    if (item.secondaryText.isNotBlank()) {
                                                        Text(
                                                            text = item.secondaryText,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = TextSecondary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                            if (index < searchSuggestions.size - 1) {
                                                HorizontalDivider(
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                                    thickness = 0.5.dp
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Step 3: Interactive Google Map with Draggable Pin
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(270.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            ) {
                                GoogleMap(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("delivery_pin_map"),
                                    cameraPositionState = cameraPositionState,
                                    onMapClick = { clickedLatLng ->
                                        pinLatLng = clickedLatLng
                                        markerState.position = clickedLatLng
                                        triggerPinReverseGeocode(clickedLatLng)
                                    },
                                    uiSettings = remember {
                                        MapUiSettings(
                                            zoomControlsEnabled = false,
                                            myLocationButtonEnabled = false,
                                            compassEnabled = true,
                                            scrollGesturesEnabled = true,
                                            zoomGesturesEnabled = true,
                                            rotationGesturesEnabled = false,
                                            tiltGesturesEnabled = false
                                        )
                                    },
                                    properties = remember(hasLocationPermission) {
                                        MapProperties(isMyLocationEnabled = hasLocationPermission)
                                    }
                                ) {
                                    Marker(
                                        state = markerState,
                                        title = "Delivery Location",
                                        snippet = "Drag to adjust pin to your exact doorstep",
                                        draggable = true,
                                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                                    )
                                }

                                // Top instruction pill
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                    shadowElevation = 3.dp,
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.TouchApp,
                                            contentDescription = null,
                                            tint = NaturalPrimary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Drag pin or tap map to set exact spot",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = NaturalPrimary
                                        )
                                    }
                                }

                                // Re-center to GPS Button
                                if (currentLat != null && currentLng != null) {
                                    FloatingActionButton(
                                        onClick = {
                                            val gpsPos = LatLng(currentLat!!, currentLng!!)
                                            pinLatLng = gpsPos
                                            markerState.position = gpsPos
                                            coroutineScope.launch {
                                                cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(gpsPos, 17f))
                                            }
                                            triggerPinReverseGeocode(gpsPos)
                                        },
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(10.dp)
                                            .size(40.dp)
                                            .testTag("recenter_gps_button"),
                                        shape = CircleShape,
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        contentColor = NaturalPrimary,
                                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MyLocation,
                                            contentDescription = "Center to my GPS",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                // Live Geocoding Loading Indicator
                                if (isReverseGeocoding) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                                        shadowElevation = 4.dp,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp),
                                                strokeWidth = 2.dp,
                                                color = NaturalPrimary
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Detecting address...",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextPrimary
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            if (formError != null) {
                                ErrorCard(
                                    message = formError!!,
                                    onRetry = { formError = null },
                                    modifier = Modifier.testTag("form_error_card")
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            // Address Label Selector
                            Text(
                                text = "SAVE AS",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                listOf(
                                    Triple("Home", Icons.Default.Home, "label_chip_home"),
                                    Triple("Work", Icons.Default.Work, "label_chip_work"),
                                    Triple("Other", Icons.Default.Place, "label_chip_other")
                                ).forEach { (label, icon, testTag) ->
                                    val isSelected = selectedLabel == label
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = if (isSelected) NaturalPrimary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) NaturalPrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        ),
                                        modifier = Modifier
                                            .clickable { selectedLabel = label }
                                            .testTag(testTag)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                icon,
                                                contentDescription = null,
                                                tint = if (isSelected) Color.White else TextSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = label,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 13.sp,
                                                color = if (isSelected) Color.White else TextPrimary
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Recipient Name
                            OutlinedTextField(
                                value = recipientName,
                                onValueChange = { recipientName = it },
                                label = { Text("Recipient Name *") },
                                leadingIcon = {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = NaturalPrimary)
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("address_name_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NaturalPrimary,
                                    focusedLabelColor = NaturalPrimary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Contact Phone Number
                            OutlinedTextField(
                                value = phone,
                                onValueChange = { phone = it },
                                label = { Text("Contact Phone *") },
                                leadingIcon = {
                                    Icon(Icons.Default.Phone, contentDescription = null, tint = NaturalPrimary)
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("address_phone_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NaturalPrimary,
                                    focusedLabelColor = NaturalPrimary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Complete Street Address
                            OutlinedTextField(
                                value = addressLine,
                                onValueChange = {
                                    addressLine = it
                                    hasUserManuallyEditedAddress = true
                                },
                                label = { Text("House / Flat / Street / Area *") },
                                leadingIcon = {
                                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = NaturalPrimary)
                                },
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("address_line_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NaturalPrimary,
                                    focusedLabelColor = NaturalPrimary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Landmark (Optional)
                            OutlinedTextField(
                                value = landmark,
                                onValueChange = { landmark = it },
                                label = { Text("Landmark (Optional)") },
                                leadingIcon = {
                                    Icon(Icons.Default.Apartment, contentDescription = null, tint = NaturalPrimary)
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("address_landmark_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NaturalPrimary,
                                    focusedLabelColor = NaturalPrimary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            // Step 4: "Confirm Location" Button
                            Button(
                                onClick = { handleSaveAddress() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("confirm_delivery_address_button")
                                    .testTag("confirm_location_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                enabled = !isSaving,
                                shape = RoundedCornerShape(25.dp)
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                } else {
                                    Text(
                                        text = "Confirm Location",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Manual City Picker Sheet
    if (showManualCityPicker) {
        CityPickerSheet(
            repository = repository,
            sessionManager = sessionManager,
            onDismiss = { showManualCityPicker = false },
            onCitySelected = { city ->
                detectedCity = city
                detectedCityMessage = "Selected: ${city.name}"
                isUnsupportedArea = false
                showManualCityPicker = false
                sessionManager.setSelectedCity(city)
                val uid = userId
                if (!uid.isNullOrBlank()) {
                    coroutineScope.launch {
                        try { repository.updateProfileCityId(uid, city.id) } catch (_: Exception) {}
                    }
                }
                if (city.centerLat != null && city.centerLng != null) {
                    val cityPos = LatLng(city.centerLat, city.centerLng)
                    currentLat = city.centerLat
                    currentLng = city.centerLng
                    pinLatLng = cityPos
                    markerState.position = cityPos
                    coroutineScope.launch {
                        cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(cityPos, 15.5f))
                    }
                    triggerPinReverseGeocode(cityPos)
                } else if (addressLine.isBlank()) {
                    addressLine = "${city.name}, ${city.state ?: ""}"
                }
            },
            onTriggerLocationDetect = {
                showManualCityPicker = false
                performGpsDetection()
            }
        )
    }
}

/**
 * Step 1 - Dedicated Location Permission Required Screen
 */
@Composable
private fun LocationPermissionRequiredContent(
    onRequestPermission: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 20.dp, bottom = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = PastelSage,
                modifier = Modifier.size(88.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = NaturalPrimary,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Location Permission Required",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Sndmart needs your location to show nearby hotels and groceries, and for accurate delivery. This is required to continue.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    PermissionBenefitRow(
                        icon = Icons.Outlined.Storefront,
                        title = "Discover Nearby Stores",
                        desc = "Browse active restaurants, fresh groceries, and food vendors delivering in your vicinity."
                    )
                    PermissionBenefitRow(
                        icon = Icons.Outlined.NearMe,
                        title = "Accurate Doorstep Delivery",
                        desc = "Ensures our delivery partners navigate directly to your house or apartment without calling."
                    )
                    PermissionBenefitRow(
                        icon = Icons.Outlined.VerifiedUser,
                        title = "Privacy Guaranteed",
                        desc = "Your location is only used to service active orders and find nearby hubs."
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Primary In-Content CTA Button (ensures prominence and visibility directly in flow)
            Button(
                onClick = onRequestPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("allow_location_access_inline_button"),
                colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                shape = RoundedCornerShape(27.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Allow Location Access",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }

        // Fixed bottom CTA bar ensures button is always visible on all screen sizes
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.background,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("allow_location_access_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                    shape = RoundedCornerShape(27.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Allow Location Access",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

/**
 * Step 2 - Blocking Permission Denied Screen
 */
@Composable
private fun LocationPermissionDeniedContent(
    isPermanentlyDenied: Boolean,
    onTryAgain: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 20.dp, bottom = 140.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = PastelCoral.copy(alpha = 0.5f),
                modifier = Modifier.size(88.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOff,
                        contentDescription = null,
                        tint = NaturalBadgeRed,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Location Access Required",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Sndmart needs your location to show nearby hotels and groceries, and for accurate delivery. This is required to continue.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = PastelCoral.copy(alpha = 0.25f)),
                border = BorderStroke(1.dp, NaturalBadgeRed.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = NaturalBadgeRed,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isPermanentlyDenied) {
                            "Permission was denied. Please open Settings and enable Location permission for Sndmart to continue."
                        } else {
                            "Location permission is mandatory to service your doorstep and browse active merchants."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // Fixed bottom CTA buttons ensures they remain visible regardless of screen height
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.background,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                if (isPermanentlyDenied) {
                    Button(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("open_settings_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                        shape = RoundedCornerShape(26.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Open Settings",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = onTryAgain,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("try_again_permission_button"),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text(
                            text = "Try Again",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Button(
                        onClick = onTryAgain,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("try_again_permission_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                        shape = RoundedCornerShape(26.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Try Again",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("open_settings_button"),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open Settings",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionBenefitRow(
    icon: ImageVector,
    title: String,
    desc: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = PastelSage,
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = NaturalPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                lineHeight = 16.sp
            )
        }
    }
}
