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
import androidx.compose.animation.animateContentSize
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
    var editingTask by remember { mutableStateOf<Task?>(null) }
    var initialPriority by remember { mutableStateOf(Priority.P0) }
    var eventOpen by rememberSaveable { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<Event?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var dateOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
    val requestNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && !state.notificationsAllowed) notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    val editTask: (Task) -> Unit = { editingTask = it; initialPriority = it.priority; taskOpen = true }
    val newTask: (Priority) -> Unit = { editingTask = null; initialPriority = it; taskOpen = true }
    val editEvent: (Event) -> Unit = { editingEvent = it; eventOpen = true }
    val newEvent: () -> Unit = { editingEvent = null; eventOpen = true }

    LaunchedEffect(Unit) {
        while (true) {
            val now = ZonedDateTime.now()
            val untilMidnight = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone)).toMillis()
            delay(minOf(30_000L, untilMidnight.coerceAtLeast(50L)))
            model.refresh()
        }
    }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); model.clearError() } }

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
        floatingActionButton = {
            if (tab != "Journal") ExtendedFloatingActionButton(
                modifier = Modifier.semantics { contentDescription = if (tab == "Events") "New event" else "New task" },
                onClick = { if (tab == "Events") newEvent() else newTask(Priority.P0) },
                containerColor = Forest, contentColor = Color.White, shape = RoundedCornerShape(19.dp),
                icon = { Icon(painterResource(R.drawable.ic_add), null) }, text = { Text(if (tab == "Events") "New event" else "New task") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(9.dp).background(Forest, CircleShape))
                    Text("DAYMARK", style = MaterialTheme.typography.labelSmall, color = Forest, letterSpacing = 2.5.sp)
                }
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

    if (taskOpen) TaskEditor(editingTask, initialPriority, state.selected,
        onDismiss = { taskOpen = false },
        onSave = { title, priority, status ->
            editingTask?.let { model.updateTask(it.copy(title = title, priority = priority, status = status)) }
                ?: model.addTask(title, priority, state.selected)
            taskOpen = false
        },
        onDelete = { editingTask?.let { model.deleteTask(it.id) }; taskOpen = false },
        onCarry = { title, priority, status -> editingTask?.let { model.saveAndCarry(it.copy(title = title, priority = priority, status = status)) }; taskOpen = false }, today = state.today,
    )
    if (eventOpen) EventEditor(editingEvent, onDismiss = { eventOpen = false },
        onSave = {
            model.saveEvent(it); eventOpen = false
            if (Build.VERSION.SDK_INT >= 33 && !state.notificationsAllowed) notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }, onDelete = { editingEvent?.let { model.deleteEvent(it.id) }; eventOpen = false })
    if (dateOpen) CalendarDialog(state.selected, { dateOpen = false }) { model.select(it); dateOpen = false }
    if (settingsOpen) ModalBottomSheet(onDismissRequest = { settingsOpen = false }, containerColor = Paper) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
    LazyColumn(modifier = Modifier.testTag("day-list"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Column {
                Text(heading, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selected.label("EEEE, MMMM d"), color = Muted, modifier = Modifier.weight(1f))
                    IconButton(onClick = openCalendar, modifier = Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_calendar_month), "Choose date", tint = Forest) }
                }
                if (!isToday) TextButton(onClick = { model.select(state.today) }, contentPadding = PaddingValues(0.dp)) { Text("Back to today") }
            }
        }
        item { WeekStrip(selected, state.today, model::select) }
        item {
            Surface(color = Color(0xFFE9EDE3), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (p0.isEmpty()) "A little intention goes a long way." else if (done == p0.size) "The essentials are taken care of." else "Make room for what matters.", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Forest)
                        if (p0.isNotEmpty()) Text("$done / ${p0.size}", color = Forest, style = MaterialTheme.typography.labelLarge)
                    }
                    if (p0.isNotEmpty()) LinearProgressIndicator(progress = { done.toFloat() / p0.size }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = Forest, trackColor = Color(0xFFD3DDCA), drawStopIndicator = {})
                    Text(if (p0.isEmpty()) "Choose your must-dos. Leave the rest some breathing room." else "${p0.size - done} must-do${if (p0.size - done == 1) "" else "s"} left · everything else is a bonus", color = Forest, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (isToday && state.pending.isNotEmpty()) item {
            PendingReview(state.pending, editTask, model::carry, model::dismiss)
        }
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
                SectionTitle("Events", "TODAY & TOMORROW", R.drawable.ic_event, newEvent)
                val occurrences = state.events.flatMap { listOfNotNull(EventRules.onDay(it, selected), EventRules.onDay(it, selected.plusDays(1))) }.sortedBy { it.at }
                if (occurrences.isEmpty()) Text("A little breathing room. No events scheduled.", color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                occurrences.forEach { EventCard(it.event, it.at, if (it.at.toLocalDate() == selected) "Today" else "Tomorrow", { editEvent(it.event) }) }
            }
        }
        item { Text("ONE DAY AT A TIME.", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
    }
}

