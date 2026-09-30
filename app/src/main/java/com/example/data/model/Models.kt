package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class City(
    val id: String = "",
    val name: String = "",
    val state: String? = null,
    val status: String = "active", // 'active'/'inactive'/'coming_soon'
    @Json(name = "center_lat") val centerLat: Double? = null,
    @Json(name = "center_lng") val centerLng: Double? = null,
    @Json(name = "service_radius_km") val serviceRadiusKm: Double? = null
)

@JsonClass(generateAdapter = true)
data class CityLocationResult(
    @Json(name = "city_id") val cityId: String = "",
    @Json(name = "city_name") val cityName: String = "",
    @Json(name = "distance_km") val distanceKm: Double? = null
)

@JsonClass(generateAdapter = true)
data class PickupPoint(
    val lat: Double,
    val lng: Double
)

@JsonClass(generateAdapter = true)
data class Category(
    val id: String = "",
    val name: String = "",
    @Json(name = "name_kn") val nameKn: String? = null,
    val slug: String? = null,
    @Json(name = "image_url") val imageUrl: String? = null,
    @Json(name = "sort_order") val sortOrder: Int? = 0,
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "vendor_type") val vendorType: String? = null, // 'grocery'/'vegetable'/'fruit'/'hotel'
    @Json(name = "vendor_id") val vendorId: String? = null, // null for grocery, set for hotel menu category
    @Json(name = "city_id") val cityId: String? = null
)

@JsonClass(generateAdapter = true)
data class Vendor(
    val id: String = "",
    val name: String = "",
    @Json(name = "vendor_type") val vendorType: String = "hotel",
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @Json(name = "is_open") val isOpen: Boolean = true,
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "approval_status") val approvalStatus: String = "approved",
    @Json(name = "banner_url") val bannerUrl: String? = null,
    @Json(name = "city_id") val cityId: String? = null,
    @Json(name = "opening_time") val openingTime: String? = null,
    @Json(name = "closing_time") val closingTime: String? = null,
    @Json(name = "is_featured") val isFeatured: Boolean? = false
) {
    // Compatibility alias: wherever imageUrl is referenced for a vendor/hotel, resolve to bannerUrl
    val imageUrl: String? get() = bannerUrl
}

val Vendor.sortTier: Int
    get() = when {
        isActive && isFeatured == true -> 0
        isActive -> 1
        isFeatured == true -> 2
        else -> 3
    }

val HotelComparator: Comparator<Vendor> = compareBy<Vendor> { it.sortTier }
    .thenBy { it.name.lowercase() }

@JsonClass(generateAdapter = true)
data class OperatingSlot(
    val id: String = "",
    @Json(name = "vendor_id") val vendorId: String = "",
    @Json(name = "start_time") val startTime: String = "",
    @Json(name = "end_time") val endTime: String = "",
    @Json(name = "is_active") val isActive: Boolean = true
)

@JsonClass(generateAdapter = true)
data class ProductVariantCityStock(
    val price: Double = 0.0,
    @Json(name = "stock_qty") val stockQty: Int? = 0,
    @Json(name = "is_available") val isAvailable: Boolean = true,
    @Json(name = "city_id") val cityId: String? = null
)

@JsonClass(generateAdapter = true)
data class ProductVariant(
    val id: String = "",
    val label: String = "",
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "product_variant_city_stock") val cityStock: List<ProductVariantCityStock>? = null
)

@JsonClass(generateAdapter = true)
data class Product(
    val id: String = "",
    @Json(name = "category_id") val categoryId: String? = null,
    @Json(name = "vendor_id") val vendorId: String? = null, // null for generic grocery items
    val name: String = "",
    val description: String? = null,
    @Json(name = "image_url") val imageUrl: String? = null,
    val price: Double = 0.0,
    val mrp: Double? = null,
    val unit: String? = null,
    @Json(name = "stock_qty") val stockQty: Int? = null,
    @Json(name = "stock_quantity") val stockQuantity: Int? = null,
    @Json(name = "is_available") val isAvailable: Boolean = true,
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "is_featured") val isFeatured: Boolean? = false,
    @Json(name = "is_veg") val isVeg: Boolean? = null,
    @Json(name = "food_type") val foodType: String? = null,
    @Json(name = "available_from") val availableFrom: String? = null,
    @Json(name = "available_until") val availableUntil: String? = null,
    @Json(name = "product_variants") val productVariants: List<ProductVariant>? = null
)

