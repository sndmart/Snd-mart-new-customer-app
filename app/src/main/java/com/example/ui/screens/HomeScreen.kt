package com.example.ui.screens

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.data.repository.AddToCartResult
import com.example.data.repository.SndmartRepository
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class BrowsingMode {
    GROCERY,
    HOTELS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: SndmartRepository,
    selectedCity: City?,
    deliveryAddressLabel: String? = null,
    onCityChangeRequested: () -> Unit,
    onNavigateToCart: () -> Unit,
    onNavigateToHotelMenu: (vendorId: String, vendorName: String) -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateToNotifications: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val unreadNotificationCount by repository.sessionManager.unreadNotificationCount.collectAsState()

    var browsingMode by remember { mutableStateOf(BrowsingMode.GROCERY) }
    var searchQuery by remember { mutableStateOf("") }

    val GROCERY_PAGE_SIZE = 30
    val HOTEL_PAGE_SIZE = 20

    // Grocery data
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var groceryProducts by remember { mutableStateOf<List<ResolvedProduct>>(emptyList()) }
    var isGroceryLoading by remember { mutableStateOf(true) }
    var isLoadingMoreProducts by remember { mutableStateOf(false) }
    var hasMoreProducts by remember { mutableStateOf(true) }
    var groceryError by remember { mutableStateOf<String?>(null) }

    // Hotels data
    var hotels by remember { mutableStateOf<List<Vendor>>(emptyList()) }
    var isHotelsLoading by remember { mutableStateOf(true) }
    var isLoadingMoreHotels by remember { mutableStateOf(false) }
    var hasMoreHotels by remember { mutableStateOf(true) }
    var hotelsError by remember { mutableStateOf<String?>(null) }

    // Cart state
    val groceryCart by repository.groceryCart.collectAsState()
    val hotelCart by repository.hotelCart.collectAsState()
    val totalCartCount = repository.getCartCount()

    // Conflict Dialog state
    var pendingHotelConflict by remember { mutableStateOf<AddToCartResult.HotelConflict?>(null) }

    // Snackbar host
    val snackbarHostState = remember { SnackbarHostState() }

    // Grocery categories (vendor_type IN grocery/vegetable/fruit, vendor_id IS NULL, is_active=true)
    // Fetched dynamically from Supabase without city restriction, and errors are properly logged.
    fun loadGroceryCategories(cityId: String? = null) {
        coroutineScope.launch {
            val catRes = repository.getGroceryCategories()
            if (catRes.isSuccess) {
                val list = catRes.getOrNull() ?: emptyList()
                categories = list
                Log.d("HomeScreen", "Loaded ${list.size} categories: ${list.map { it.name }}")
            } else {
                val error = catRes.exceptionOrNull()
                Log.e("HomeScreen", "Categories fetch error: ${error?.message}", error)
                categories = emptyList()
                if (groceryProducts.isEmpty()) {
                    groceryError = error?.message
                }
            }
        }
    }

    // Grocery products depend on city + selected category + search query.
    // Rule 1: Paginate by 30 at a time.
    fun loadGroceryProducts(cityId: String, query: String, categoryId: String? = selectedCategoryId, reset: Boolean = true) {
        coroutineScope.launch {
            if (reset) {
                isGroceryLoading = true
                groceryError = null
            } else {
                isLoadingMoreProducts = true
            }

            val offset = if (reset) 0 else groceryProducts.size
            val prodRes = repository.getResolvedGroceryProducts(
                cityId = cityId,
                categoryId = categoryId,
                searchQuery = query.ifBlank { null },
                limit = GROCERY_PAGE_SIZE,
                offset = offset
            )
            if (prodRes.isSuccess) {
                val newProducts = prodRes.getOrNull() ?: emptyList()
                if (reset) {
                    groceryProducts = newProducts.sortedWith(
                        compareByDescending<ResolvedProduct> { it.isInStockAndActive }
                            .thenBy { it.name.lowercase() }
                    )
                } else {
                    groceryProducts = (groceryProducts + newProducts).distinctBy { it.id }.sortedWith(
                        compareByDescending<ResolvedProduct> { it.isInStockAndActive }
                            .thenBy { it.name.lowercase() }
                    )
                }
                hasMoreProducts = newProducts.size >= GROCERY_PAGE_SIZE
            } else {
                if (reset) {
                    groceryProducts = emptyList()
                    groceryError = prodRes.exceptionOrNull()?.message
                }
            }
            isGroceryLoading = false
            isLoadingMoreProducts = false
        }
    }

    // Refresh function for hotels.
    // Rule 1: Hotel list: 20 at a time.
    fun loadHotelsData(cityId: String, query: String, reset: Boolean = true) {
        coroutineScope.launch {
            if (reset) {
                isHotelsLoading = true
                hotelsError = null
            } else {
                isLoadingMoreHotels = true
            }

            val offset = if (reset) 0 else hotels.size
            val res = repository.getHotels(
                cityId = cityId,
                searchQuery = query.ifBlank { null },
                limit = HOTEL_PAGE_SIZE,
                offset = offset
            )
            if (res.isSuccess) {
                val newHotels = res.getOrNull() ?: emptyList()
                if (reset) {
                    hotels = newHotels.sortedWith(
                        compareBy<Vendor> { vendor ->
                            when {
                                vendor.isActive && vendor.isFeatured == true -> 0
                                vendor.isActive -> 1
                                else -> 2
                            }
                        }.thenBy { it.name.lowercase() }
                    )
                } else {
                    hotels = (hotels + newHotels).distinctBy { it.id }.sortedWith(
                        compareBy<Vendor> { vendor ->
                            when {
                                vendor.isActive && vendor.isFeatured == true -> 0
                                vendor.isActive -> 1
                                else -> 2
                            }
                        }.thenBy { it.name.lowercase() }
                    )
                }
                hasMoreHotels = newHotels.size >= HOTEL_PAGE_SIZE
            } else {
                if (reset) {
                    hotelsError = res.exceptionOrNull()?.message
                }
            }
            isHotelsLoading = false
            isLoadingMoreHotels = false
        }
    }

    // Re-fetch fresh data whenever the screen resumes (e.g., navigating back)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, selectedCity?.id) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val cityId = selectedCity?.id
                if (cityId != null) {
                    loadGroceryProducts(cityId, searchQuery)
                    loadHotelsData(cityId, searchQuery)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Initial category load
    LaunchedEffect(Unit) {
        loadGroceryCategories()
    }

    // Categories + hotels reload when the city changes.
    LaunchedEffect(selectedCity?.id) {
        loadGroceryCategories()
        val cityId = selectedCity?.id ?: return@LaunchedEffect
        loadHotelsData(cityId, "")
    }

    // Hotel search is server-side (name=ilike), debounced, only while on the hotels tab.
    LaunchedEffect(searchQuery, browsingMode) {
        if (browsingMode != BrowsingMode.HOTELS) return@LaunchedEffect
        val cityId = selectedCity?.id ?: return@LaunchedEffect
        delay(350)
        loadHotelsData(cityId, searchQuery)
    }

    // Products reload immediately on city or category change.
    LaunchedEffect(selectedCity?.id, selectedCategoryId) {
        val cityId = selectedCity?.id ?: return@LaunchedEffect
        loadGroceryProducts(cityId, searchQuery, selectedCategoryId)
    }

    // Products reload on search query change (debounced so typing doesn't spam backend).
    LaunchedEffect(searchQuery) {
        val cityId = selectedCity?.id ?: return@LaunchedEffect
        delay(300)
        loadGroceryProducts(cityId, searchQuery, selectedCategoryId)
    }

    // Handle single-hotel rule conflict dialog
    if (pendingHotelConflict != null) {
        val conflict = pendingHotelConflict!!
        AlertDialog(
            onDismissRequest = { pendingHotelConflict = null },
            title = { Text("Replace Hotel Cart Items?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Your cart already contains items from another hotel. Sndmart orders items from one hotel at a time. Would you like to clear the existing hotel cart and add this item?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.forceClearHotelCartAndAdd(conflict.pendingItem)
                        pendingHotelConflict = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Cart updated with new hotel item")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TangerineOrange)
                ) {
                    Text("Clear & Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingHotelConflict = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            SndmartTopBar(
                currentCity = selectedCity,
                cartCount = totalCartCount,
                deliveryAddressLabel = deliveryAddressLabel,
                unreadNotificationCount = unreadNotificationCount,
                onCityClick = onCityChangeRequested,
                onCartClick = onNavigateToCart,
                onNotificationsClick = onNavigateToNotifications,
                onSettingsClick = onOpenSettings
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Natural Tones Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("home_search_input"),
                placeholder = {
                    Text(
                        if (browsingMode == BrowsingMode.GROCERY) "Search groceries or hotels..."
                        else "Search hotels, restaurants, cuisines...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = TextSecondary)
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceVariantLight,
                    unfocusedContainerColor = SurfaceVariantLight,
                    focusedBorderColor = NaturalPrimary,
                    unfocusedBorderColor = OutlineBorder
                ),
                singleLine = true
            )

            // Natural Tones Segmented Mode Pill
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = NaturalPrimaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("home_mode_toggle")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isGrocery = browsingMode == BrowsingMode.GROCERY
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isGrocery) NaturalPrimary else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { browsingMode = BrowsingMode.GROCERY }
                            .testTag("toggle_grocery")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 10.dp)
                        ) {
                            Text(
                                text = "Fresh Groceries & Fruits",
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                maxLines = 1,
                                color = if (isGrocery) Color.White else TextSecondary
                            )
                        }
                    }

                    val isHotel = browsingMode == BrowsingMode.HOTELS
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isHotel) NaturalPrimary else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { browsingMode = BrowsingMode.HOTELS }
                            .testTag("toggle_hotels")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 10.dp)
                        ) {
                            Text(
                                text = "Hotel Food & Dining",
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                maxLines = 1,
                                color = if (isHotel) Color.White else TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (selectedCity == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Please select your delivery city to view live stock & pricing")
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onCityChangeRequested) {
                            Text("Choose City")
                        }
                    }
                }
                return@Scaffold
            }

            // --- GROCERY VIEW ---
            if (browsingMode == BrowsingMode.GROCERY) {
                if (isGroceryLoading && groceryProducts.isEmpty()) {
                    // Rule 7: Skeleton grid loader instead of a blank screen or single spinner
                    GroceryProductGridSkeleton(count = 6)
                } else if (groceryError != null && groceryProducts.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        ErrorCard(
                            message = groceryError!!,
                            onRetry = {
                                selectedCity.id.let {
                                    loadGroceryCategories()
                                    loadGroceryProducts(it, searchQuery, selectedCategoryId, reset = true)
                                }
                            }
                        )
                    }
                } else {
                    // Search is performed server-side (name=ilike); just render the results.
                    val filteredProducts = groceryProducts

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (searchQuery.isBlank()) {
                            // Featured Deals Banner
                            item(span = { GridItemSpan(2) }) {
                                NaturalFeaturedDealsBanner(
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }

                            // Horizontal scroll of grocery category image cards (image_url + name)
                            item(span = { GridItemSpan(2) }) {
                                GroceryCategoryList(
                                    categories = categories,
                                    selectedCategoryId = selectedCategoryId,
                                    onCategorySelected = { selectedCategoryId = it }
                                )
                            }
                        }

                        if (filteredProducts.isEmpty()) {
                            item(span = { GridItemSpan(2) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No grocery products found.",
                                        color = TextSecondary,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        } else {
                            items(filteredProducts, key = { it.id }) { product ->
                                val inCartItem = groceryCart.find { it.productId == product.id }
                                val quantityInCart = inCartItem?.quantity ?: 0

                                GroceryProductCard(
                                    product = product,
                                    quantityInCart = quantityInCart,
                                    onIncrease = {
                                        repository.addToCart(
                                            productId = product.id,
                                            vendorId = null,
                                            cityId = selectedCity.id,
                                            quantityDelta = 1,
                                            isHotel = false
                                        )
                                    },
                                    onDecrease = {
                                        repository.addToCart(
                                            productId = product.id,
                                            vendorId = null,
                                            cityId = selectedCity.id,
                                            quantityDelta = -1,
                                            isHotel = false
                                        )
                                    }
                                )
                            }

                            if (hasMoreProducts) {
                                item(span = { GridItemSpan(2) }) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isLoadingMoreProducts) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(28.dp),
                                                strokeWidth = 2.5.dp,
                                                color = NaturalPrimary
                                            )
                                        } else {
                                            OutlinedButton(
                                                onClick = {
                                                    loadGroceryProducts(selectedCity.id, searchQuery, selectedCategoryId, reset = false)
                                                },
                                                shape = RoundedCornerShape(20.dp),
                                                modifier = Modifier.testTag("load_more_grocery_button")
                                            ) {
                                                Text("Load More Products (30)")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- HOTELS VIEW ---
            if (browsingMode == BrowsingMode.HOTELS) {
                if (isHotelsLoading && hotels.isEmpty()) {
                    // Rule 7: Skeleton list loader instead of a single spinner
                    HotelsListSkeleton(count = 4)
                } else if (hotelsError != null && hotels.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        ErrorCard(
                            message = hotelsError!!,
                            onRetry = { selectedCity.id.let { loadHotelsData(it, searchQuery, reset = true) } }
                        )
                    }
                } else {
                    // Search is performed server-side (name=ilike); just render the results.
                    val filteredHotels = hotels

                    if (filteredHotels.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No hotels or restaurants available in this city.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(filteredHotels, key = { it.id }) { hotel ->
                                HotelCard(
                                    vendor = hotel,
                                    repository = repository,
                                    onClick = {
                                        onNavigateToHotelMenu(hotel.id, hotel.name)
                                    }
                                )
                            }

                            if (hasMoreHotels) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isLoadingMoreHotels) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(28.dp),
                                                strokeWidth = 2.5.dp,
                                                color = NaturalPrimary
                                            )
                                        } else {
                                            OutlinedButton(
                                                onClick = {
                                                    loadHotelsData(selectedCity.id, searchQuery, reset = false)
                                                },
                                                shape = RoundedCornerShape(20.dp),
                                                modifier = Modifier.testTag("load_more_hotels_button")
                                            ) {
                                                Text("Load More Hotels (20)")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NaturalFeaturedDealsBanner(
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = NaturalOceanBlue),
        modifier = modifier
            .fillMaxWidth()
            .height(130.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Silhouette decorations from Natural Tones design
            Text(
                text = "🥬",
                fontSize = 58.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 10.dp, y = 8.dp)
                    .alpha(0.25f)
            )
            Text(
                text = "🍅",
                fontSize = 44.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-36).dp, y = 4.dp)
                    .alpha(0.20f)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "UP TO 40% OFF",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Organic Farm\nFresh Picks",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 22.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White
                ) {
                    Text(
                        text = "SHOP NOW",
                        color = NaturalOceanBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}


@Composable
fun GroceryProductCard(
    product: ResolvedProduct,
    quantityInCart: Int,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit
) {
    val isInStockAndActive = product.isInStockAndActive

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isInStockAndActive) 1f else 0.65f)
            .testTag("product_card_${product.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isInStockAndActive) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(115.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                ProductImage(
                    url = product.imageUrl,
                    contentDescription = product.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isInStockAndActive) 1f else 0.7f)
                )
                if (isInStockAndActive && product.isFeatured) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = NaturalOceanBlue,
                        contentColor = Color.White
                    ) {
                        Text(
                            text = "FEATURED",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (!isInStockAndActive) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        color = Color.Transparent
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = NaturalBadgeRed,
                                contentColor = Color.White
                            ) {
                                Text(
                                    text = "OUT OF STOCK",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = product.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isInStockAndActive) TextPrimary else TextMuted
            )

            PriceDisplay(
                price = product.effectivePrice,
                mrp = product.effectiveMrp,
                unit = product.unit,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .alpha(if (isInStockAndActive) 1f else 0.7f)
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (isInStockAndActive) {
                QuantityStepper(
                    quantity = quantityInCart,
                    onIncrease = onIncrease,
                    onDecrease = onDecrease,
                    modifier = Modifier.fillMaxWidth(),
                    testTagPrefix = "grocery_${product.id}"
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "Out of Stock",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HotelCard(
    vendor: Vendor,
    repository: SndmartRepository,
    onClick: () -> Unit
) {
    val isOpen = vendor.isOpen && vendor.isActive

    // Average rating from vendor_reviews (computed client-side). Fetched per card so
    // only visible hotels make the call; 0.0 means "no reviews yet".
    var avgRating by remember(vendor.id) { mutableStateOf(0.0) }
    LaunchedEffect(vendor.id) {
        avgRating = repository.getVendorAverageRating(vendor.id).getOrNull() ?: 0.0
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (vendor.isActive) 1f else 0.65f)
            .clickable { onClick() }
            .testTag("hotel_card_${vendor.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (vendor.isActive) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(135.dp)
            ) {
                ProductImage(
                    url = vendor.bannerUrl,
                    contentDescription = vendor.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (vendor.isActive) 1f else 0.7f)
                )
                // Status chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (vendor.isActive && vendor.isFeatured == true) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = NaturalOceanBlue,
                            contentColor = Color.White
                        ) {
                            Text(
                                "FEATURED",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (!vendor.isActive) NaturalBadgeRed else if (isOpen) SuccessGreen else TextMuted,
                        contentColor = Color.White
                    ) {
                        Text(
                            text = if (!vendor.isActive) "CURRENTLY CLOSED" else if (isOpen) "OPEN NOW" else "CLOSED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = vendor.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (vendor.isActive) TextPrimary else TextMuted
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "View Menu",
                        tint = NaturalPrimary
                    )
                }

                if (avgRating > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = TangerineOrange,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "%.1f".format(avgRating),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = TextPrimary
                        )
                    }
                }

                if (!vendor.address.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = TextMuted
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = vendor.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (!vendor.openingTime.isNullOrBlank() && !vendor.closingTime.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = TextMuted
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Hours: ${vendor.openingTime} - ${vendor.closingTime}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
