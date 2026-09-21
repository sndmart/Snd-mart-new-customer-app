package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Fastfood
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.*
import com.example.data.repository.AddToCartResult
import com.example.data.repository.SndmartRepository
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.util.isVendorWithinOperatingHours
import com.example.util.isWithinAnySlot
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private fun getHotelMenuItemTier(prod: ResolvedProduct, isHotelActive: Boolean, vendorSlots: List<OperatingSlot> = emptyList()): Int {
    val isAvail = isHotelActive && prod.isHotelItemAvailable(vendorSlots)
    return when {
        isAvail && prod.isFeatured -> 0
        isAvail -> 1
        else -> 2
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotelMenuScreen(
    vendorId: String,
    vendorName: String,
    cityId: String,
    repository: SndmartRepository,
    onBack: () -> Unit,
    onNavigateToCart: () -> Unit,
    onProceedToCheckout: (isHotel: Boolean) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var vendor by remember { mutableStateOf<Vendor?>(null) }
    var operatingSlots by remember { mutableStateOf<List<OperatingSlot>>(emptyList()) }
    val effectiveSlots by remember(operatingSlots, vendor) {
        derivedStateOf {
            if (operatingSlots.isNotEmpty()) {
                operatingSlots
            } else if (!vendor?.openingTime.isNullOrBlank() && !vendor?.closingTime.isNullOrBlank()) {
                listOf(
                    OperatingSlot(
                        vendorId = vendor?.id ?: "",
                        startTime = vendor!!.openingTime!!,
                        endTime = vendor!!.closingTime!!
                    )
                )
            } else {
                emptyList()
            }
        }
    }
    var categories by remember(vendorId) {
        mutableStateOf(repository.getCachedHotelCategories(vendorId) ?: emptyList())
    }
    var categoryProducts by remember(vendorId) { mutableStateOf<List<ResolvedProduct>>(emptyList()) }
    var selectedCategoryId by remember(vendorId) {
        mutableStateOf(categories.firstOrNull()?.id)
    }
    var lastLoadedCategoryId by remember(vendorId) { mutableStateOf<String?>(null) }
    var isLoadingCategories by remember { mutableStateOf(categories.isEmpty()) }
    var isLoadingProducts by remember { mutableStateOf(false) }
    var isLoadingMoreProducts by remember { mutableStateOf(false) }
    var hasMoreProducts by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val menuListState = rememberLazyListState()

    val hotelCart by repository.hotelCart.collectAsState()
    var pendingConflict by remember { mutableStateOf<AddToCartResult.HotelConflict?>(null) }

    val thisHotelCartItems = remember(hotelCart, vendorId) {
        hotelCart.filter { it.vendorId == vendorId || it.vendorId.isNullOrBlank() }
    }
    val thisHotelItemCount = thisHotelCartItems.sumOf { it.quantity }

    var freshHotelCartItems by remember { mutableStateOf<List<CartItemUi>>(emptyList()) }
    LaunchedEffect(hotelCart, cityId) {
        if (cityId.isNotBlank() && hotelCart.isNotEmpty()) {
            val res = repository.getFreshCartItems(isHotel = true, cityId = cityId)
            if (res.isSuccess) {
                freshHotelCartItems = res.getOrNull() ?: emptyList()
            }
        } else {
            freshHotelCartItems = emptyList()
        }
    }

    val thisHotelTotalPrice = remember(thisHotelCartItems, freshHotelCartItems, categoryProducts) {
        val freshForThis = freshHotelCartItems.filter { it.cartItem.vendorId == vendorId || it.cartItem.vendorId.isNullOrBlank() }
        if (freshForThis.isNotEmpty()) {
            freshForThis.sumOf { it.totalPrice }
        } else {
            thisHotelCartItems.sumOf { item ->
                val prod = categoryProducts.find { it.id == item.productId }
                (prod?.effectivePrice ?: 0.0) * item.quantity
            }
        }
    }

    BackHandler {
        onBack()
    }

    fun loadInitialData(forceRefresh: Boolean = false) {
        if (!forceRefresh && categories.isNotEmpty() && vendor != null) {
            return
        }
        coroutineScope.launch {
            if (categories.isEmpty()) {
                isLoadingCategories = true
            }
            errorMessage = null
            coroutineScope {
                val vDeferred = async { repository.getVendor(vendorId) }
                val sDeferred = async { repository.getVendorOperatingSlots(vendorId) }
                val cDeferred = async { repository.getHotelCategories(vendorId, forceRefresh = forceRefresh) }

                val vRes = vDeferred.await()
                if (vRes.isSuccess) {
                    vendor = vRes.getOrNull()
                }

                val sRes = sDeferred.await()
                if (sRes.isSuccess) {
                    operatingSlots = sRes.getOrNull() ?: emptyList()
                }

                val cRes = cDeferred.await()
                if (cRes.isSuccess) {
                    val cats = cRes.getOrNull() ?: emptyList()
                    categories = cats
                    if (cats.isNotEmpty() && (selectedCategoryId == null || cats.none { it.id == selectedCategoryId })) {
                        selectedCategoryId = cats.first().id
                    }
                } else {
                    errorMessage = cRes.exceptionOrNull()?.message
                }
            }
            isLoadingCategories = false
        }
    }

    // Rule 4: Paginate hotel menu items by 20-30 at a time (default 25)
    // Rule 3: Prevent redundant re-fetching when already loaded
    fun loadProductsForCategory(catId: String?, reset: Boolean = true, forceRefresh: Boolean = false) {
        if (catId == null) return

        // Prevent redundant re-fetch if already loaded
        if (reset && !forceRefresh && catId == lastLoadedCategoryId && categoryProducts.isNotEmpty()) {
            return
        }

        // Check in-memory cache first for instant category switching
        if (reset && !forceRefresh) {
            val cached = repository.getCachedHotelProducts(vendorId, cityId, catId)
            if (cached != null && cached.isNotEmpty()) {
                categoryProducts = cached
                lastLoadedCategoryId = catId
                isLoadingProducts = false
                hasMoreProducts = cached.size >= 25
                return
            }
        }

        coroutineScope.launch {
            if (reset) {
                isLoadingProducts = true
                errorMessage = null
            } else {
                isLoadingMoreProducts = true
            }

            val offset = if (reset) 0 else categoryProducts.size
            val res = repository.getHotelProducts(
                vendorId = vendorId,
                cityId = cityId,
                categoryId = catId,
                limit = 25,
                offset = offset,
                forceRefresh = forceRefresh
            )
            if (res.isSuccess) {
                val newItems = res.getOrNull() ?: emptyList()
                if (reset) {
                    categoryProducts = newItems
                    lastLoadedCategoryId = catId
                } else {
                    categoryProducts = (categoryProducts + newItems).distinctBy { it.id }
                }
                hasMoreProducts = newItems.size >= 25
            } else {
                if (reset) {
                    errorMessage = res.exceptionOrNull()?.message
                }
            }
            isLoadingProducts = false
            isLoadingMoreProducts = false
        }
    }

    LaunchedEffect(vendorId, cityId) {
        loadInitialData()
    }

    LaunchedEffect(selectedCategoryId) {
        if (selectedCategoryId != null) {
            loadProductsForCategory(selectedCategoryId, reset = true)
        }
    }

    // Auto-paginate on scroll near bottom
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = menuListState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= totalItems - 3
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && hasMoreProducts && !isLoadingMoreProducts && !isLoadingProducts) {
            loadProductsForCategory(selectedCategoryId, reset = false)
        }
    }

    // Single-hotel conflict dialog
    if (pendingConflict != null) {
        val conflict = pendingConflict!!
        AlertDialog(
            onDismissRequest = { pendingConflict = null },
            title = { Text("Replace Hotel Cart Items?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Your cart already contains items from another hotel. Clear existing items and add from $vendorName?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.forceClearHotelCartAndAdd(conflict.pendingItem)
                        pendingConflict = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Cart cleared and item added")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("Clear & Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConflict = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = vendorName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Hotel Menu",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("hotel_menu_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            loadInitialData(forceRefresh = true)
                            loadProductsForCategory(selectedCategoryId, reset = true, forceRefresh = true)
                        },
                        modifier = Modifier.testTag("hotel_menu_refresh_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Menu")
                    }
                    IconButton(onClick = onNavigateToCart) {
                        val count = hotelCart.sumOf { it.quantity }
                        BadgedBox(
                            badge = {
                                if (count > 0) {
                                    Badge(containerColor = NaturalPrimary, contentColor = Color.White) {
                                        Text("$count", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.ShoppingCart, contentDescription = "View Cart")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AnimatedVisibility(
                visible = thisHotelItemCount > 0,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                FloatingCartButton(
                    itemCount = thisHotelItemCount,
                    totalPrice = thisHotelTotalPrice,
                    onClick = { onProceedToCheckout(true) },
                    testTag = "hotel_floating_checkout_button"
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if ((isLoadingCategories || isLoadingProducts) && categoryProducts.isEmpty()) {
                // Rule 7: Skeleton loading state instead of a spinner
                OrderListSkeleton(count = 5)
            } else if (errorMessage != null && categoryProducts.isEmpty()) {
                ErrorCard(
                    message = errorMessage!!,
                    onRetry = {
                        loadInitialData(forceRefresh = true)
                        loadProductsForCategory(selectedCategoryId, reset = true, forceRefresh = true)
                    },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                val isHotelActive = vendor?.isActive ?: true
                val isWithinHours = isWithinAnySlot(effectiveSlots)
                val isHotelOpen = isHotelActive && isWithinHours

                // Filter items reacting to the selected category (Swiggy-style)
                // The item grid should always be filtered to whichever category is currently selected - never show all of this hotel's items unfiltered.
                // Preserving existing sort logic: available+featured first, unavailable last
                val displayedProducts = remember(categoryProducts, isHotelOpen, effectiveSlots) {
                    categoryProducts.sortedWith(
                        compareBy<ResolvedProduct> { getHotelMenuItemTier(it, isHotelOpen, effectiveSlots) }
                            .thenByDescending { it.isFeatured }
                            .thenBy { it.name.lowercase() }
                    )
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    if (!isHotelOpen) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = NaturalBadgeRed.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, NaturalBadgeRed.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = NaturalBadgeRed,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                val bannerMessage = when {
                                    !isWithinHours && operatingSlots.isNotEmpty() ->
                                        "This hotel is currently closed outside operating hours (${operatingSlots.joinToString(", ") { "${it.startTime}-${it.endTime}" }}). Ordering is unavailable."
                                    !isWithinHours && !vendor?.openingTime.isNullOrBlank() ->
                                        "This hotel is currently closed. Opens at ${vendor?.openingTime}."
                                    !isWithinHours ->
                                        "This hotel is currently closed outside operating hours. Ordering is unavailable."
                                    else ->
                                        "This restaurant is currently closed. You can browse the menu, but ordering is unavailable."
                                }
                                Text(
                                    text = bannerMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = NaturalBadgeRed
                                )
                            }
                        }
                    }

                    // 1 & 4: Horizontal category row (fixed at top, does not scroll away)
                    if (categories.isNotEmpty()) {
                        HotelCategoryTabsRow(
                            categories = categories,
                            selectedCategoryId = selectedCategoryId,
                            onCategorySelected = { selectedCategoryId = it },
                            modifier = Modifier.fillMaxWidth()
                        )

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            thickness = 1.dp
                        )
                    }

                    // 3 & 4: Single scrollable grid/list of items below reacting to the selected tab
                    if (displayedProducts.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Outlined.RestaurantMenu,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = if (categories.isEmpty()) "No menu items currently listed for this hotel."
                                    else "No items found in this category.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextMuted
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = menuListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("hotel_menu_items_list"),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (!vendor?.bannerUrl.isNullOrBlank()) {
                                item(key = "hotel_banner_header") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(140.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                    ) {
                                        ProductImage(
                                            url = vendor!!.bannerUrl,
                                            contentDescription = vendorName,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }

                            items(displayedProducts, key = { it.id }) { product ->
                                val inCart = hotelCart.find { it.productId == product.id }
                                val qty = inCart?.quantity ?: 0
                                MenuItemCard(
                                    product = product,
                                    quantityInCart = qty,
                                    isHotelActive = isHotelOpen,
                                    vendorSlots = effectiveSlots,
                                    onIncrease = {
                                        val res = repository.addToCart(
                                            productId = product.id,
                                            vendorId = vendorId,
                                            cityId = cityId,
                                            quantityDelta = 1,
                                            isHotel = true
                                        )
                                        if (res is AddToCartResult.HotelConflict) {
                                            pendingConflict = res
                                        }
                                    },
                                    onDecrease = {
                                        repository.addToCart(
                                            productId = product.id,
                                            vendorId = vendorId,
                                            cityId = cityId,
                                            quantityDelta = -1,
                                            isHotel = true
                                        )
                                    }
                                )
                            }

                            if (hasMoreProducts) {
                                item(key = "hotel_menu_load_more") {
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
                                                    loadProductsForCategory(selectedCategoryId, reset = false)
                                                },
                                                shape = RoundedCornerShape(20.dp),
                                                border = BorderStroke(1.dp, NaturalPrimary.copy(alpha = 0.5f))
                                            ) {
                                                Text("Load more items", color = NaturalPrimary, style = MaterialTheme.typography.labelLarge)
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

/**
 * Swiggy-style horizontal scrollable row of circular category tabs.
 * Only the hotel's real categories (e.g. "Biriyani", "fast food") are displayed (no "All" tab).
 * Selected category tab is visually highlighted with an active orange ring.
 */
@Composable
fun HotelCategoryTabsRow(
    categories: List<Category>,
    selectedCategoryId: String?,
    onCategorySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("hotel_category_tabs_row"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(categories, key = { it.id }) { category ->
                HotelCategoryCircleTab(
                    title = category.name,
                    imageUrl = category.imageUrl,
                    isSelected = selectedCategoryId == category.id,
                    onClick = { onCategorySelected(category.id) },
                    testTag = "hotel_category_tab_${category.id}"
                )
            }
        }
    }
}

/**
 * Circular Category Tab Item (Swiggy-style):
 * - 64dp circular avatar with active colored ring when selected.
 * - Center-aligned label below with clear bold contrast on selection.
 */
@Composable
fun HotelCategoryCircleTab(
    title: String,
    imageUrl: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isAllOption: Boolean = false,
    testTag: String = ""
) {
    val activeColor = NaturalPrimary
    val ringColor = if (isSelected) activeColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val ringWidth = if (isSelected) 2.5.dp else 1.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .testTag(testTag)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
            .widthIn(min = 64.dp, max = 76.dp)
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .border(
                    BorderStroke(ringWidth, ringColor),
                    shape = CircleShape
                )
                .padding(if (isSelected) 2.5.dp else 0.dp)
                .clip(CircleShape)
                .background(
                    if (isAllOption && isSelected) activeColor.copy(alpha = 0.14f)
                    else if (isAllOption) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isAllOption) {
                Icon(
                    imageVector = Icons.Outlined.RestaurantMenu,
                    contentDescription = "All items",
                    tint = if (isSelected) activeColor else TextSecondary,
                    modifier = Modifier.size(26.dp)
                )
            } else if (!imageUrl.isNullOrBlank()) {
                ProductImage(
                    url = imageUrl,
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Fastfood,
                    contentDescription = title,
                    tint = if (isSelected) activeColor else TextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) activeColor else TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp
        )
    }
}

@Composable
fun MenuItemCard(
    product: ResolvedProduct,
    quantityInCart: Int,
    isHotelActive: Boolean = true,
    vendorSlots: List<OperatingSlot> = emptyList(),
    onIncrease: () -> Unit,
    onDecrease: () -> Unit
) {
    val isAvailable = isHotelActive && product.isHotelItemAvailable(vendorSlots)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isAvailable) 1f else 0.65f)
            .testTag("menu_item_${product.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAvailable) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                ProductImage(
                    url = product.imageUrl,
                    contentDescription = product.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isAvailable) 1f else 0.7f)
                )
                if (isAvailable && product.isFeatured) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = PastelPeach,
                        contentColor = DarkGreenText
                    ) {
                        Text(
                            "POPULAR",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                if (!isAvailable) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Black.copy(alpha = 0.45f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = NaturalBadgeRed,
                                contentColor = Color.White
                            ) {
                                Text(
                                    text = if (!isHotelActive) "CLOSED" else "UNAVAILABLE",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isAvailable) TextPrimary else TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!product.description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = product.description!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!product.baseProduct.availableFrom.isNullOrBlank() && !product.baseProduct.availableUntil.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Available ${product.baseProduct.availableFrom} - ${product.baseProduct.availableUntil}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isAvailable) NaturalPrimary else NaturalBadgeRed,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                PriceDisplay(
                    price = product.effectivePrice,
                    mrp = product.effectiveMrp,
                    unit = product.unit,
                    modifier = Modifier.alpha(if (isAvailable) 1f else 0.7f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isAvailable) {
                QuantityStepper(
                    quantity = quantityInCart,
                    onIncrease = onIncrease,
                    onDecrease = onDecrease,
                    testTagPrefix = "hotel_item_${product.id}"
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = if (!isHotelActive) "Closed" else "Unavailable",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