@Composable private fun WeekStrip(selected: LocalDate, today: LocalDate, choose: (LocalDate) -> Unit) {
    val monday = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        (0L..6L).forEach { index ->
            val day = monday.plusDays(index)
            val active = day == selected
            Column(Modifier.weight(1f).clip(RoundedCornerShape(15.dp)).background(if (active) Forest else Color.Transparent)
                .clickable { choose(day) }.padding(vertical = 12.dp)
                .semantics(mergeDescendants = true) { contentDescription = day.label("EEEE, MMMM d, yyyy"); this.selected = active }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(day.label("EEEEE"), color = if (active) Color.White.copy(alpha = .7f) else Muted, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                Text(day.dayOfMonth.toString(), color = if (active) Color.White else Ink, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.size(4.dp).background(if (day == today) { if (active) Color(0xFFDCC58C) else Forest } else Color.Transparent, CircleShape))
            }
        }
    }
}

@Composable private fun TaskRow(task: Task, toggle: () -> Unit, edit: () -> Unit) {
    val terminal = task.status == TaskStatus.STALE || task.status == TaskStatus.MOVED
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = edit).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (terminal) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(if (task.status == TaskStatus.STALE) R.drawable.ic_remove_circle_outline else R.drawable.ic_arrow_forward), null, tint = Muted, modifier = Modifier.size(21.dp))
        } else Checkbox(checked = task.status == TaskStatus.DONE, onCheckedChange = { toggle() }, colors = CheckboxDefaults.colors(checkedColor = Forest, uncheckedColor = Color(0xFF90988A)), modifier = Modifier.semantics { contentDescription = "Complete ${task.title}" })
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
        Icon(painterResource(R.drawable.ic_more_horiz), "Edit ${task.title}", tint = Muted, modifier = Modifier.padding(12.dp).size(20.dp))
    }
}

@Composable private fun PendingReview(tasks: List<Task>, edit: (Task) -> Unit, carry: (Set<Long>) -> Unit, dismiss: (Set<Long>) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    val ids = tasks.map { it.id }.toSet()
    val selected = selection.intersect(ids)
    Surface(color = Color(0xFFF0EADF), shape = RoundedCornerShape(20.dp), modifier = Modifier.animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_history), null, tint = Olive, modifier = Modifier.padding(end = 12.dp))
                Column(Modifier.weight(1f)) {
                    Text("A fresh look", style = MaterialTheme.typography.titleMedium)
                    Text("${tasks.size} optional task${if (tasks.size == 1) "" else "s"} from earlier days", style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
                IconButton(onClick = { expanded = !expanded }) { Icon(painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more), if (expanded) "Collapse carry-over review" else "Review carry-over tasks") }
            }
            if (expanded) {
                Text("Bring what still matters into today.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { selection = if (selected == ids) emptySet() else ids }) { Text(if (selected == ids) "Deselect all" else "Select all") }
                tasks.forEach { task ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = task.id in selected, onCheckedChange = { selection = if (it) selection + task.id else selection - task.id }, modifier = Modifier.semantics { contentDescription = "Select ${task.title} for carry-over" })
                        Column(Modifier.weight(1f).clickable { edit(task) }.padding(vertical = 8.dp)) {
                            Text(task.title, style = MaterialTheme.typography.bodyMedium)
                            Text("${task.priority.name} · ${task.day.label("MMM d")}", color = Muted, style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(onClick = { edit(task) }) { Icon(painterResource(R.drawable.ic_more_horiz), "Edit ${task.title}", tint = Muted) }
                    }
                }
                Button(onClick = { carry(selected); selection = emptySet() }, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Move to today${if (selected.isEmpty()) "" else " (${selected.size})"}") }
                TextButton(onClick = { dismiss(selected); selection = emptySet() }, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Keep in history") }
            }
        }
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
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(58.dp)) {
                Text(at.label("HH:mm"), style = MaterialTheme.typography.titleMedium, color = Forest)
                Text(dayLabel, style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Box(Modifier.width(2.dp).height(36.dp).background(Color(0xFFD4DCCB), CircleShape))
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(event.title, style = MaterialTheme.typography.titleMedium)
                Text(if (event.repeat == Repeat.ONCE) at.label("EEE, MMM d") else event.repeat.label, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            if (event.repeat != Repeat.ONCE) Icon(painterResource(R.drawable.ic_repeat), "Repeating event", tint = Muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable private fun EventsScreen(state: DaymarkState, edit: (Event) -> Unit, add: () -> Unit, settings: () -> Unit) {
    val now = LocalDateTime.now()
    val upcoming = state.events.mapNotNull { event -> EventRules.next(event, now)?.let { Occurrence(event, it) } }.sortedBy { it.at }
    val past = state.events.filter { it.repeat == Repeat.ONCE && it.start < now }.sortedByDescending { it.start }
    LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
            Surface(onClick = { choose(day) }, shape = RoundedCornerShape(18.dp), color = if (day == state.today) Color(0xFFE9EDE3) else Color(0xFFFDFCFA), border = BorderStroke(1.dp, Line)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(day.label("MMM").uppercase(Locale.ENGLISH), color = Muted, style = MaterialTheme.typography.labelSmall)
                        Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.headlineMedium, color = Forest)
                    }
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(if (day == state.today) "Today" else day.label("EEEE, yyyy"), style = MaterialTheme.typography.titleMedium)
                        Text(if (tasks.isEmpty()) "A quiet page" else "$done completed · $moved carried · ${tasks.count { it.status == TaskStatus.STALE }} stale", color = Muted, style = MaterialTheme.typography.bodyMedium)
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