@JsonClass(generateAdapter = true)
data class ProductCityStock(
    @Json(name = "product_id") val productId: String = "",
    @Json(name = "city_id") val cityId: String = "",
    val price: Double = 0.0,
    val mrp: Double? = null,
    @Json(name = "stock_qty") val stockQty: Int? = 0,
    @Json(name = "is_available") val isAvailable: Boolean = true
)

// UI Model: Individual resolved variant for the selected city
data class ResolvedVariant(
    val id: String,
    val label: String,
    val price: Double,
    val stockQty: Int,
    val isAvailable: Boolean
) {
    val isInStock: Boolean get() = isAvailable && stockQty > 0
}

// UI Model: Product resolved with fresh city price & stock override and optional variants
data class ResolvedProduct(
    val baseProduct: Product,
    val effectivePrice: Double,
    val effectiveMrp: Double?,
    val effectiveStock: Int,
    val effectiveIsAvailable: Boolean,
    val variants: List<ResolvedVariant> = emptyList()
) {
    val id: String get() = baseProduct.id
    val name: String get() = baseProduct.name
    val description: String? get() = baseProduct.description
    val imageUrl: String? get() = baseProduct.imageUrl
    val unit: String? get() = baseProduct.unit
    val vendorId: String? get() = baseProduct.vendorId
    val categoryId: String? get() = baseProduct.categoryId
    val isFeatured: Boolean get() = baseProduct.isFeatured == true
    val isActive: Boolean get() = baseProduct.isActive
    val isVeg: Boolean? get() = baseProduct.isVeg
    val foodType: String? get() = baseProduct.foodType
    val hasVariants: Boolean get() = variants.isNotEmpty()
    val startingPrice: Double? get() = if (hasVariants) variants.minOfOrNull { it.price } else null

    val isInStockAndActive: Boolean
        get() = if (hasVariants) {
            baseProduct.isActive && variants.any { it.isInStock }
        } else {
            baseProduct.isActive && effectiveIsAvailable && effectiveStock > 0
        }

    val isHotelItemAvailable: Boolean
        get() {
            if (!baseProduct.isActive || !effectiveIsAvailable) return false
            // Item-specific availability window check (e.g. breakfast only 07:00-11:00)
            if (!baseProduct.availableFrom.isNullOrBlank() && !baseProduct.availableUntil.isNullOrBlank()) {
                return com.example.util.isVendorWithinOperatingHours(
                    baseProduct.availableFrom,
                    baseProduct.availableUntil
                )
            }
            return true
        }

    fun isHotelItemAvailable(vendorSlots: List<OperatingSlot>): Boolean {
        if (!baseProduct.isActive || !effectiveIsAvailable) return false
        val hotelOpen = com.example.util.isWithinAnySlot(vendorSlots)
        if (!hotelOpen) return false
        if (!baseProduct.availableFrom.isNullOrBlank() && !baseProduct.availableUntil.isNullOrBlank()) {
            return com.example.util.isVendorWithinOperatingHours(
                baseProduct.availableFrom,
                baseProduct.availableUntil
            )
        }
        return true
    }
}

@JsonClass(generateAdapter = true)
data class CartItem(
    val id: String? = null,
    @Json(name = "user_id") val userId: String = "",
    @Json(name = "product_id") val productId: String = "",
    @Json(name = "variant_id") val variantId: String? = null,
    @Json(name = "vendor_id") val vendorId: String? = null,
    @Json(name = "city_id") val cityId: String? = null,
    val quantity: Int = 1
)

