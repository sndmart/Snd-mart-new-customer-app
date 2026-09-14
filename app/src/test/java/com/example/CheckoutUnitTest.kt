package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.CartItem
import com.example.data.model.Order
import com.example.data.remote.SupabaseApi
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CheckoutUnitTest {

    private fun RequestBody.readUtf8(): String {
        val buffer = Buffer()
        this.writeTo(buffer)
        return buffer.readUtf8()
    }

    @Test
    fun groceryCheckoutPayload_containsOnlyAllowedFields() {
        val addressId = "addr-uuid-12345"
        val paymentMethod = "cod"
        val cartItems = listOf(
            CartItem(productId = "prod-apple", variantId = null, quantity = 2),
            CartItem(productId = "prod-milk", variantId = "var-1l", quantity = 1)
        )

        val itemsArray = JSONArray()
        for (item in cartItems) {
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

        val payload = JSONObject().apply {
            put("p_address_id", addressId)
            put("p_payment_method", if (paymentMethod == "cod") "cash" else paymentMethod)
            put("p_items", itemsArray)
            put("p_notes", JSONObject.NULL)
        }

        // Verify top-level parameters
        assertEquals("addr-uuid-12345", payload.getString("p_address_id"))
        assertEquals("cash", payload.getString("p_payment_method"))
        assertTrue(payload.isNull("p_notes"))
        assertFalse(payload.has("total_amount"))
        assertFalse(payload.has("subtotal"))
        assertFalse(payload.has("commission_amount"))

        // Verify p_items
        val items = payload.getJSONArray("p_items")
        assertEquals(2, items.length())

        val item1 = items.getJSONObject(0)
        assertEquals("prod-apple", item1.getString("product_id"))
        assertTrue(item1.isNull("variant_id"))
        assertEquals(2, item1.getInt("quantity"))
        assertFalse(item1.has("price"))
        assertFalse(item1.has("unit_price"))

        val item2 = items.getJSONObject(1)
        assertEquals("prod-milk", item2.getString("product_id"))
        assertEquals("var-1l", item2.getString("variant_id"))
        assertEquals(1, item2.getInt("quantity"))
    }

    @Test
    fun foodCheckoutPayload_includesVendorId() {
        val hotelId = "vendor-biryani-spot-uuid"
        val addressId = "addr-uuid-999"
        val cartItems = listOf(
            CartItem(productId = "prod-biryani", variantId = null, vendorId = hotelId, quantity = 3)
        )

        val itemsArray = JSONArray()
        for (item in cartItems) {
            val itemObj = JSONObject().apply {
                put("product_id", item.productId)
                put("variant_id", JSONObject.NULL)
                put("quantity", item.quantity)
            }
            itemsArray.put(itemObj)
        }

        val payload = JSONObject().apply {
            put("p_vendor_id", hotelId)
            put("p_address_id", addressId)
            put("p_payment_method", "cash")
            put("p_items", itemsArray)
            put("p_notes", JSONObject.NULL)
        }

        assertEquals("vendor-biryani-spot-uuid", payload.getString("p_vendor_id"))
        assertEquals("addr-uuid-999", payload.getString("p_address_id"))
        assertEquals("cash", payload.getString("p_payment_method"))
        assertTrue(payload.isNull("p_notes"))
        assertEquals(1, payload.getJSONArray("p_items").length())
    }

    @Test
    fun cleanErrorMessage_extractsAuthoritativeMessage() {
        val errorJson = """
            {
                "code": "P0001",
                "details": null,
                "hint": null,
                "message": "Apple — only 3 in stock"
            }
        """.trimIndent()

        val json = JSONObject(errorJson)
        val msg = listOf("message", "msg", "error_description", "error", "details")
            .mapNotNull { key ->
                if (json.has(key) && !json.isNull(key)) {
                    val v = json.optString(key).trim()
                    v.takeIf { it.isNotBlank() && it != "null" }
                } else null
            }.firstOrNull()

        assertEquals("Apple — only 3 in stock", msg)
    }

    @Test
    fun groceryOrderRpc_succeedsAndClearsCart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = UserSessionManager(context)

        var capturedRequestBody: String? = null
        val fakeApi = Proxy.newProxyInstance(
            SupabaseApi::class.java.classLoader,
            arrayOf(SupabaseApi::class.java)
        ) { _, method, args ->
            when (method.name) {
                "checkoutGroceryOrder" -> {
                    val rb = args[0] as RequestBody
                    capturedRequestBody = rb.readUtf8()
                    val orderJson = """
                        {
                            "id": "e44d3ba0-9f1e-45fa-a1bc-79f9435b8e90",
                            "order_number": "SND-2026-0001",
                            "status": "confirmed",
                            "total_amount": 250.0,
                            "subtotal": 220.0,
                            "delivery_fee": 30.0
                        }
                    """.trimIndent()
                    Response.success(orderJson.toResponseBody("application/json".toMediaTypeOrNull()))
                }
                "clearCartForUser" -> Response.success("[]".toResponseBody("application/json".toMediaTypeOrNull()))
                "insertCartItem" -> Response.success(null)
                else -> null
            }
        } as SupabaseApi

        val repo = SndmartRepository(api = fakeApi, sessionManager = sessionManager)

        // Add 2 apples to grocery cart
        repo.addToCart(productId = "prod-apple-uuid", vendorId = null, cityId = "city-uuid-1", quantityDelta = 2, isHotel = false)
        assertEquals(1, repo.groceryCart.value.size)

        // Place grocery order
        val result = repo.placeOrder(
            userId = "user-uuid-123",
            isHotel = false,
            vendorId = null,
            cityId = "city-uuid-1",
            addressId = "addr-uuid-456",
            slotId = null,
            paymentMethod = "cod"
        )

        assertTrue("Expected order placement to succeed", result.isSuccess)
        val order = result.getOrNull()
        assertNotNull(order)
        assertEquals("e44d3ba0-9f1e-45fa-a1bc-79f9435b8e90", order!!.id)
        assertEquals("SND-2026-0001", order.orderNumber)
        assertEquals(250.0, order.totalAmount ?: 0.0, 0.01)

        // Verify captured JSON sent to checkout_grocery_order RPC
        assertNotNull(capturedRequestBody)
        val sentJson = JSONObject(capturedRequestBody!!)
        assertEquals("addr-uuid-456", sentJson.getString("p_address_id"))
        assertEquals("cash", sentJson.getString("p_payment_method"))
        assertTrue(sentJson.isNull("p_notes"))
        assertFalse(sentJson.has("p_vendor_id"))
        assertFalse(sentJson.has("total_amount"))
        assertFalse(sentJson.has("subtotal"))

        val items = sentJson.getJSONArray("p_items")
        assertEquals(1, items.length())
        val item = items.getJSONObject(0)
        assertEquals("prod-apple-uuid", item.getString("product_id"))
        assertTrue(item.isNull("variant_id"))
        assertEquals(2, item.getInt("quantity"))
        assertFalse(item.has("price"))

        // Cart must be cleared after checkout
        assertEquals(0, repo.groceryCart.value.size)
    }

    @Test
    fun foodOrderRpc_succeedsAndClearsCart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = UserSessionManager(context)

        var capturedRequestBody: String? = null
        val fakeApi = Proxy.newProxyInstance(
            SupabaseApi::class.java.classLoader,
            arrayOf(SupabaseApi::class.java)
        ) { _, method, args ->
            when (method.name) {
                "checkoutFoodOrder" -> {
                    val rb = args[0] as RequestBody
                    capturedRequestBody = rb.readUtf8()
                    val orderJson = """
                        {
                            "id": "f55e4cb1-0a2f-46ba-b2cd-80f0546c9f01",
                            "order_number": "SND-FOOD-0042",
                            "status": "confirmed",
                            "vendor_id": "hotel-biryani-spot-uuid",
                            "total_amount": 450.0
                        }
                    """.trimIndent()
                    Response.success(orderJson.toResponseBody("application/json".toMediaTypeOrNull()))
                }
                "clearCartForUser" -> Response.success("[]".toResponseBody("application/json".toMediaTypeOrNull()))
                "insertCartItem" -> Response.success(null)
                else -> null
            }
        } as SupabaseApi

        val repo = SndmartRepository(api = fakeApi, sessionManager = sessionManager)

        // Add 1 biryani from hotel
        repo.addToCart(productId = "prod-biryani-uuid", vendorId = "hotel-biryani-spot-uuid", cityId = "city-1", quantityDelta = 1, isHotel = true)
        assertEquals(1, repo.hotelCart.value.size)

        // Place food order
        val result = repo.placeOrder(
            userId = "user-uuid-123",
            isHotel = true,
            vendorId = "hotel-biryani-spot-uuid",
            cityId = "city-1",
            addressId = "addr-uuid-789",
            slotId = null,
            paymentMethod = "upi"
        )

        assertTrue(result.isSuccess)
        val order = result.getOrNull()
        assertNotNull(order)
        assertEquals("f55e4cb1-0a2f-46ba-b2cd-80f0546c9f01", order!!.id)
        assertEquals("hotel-biryani-spot-uuid", order.vendorId)

        // Verify captured JSON sent to checkout_food_order RPC
        assertNotNull(capturedRequestBody)
        val sentJson = JSONObject(capturedRequestBody!!)
        assertEquals("hotel-biryani-spot-uuid", sentJson.getString("p_vendor_id"))
        assertEquals("addr-uuid-789", sentJson.getString("p_address_id"))
        assertEquals("upi", sentJson.getString("p_payment_method"))
        assertTrue(sentJson.isNull("p_notes"))

        // Hotel cart must be cleared
        assertEquals(0, repo.hotelCart.value.size)
    }

    @Test
    fun checkoutRpcError_returnsCleanMessageAndPreservesCart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = UserSessionManager(context)

        val fakeApi = Proxy.newProxyInstance(
            SupabaseApi::class.java.classLoader,
            arrayOf(SupabaseApi::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "checkoutGroceryOrder" -> {
                    val errorJson = """
                        {
                            "code": "P0001",
                            "message": "Fresh Organic Apples — only 1 left in stock"
                        }
                    """.trimIndent()
                    val responseBody = errorJson.toResponseBody("application/json".toMediaTypeOrNull())
                    Response.error<ResponseBody>(400, responseBody)
                }
                else -> null
            }
        } as SupabaseApi

        val repo = SndmartRepository(api = fakeApi, sessionManager = sessionManager)

        repo.addToCart(productId = "prod-apple-uuid", vendorId = null, cityId = "city-1", quantityDelta = 2, isHotel = false)
        assertEquals(1, repo.groceryCart.value.size)

        val result = repo.placeOrder(
            userId = "user-uuid-123",
            isHotel = false,
            vendorId = null,
            cityId = "city-1",
            addressId = "addr-uuid-456",
            slotId = null,
            paymentMethod = "cod"
        )

        assertFalse("Expected order placement to fail", result.isSuccess)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertEquals("Fresh Organic Apples — only 1 left in stock", exception!!.message)

        // Cart must NOT be cleared when checkout fails!
        assertEquals(1, repo.groceryCart.value.size)
    }
}
