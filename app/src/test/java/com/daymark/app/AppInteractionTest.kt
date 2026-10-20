package com.daymark.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@LooperMode(LooperMode.Mode.PAUSED)
class AppInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun `create a task complete it and change its priority`() {
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("New task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("New task").performClick()
        compose.onNode(hasSetTextAction() and hasText("Task")).performTextInput("Prepare for the exam")
        compose.onNode(hasSetTextAction() and hasText("Task")).assertTextContains("Prepare for the exam")
        compose.onNodeWithText("Add task").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Prepare for the exam", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("day-list").performScrollToNode(hasText("Prepare for the exam"))
        compose.onNodeWithContentDescription("Complete Prepare for the exam", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(isOn() and hasContentDescription("Complete Prepare for the exam"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Complete Prepare for the exam", useUnmergedTree = true).assertIsOn()
        compose.onNodeWithText("Prepare for the exam").performClick()
        compose.onNode(hasText("P2") and hasClickAction()).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Save changes").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Save changes").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("day-list").performScrollToNode(hasText("Prepare for the exam"))
        compose.onNodeWithText("Prepare for the exam").assertExists()
    }

    @Test fun `event editor exposes yearly recurrence and requires a title`() {
        compose.onNodeWithText("Events").performClick()
        compose.onNodeWithText("On the horizon.").assertIsDisplayed()
        compose.onNodeWithContentDescription("New event").performClick()
        compose.onNodeWithText("Something to remember.").assertIsDisplayed()
        compose.onNodeWithText("Event name").assertExists()
        compose.onNodeWithText("Yearly").performScrollTo().performClick()
        compose.onNodeWithText("Add event").assertIsNotEnabled()
    }

    @Test fun `journal opens a historical day`() {
        compose.onNodeWithText("Journal").performClick()
        compose.onNodeWithText("Days, collected.").assertIsDisplayed()
        compose.onAllNodesWithText("A quiet page")[1].performClick()
        compose.onNodeWithText("Back to today").assertExists()
    }
}
