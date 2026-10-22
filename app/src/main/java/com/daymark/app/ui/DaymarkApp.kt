@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.daymark.app.ui

import android.Manifest
import androidx.annotation.DrawableRes
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.daymark.app.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.domain.*
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun LocalDate.label(pattern: String) = format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
internal fun LocalDateTime.label(pattern: String) = format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))

@Composable fun DaymarkApp(model: DaymarkViewModel, openEvents: Boolean = false) {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(if (openEvents) "Events" else "Today") }
    var taskOpen by rememberSaveable { mutableStateOf(false) }
    var editingTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    var taskDay by rememberSaveable { mutableStateOf(state.selected.toString()) }
    var initialPriority by rememberSaveable { mutableStateOf(Priority.P0) }
    var eventOpen by rememberSaveable { mutableStateOf(false) }
    var editingEventId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editingTask = state.allTasks.find { it.id == editingTaskId }
    val editingEvent = state.events.find { it.id == editingEventId }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var dateOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
    val requestNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && !state.notificationsAllowed) notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    val editTask: (Task) -> Unit = { model.clearEditorResult(); editingTaskId = it.id; initialPriority = it.priority; taskDay = it.day.toString(); taskOpen = true }
    val newTask: (Priority) -> Unit = { model.clearEditorResult(); editingTaskId = null; initialPriority = it; taskDay = state.selected.toString(); taskOpen = true }
    val editEvent: (Event) -> Unit = { model.clearEditorResult(); editingEventId = it.id; eventOpen = true }
    val newEvent: () -> Unit = { model.clearEditorResult(); editingEventId = null; eventOpen = true }

    LaunchedEffect(Unit) {
        while (true) {
            val now = ZonedDateTime.now()
            val untilMidnight = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone)).toMillis()
            delay(minOf(30_000L, untilMidnight.coerceAtLeast(50L)))
            model.refresh()
        }
    }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); model.clearError() } }
    LaunchedEffect(state.editorSaved) {
        if (state.editorSaved) {
            val savedEvent = eventOpen
            taskOpen = false
            eventOpen = false
            model.clearEditorResult()
            if (savedEvent && Build.VERSION.SDK_INT >= 33 && !state.notificationsAllowed) notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.background(Paper)) {
                HorizontalDivider(color = Line)
                NavigationBar(containerColor = Paper, tonalElevation = 0.dp) {
                    listOf("Today" to R.drawable.ic_check_circle, "Events" to R.drawable.ic_event, "Journal" to R.drawable.ic_auto_stories).forEach { (name, icon) ->
                        NavigationBarItem(selected = tab == name,
                            onClick = { tab = name; if (name == "Today") model.select(state.today) },
                            icon = { Icon(painterResource(icon), null) }, label = { Text(name) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Forest, selectedTextColor = Forest, indicatorColor = Color(0xFFE7EBDF), unselectedIconColor = Muted, unselectedTextColor = Muted))
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(9.dp).background(Forest, CircleShape))
                    Text("DAYMARK", style = MaterialTheme.typography.labelSmall, color = Forest, letterSpacing = 2.5.sp)
                }
                if (tab != "Journal") FilledIconButton(
                    onClick = { if (tab == "Events") newEvent() else newTask(Priority.P0) },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Forest, contentColor = Color.White),
                ) { Icon(painterResource(R.drawable.ic_add), if (tab == "Events") "New event" else "New task") }
                IconButton(onClick = { settingsOpen = true }) { Icon(painterResource(R.drawable.ic_notifications_none), "Reminder settings", tint = Forest) }
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Forest) }
                tab == "Events" -> EventsScreen(state, editEvent, newEvent, { settingsOpen = true })
                tab == "Journal" -> JournalScreen(state) { model.select(it); tab = "Today" }
                else -> DayScreen(state, model, newTask, editTask, editEvent, newEvent, { dateOpen = true })
            }
        }
    }

    // Wait for SQLite after process restoration; a missing ID must never become a new item.
    val missingItem = !state.loading && !state.editorSaved &&
        ((taskOpen && editingTaskId != null && editingTask == null) || (eventOpen && editingEventId != null && editingEvent == null))
    if (missingItem) AlertDialog(onDismissRequest = { taskOpen = false; eventOpen = false },
        title = { Text("Entry unavailable") }, text = { Text("This entry is no longer available. Your other entries are safe.") },
        confirmButton = { TextButton(onClick = { taskOpen = false; eventOpen = false }) { Text("Close") } })
    if (taskOpen && !state.loading && !missingItem && !state.editorSaved) TaskEditor(editingTask, initialPriority, LocalDate.parse(taskDay),
        onDismiss = { taskOpen = false },
        onSave = { title, priority, status ->
            editingTask?.let { model.updateTask(it.copy(title = title, priority = priority, status = status), fromEditor = true) }
                ?: model.addTask(title, priority, LocalDate.parse(taskDay))
        },
        onDelete = { editingTask?.let { model.deleteTask(it.id) } },
        saving = state.editorSaving, error = state.editorError,
    )
    if (eventOpen && !state.loading && !missingItem && !state.editorSaved) EventEditor(editingEvent, onDismiss = { eventOpen = false },
        onSave = model::saveEvent, onDelete = { editingEvent?.let { model.deleteEvent(it.id) } }, saving = state.editorSaving, error = state.editorError)
    if (dateOpen) CalendarDialog(state.selected, { dateOpen = false }) { model.select(it); dateOpen = false }
    if (settingsOpen) ModalBottomSheet(onDismissRequest = { settingsOpen = false }, containerColor = Paper) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("A little heads-up.", style = MaterialTheme.typography.headlineMedium)
            Text("Every event gets two reminders: 24 hours before and 30 minutes before. Times follow your phone's local time zone.", color = Muted)
            SettingRow("Notifications", if (state.notificationsAllowed) "Enabled" else "Allow Daymark to notify you", state.notificationsAllowed)
            OutlinedButton(onClick = requestNotifications, modifier = Modifier.fillMaxWidth()) { Text(if (state.notificationsAllowed) "Notification settings" else "Enable notifications") }
            SettingRow("Precise reminders", if (state.exactAllowed) "Enabled" else "Allow precise timing in Android settings", state.exactAllowed)
            if (!state.exactAllowed) {
                Text("Until enabled, Android may delay reminders to save battery.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 31) runCatching {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }.onFailure { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                }, modifier = Modifier.fillMaxWidth()) { Text("Enable precise reminders") }
            }
            HorizontalDivider(color = Line)
            Text("Yours, and only yours.", style = MaterialTheme.typography.titleLarge)
            Text("Your tasks and events stay on this phone. No account. No sync.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable private fun SettingRow(title: String, detail: String, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, color = Muted, style = MaterialTheme.typography.bodyMedium) }
        Icon(painterResource(if (enabled) R.drawable.ic_check_circle else R.drawable.ic_info), null, tint = if (enabled) Forest else Clay)
    }
}

