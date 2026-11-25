package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.data.ChatUtils
import com.example.model.MessageStatus
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.ProfileSetupScreen
import com.example.ui.theme.MyChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `app name matches My Chat`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("My Chat", appName)
    }

    @Test
    fun `deterministic chat id prevents duplicate conversations regardless of order`() {
        val chatA = ChatUtils.deterministicChatId("uid_bob", "uid_alice")
        val chatB = ChatUtils.deterministicChatId("uid_alice", "uid_bob")
        assertEquals("uid_alice_uid_bob", chatA)
        assertEquals(chatA, chatB)
    }

    @Test
    fun `username validation and normalization enforces rules`() {
        assertEquals("farhad_11", ChatUtils.normalizeUsername("  @Farhad_11 "))
        assertTrue(ChatUtils.isValidUsername("farhad_11"))
        assertFalse(ChatUtils.isValidUsername("ab"))
        assertFalse(ChatUtils.isValidUsername("bad-user!"))
    }

    @Test
    fun `sha256 email hash is deterministic and hides raw email`() {
        val h1 = ChatUtils.sha256Email("  Alice@Gmail.com ")
        val h2 = ChatUtils.sha256Email("alice@gmail.com")
        assertEquals(64, h1.length)
        assertEquals(h1, h2)
        assertFalse(h1.contains("alice", ignoreCase = true))
    }

    @Test
    fun `message status safe parsing falls back cleanly`() {
        assertEquals(MessageStatus.SENT, MessageStatus.fromRaw("SENT"))
        assertEquals(MessageStatus.DELIVERED, MessageStatus.fromRaw("DELIVERED"))
        assertEquals(MessageStatus.SEEN, MessageStatus.fromRaw("SEEN"))
        assertEquals(MessageStatus.SENT, MessageStatus.fromRaw("UNKNOWN_FUTURE_VALUE"))
    }

    @Test
    fun `auth screen renders Google Sign-In button and branding`() {
        composeTestRule.setContent {
            MyChatTheme {
                AuthScreen(onAuthSuccess = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("My Chat").assertIsDisplayed()
        composeTestRule.onNodeWithTag("google_sign_in_button").assertIsDisplayed()
    }

    @Test
    fun `profile setup screen displays verified gmail and registration inputs`() {
        composeTestRule.setContent {
            MyChatTheme {
                ProfileSetupScreen(
                    initialName = "Farhad Ahmed",
                    verifiedEmail = "farhad@gmail.com",
                    usernameAvailable = true,
                    isBusy = false,
                    errorMessage = null,
                    onUsernameChanged = {},
                    onSubmit = { _, _, _, _ -> },
                    onLogout = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("farhad@gmail.com").assertIsDisplayed()
        composeTestRule.onNodeWithTag("full_name_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("username_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("complete_registration_button").assertIsDisplayed()
    }
}