// UI Model for Cart item with freshly fetched product data and variant resolution
data class CartItemUi(
    val cartItem: CartItem,
    val product: ResolvedProduct,
    val variant: ResolvedVariant? = null
) {
    val effectivePrice: Double get() = variant?.price ?: product.effectivePrice
    val totalPrice: Double get() = effectivePrice * cartItem.quantity
    val displayName: String
        get() = if (variant != null && variant.label.isNotBlank()) {
            "${product.name} - ${variant.label}"
        } else {
            product.name
        }
}

@JsonClass(generateAdapter = true)
data class CustomerAddress(
    val id: String? = null,
    @Json(name = "user_id") val userId: String = "",
    val label: String = "Home", // Home, Work, Other
    @Json(name = "recipient_name") val recipientName: String = "",
    val phone: String = "",
    @Json(name = "address_line") val addressLine: String = "",
    val landmark: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    @Json(name = "city_id") val cityId: String? = null,
    @Json(name = "is_default") val isDefault: Boolean = false
)

@JsonClass(generateAdapter = true)
data class ExpressDeliveryConfig(
    val enabled: Boolean = false,
    @Json(name = "delivery_minutes") val deliveryMinutes: Int = 30,
    @Json(name = "distance_rates") val distanceRates: List<Map<String, Any?>>? = null
)

data class CouponValidationResult(
    val isValid: Boolean,
    val coupon: Coupon? = null,
    val discountAmount: Double = 0.0,
    val errorMessage: String? = null
)

@JsonClass(generateAdapter = true)
data class CityDeliverySettings(
    val id: String? = null,
    @Json(name = "city_id") val cityId: String = "",
    @Json(name = "free_delivery_min_order_amount") val freeDeliveryMinOrderAmount: Double? = null,
    @Json(name = "free_delivery_min_order") val freeDeliveryMinOrder: Double? = null,
    @Json(name = "express_delivery_minutes") val expressDeliveryMinutes: Int? = 30,
    @Json(name = "is_active") val isActive: Boolean? = true
) {
    val effectiveFreeDeliveryMinOrder: Double?
        get() = freeDeliveryMinOrderAmount ?: freeDeliveryMinOrder
}

@JsonClass(generateAdapter = true)
data class DeliveryZone(
    val id: String? = null,
    @Json(name = "city_id") val cityId: String? = null,
    val name: String? = null,
    @Json(name = "center_latitude") val centerLatitude: Double? = null,
    @Json(name = "center_longitude") val centerLongitude: Double? = null,
    @Json(name = "is_active") val isActive: Boolean? = true,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class Coupon(
    val id: String = "",
    val code: String = "",
    val description: String? = null,
    @Json(name = "discount_type") val discountType: String = "flat", // flat / percentage
    @Json(name = "discount_value") val discountValue: Double = 0.0,
    @Json(name = "min_order_amount") val minOrderAmount: Double? = 0.0,
    @Json(name = "max_discount_amount") val maxDiscountAmount: Double? = null,
    @Json(name = "usage_limit") val usageLimit: Int? = null,
    @Json(name = "used_count") val usedCount: Int? = 0,
    @Json(name = "starts_at") val startsAt: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "city_id") val cityId: String? = null
)

@JsonClass(generateAdapter = true)
data class CouponUsage(
    val id: String? = null,
    @Json(name = "coupon_id") val couponId: String = "",
    @Json(name = "user_id") val userId: String = "",
    @Json(name = "order_id") val orderId: String = ""
)