@Composable private fun DayScreen(
    state: DaymarkState, model: DaymarkViewModel, newTask: (Priority) -> Unit, editTask: (Task) -> Unit,
    editEvent: (Event) -> Unit, newEvent: () -> Unit, openCalendar: () -> Unit,
) {
    val selected = state.selected
    val isToday = selected == state.today
    val heading = when (selected) { state.today -> "Today, gently."; state.today.plusDays(1) -> "Tomorrow."; else -> selected.label("MMMM d.") }
    val p0 = state.tasks.filter { it.priority == Priority.P0 && it.status != TaskStatus.MOVED && it.status != TaskStatus.STALE }
    val done = p0.count { it.status == TaskStatus.DONE }
    val occurrences = state.events.flatMap { listOfNotNull(EventRules.onDay(it, selected), EventRules.onDay(it, selected.plusDays(1))) }.sortedBy { it.at }
    val nextEvent = occurrences.firstOrNull { !isToday || it.at >= LocalDateTime.now() }
    LazyColumn(modifier = Modifier.testTag("day-list"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column {
                Text(heading, style = MaterialTheme.typography.displaySmall)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Choose date", onClick = openCalendar), verticalAlignment = Alignment.CenterVertically) {
                    Text(selected.label(if (isToday) "EEEE, MMMM d" else "EEEE, MMMM d, yyyy"), color = Muted, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Icon(painterResource(R.drawable.ic_calendar_month), "Choose date", tint = Forest)
                }
                if (!isToday) TextButton(onClick = { model.select(state.today) }, contentPadding = PaddingValues(0.dp)) { Text("Back to today") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (p0.isEmpty()) "Choose your must-dos." else "P0 · $done / ${p0.size} complete", color = Forest, style = MaterialTheme.typography.bodyMedium)
                if (p0.isNotEmpty()) LinearProgressIndicator(progress = { done.toFloat() / p0.size }, modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape), color = Forest, trackColor = Color(0xFFD3DDCA), drawStopIndicator = {})
            }
        }
        nextEvent?.let { occurrence -> item {
            Surface(onClick = { editEvent(occurrence.event) }, color = Color(0xFFE9EDE3), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${if (isToday) { if (occurrence.at.toLocalDate() == selected) "Today" else "Tomorrow" } else occurrence.at.label("MMM d, yyyy")} · ${occurrence.at.label("HH:mm")}", color = Forest, style = MaterialTheme.typography.labelLarge)
                    Text(occurrence.event.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        } }
        Priority.entries.forEach { priority ->
            val tasks = state.tasks.filter { it.priority == priority }
            item(key = priority.name) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = priority.color().copy(alpha = .10f), shape = RoundedCornerShape(7.dp)) {
                            Text(priority.name, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = priority.color(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(priority.subtitle, Modifier.padding(start = 10.dp).weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("${tasks.count { it.status == TaskStatus.DONE }}/${tasks.size}", color = Muted, style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { newTask(priority) }) { Icon(painterResource(R.drawable.ic_add), "Add ${priority.name} task", tint = priority.color()) }
                    }
                    if (tasks.isEmpty()) {
                        Text(if (priority == Priority.P0) "What needs your attention?" else "Space for a little more.", modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { newTask(priority) }.padding(vertical = 18.dp, horizontal = 12.dp), color = Muted, style = MaterialTheme.typography.bodyMedium)
                    } else tasks.sortedBy { when (it.status) { TaskStatus.OPEN -> 0; TaskStatus.DONE -> 1; TaskStatus.STALE -> 2; TaskStatus.MOVED -> 3 } }.forEach { task ->
                        TaskRow(task, { model.updateTask(task.copy(status = if (task.status == TaskStatus.DONE) TaskStatus.OPEN else TaskStatus.DONE)) }, { editTask(task) })
                    }
                    HorizontalDivider(color = Line)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Events", if (isToday) "TODAY & TOMORROW" else "${selected.label("MMM d")} & ${selected.plusDays(1).label("MMM d, yyyy")}", R.drawable.ic_event, newEvent)
                if (occurrences.isEmpty()) Text("A little breathing room. No events scheduled.", color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                occurrences.forEach { EventCard(it.event, it.at, if (isToday) { if (it.at.toLocalDate() == selected) "Today" else "Tomorrow" } else it.at.label("MMM d, yyyy"), { editEvent(it.event) }) }
            }
        }
        item { Text("ONE DAY AT A TIME.", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
    }
}

@Composable private fun TaskRow(task: Task, toggle: () -> Unit, edit: () -> Unit) {
    val terminal = task.status == TaskStatus.STALE || task.status == TaskStatus.MOVED
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = edit).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (terminal) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(if (task.status == TaskStatus.STALE) R.drawable.ic_remove_circle_outline else R.drawable.ic_arrow_forward), null, tint = Muted, modifier = Modifier.size(21.dp))
        } else Checkbox(checked = task.status == TaskStatus.DONE, onCheckedChange = { toggle() }, colors = CheckboxDefaults.colors(checkedColor = Forest, uncheckedColor = Muted), modifier = Modifier.semantics { contentDescription = task.title; stateDescription = if (task.status == TaskStatus.DONE) "Completed" else "Not completed" })
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(task.title, color = if (task.status == TaskStatus.OPEN) Ink else Muted,
                textDecoration = if (task.status == TaskStatus.DONE || task.status == TaskStatus.STALE) TextDecoration.LineThrough else null,
                style = MaterialTheme.typography.bodyLarge)
            val detail = when {
                task.status == TaskStatus.MOVED -> "Carried to ${task.movedTo?.label("MMM d")}"
                task.status == TaskStatus.STALE -> "Stale · no longer needed"
                task.originDay != task.day -> "Carried from ${task.originDay.label("MMM d")}"
                else -> null
            }
            detail?.let { Text(it, color = Muted, style = MaterialTheme.typography.labelMedium) }
        }
        Icon(painterResource(R.drawable.ic_more_horiz), null, tint = Muted, modifier = Modifier.padding(12.dp).size(20.dp))
    }
}

