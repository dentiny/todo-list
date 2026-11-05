package com.daymark.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.daymark.app.ui.DaymarkViewModel
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = LoggedOutTestApplication::class, sdk = [35], qualifiers = "w411dp-h891dp")
@LooperMode(LooperMode.Mode.PAUSED)
class PasswordFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun `signed-out launch shows only the shared password entry`() {
        compose.onNodeWithText("Shared password").assertIsDisplayed()
        compose.onNodeWithText("Open shared list").assertIsNotEnabled()
        compose.onNodeWithText("Today").assertDoesNotExist()
        compose.onNodeWithContentDescription("New task").assertDoesNotExist()
    }
    @Test fun `wrong password keeps task content locked`() {
        compose.onNodeWithText("Shared password").performTextInput("wrong-password")
        compose.onNodeWithText("Open shared list").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Invalid login credentials").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Today").assertDoesNotExist()
    }
    @Test fun `successful login stays signed in across recreation and sign out returns to password entry`() {
        compose.onNodeWithText("Shared password").performTextInput("friend-password")
        compose.onNodeWithText("Open shared list").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("New task").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("New task").assertExists()
        compose.onNodeWithText("Shared password").assertDoesNotExist()
        compose.onNodeWithText("ruien-todo-list",substring=true).performClick()
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Shared password").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("New task").assertDoesNotExist()
    }
    @Test fun `unsubmitted password is not restored after recreation`() {
        compose.onNodeWithText("Shared password").performTextInput("private-draft")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Open shared list").assertIsNotEnabled()
    }
}
