package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.Category
import androidx.compose.runtime.remember
import com.example.ui.theme.*

/**
 * Horizontal scroll of grocery category image cards (image_url + name).
 * Only "Vegitables" and "Fruits" categories are displayed (no "All" tab).
 * Tapping a card selects that category.
 */
@Composable
fun GroceryCategoryList(
    categories: List<Category>,
    selectedCategoryId: String?,
    onCategorySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Only "Vegitables" and "Fruits" categories remain, with Vegetables first
    val displayCategories = remember(categories) {
        val filtered = categories.filter { c ->
            val n = c.name.lowercase()
            n.contains("veg") || n.contains("fruit")
        }
        if (filtered.isNotEmpty()) {
            filtered.sortedBy { if (it.name.lowercase().contains("veg")) 0 else 1 }
        } else {
            categories
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "Shop by Category",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("grocery_category_list")
        ) {
            items(displayCategories, key = { it.id }) { category ->
                GroceryCategoryCard(
                    name = category.name,
                    imageUrl = category.imageUrl,
                    isSelected = selectedCategoryId == category.id,
                    fallbackIcon = Icons.Outlined.Storefront,
                    onClick = {
                        onCategorySelected(category.id)
                    },
                    testTag = "category_item_${category.id}"
                )
            }
        }
    }
}

@Composable
private fun GroceryCategoryCard(
    name: String,
    imageUrl: String?,
    isSelected: Boolean,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    testTag: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .testTag(testTag)
            .padding(vertical = 2.dp)
    ) {
        Surface(
            shape = CircleShape,
            modifier = Modifier.size(64.dp),
            color = if (isSelected) PastelSage else MaterialTheme.colorScheme.surfaceVariant,
            border = if (isSelected) BorderStroke(2.5.dp, NaturalPrimary)
            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            val fallbackPainter = androidx.compose.ui.graphics.vector.rememberVectorPainter(fallbackIcon)
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    placeholder = fallbackPainter,
                    error = fallbackPainter,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = fallbackIcon,
                        contentDescription = name,
                        tint = if (isSelected) NaturalPrimary else TextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = name,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) NaturalPrimary else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