@Composable private fun SectionTitle(title: String, subtitle: String, @DrawableRes icon: Int, add: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, tint = Forest, modifier = Modifier.padding(end = 10.dp).size(21.dp))
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleLarge); Text(subtitle, color = Muted, style = MaterialTheme.typography.labelSmall) }
        IconButton(onClick = add) { Icon(painterResource(R.drawable.ic_add), "Add event", tint = Forest) }
    }
}

@Composable private fun EventCard(event: Event, at: LocalDateTime, dayLabel: String, edit: () -> Unit) {
    Surface(onClick = edit, color = Color(0xFFFDFCFA), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Line)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(at.label("HH:mm"), style = MaterialTheme.typography.titleMedium, color = Forest, softWrap = false)
                Text(dayLabel, style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(event.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (event.repeat != Repeat.ONCE) Icon(painterResource(R.drawable.ic_repeat), "Repeating event", tint = Muted, modifier = Modifier.size(18.dp))
            }
            Text(if (event.repeat == Repeat.ONCE) at.label("EEE, MMM d, yyyy") else event.repeat.label, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable private fun EventsScreen(state: DaymarkState, edit: (Event) -> Unit, add: () -> Unit, settings: () -> Unit) {
    val now = LocalDateTime.now()
    val upcoming = state.events.mapNotNull { event -> EventRules.next(event, now)?.let { Occurrence(event, it) } }.sortedBy { it.at }
    val past = state.events.filter { it.repeat == Repeat.ONCE && it.start < now }.sortedByDescending { it.start }
    LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Column { Text("On the horizon.", style = MaterialTheme.typography.displaySmall); Spacer(Modifier.height(8.dp)); Text("Dates worth keeping close.", color = Muted) } }
        item {
            Surface(onClick = settings, color = Color(0xFFE9EDE3), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_notifications_none), null, tint = Forest)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(if (!state.notificationsAllowed) "Turn on your reminders" else if (!state.exactAllowed) "Fine-tune reminder timing" else "One less thing to remember", color = Forest, style = MaterialTheme.typography.titleMedium)
                        Text(if (!state.notificationsAllowed || !state.exactAllowed) "Tap to finish setting up reminders" else "A heads-up 24 hours and 30 minutes before", color = Forest, style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(painterResource(R.drawable.ic_keyboard_arrow_right), null, tint = Forest)
                }
            }
        }
        if (state.events.isEmpty()) item { EmptyNote(R.drawable.ic_event, "Good things ahead.", "An exam, a birthday, a coffee with a friend. Give it a date and we'll remember.", "Add your first event", add) }
        if (upcoming.isNotEmpty()) item { Text("UPCOMING", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp)) }
        items(upcoming, key = { it.event.id }) { occurrence -> EventCard(occurrence.event, occurrence.at, occurrence.at.label("MMM d")) { edit(occurrence.event) } }
        if (past.isNotEmpty()) item { Text("PAST EVENTS", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp)) }
        items(past, key = { -it.id }) { EventCard(it, it.start, it.start.label("MMM d")) { edit(it) } }
    }
}

