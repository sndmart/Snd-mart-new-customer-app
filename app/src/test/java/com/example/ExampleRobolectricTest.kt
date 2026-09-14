package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.session.UserSessionManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Sndmart", appName)
  }

  @Test
  fun `unread notification count flow in UserSessionManager`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val sessionManager = UserSessionManager(context)

    sessionManager.setUnreadNotificationCount(5)
    assertEquals(5, sessionManager.unreadNotificationCount.value)

    sessionManager.decrementUnreadNotificationCount()
    assertEquals(4, sessionManager.unreadNotificationCount.value)

    sessionManager.logout()
    assertEquals(0, sessionManager.unreadNotificationCount.value)
  }
}