@JsonClass(generateAdapter = true)
data class Order(
    val id: String? = null,
    @Json(name = "order_number") val orderNumber: String = "",
    @Json(name = "customer_id") val customerId: String = "",
    @Json(name = "vendor_id") val vendorId: String? = null,
    @Json(name = "delivery_partner_id") val deliveryPartnerId: String? = null,
    @Json(name = "address_id") val addressId: String? = null,
    @Json(name = "slot_id") val slotId: String? = null,
    val status: String = "pending", // pending, confirmed, preparing, ready, out_for_delivery, delivered, cancelled, rejected
    @Json(name = "payment_method") val paymentMethod: String = "cod",
    @Json(name = "payment_status") val paymentStatus: String = "pending", // pending, paid, failed, refunded, cod
    val subtotal: Double = 0.0,
    @Json(name = "discount_amount") val discountAmount: Double = 0.0,
    @Json(name = "delivery_fee") val deliveryFee: Double = 0.0,
    @Json(name = "handling_fee") val handlingFee: Double = 0.0,
    @Json(name = "total_amount") val totalAmount: Double = 0.0,
    @Json(name = "city_id") val cityId: String? = null,
    @Json(name = "delivery_type") val deliveryType: String? = null,
    @Json(name = "delivery_distance_km") val deliveryDistanceKm: Double? = null,
    @Json(name = "delivery_option_snapshot") val deliveryOptionSnapshot: Map<String, @JvmSuppressWildcards Any?>? = null,
    @Json(name = "placed_at") val placedAt: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "delivery_partners") val deliveryPartner: DeliveryPartner? = null
)

@JsonClass(generateAdapter = true)
data class OrderItem(
    val id: String? = null,
    @Json(name = "order_id") val orderId: String? = null,
    @Json(name = "product_id") val productId: String = "",
    @Json(name = "product_name") val productName: String = "",
    @Json(name = "variant_label") val variantLabel: String? = null,
    val quantity: Int = 1,
    @Json(name = "unit_price") val unitPrice: Double = 0.0,
    @Json(name = "total_price") val totalPrice: Double = 0.0,
    @Json(name = "vendor_id") val vendorId: String? = null
)

