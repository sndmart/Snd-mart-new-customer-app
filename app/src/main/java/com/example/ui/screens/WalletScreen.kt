package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CustomerWalletTransaction
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.ui.components.ErrorCard
import com.example.ui.components.OrderListSkeleton
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    repository: SndmartRepository,
    sessionManager: UserSessionManager,
    onBack: () -> Unit
) {
    val PAGE_SIZE = 20
    val coroutineScope = rememberCoroutineScope()
    val userId = sessionManager.userId.collectAsState().value

    var transactions by remember { mutableStateOf<List<CustomerWalletTransaction>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var walletBalance by remember { mutableStateOf<Double?>(null) }
    var isBalanceError by remember { mutableStateOf(false) }

    fun loadBalance() {
        if (userId.isNullOrBlank()) return
        coroutineScope.launch {
            val res = repository.getWalletBalance(userId)
            if (res.isSuccess) {
                walletBalance = res.getOrNull()
                isBalanceError = false
            } else {
                isBalanceError = true
            }
        }
    }

    fun loadWallet(reset: Boolean = true) {
        if (userId.isNullOrBlank()) {
            isLoading = false
            return
        }
        coroutineScope.launch {
            if (reset) {
                isLoading = true
                errorMessage = null
            } else {
                isLoadingMore = true
            }

            val offset = if (reset) 0 else transactions.size
            val res = repository.getWalletTransactions(userId, limit = PAGE_SIZE, offset = offset)
            if (res.isSuccess) {
                val list = res.getOrNull() ?: emptyList()
                if (reset) {
                    transactions = list
                } else {
                    transactions = transactions + list
                }
                hasMore = list.size >= PAGE_SIZE
            } else {
                if (reset) {
                    errorMessage = res.exceptionOrNull()?.message
                }
            }
            isLoading = false
            isLoadingMore = false
        }
    }

    LaunchedEffect(userId) {
        loadWallet(reset = true)
        loadBalance()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Wallet", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            loadWallet(reset = true)
                            loadBalance()
                        },
                        modifier = Modifier.testTag("refresh_wallet_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (isLoading && transactions.isEmpty()) {
                // Rule 7: Skeleton loading
                OrderListSkeleton(count = 3)
            } else if (errorMessage != null && transactions.isEmpty()) {
                ErrorCard(
                    message = errorMessage!!,
                    onRetry = { loadWallet(reset = true) },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Balance card
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wallet_balance_card"),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = NaturalPrimary,
                                contentColor = Color.White
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.AccountBalanceWallet,
                                        contentDescription = null,
                                        tint = PastelMint,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "SNDMART WALLET BALANCE",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White.copy(alpha = 0.85f),
                                        letterSpacing = 1.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                if (isBalanceError) {
                                    Text(
                                        "Balance unavailable",
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                } else {
                                    Text(
                                        if (walletBalance != null) "₹${"%.2f".format(walletBalance)}" else "...",
                                        fontSize = 38.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Refunds from cancelled orders are credited here automatically.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }

                    item {
                        Text(
                            "Transaction History",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (transactions.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(28.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No wallet transactions recorded.",
                                        color = TextSecondary,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    } else {
                        items(transactions, key = { it.id ?: it.hashCode().toString() }) { txn ->
                            val isCredit = txn.type.lowercase() == "credit"
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = txn.reason ?: (if (isCredit) "Wallet Credit" else "Wallet Debit"),
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                        if (!txn.orderId.isNullOrBlank()) {
                                            Text(
                                                text = "Order #${txn.orderId.take(8)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = NaturalPrimary,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        if (!txn.createdAt.isNullOrBlank()) {
                                            Text(
                                                text = txn.createdAt.take(16).replace("T", " "),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextMuted
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${if (isCredit) "+" else "-"}₹${"%.2f".format(txn.amount)}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = if (isCredit) DarkGreenText else NaturalBadgeRed
                                    )
                                }
                            }
                        }

                        if (hasMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isLoadingMore) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(28.dp),
                                            strokeWidth = 2.5.dp,
                                            color = NaturalPrimary
                                        )
                                    } else {
                                        OutlinedButton(
                                            onClick = { loadWallet(reset = false) },
                                            shape = RoundedCornerShape(20.dp),
                                            modifier = Modifier.testTag("load_more_wallet_button")
                                        ) {
                                            Text("Load More Transactions (20)")
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
