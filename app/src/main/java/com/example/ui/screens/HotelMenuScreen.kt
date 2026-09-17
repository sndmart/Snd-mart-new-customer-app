package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import com.example.data.model.CartItem
import com.example.data.model.Category
import com.example.data.model.ResolvedProduct
import com.example.data.model.Vendor
import com.example.data.repository.AddToCartResult
import com.example.data.repository.SndmartRepository
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private fun getHotelMenuItemTier(prod: ResolvedProduct, isHotelActive: Boolean): Int {
    val isAvail = isHotelActive && prod.isHotelItemAvailable
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
    onNavigateToCart: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val MENU_PAGE_SIZE = 30

    var vendor by remember { mutableStateOf<Vendor?>(null) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var allProducts by remember { mutableStateOf<List<ResolvedProduct>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMoreProducts by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val hotelCart by repository.hotelCart.collectAsState()
    var pendingConflict by remember { mutableStateOf<AddToCartResult.HotelConflict?>(null) }

    // Rule 1: menu items load 30 at a time, not the whole menu — categories only need
    // fetching on the first page since they don't change across pages.
    fun loadMenu(reset: Boolean = true) {
        coroutineScope.launch {
            if (reset) {
                isLoading = true
                errorMessage = null
            } else {
                isLoadingMore = true
            }
            val offset = if (reset) 0 else allProducts.size
            // Rule 4: Parallelize independent requests
            coroutineScope {
                val vDeferred = if (reset) async { repository.getVendor(vendorId) } else null
                val menuDeferred = async {
                    repository.getHotelMenu(vendorId = vendorId, cityId = cityId, limit = MENU_PAGE_SIZE, offset = offset)
                }

                if (vDeferred != null) {
                    val vRes = vDeferred.await()
                    if (vRes.isSuccess) {
                        vendor = vRes.getOrNull()
                    }
                }

                val res = menuDeferred.await()
                if (res.isSuccess) {
                    val pair = res.getOrNull()!!
                    if (pair.first.isNotEmpty()) {
                        categories = pair.first
                    }
                    val newProducts = pair.second
                    allProducts = if (reset) newProducts else (allProducts + newProducts).distinctBy { it.id }
                    hasMoreProducts = newProducts.size >= MENU_PAGE_SIZE
                } else if (reset) {
                    errorMessage = res.exceptionOrNull()?.message
                }
            }
            isLoading = false
            isLoadingMore = false
        }
    }

    LaunchedEffect(vendorId, cityId) {
        loadMenu()
    }

    // Re-fetch on resume
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vendorId, cityId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                loadMenu()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
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
            val count = hotelCart.sumOf { it.quantity }
            if (count > 0) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "$count item(s) in Hotel Cart",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Single hotel checkout",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Button(
                            onClick = onNavigateToCart,
                            colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.testTag("hotel_view_cart_button")
                        ) {
                            Text("View Cart")
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (isLoading && allProducts.isEmpty()) {
                // Rule 7: Skeleton loading state instead of a spinner
                OrderListSkeleton(count = 5)
            } else if (errorMessage != null && allProducts.isEmpty()) {
                ErrorCard(
                    message = errorMessage!!,
                    onRetry = { loadMenu() },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (allProducts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No menu items currently listed for this hotel.", color = TextSecondary)
                }
            } else {
                val isHotelActive = vendor?.isActive ?: true

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!isHotelActive) {
                        item(key = "closed_warning_banner") {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = NaturalBadgeRed.copy(alpha = 0.12f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, NaturalBadgeRed.copy(alpha = 0.35f))
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
                                    Text(
                                        text = "This restaurant is currently closed. You can browse the menu, but ordering is unavailable.",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = NaturalBadgeRed
                                    )
                                }
                            }
                        }
                    }

                    // If categories exist, group items or show all items with category headers
                    if (categories.isNotEmpty()) {
                        categories.forEach { category ->
                            val prodsForCat = allProducts.filter { it.categoryId == category.id }
                                .sortedWith(
                                    compareBy<ResolvedProduct> { getHotelMenuItemTier(it, isHotelActive) }
                                        .thenBy { it.name.lowercase() }
                                )
                            if (prodsForCat.isNotEmpty()) {
                                item(key = "cat_${category.id}") {
                                    Text(
                                        text = category.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = NaturalPrimary,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                    )
                                }
                                items(prodsForCat, key = { it.id }) { product ->
                                    val inCart = hotelCart.find { it.productId == product.id }
                                    val qty = inCart?.quantity ?: 0
                                    MenuItemCard(
                                        product = product,
                                        quantityInCart = qty,
                                        isHotelActive = isHotelActive,
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
                            }
                        }

                        // Also check items without category
                        val uncategorized = allProducts.filter { prod -> categories.none { it.id == prod.categoryId } }
                            .sortedWith(
                                compareBy<ResolvedProduct> { getHotelMenuItemTier(it, isHotelActive) }
                                    .thenBy { it.name.lowercase() }
                            )
                        if (uncategorized.isNotEmpty()) {
                            item(key = "cat_other") {
                                Text(
                                    text = "Other Special Dishes",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = NaturalPrimary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                            }
                            items(uncategorized, key = { it.id }) { product ->
                                val inCart = hotelCart.find { it.productId == product.id }
                                val qty = inCart?.quantity ?: 0
                                MenuItemCard(
                                    product = product,
                                    quantityInCart = qty,
                                    isHotelActive = isHotelActive,
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
                        }
                    } else {
                        val sortedAll = allProducts.sortedWith(
                            compareBy<ResolvedProduct> { getHotelMenuItemTier(it, isHotelActive) }
                                .thenBy { it.name.lowercase() }
                        )
                        items(sortedAll, key = { it.id }) { product ->
                            val inCart = hotelCart.find { it.productId == product.id }
                            val qty = inCart?.quantity ?: 0
                            MenuItemCard(
                                product = product,
                                quantityInCart = qty,
                                isHotelActive = isHotelActive,
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
                    }

                    if (hasMoreProducts) {
                        item(key = "load_more_menu_items") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 2.5.dp,
                                        color = NaturalPrimary
                                    )
                                } else {
                                    OutlinedButton(onClick = { loadMenu(reset = false) }) {
                                        Text("Load More Items")
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
fun MenuItemCard(
    product: ResolvedProduct,
    quantityInCart: Int,
    isHotelActive: Boolean = true,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit
) {
    val isAvailable = isHotelActive && product.isHotelItemAvailable

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
