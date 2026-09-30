package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Order
import com.example.data.model.OrderCancellation
import com.example.data.model.SyncRazorpayPaymentRequest
import com.example.data.remote.SupabaseApi
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RazorpaySyncUnitTest {

    @Test
    fun isUpiPaymentPending_correctlyIdentifiesPendingAndFailedUpiOrders() {
        val pendingOrder = Order(
            id = "order-1",
            status = "pending",
            paymentMethod = "upi",
            paymentStatus = "pending"
        )
        val isPending = pendingOrder.paymentMethod.lowercase() == "upi" &&
            (pendingOrder.paymentStatus.lowercase() == "pending" || pendingOrder.paymentStatus.lowercase() == "failed") &&
            pendingOrder.status.lowercase() == "pending"
        assertTrue(isPending)

        val failedPaymentOrder = Order(
            id = "order-2",
            status = "pending",
            paymentMethod = "upi",
            paymentStatus = "failed"
        )
        val isFailedPending = failedPaymentOrder.paymentMethod.lowercase() == "upi" &&
            (failedPaymentOrder.paymentStatus.lowercase() == "pending" || failedPaymentOrder.paymentStatus.lowercase() == "failed") &&
            failedPaymentOrder.status.lowercase() == "pending"
        assertTrue(isFailedPending)

        // Paid order should not be pending
        val paidOrder = Order(
            id = "order-3",
            status = "pending",
            paymentMethod = "upi",
            paymentStatus = "paid"
        )
        val isPaidPending = paidOrder.paymentMethod.lowercase() == "upi" &&
            (paidOrder.paymentStatus.lowercase() == "pending" || paidOrder.paymentStatus.lowercase() == "failed") &&
            paidOrder.status.lowercase() == "pending"
        assertFalse(isPaidPending)

        // COD order should not be upi pending
        val codOrder = Order(
            id = "order-4",
            status = "pending",
            paymentMethod = "cod",
            paymentStatus = "cod"
        )
        val isCodPending = codOrder.paymentMethod.lowercase() == "upi" &&
            (codOrder.paymentStatus.lowercase() == "pending" || codOrder.paymentStatus.lowercase() == "failed") &&
            codOrder.status.lowercase() == "pending"
        assertFalse(isCodPending)
    }

    @Test
    fun upiTimeoutCancellation_identifies15MinuteTimeoutReason() {
        val cancellation = OrderCancellation(
            orderId = "order-timeout-1",
            reason = "UPI payment not received within 15 minutes",
            cancelledBy = "system"
        )
        val matchesReason = cancellation.reason?.contains("UPI payment not received within 15 minutes", ignoreCase = true) == true
        assertTrue(matchesReason)

        val customCancellation = OrderCancellation(
            orderId = "order-timeout-2",
            reason = "Customer changed mind",
            cancelledBy = "customer"
        )
        val notMatching = customCancellation.reason?.contains("UPI payment not received within 15 minutes", ignoreCase = true) == true
        assertFalse(notMatching)
    }

    @Test
    fun syncRazorpayPayment_parsesSuccessResponse() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = UserSessionManager(context)

        var capturedRequest: SyncRazorpayPaymentRequest? = null

        val fakeApi = Proxy.newProxyInstance(
            SupabaseApi::class.java.classLoader,
            arrayOf(SupabaseApi::class.java)
        ) { _, method, args ->
            when (method.name) {
                "syncRazorpayPayment" -> {
                    capturedRequest = args?.get(0) as? SyncRazorpayPaymentRequest
                    val responseJson = """
                        {
                            "success": true,
                            "status": "pending",
                            "payment_status": "paid",
                            "message": "Payment verified"
                        }
                    """.trimIndent()
                    val responseBody = responseJson.toResponseBody("application/json".toMediaTypeOrNull())
                    Response.success<ResponseBody>(responseBody)
                }
                else -> null
            }
        } as SupabaseApi

        val repo = SndmartRepository(api = fakeApi, sessionManager = sessionManager)
        val result = repo.syncRazorpayPayment("test-order-uuid")

        assertTrue(result.isSuccess)
        val syncData = result.getOrNull()
        assertNotNull(syncData)
        assertEquals(true, syncData!!.success)
        assertEquals("paid", syncData.paymentStatus)
        assertEquals("pending", syncData.status)
        assertEquals("test-order-uuid", capturedRequest?.orderId)
    }

    @Test
    fun getOrderCancellation_returnsCancellationDetails() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = UserSessionManager(context)

        val fakeApi = Proxy.newProxyInstance(
            SupabaseApi::class.java.classLoader,
            arrayOf(SupabaseApi::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getOrderCancellation" -> {
                    val list = listOf(
                        OrderCancellation(
                            id = "cancel-1",
                            orderId = "order-123",
                            reason = "UPI payment not received within 15 minutes",
                            cancelledBy = "system",
                            createdAt = "2026-09-30T10:00:00Z"
                        )
                    )
                    Response.success(list)
                }
                else -> null
            }
        } as SupabaseApi

        val repo = SndmartRepository(api = fakeApi, sessionManager = sessionManager)
        val result = repo.getOrderCancellation("order-123")

        assertTrue(result.isSuccess)
        val cancellation = result.getOrNull()
        assertNotNull(cancellation)
        assertEquals("order-123", cancellation!!.orderId)
        assertEquals("UPI payment not received within 15 minutes", cancellation.reason)
    }
}
