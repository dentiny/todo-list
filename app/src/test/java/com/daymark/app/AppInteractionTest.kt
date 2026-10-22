package com.daymark.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import com.daymark.app.ui.DaymarkViewModel
import com.daymark.app.domain.*
import org.junit.Assert.*
import org.junit.Before
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

    // Robolectric may reuse AndroidViewModelFactory's Application across test activities.
    // Read the same store as the UI rather than a different activity Application's store.
    private fun uiApp() = compose.runOnIdle {
        ViewModelProvider(compose.activity)[DaymarkViewModel::class.java].getApplication<DaymarkApplication>().daymark
    }

    @Before fun clearEntries() {
        val app = uiApp()
        val model = ViewModelProvider(compose.activity)[DaymarkViewModel::class.java]
        compose.runOnIdle {
            app.store.allTasks().forEach { app.store.deleteTask(it.id) }
            app.store.events().forEach { app.scheduler.delete(it.id) }
            model.refresh()
        }
        compose.waitUntil(10_000) {
            val state = model.state.value
            !state.loading && state.allTasks.isEmpty() && state.events.isEmpty()
        }
    }

    @Test fun `create a task complete it and change its priority`() {
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("New task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("New task").performClick()
        compose.onNode(hasSetTextAction() and hasText("Task")).performTextInput("Prepare for the exam")
        compose.onNode(hasSetTextAction() and hasText("Task")).assertTextContains("Prepare for the exam")
        compose.onNodeWithText("Add task").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Prepare for the exam", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("day-list").performScrollToNode(hasText("Prepare for the exam"))
        compose.onNode(isToggleable() and hasContentDescription("Prepare for the exam"), useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(isOn() and hasContentDescription("Prepare for the exam"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(isToggleable() and hasContentDescription("Prepare for the exam"), useUnmergedTree = true).assertIsOn()
        compose.onNodeWithText("Prepare for the exam").performClick()
        compose.onNode(hasText("P2") and hasClickAction()).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Save changes").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Save changes").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("day-list").performScrollToNode(hasText("Prepare for the exam"))
        compose.onNodeWithText("Prepare for the exam").assertExists()
    }

    @Test fun `event editor exposes yearly recurrence and requires a title`() {
        compose.onNode(hasText("Events") and hasClickAction()).performClick()
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

    @Test fun `task edit survives recreation and updates the original row`() {
        val store = uiApp().store
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("New task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("New task").performClick()
        compose.onNode(hasSetTextAction() and hasText("Task")).performTextInput("Rotation task")
        compose.onNode(hasText("P1") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Add task").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("A little intention.").fetchSemanticsNodes().isEmpty() }
        val original = compose.runOnIdle { store.allTasks().single() }
        compose.onNodeWithTag("day-list").performScrollToNode(hasText("Rotation task"))
        compose.onNodeWithText("Rotation task").performClick()
        compose.onNode(hasSetTextAction() and hasText("Task")).performTextReplacement("Edited after rotation")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Make it yours.").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Task")).assertTextContains("Edited after rotation")
        compose.onNodeWithText("Save changes").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Make it yours.").fetchSemanticsNodes().isEmpty() }
        val saved = compose.runOnIdle { store.allTasks().single() }
        assertEquals(original.id, saved.id)
        assertEquals("Edited after rotation", saved.title)
        assertEquals(Priority.P1, saved.priority)
    }

    @Test fun `event edit survives recreation without creating a duplicate`() {
        val app = uiApp()
        compose.onNode(hasText("Events") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("New event").performClick()
        compose.onNode(hasSetTextAction() and hasText("Event name")).performTextInput("Rotation birthday")
        compose.onNodeWithText("Yearly").performScrollTo().performClick()
        compose.onNodeWithText("Add event").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Something to remember.").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Rotation birthday").fetchSemanticsNodes().isNotEmpty() }
        val original = compose.runOnIdle { app.store.events().single() }
        compose.onNodeWithText("Rotation birthday").performClick()
        compose.onNode(hasSetTextAction() and hasText("Event name")).performTextReplacement("Birthday updated")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Keep it close.").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Event name")).assertTextContains("Birthday updated")
        compose.onNodeWithText("Save changes").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Keep it close.").fetchSemanticsNodes().isEmpty() }
        val saved = compose.runOnIdle { app.store.events().single() }
        assertEquals(original.id, saved.id)
        assertEquals("Birthday updated", saved.title)
        assertEquals(original.start, saved.start)
        assertEquals(Repeat.YEARLY, saved.repeat)
    }

    @Test fun `dismiss protects a changed draft and allows explicit discard`() {
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("New task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("New task").performClick()
        compose.onNode(hasSetTextAction() and hasText("Task")).performTextInput("Keep this draft")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss) { it() }
        compose.onNodeWithText("Discard changes?").assertExists()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNode(hasSetTextAction() and hasText("Task")).assertTextContains("Keep this draft")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss) { it() }
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithContentDescription("New task").performClick()
        compose.onNode(hasSetTextAction() and hasText("Task")).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        val app = uiApp()
        assertTrue(compose.runOnIdle { app.store.allTasks().isEmpty() })
    }
    @Test fun `overdue P1 and P2 appear in today without a carry-over review`() {
        val store = uiApp().store
        val today = java.time.LocalDate.now()
        compose.runOnIdle {
            Priority.entries.forEach { store.addTask("Carry ${it.name}", it, today.minusDays(2)) }
            ViewModelProvider(compose.activity)[DaymarkViewModel::class.java].refresh()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Carry P0").fetchSemanticsNodes().isNotEmpty()
        }
        Priority.entries.forEach { priority ->
            compose.onNodeWithTag("day-list").performScrollToNode(hasText("Carry ${priority.name}"))
            compose.onNodeWithText("Carry ${priority.name}").assertIsDisplayed()
        }
        assertEquals(Priority.entries.toSet(), compose.runOnIdle { store.tasks(today).map { it.priority }.toSet() })
        compose.onNodeWithText("Review carry-over", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Carry P2").performClick()
        compose.onNodeWithText("If there's room. Unfinished tasks carry into the next day automatically.").assertExists()
        compose.onNodeWithText("Move to today").assertDoesNotExist()
    }

}