@Composable private fun JournalScreen(state: DaymarkState, choose: (LocalDate) -> Unit) {
    val days = (state.allTasks.map { it.day } + (0L..13L).map { state.today.minusDays(it) }).distinct().sortedDescending()
    LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Column(Modifier.padding(bottom = 16.dp)) { Text("Days, collected.", style = MaterialTheme.typography.displaySmall); Spacer(Modifier.height(8.dp)); Text("A record of effort, not perfection.", color = Muted) } }
        items(days, key = { it.toString() }) { day ->
            val tasks = state.allTasks.filter { it.day == day }
            val done = tasks.count { it.status == TaskStatus.DONE }
            val moved = tasks.count { it.status == TaskStatus.MOVED }
            val open = tasks.count { it.status == TaskStatus.OPEN }
            val stale = tasks.count { it.status == TaskStatus.STALE }
            val summary = listOfNotNull(
                "$open open".takeIf { open > 0 }, "$done completed".takeIf { done > 0 },
                "$moved carried".takeIf { moved > 0 }, "$stale stale".takeIf { stale > 0 },
            ).joinToString(" · ")
            Surface(onClick = { choose(day) }, shape = RoundedCornerShape(18.dp), color = if (day == state.today) Color(0xFFE9EDE3) else Color(0xFFFDFCFA), border = BorderStroke(1.dp, Line)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(day.label("MMM").uppercase(Locale.ENGLISH), color = Muted, style = MaterialTheme.typography.labelSmall)
                        Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.headlineMedium, color = Forest)
                    }
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(if (day == state.today) "Today" else day.label("EEEE, yyyy"), style = MaterialTheme.typography.titleMedium)
                        Text(if (tasks.isEmpty()) "A quiet page" else summary, color = Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(painterResource(R.drawable.ic_keyboard_arrow_right), "Open ${day.label("MMMM d")}", tint = Muted)
                }
            }
        }
    }
}

@Composable private fun EmptyNote(@DrawableRes icon: Int, title: String, body: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(64.dp).background(Color(0xFFE9EDE3), CircleShape), contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, tint = Forest, modifier = Modifier.size(28.dp)) }
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(body, color = Muted, modifier = Modifier.padding(horizontal = 16.dp))
        OutlinedButton(onClick = onAction) { Text(action) }
    }
}