@JsonClass(generateAdapter = true)
data class OrderStatusHistory(
    val id: String? = null,
    @Json(name = "order_id") val orderId: String = "",
    val status: String = "",
    val note: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class DeliveryAssignment(
    @Json(name = "order_id") val orderId: String = "",
    @Json(name = "delivery_partner_id") val deliveryPartnerId: String? = null,
    val status: String? = null,
    @Json(name = "accepted_at") val acceptedAt: String? = null,
    @Json(name = "estimated_delivery_minutes") val estimatedDeliveryMinutes: Int? = null,
    @Json(name = "estimated_delivery_at") val estimatedDeliveryAt: String? = null,
    @Json(name = "delivery_otp") val deliveryOtp: String? = null
) {
    val isAccepted: Boolean
        get() {
            val s = status?.lowercase()?.trim()
            return s == "accepted" || s == "picked_up" || s == "out_for_delivery"
        }
}

@JsonClass(generateAdapter = true)
data class DeliveryPartner(
    val id: String = "",
    val name: String = "",
    val phone: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @Json(name = "vehicle_type") val vehicleType: String? = null,
    @Json(name = "vehicle_number") val vehicleNumber: String? = null
)

@JsonClass(generateAdapter = true)
data class VendorReview(
    @Json(name = "vendor_id") val vendorId: String = "",
    @Json(name = "customer_id") val customerId: String = "",
    @Json(name = "order_id") val orderId: String? = null,
    val rating: Int = 5,
    val comment: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class DeliveryPartnerReview(
    @Json(name = "delivery_partner_id") val deliveryPartnerId: String = "",
    @Json(name = "customer_id") val customerId: String = "",
    @Json(name = "order_id") val orderId: String? = null,
    val rating: Int = 5,
    val comment: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class CustomerWalletTransaction(
    val id: String? = null,
    @Json(name = "customer_id") val customerId: String = "",
    @Json(name = "order_id") val orderId: String? = null,
    val type: String = "credit", // 'credit' / 'debit'
    val amount: Double = 0.0,
    val reason: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class WalletAmountRow(
    @Json(name = "type") val type: String = "",
    @Json(name = "amount") val amount: Double = 0.0
)

@JsonClass(generateAdapter = true)
data class DeviceToken(
    @Json(name = "user_id") val userId: String = "",
    @Json(name = "user_type") val userType: String = "customer",
    @Json(name = "fcm_token") val fcmToken: String = "",
    val active: Boolean = true,
    @Json(name = "firebase_project") val firebaseProject: String = "native"
)

@JsonClass(generateAdapter = true)
data class CustomerNotification(
    val id: String = "",
    @Json(name = "user_id") val userId: String? = null,
    @Json(name = "user_type") val userType: String? = null,
    val title: String? = null,
    val message: String? = null,
    val body: String? = null,
    @Json(name = "order_id") val orderId: String? = null,
    @Json(name = "is_read") val isRead: Boolean = false,
    @Json(name = "created_at") val createdAt: String? = null
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: "Order Update"

    val displayBody: String
        get() = body?.takeIf { it.isNotBlank() }
            ?: message?.takeIf { it.isNotBlank() }
            ?: "Your order status has been updated."

    val resolvedOrderId: String?
        get() = orderId?.takeIf { it.isNotBlank() }
}

@JsonClass(generateAdapter = true)
data class Profile(
    val id: String = "",
    val role: String = "customer",
    @Json(name = "full_name") val fullName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    @Json(name = "city_id") val cityId: String? = null,
    @Json(name = "current_device_session") val currentDeviceSession: String? = null
)

// Supabase Auth models
@JsonClass(generateAdapter = true)
data class SupabaseAuthUser(
    val id: String = "",
    val email: String? = null,
    val phone: String? = null,
    @Json(name = "user_metadata") val userMetadata: Map<String, Any?>? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseAuthResponse(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "expires_in") val expiresIn: Long? = null,
    val user: SupabaseAuthUser? = null
)

// App Version & Maintenance Models
@JsonClass(generateAdapter = true)
data class AppVersionInfo(
    val id: String? = null,
    val platform: String? = null,
    @Json(name = "minimum_supported_version") val minimumSupportedVersion: String? = null,
    @Json(name = "latest_version") val latestVersion: String? = null,
    @Json(name = "force_update") val forceUpdate: Boolean = false,
    @Json(name = "update_message") val updateMessage: String? = null
)

data class MaintenanceSettings(
    val enabled: Boolean = false,
    val message: String? = null
)

sealed class StartupCheckResult {
    object Passed : StartupCheckResult()
    data class BlockingUpdate(val updateMessage: String?, val updateUrl: String? = null) : StartupCheckResult()
    data class Maintenance(val message: String) : StartupCheckResult()
}

// Razorpay Models
@JsonClass(generateAdapter = true)
data class CreateRazorpayOrderRequest(
    @Json(name = "order_id") val orderId: String
)

@JsonClass(generateAdapter = true)
data class RazorpayOrderResponse(
    @Json(name = "key_id") val keyId: String = "",
    @Json(name = "amount") val amount: Double = 0.0,
    @Json(name = "currency") val currency: String = "INR",
    @Json(name = "razorpay_order_id") val razorpayOrderId: String = "",
    @Json(name = "error") val error: String? = null,
    @Json(name = "message") val message: String? = null
) {
    val amountInPaise: Long
        get() = amount.toLong()
}

@JsonClass(generateAdapter = true)
data class VerifyRazorpayPaymentRequest(
    @Json(name = "order_id") val orderId: String,
    @Json(name = "razorpay_order_id") val razorpayOrderId: String,
    @Json(name = "razorpay_payment_id") val razorpayPaymentId: String,
    @Json(name = "razorpay_signature") val razorpaySignature: String
)

@JsonClass(generateAdapter = true)
data class VerifyRazorpayPaymentResponse(
    @Json(name = "success") val success: Boolean = false,
    @Json(name = "message") val message: String? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class SyncRazorpayPaymentRequest(
    @Json(name = "order_id") val orderId: String
)

@JsonClass(generateAdapter = true)
data class SyncRazorpayPaymentResponse(
    @Json(name = "success") val success: Boolean = false,
    @Json(name = "status") val status: String? = null,
    @Json(name = "payment_status") val paymentStatus: String? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class OrderCancellation(
    val id: String? = null,
    @Json(name = "order_id") val orderId: String = "",
    val reason: String? = null,
    @Json(name = "cancelled_by") val cancelledBy: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

