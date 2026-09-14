package com.example

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.City
import com.example.ui.components.PriceDisplay
import com.example.ui.components.SndmartTopBar
import com.example.ui.theme.*
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    composeTestRule.setContent {
      SndmartTheme {
        PriceDisplay(price = 149.0, mrp = 199.0, unit = "kg")
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }

  @OptIn(ExperimentalMaterial3Api::class)
  @Test
  fun home_screen_screenshot() {
    composeTestRule.setContent {
      SndmartTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            SndmartTopBar(
              currentCity = City(id = "1", name = "Bengaluru", state = "Karnataka", status = "active"),
              cartCount = 2,
              deliveryAddressLabel = "Indiranagar",
              unreadNotificationCount = 1,
              onCityClick = {},
              onCartClick = {},
              onNotificationsClick = {},
              onSettingsClick = {}
            )

            // Search Bar
            OutlinedTextField(
              value = "",
              onValueChange = {},
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
              placeholder = {
                Text("Search fresh vegetables, fruits, dairy, food...", color = TextSecondary, fontSize = 14.sp)
              },
              leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary)
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

            // Delivery Banner
            Card(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
              shape = RoundedCornerShape(16.dp),
              colors = CardDefaults.cardColors(containerColor = NaturalPrimary)
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "⚡ Instant Delivery in 15 Mins",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                    text = "Farm-fresh vegetables, organic fruits & dairy at lowest prices",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp
                  )
                }
                Surface(
                  shape = RoundedCornerShape(12.dp),
                  color = Color.White.copy(alpha = 0.2f)
                ) {
                  Text(
                    text = "UP TO 40% OFF",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                  )
                }
              }
            }

            // Category Chips
            val categories = listOf("All Items", "🥬 Vegetables", "🍎 Fruits", "🥛 Dairy", "🌾 Staples", "🍿 Snacks")
            LazyRow(
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              items(categories) { cat ->
                val isSelected = cat == "All Items"
                Surface(
                  shape = RoundedCornerShape(20.dp),
                  color = if (isSelected) NaturalPrimary else SurfaceVariantLight,
                  border = BorderStroke(1.dp, if (isSelected) NaturalPrimary else OutlineBorder),
                  modifier = Modifier.height(36.dp)
                ) {
                  Box(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                  ) {
                    Text(
                      text = cat,
                      color = if (isSelected) Color.White else TextPrimary,
                      fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                      fontSize = 13.sp
                    )
                  }
                }
              }
            }

            // Products Section Header
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "Fresh Vegetables & Essentials",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
              )
              Text(
                text = "See all",
                style = MaterialTheme.typography.labelMedium,
                color = NaturalPrimary,
                fontWeight = FontWeight.SemiBold
              )
            }

            // Sample Product Cards Row
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              // Card 1
              Card(
                modifier = Modifier
                  .weight(1f)
                  .clip(RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
              ) {
                Column(modifier = Modifier.padding(12.dp)) {
                  Surface(
                    modifier = Modifier
                      .fillMaxWidth()
                      .height(90.dp)
                      .clip(RoundedCornerShape(10.dp)),
                    color = SurfaceVariantLight
                  ) {
                    Box(contentAlignment = Alignment.Center) {
                      Text("🍅", fontSize = 40.sp)
                    }
                  }
                  Spacer(modifier = Modifier.height(8.dp))
                  Text(
                    "Farm Fresh Tomatoes",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text("500 g", color = TextSecondary, fontSize = 11.sp)
                  Spacer(modifier = Modifier.height(6.dp))
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    PriceDisplay(price = 28.0, mrp = 38.0, unit = "")
                    Button(
                      onClick = {},
                      colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                      modifier = Modifier.height(30.dp),
                      shape = RoundedCornerShape(8.dp)
                    ) {
                      Text("ADD", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                  }
                }
              }

              // Card 2
              Card(
                modifier = Modifier
                  .weight(1f)
                  .clip(RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
              ) {
                Column(modifier = Modifier.padding(12.dp)) {
                  Surface(
                    modifier = Modifier
                      .fillMaxWidth()
                      .height(90.dp)
                      .clip(RoundedCornerShape(10.dp)),
                    color = SurfaceVariantLight
                  ) {
                    Box(contentAlignment = Alignment.Center) {
                      Text("🥦", fontSize = 40.sp)
                    }
                  }
                  Spacer(modifier = Modifier.height(8.dp))
                  Text(
                    "Organic Broccoli",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text("1 pc (~250-350g)", color = TextSecondary, fontSize = 11.sp)
                  Spacer(modifier = Modifier.height(6.dp))
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    PriceDisplay(price = 65.0, mrp = 90.0, unit = "")
                    Button(
                      onClick = {},
                      colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                      modifier = Modifier.height(30.dp),
                      shape = RoundedCornerShape(8.dp)
                    ) {
                      Text("ADD", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                  }
                }
              }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Bottom Navigation Bar
            NavigationBar(
              containerColor = Color.White,
              tonalElevation = 8.dp
            ) {
              NavigationBarItem(
                selected = true,
                onClick = {},
                icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                label = { Text("Home", fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                  selectedIconColor = NaturalPrimary,
                  selectedTextColor = NaturalPrimary,
                  indicatorColor = NaturalPrimaryContainer
                )
              )
              NavigationBarItem(
                selected = false,
                onClick = {},
                icon = { Icon(Icons.Outlined.ReceiptLong, contentDescription = "Orders") },
                label = { Text("Orders", fontSize = 11.sp) }
              )
              NavigationBarItem(
                selected = false,
                onClick = {},
                icon = { Icon(Icons.Outlined.AccountBalanceWallet, contentDescription = "Wallet") },
                label = { Text("Wallet", fontSize = 11.sp) }
              )
              NavigationBarItem(
                selected = false,
                onClick = {},
                icon = { Icon(Icons.Outlined.Person, contentDescription = "Profile") },
                label = { Text("Profile", fontSize = 11.sp) }
              )
            }
          }
        }
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/home_screen.png")
  }
}

